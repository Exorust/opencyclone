package ai.opencyclone.ui.voice

/**
 * Splits a live transcript into confirmed words (ink) and unconfirmed words (ink-3), from the last two
 * partial results of the recognizer. A word is confirmed when it is the same in both results, every word
 * before it is the same too, and it is not the last word of the newest result (the last word still grows).
 */
object TranscriptFormatter {

    /** [text] to show; characters before [confirmedEnd] are confirmed, the rest are not. */
    data class Split(val text: String, val confirmedEnd: Int) {
        val confirmed: String get() = text.substring(0, confirmedEnd)
        val unconfirmed: String get() = text.substring(confirmedEnd)
    }

    private val WORD = Regex("\\S+")

    fun split(previous: String, current: String): Split {
        val text = current.trim()
        if (text.isEmpty()) return Split("", 0)
        val now = WORD.findAll(text).toList()
        val before = WORD.findAll(previous.trim()).map { it.value }.toList()
        var same = 0
        while (same < now.size && same < before.size && now[same].value == before[same]) same++
        val confirmedWords = same.coerceAtMost(now.size - 1)
        val end = if (confirmedWords == 0) 0 else now[confirmedWords - 1].range.last + 1
        return Split(text, end)
    }

    /** A final result: every word is confirmed. */
    fun final(text: String): Split = text.trim().let { Split(it, it.length) }
}
