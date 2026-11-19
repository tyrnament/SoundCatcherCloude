package app.soundcatcher

/** Кодировщик MP3 на базе LAME (нативная часть — app/src/main/cpp). */
class LameEncoder(sampleRate: Int, val channels: Int, bitrateKbps: Int = 128, quality: Int = 5) {

    private var handle: Long = nativeInit(sampleRate, channels, bitrateKbps, quality)

    init {
        check(handle != 0L) { "Не удалось инициализировать кодировщик LAME" }
    }

    /** pcm — 16-бит, для стерео чередующиеся L/R; frames — число отсчётов на канал. */
    fun encode(pcm: ShortArray, frames: Int, out: ByteArray): Int {
        check(handle != 0L) { "Кодировщик закрыт" }
        return nativeEncode(handle, pcm, frames, out)
    }

    fun flush(out: ByteArray): Int {
        check(handle != 0L) { "Кодировщик закрыт" }
        return nativeFlush(handle, out)
    }

    fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    private external fun nativeInit(sampleRate: Int, channels: Int, bitrate: Int, quality: Int): Long
    private external fun nativeEncode(handle: Long, pcm: ShortArray, frames: Int, out: ByteArray): Int
    private external fun nativeFlush(handle: Long, out: ByteArray): Int
    private external fun nativeClose(handle: Long)

    companion object {
        init {
            System.loadLibrary("lamejni")
        }

        /** Рекомендуемый LAME размер выходного буфера. */
        fun outBufferSize(frames: Int): Int = (1.25 * frames + 7200).toInt()
    }
}
