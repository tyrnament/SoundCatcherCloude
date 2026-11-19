package app.soundcatcher

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Служба записи. Два режима:
 *  - MODE_SYSTEM: захват всего, что воспроизводит телефон (AudioPlaybackCapture, Android 10+);
 *  - MODE_MIC: запись с микрофона (для разговора по громкой связи).
 * Звук сразу кодируется в MP3 (LAME) и пишется в MediaStore.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "app.soundcatcher.START"
        const val ACTION_STOP = "app.soundcatcher.STOP"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val MODE_SYSTEM = "system"
        const val MODE_MIC = "mic"

        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 44100
        private const val BITRATE = 128

        @Volatile var isRecording = false
        @Volatile var startedAt = 0L
        @Volatile var lastError: String? = null
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running) start(intent)
            ACTION_STOP -> if (running) running = false else stopSelf()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun start(intent: Intent) {
        val system = intent.getStringExtra(EXTRA_MODE) == MODE_SYSTEM
        lastError = null
        createChannel()

        // Сначала — foreground-служба нужного типа (на Android 14+ это требование для MediaProjection).
        val type = if (system) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            fail("Не удалось запустить службу: ${e.message}")
            return
        }

        val channels = if (system) 2 else 1
        val mask = if (system) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf * 2, 32768)

        val record = try {
            val r = if (system) createPlaybackRecord(intent, mask, bufBytes)
            else AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
            check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord не инициализирован (проверьте разрешения)" }
            r
        } catch (e: Exception) {
            releaseProjection()
            fail("Не удалось начать захват звука: ${e.message}")
            return
        }

        running = true
        isRecording = true
        startedAt = SystemClock.elapsedRealtime()
        Thread({ recordLoop(record, channels, system) }, "recorder").start()
    }

    private fun createPlaybackRecord(intent: Intent, mask: Int, bufBytes: Int): AudioRecord {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = requireNotNull(intent.getParcelableExtra<Intent>(EXTRA_DATA)) { "Нет разрешения на захват" }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { running = false }
        }, main)
        projection = p

        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(mask)
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufBytes)
            .setAudioPlaybackCaptureConfig(cfg)
            .build()
    }

    private fun recordLoop(record: AudioRecord, channels: Int, system: Boolean) {
        var uri: Uri? = null
        var out: OutputStream? = null
        var encoder: LameEncoder? = null
        val pcm = ShortArray(4096 * channels)
        val mp3 = ByteArray(LameEncoder.outBufferSize(pcm.size))
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val (u, o) = Storage.create(this, "${if (system) "sys" else "mic"}_$stamp.mp3")
            uri = u
            out = BufferedOutputStream(o, 64 * 1024)
            encoder = LameEncoder(SAMPLE_RATE, channels, BITRATE)

            record.startRecording()
            while (running) {
                val n = record.read(pcm, 0, pcm.size)   // блокирующее чтение
                if (n < 0) {
                    lastError = "Ошибка чтения звука ($n)"
                    break
                }
                if (n == 0) continue
                val bytes = encoder.encode(pcm, n / channels, mp3)
                if (bytes > 0) out.write(mp3, 0, bytes)
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.toString()
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
            try {
                val tail = encoder?.flush(mp3) ?: 0
                if (tail > 0) out?.write(mp3, 0, tail)
                out?.close()
                uri?.let { Storage.publish(this, it) }
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
            }
            encoder?.close()
            releaseProjection()
            running = false
            isRecording = false
            main.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun releaseProjection() {
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    private fun fail(message: String) {
        lastError = message
        isRecording = false
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Запись звука", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RecorderService::class.java).setAction(ACTION_STOP), flags
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Идёт запись звука")
            .setContentText("Нажмите «Стоп», чтобы сохранить MP3")
            .setContentIntent(open)
            .addAction(0, "Стоп", stop)
            .setOngoing(true)
            .build()
    }
}
