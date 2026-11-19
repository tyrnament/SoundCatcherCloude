package app.soundcatcher

object Fmt {
    fun time(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        val h = m / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60)
        else "%02d:%02d".format(m, s % 60)
    }

    fun size(bytes: Long): String =
        if (bytes >= 1L shl 20) "%.1f МБ".format(bytes / 1048576.0)
        else "%d КБ".format(bytes / 1024)
}
