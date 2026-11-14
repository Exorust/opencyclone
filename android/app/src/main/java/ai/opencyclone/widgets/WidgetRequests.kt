package ai.opencyclone.widgets

/** Recognises the owner's widget sentences. Checked before built-app sentences. */
object WidgetRequests {
    sealed class Request {
        data object Build : Request()
        /** [name] is the owner's words for the widget ("streak" in "make the streak widget wide"). */
        data class Change(val name: String) : Request()
    }

    private val options = setOf(RegexOption.IGNORE_CASE)
    private val wake = Regex("""^\s*(?:(?:hey|ok|okay)\s+)?(?:cyclone|open\s?cyclone|cyclone|phone)\b[\s,:!.]*""", options)
    private val please = Regex("""^\s*(?:please|can you|could you|would you)[\s,]+""", options)
    private val build = Regex("""^(?:make|build|create|add)\s+(?:me\s+)?(?:an?\s+)?(?:new\s+)?widget\b.*""", options)
    private val change = Regex("""^(?:change|make|update|edit|rebuild|fix|redo)\s+(?:the|my|this|that)\s+(.+?)\s+widget\b.*""", options)

    fun clean(sentence: String): String =
        sentence.replace(wake, "").replace(please, "").trim().trimEnd('.', '!', '?', ' ')

    fun parse(sentence: String): Request? {
        val text = clean(sentence)
        if (build.matches(text)) return Request.Build
        change.matchEntire(text)?.let { return Request.Change(it.groupValues[1].trim()) }
        return null
    }

    /** The saved widgets that [name] means: a name contains the words or the words contain a name, else shared words. */
    fun find(name: String, widgets: List<WidgetEntity>): List<WidgetEntity> {
        val words = name.trim().lowercase()
        if (words.isEmpty()) return emptyList()
        val direct = widgets.filter { it.name.lowercase().contains(words) || words.contains(it.name.lowercase()) }
        if (direct.isNotEmpty()) return direct
        val wanted = significant(words)
        return widgets.filter { widget -> significant(widget.name.lowercase()).any { it in wanted } }
    }

    private fun significant(text: String) = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
}
