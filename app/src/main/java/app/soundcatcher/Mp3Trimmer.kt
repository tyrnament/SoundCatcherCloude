package app.soundcatcher

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.OutputStream
import java.nio.ByteOrder

/**
 * Обрезка: файл декодируется системным декодером (MediaCodec) в PCM,
 * берётся диапазон [startMs, endMs] и кодируется в новый MP3 через LAME.
 */
object Mp3Trimmer {

    fun trim(
        ctx: Context, src: Uri, out: OutputStream,
        startMs: Long, endMs: Long, onProgress: (Int) -> Unit
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var encoder: LameEncoder? = null
        try {
            extractor.setDataSource(ctx, src, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("В файле нет звуковой дорожки")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)

            val startUs = startMs * 1000
            val endUs = endMs * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val dec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()

            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var mp3 = ByteArray(LameEncoder.outBufferSize(16384))
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var written = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        val t = extractor.sampleTime
                        if (size < 0 || t > endUs + 300_000) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, t, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val bb = dec.getOutputBuffer(outIdx)!!
                        bb.position(info.offset)
                        bb.limit(info.offset + info.size)
                        val shorts = ShortArray(info.size / 2)
                        bb.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

                        val frames = shorts.size / channels
                        val bufStartUs = info.presentationTimeUs
                        val from = maxOf(0L, (startUs - bufStartUs) * sampleRate / 1_000_000L)
                        val to = minOf(frames.toLong(), (endUs - bufStartUs) * sampleRate / 1_000_000L)
                        if (to > from) {
                            if (encoder == null) encoder = LameEncoder(sampleRate, channels, 128)
                            val n = (to - from).toInt()
                            val slice = shorts.copyOfRange(from.toInt() * channels, to.toInt() * channels)
                            val need = LameEncoder.outBufferSize(n)
                            if (mp3.size < need) mp3 = ByteArray(need)
                            val bytes = encoder.encode(slice, n, mp3)
                            if (bytes > 0) {
                                out.write(mp3, 0, bytes)
                                written += bytes
                            }
                        }
                        val p = ((bufStartUs - startUs) * 100 / (endUs - startUs).coerceAtLeast(1)).toInt()
                        onProgress(p.coerceIn(0, 99))
                        if (bufStartUs >= endUs) outputDone = true
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }

            val enc = encoder ?: throw IllegalStateException("Выбранный фрагмент пуст")
            val tail = enc.flush(mp3)
            if (tail > 0) out.write(mp3, 0, tail)
            out.flush()
            onProgress(100)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            encoder?.close()
        }
    }
}
