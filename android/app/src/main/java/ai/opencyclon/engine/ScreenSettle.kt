package ai.opencyclon.engine

/**
 * Waits after an action until the hidden display is quiet (F18.2), in place of a fixed wait.
 *
 * The screen is quiet when two reads a full poll apart agree and no spinner shows. A read is the hash of the element
 * list, the accessibility service's change count for the display and, near and after the minimum, a tiny picture of
 * the display (an 8x8 hash): a drawing animation changes no accessibility content and sends no events, so only the
 * picture sees it. The wait lasts at least [minMs] and at most [MAX_MS].
 */
object ScreenSettle {
    const val POLL_MS = 250L
    /** Two reads closer than this say nothing about a screen that changes every 100 ms or so. */
    const val MIN_PAIR_MS = 200L
    const val MIN_MS = 300L
    const val MIN_AFTER_OPEN_MS = 800L
    const val MAX_MS = 3000L
    private const val MIN_SLEEP_MS = 20L

    /** One read of the display. [loading]: a spinner is visible. [picture]: the 8x8 picture hash, when taken. */
    data class Snapshot(val elementsHash: Int?, val changes: Long?, val loading: Boolean = false, val picture: Long? = null)

    /** Two reads agree on elements, changes and spinner, and on the picture when both have one. */
    internal fun agree(a: Snapshot?, b: Snapshot?): Boolean {
        if (a == null || b == null) return a == b
        val pictures = a.picture == null || b.picture == null || a.picture == b.picture
        return pictures && a.copy(picture = null) == b.copy(picture = null)
    }

    fun minFor(actionType: String?): Long =
        if (actionType == "open_app" || actionType == "open_link") MIN_AFTER_OPEN_MS else MIN_MS

    /**
     * Returns the time waited in milliseconds. [read] null means nothing can be read: the wait is then [minMs].
     * [picture] is asked only for reads that can be one of a deciding pair (from one poll before the minimum on) and
     * not while a spinner shows; null from it means no picture.
     * The first sleep is the remainder of [minMs] over [POLL_MS], so the read at the minimum is a full poll after the
     * one before it. Elapsed time is the larger of the clock and the sum of the sleeps, so a test clock that stands
     * still ends too.
     */
    suspend fun await(
        minMs: Long,
        read: () -> Snapshot?,
        now: () -> Long,
        sleep: suspend (Long) -> Unit,
        maxMs: Long = MAX_MS,
        picture: (() -> Long?)? = null,
    ): Long {
        val start = now()
        var slept = 0L
        fun elapsed() = maxOf(now() - start, slept)
        fun readAt(at: Long): Snapshot? {
            val s = read() ?: return null
            if (picture == null || s.loading || at < minMs - POLL_MS) return s
            return s.copy(picture = picture())
        }
        var previousAt = elapsed()
        var previous = readAt(previousAt)
        // Reads are due at fixed times (the remainder, then every poll), so the time a read takes does not stretch
        // the polls; a read that overran its slot is followed by at least MIN_SLEEP_MS.
        var due = if (minMs > 0 && minMs % POLL_MS != 0L) minMs % POLL_MS else POLL_MS
        while (true) {
            val e = elapsed()
            if (e >= maxMs) return e
            while (due < e + MIN_SLEEP_MS) due += POLL_MS
            val step = (due - e).coerceAtMost(maxMs - e)
            sleep(step)
            slept += step
            val at = elapsed()
            val current = readAt(at)
            val quiet = agree(previous, current) && current?.loading != true && at - previousAt >= MIN_PAIR_MS
            if (quiet && elapsed() >= minMs) return elapsed()
            previous = current
            previousAt = at
        }
    }
}
