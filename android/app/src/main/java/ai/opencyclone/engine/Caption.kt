package ai.opencyclone.engine

/** Cleans the model's `caption` before the home page shows it as "<caption>." */
object Caption {
    const val MAX_LENGTH = 40
    private const val MAX_WORDS = 8

    private val URL = Regex("""(?i)(\b[a-z][a-z0-9+.-]*://|\bwww\.|\b[a-z0-9-]+\.(com|net|org|io|ai|co|app|dev|me|in|uk|ly)\b)""")
    private val LONG_DIGITS = Regex("""\d{7,}""")

    /** The caption to show, or null when there is none or it looks like copied text rather than a short phrase. */
    fun clean(raw: String?): String? {
        var text = raw?.trim() ?: return null
        if (text.isEmpty()) return null
        if ('\n' in text || '\r' in text) return null
        if (URL.containsMatchIn(text)) return null
        if (LONG_DIGITS.containsMatchIn(text)) return null
        if (text.split(Regex("\\s+")).size > MAX_WORDS) return null
        text = text.trimEnd('.', ' ')
        if (text.length > MAX_LENGTH) {
            val space = text.lastIndexOf(' ', MAX_LENGTH)
            text = if (space > 0) text.substring(0, space) else text.take(MAX_LENGTH)
            text = text.trimEnd(' ', ',', ';', ':', '-', '.')
        }
        return text.ifEmpty { null }
    }
}
