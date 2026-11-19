package app.soundcatcher

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

data class Rec(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateSec: Long,
    val durationMs: Long
)

/** Записи лежат в «Музыка/SoundCatcher» (MediaStore) — разрешение на память не нужно. */
object Storage {
    private const val REL_PATH = "Music/SoundCatcher"
    private val COLLECTION: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    fun create(ctx: Context, displayName: String): Pair<Uri, OutputStream> {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, REL_PATH)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(COLLECTION, values)
            ?: throw IOException("Не удалось создать файл в MediaStore")
        val out = ctx.contentResolver.openOutputStream(uri)
            ?: throw IOException("Не удалось открыть файл для записи")
        return uri to out
    }

    /** Сделать файл видимым для плееров и галереи. */
    fun publish(ctx: Context, uri: Uri) {
        val v = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, v, null, null)
    }

    fun delete(ctx: Context, uri: Uri) {
        ctx.contentResolver.delete(uri, null, null)
    }

    fun list(ctx: Context): List<Rec> {
        val res = ArrayList<Rec>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION
        )
        ctx.contentResolver.query(
            COLLECTION, proj,
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf("$REL_PATH%"),
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                res.add(
                    Rec(
                        id = id,
                        uri = ContentUris.withAppendedId(COLLECTION, id),
                        name = c.getString(1) ?: "recording.mp3",
                        size = c.getLong(2),
                        dateSec = c.getLong(3),
                        durationMs = c.getLong(4)
                    )
                )
            }
        }
        return res
    }
}
