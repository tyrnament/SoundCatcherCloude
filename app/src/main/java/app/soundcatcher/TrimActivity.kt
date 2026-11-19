package app.soundcatcher

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.RangeSlider
import java.io.BufferedOutputStream

class TrimActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "name"
    }

    private lateinit var uri: Uri
    private lateinit var baseName: String
    private var durationMs = 0L

    private lateinit var slider: RangeSlider
    private lateinit var tvRange: TextView
    private lateinit var btnPreview: Button
    private lateinit var btnSave: Button
    private lateinit var progress: ProgressBar

    private val ui = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trim)

        val data = intent.data
        if (data == null) {
            finish()
            return
        }
        uri = data
        val name = intent.getStringExtra(EXTRA_NAME) ?: "recording.mp3"
        baseName = name.substringBeforeLast('.')
        findViewById<TextView>(R.id.tvTrimName).text = name

        durationMs = readDuration()
        if (durationMs < 500) {
            Toast.makeText(this, "Не удалось определить длительность файла", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        slider = findViewById(R.id.slider)
        tvRange = findViewById(R.id.tvRange)
        btnPreview = findViewById(R.id.btnPreview)
        btnSave = findViewById(R.id.btnSave)
        progress = findViewById(R.id.progress)

        slider.valueFrom = 0f
        slider.valueTo = durationMs.toFloat()
        slider.values = listOf(0f, durationMs.toFloat())
        slider.setLabelFormatter { Fmt.time(it.toLong()) }
        slider.addOnChangeListener { _, _, _ -> updateRange() }
        updateRange()

        btnPreview.setOnClickListener { togglePreview() }
        btnSave.setOnClickListener { save() }
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }

    private fun readDuration(): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            r.release()
        }
    }

    private fun range(): Pair<Long, Long> {
        val v = slider.values
        val a = v[0].toLong()
        val b = v[v.size - 1].toLong()
        return minOf(a, b) to maxOf(a, b)
    }

    private fun updateRange() {
        val (s, e) = range()
        tvRange.text = "Начало ${Fmt.time(s)}  ·  Конец ${Fmt.time(e)}  ·  Длина ${Fmt.time(e - s)}"
    }

    // ---------- предпрослушивание выбранного фрагмента ----------

    private val watch = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.isPlaying || p.currentPosition >= range().second) stopPreview()
            else ui.postDelayed(this, 100)
        }
    }

    private fun togglePreview() {
        if (player != null) {
            stopPreview()
            return
        }
        try {
            val (s, _) = range()
            player = MediaPlayer().apply {
                setDataSource(this@TrimActivity, uri)
                prepare()
                seekTo(s.toInt())
                start()
            }
            btnPreview.text = "■ Стоп"
            ui.postDelayed(watch, 300)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось воспроизвести: ${e.message}", Toast.LENGTH_LONG).show()
            stopPreview()
        }
    }

    private fun stopPreview() {
        ui.removeCallbacks(watch)
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
        if (::btnPreview.isInitialized) btnPreview.text = "▶ Прослушать фрагмент"
    }

    // ---------- сохранение ----------

    private fun save() {
        val (s, e) = range()
        if (e - s < 300) {
            Toast.makeText(this, "Фрагмент слишком короткий", Toast.LENGTH_SHORT).show()
            return
        }
        stopPreview()
        btnSave.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.progress = 0

        val ctx = applicationContext
        val src = uri
        val newName = "${baseName}_cut.mp3"

        Thread {
            var created: Uri? = null
            var error: String? = null
            try {
                val (outUri, raw) = Storage.create(ctx, newName)
                created = outUri
                BufferedOutputStream(raw, 64 * 1024).use { out ->
                    Mp3Trimmer.trim(ctx, src, out, s, e) { p -> ui.post { progress.progress = p } }
                }
                Storage.publish(ctx, outUri)
            } catch (ex: Exception) {
                error = ex.message ?: ex.toString()
                created?.let { try { Storage.delete(ctx, it) } catch (_: Exception) {} }
            }
            ui.post {
                progress.visibility = View.GONE
                btnSave.isEnabled = true
                if (error == null) {
                    Toast.makeText(this, "Сохранено: $newName", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this, "Ошибка: $error", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
