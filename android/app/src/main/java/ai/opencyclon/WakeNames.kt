package ai.opencyclon

/**
 * The names the owner may say before a request ("Cyclon, build me an app"). One list for every handler: the request
 * router removes the name before any handler sees the sentence. A rename replaces the names here only.
 */
object WakeNames {
    /** Longest first, so "open cyclon" is removed whole. */
    val ALL: List<String> = listOf("open cyclon", "cyclon")

    /**
     * An optional "hey", "ok" or "okay", then one of [ALL] (a space inside a name may be left out), then any comma,
     * colon or full stop. The word "phone" counts only when a comma or colon follows it ("Phone, build me an app"),
     * so that "Phone Alex" keeps its verb.
     */
    val PATTERN: Regex = Regex(
        "^\\s*(?:(?:hey|ok|okay)\\s+)?(?:(?:" +
            ALL.joinToString("|") { name -> name.split(' ').joinToString("\\s?") { Regex.escape(it) } } +
            ")\\b[\\s,:!.]*|phone\\s*[,:]\\s*)",
        RegexOption.IGNORE_CASE,
    )

    private val PLEASE = Regex("^\\s*please\\b[\\s,]*", RegexOption.IGNORE_CASE)

    /** [sentence] without a leading wake name and a leading "please"; the rest is unchanged. */
    fun strip(sentence: String): String = sentence.replace(PATTERN, "").replace(PLEASE, "").trim()
}
