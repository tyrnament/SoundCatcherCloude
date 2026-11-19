package app.soundcatcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var btnRecord: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var radioSystem: RadioButton
    private lateinit var radioMic: RadioButton
    private val adapter = RecAdapter()

    private val ui = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var playingId = -1L
    private var wasRecording = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMic()) proceedStart() else toast("Нужно разрешение на запись звука")
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                launchService(RecorderService.MODE_SYSTEM, r.resultCode, data)
            } else {
                toast("Доступ к захвату звука не выдан")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnRecord = findViewById(R.id.btnRecord)
        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        radioSystem = findViewById(R.id.radioSystem)
        radioMic = findViewById(R.id.radioMic)

        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        btnRecord.setOnClickListener {
            if (RecorderService.isRecording) {
                startService(
                    Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP)
                )
            } else {
                stopPlayer()
                ensurePermissionsThenStart()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        stopPlayer()
        super.onPause()
    }

    // ---------- запись ----------

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionsThenStart() {
        val missing = ArrayList<String>()
        if (!hasMic()) missing.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) missing.add(Manifest.permission.POST_NOTIFICATIONS)

        if (missing.isEmpty()) proceedStart() else permLauncher.launch(missing.toTypedArray())
    }

    private fun proceedStart() {
        if (radioSystem.isChecked) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val intent = if (Build.VERSION.SDK_INT >= 34)
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else mpm.createScreenCaptureIntent()
            projectionLauncher.launch(intent)
        } else {
            launchService(RecorderService.MODE_MIC, 0, null)
        }
    }

    private fun launchService(mode: String, resultCode: Int, data: Intent?) {
        val i = Intent(this, RecorderService::class.java)
            .setAction(RecorderService.ACTION_START)
            .putExtra(RecorderService.EXTRA_MODE, mode)
        if (data != null) {
            i.putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode)
            i.putExtra(RecorderService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(this, i)
    }

    private val tick = object : Runnable {
        override fun run() {
            val rec = RecorderService.isRecording
            if (rec) {
                val ms = SystemClock.elapsedRealtime() - RecorderService.startedAt
                tvTimer.text = Fmt.time(ms)
                tvStatus.text = "Идёт запись…"
                btnRecord.text = "■ Остановить и сохранить"
            } else {
                btnRecord.text = "● Начать запись"
                val err = RecorderService.lastError
                tvStatus.text = if (err != null) "Ошибка: $err" else "Готов к записи"
                if (wasRecording) tvTimer.text = "00:00"
            }
            radioSystem.isEnabled = !rec
            radioMic.isEnabled = !rec
            if (wasRecording && !rec) refreshList()
            wasRecording = rec
            ui.postDelayed(this, 500)
        }
    }

    // ---------- список записей ----------

    private fun refreshList() {
        adapter.items = Storage.list(this)
        adapter.notifyDataSetChanged()
    }

    private fun togglePlay(rec: Rec) {
        if (playingId == rec.id) {
            stopPlayer()
            return
        }
        stopPlayer()
        try {
            player = MediaPlayer().apply {
                setDataSource(this@MainActivity, rec.uri)
                setOnCompletionListener { stopPlayer() }
                prepare()
                start()
            }
            playingId = rec.id
        } catch (e: Exception) {
            toast("Не удалось воспроизвести: ${e.message}")
            stopPlayer()
        }
        adapter.notifyDataSetChanged()
    }

    private fun stopPlayer() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        player = null
        if (playingId != -1L) {
            playingId = -1L
            adapter.notifyDataSetChanged()
        }
    }

    private fun share(rec: Rec) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/mpeg")
            .putExtra(Intent.EXTRA_STREAM, rec.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Отправить запись"))
    }

    private fun confirmDelete(rec: Rec) {
        AlertDialog.Builder(this)
            .setMessage("Удалить «${rec.name}»?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                try {
                    if (playingId == rec.id) stopPlayer()
                    Storage.delete(this, rec.uri)
                } catch (e: Exception) {
                    toast("Не удалось удалить: ${e.message}")
                }
                refreshList()
            }
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private inner class RecAdapter : RecyclerView.Adapter<RecAdapter.VH>() {
        var items: List<Rec> = emptyList()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tvName)
            val info: TextView = v.findViewById(R.id.tvInfo)
            val play: Button = v.findViewById(R.id.btnPlay)
            val trim: Button = v.findViewById(R.id.btnTrim)
            val share: Button = v.findViewById(R.id.btnShare)
            val del: Button = v.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val rec = items[position]
            val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(rec.dateSec * 1000))
            val dur = if (rec.durationMs > 0) "${Fmt.time(rec.durationMs)} · " else ""
            h.name.text = rec.name
            h.info.text = "$dur${Fmt.size(rec.size)} · $date"
            h.play.text = if (playingId == rec.id) "■ Стоп" else "▶ Слушать"
            h.play.setOnClickListener { togglePlay(rec) }
            h.trim.setOnClickListener {
                stopPlayer()
                startActivity(
                    Intent(this@MainActivity, TrimActivity::class.java)
                        .setData(rec.uri)
                        .putExtra(TrimActivity.EXTRA_NAME, rec.name)
                )
            }
            h.share.setOnClickListener { share(rec) }
            h.del.setOnClickListener { confirmDelete(rec) }
        }
    }
}
