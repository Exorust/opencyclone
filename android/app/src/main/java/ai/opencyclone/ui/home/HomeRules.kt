package ai.opencyclone.ui.home

import ai.opencyclone.R
import ai.opencyclone.feed.LiveState
import ai.opencyclone.feed.ThreadItem
import ai.opencyclone.feed.ThreadKind
import ai.opencyclone.ui.theme.Wash
import androidx.annotation.StringRes

/** The home page's choices that depend only on the feed and the clock (UI.md 5.1 to 5.10, sections 9 and 10). */
object HomeRules {

    /** "Good morning." 5 to 11, "Good afternoon." 12 to 17, "Good evening." 18 to 4. */
    @StringRes
    fun greeting(hour: Int): Int = when (hour) {
        in 5..11 -> R.string.home_greeting_morning
        in 12..17 -> R.string.home_greeting_afternoon
        else -> R.string.home_greeting_evening
    }

    /** A task runs, an approval waits, or a question waits: the status is all `ink`. */
    fun isWorking(live: LiveState): Boolean =
        live.running.isNotEmpty() || live.approval != null || live.question != null

    /** The notification window is active at [now]. */
    fun windowActive(live: LiveState, now: Long): Boolean = live.window?.let { it.endsAt > now } == true

    /**
     * `Cool` while working or while the notification window is active, and when idle in the evening
     * (from 18:00 until the morning greeting at 5:00); `Warm` when idle from 5:00 to 18:00.
     */
    fun wash(live: LiveState, hour: Int, now: Long): Wash = when {
        isWorking(live) || windowActive(live, now) -> Wash.Cool
        hour >= 18 || hour < 5 -> Wash.Cool
        else -> Wash.Warm
    }

    /** Section 10: the footer is hidden while the thread ends in an app change (a PROGRESS item). */
    fun footerHidden(items: List<ThreadItem>): Boolean = items.lastOrNull()?.kind == ThreadKind.PROGRESS

    /** The owner's most recent words in today's thread, for the speaking screen. */
    fun lastOwnerWords(items: List<ThreadItem>): String? =
        items.lastOrNull { it.kind == ThreadKind.OWNER }?.text?.takeIf { it.isNotBlank() }
}
