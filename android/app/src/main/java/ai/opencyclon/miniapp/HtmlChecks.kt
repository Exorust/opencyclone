package ai.opencyclon.miniapp

object HtmlChecks {
    const val MAX_BYTES = 300 * 1024
    private val tags = Regex("""<(script|link)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attributes = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val title = Regex("""<title\b[^>]*>(.*?)</title\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    // Linear-time two-step check: find each quoted 32+ letter-and-digit literal, then look at the name
    // assigned to it just before. A single backtracking regex was quadratic on long word runs.
    private val longLiteral = Regex("""["'][A-Za-z0-9]{32,}["']""")
    private val assignedName = Regex("""([\w$]{1,100})["']?\s{0,20}[:=]\s{0,20}$""")
    private val secretName = Regex("key|token|secret", RegexOption.IGNORE_CASE)

    private fun hasSecret(html: String): Boolean = longLiteral.findAll(html).any { literal ->
        val before = html.substring(maxOf(0, literal.range.first - 160), literal.range.first)
        assignedName.find(before)?.groupValues?.get(1)?.let { secretName.containsMatchIn(it) } == true
    }

    fun check(html: String): List<String> = buildList {
        if (!Regex("<html\\b", RegexOption.IGNORE_CASE).containsMatchIn(html)) add(NO_HTML)
        if (name(html).isNullOrBlank()) add(NO_TITLE)
        if (html.toByteArray(Charsets.UTF_8).size > MAX_BYTES) add(TOO_LARGE)
        tags.findAll(html).forEach { tag ->
            val attrs = attributes.findAll(tag.value).associate {
                it.groupValues[1].lowercase() to it.groupValues.drop(2).firstOrNull { value -> value.isNotEmpty() }.orEmpty()
            }
            val source = if (tag.groupValues[1].equals("script", true)) attrs["src"]
                else if (attrs["rel"]?.split(Regex("\\s+"))?.any { it.equals("stylesheet", true) } == true) attrs["href"] else null
            // Even relative resources require a second file. Only embedded data is self-contained.
            if (!source.isNullOrBlank() && !source.startsWith("data:", true) && !source.startsWith("#")) add(EXTERNAL_RESOURCE)
        }
        if (hasSecret(html)) add(SECRET)
        // Fixable, not fatal: Builder asks for it in the repair rounds and accepts the default accent after the last one.
        if (BuiltAppStyle.declaredAccent(html) == null) add(NO_ACCENT)
    }.distinct()

    fun name(html: String): String? = title.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    const val NO_HTML = "Missing <html> element"
    const val NO_TITLE = "Missing app name in <title>"
    const val TOO_LARGE = "HTML exceeds 300 KB"
    const val EXTERNAL_RESOURCE = "All scripts and styles must be inline"
    const val SECRET = "Remove the embedded service key, token, or secret"
    const val NO_ACCENT = "Declare one accent colour in <head> as <meta name=\"accent\" content=\"#RRGGBB\">"
}
