package ai.opencyclone.feed

/**
 * The status sentence under the greeting: the single most important fact, at most [MAX] characters,
 * ending with a full stop. Priority: approval waiting > not connected > question waiting > running > window active
 * > idle.
 */
object StatusSentence {
    const val MAX = 60
    const val IDLE = "All caught up."
    const val QUESTION = "I have a question."
    const val WORKING = "Working on it."
    const val APPROVAL_NO_TITLE = "Waiting for your approval."
    const val NOT_CONNECTED = "Not connected. Tap to reconnect."
    private const val ELLIPSIS = "…"

    /**
     * What [of] needs to know about a waiting approval. [verb] is the held label ("Send"); [noun] the plural noun
     * of the items ("photos"); [hasText] is true when the gate read the text that will be sent.
     */
    data class Approval(val count: Int?, val title: String?, val verb: String,
                        val noun: String? = null, val hasText: Boolean = false)

    /** A sentence and the characters of it that name a person, an app, a count, or a time (drawn darker when idle). */
    data class Line(val text: String, val emphasis: IntRange? = null)

    fun of(
        approval: Approval?,
        questionWaiting: Boolean,
        runningCaption: String?,
        running: Boolean,
        windowEndsAt: Long?,
        clock: (Long) -> String = FeedText::clock,
        notConnected: Boolean = false,
    ): String = line(approval, questionWaiting, runningCaption, running, windowEndsAt, clock, notConnected).text

    fun line(
        approval: Approval?,
        questionWaiting: Boolean,
        runningCaption: String?,
        running: Boolean,
        windowEndsAt: Long?,
        clock: (Long) -> String = FeedText::clock,
        /** No privileged connection after the start is done (AgentConnection.notConnected). */
        notConnected: Boolean = false,
    ): Line = when {
        approval != null -> approvalSentence(approval)
        notConnected -> Line(NOT_CONNECTED)
        questionWaiting -> Line(QUESTION)
        running -> runningCaption?.trim()?.trimEnd('.', ' ')?.takeIf { it.isNotEmpty() }
            ?.let { Line(fit("", it, ".")) } ?: Line(WORKING)
        windowEndsAt != null -> fitLine("Handling your notifications until ", clock(windowEndsAt), ".")
        else -> Line(IDLE)
    }

    private fun approvalSentence(a: Approval): Line {
        val verb = a.verb.lowercase().ifBlank { "send" }
        return when {
            a.count != null -> fitLine("", FeedText.items(a.count, a.noun), if (a.count == 1) " is ready to $verb." else " are ready to $verb.")
            a.hasText && a.title != null -> fitLine("A message to ", a.title, " is ready.")
            a.title != null -> fitLine("Ready to $verb to ", a.title, ".")
            else -> Line(APPROVAL_NO_TITLE)
        }
    }

    /** prefix + variable + suffix; a variable that does not fit is shortened with an ellipsis and marked as the emphasis. */
    private fun fitLine(prefix: String, variable: String, suffix: String): Line {
        val room = MAX - prefix.length - suffix.length
        val shown = when {
            prefix.length + variable.length + suffix.length <= MAX -> variable
            room <= 1 -> return Line(fit(prefix, variable, suffix))
            else -> variable.take(room - 1).trimEnd(' ', ',', '.') + ELLIPSIS
        }
        return Line(prefix + shown + suffix, prefix.length until prefix.length + shown.length)
    }

    /** prefix + variable + suffix, with the variable cut at a word where possible so the whole fits [MAX]. */
    internal fun fit(prefix: String, variable: String, suffix: String): String {
        val whole = prefix + variable + suffix
        if (whole.length <= MAX) return whole
        val room = MAX - prefix.length - suffix.length
        if (room <= 0) return (prefix + suffix).take(MAX - 1).trimEnd() + "."
        val cut = variable.take(room)
        val space = cut.lastIndexOf(' ')
        return prefix + (if (space > room / 2) cut.substring(0, space) else cut).trimEnd(' ', ',', '.') + suffix
    }
}
