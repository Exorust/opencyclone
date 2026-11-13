package ai.opencyclon.proactive

import android.content.Context
import android.content.SharedPreferences
import ai.opencyclon.tasks.WindowDao
import ai.opencyclon.feed.ThreadKind
import ai.opencyclon.feed.ThreadRecorder
import ai.opencyclon.feed.ThreadState
import ai.opencyclon.R
import kotlinx.coroutines.flow.first

/** Consent for notification suggestions. Hide rules still run locally while this is off. */
class Suggestions(private val prefs: SharedPreferences, private val windows: WindowDao? = null) {
    constructor(context: Context, windows: WindowDao? = null) :
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), windows)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) { prefs.edit().putBoolean(KEY, value).apply() }

    suspend fun allowed(now: Long): Boolean = enabled ||
        windows?.latest()?.let { it.startedAt <= now && it.endsAt > now } == true

    suspend fun request(context: Context, recorder: ThreadRecorder, enable: Boolean) {
        if (!enable) enabled = false
        // A later request replaces any unanswered invitation to turn on.
        recorder.today().first().filter {
            it.kind == ThreadKind.CONFIRM && it.title == CONFIRM_REF && it.state == ThreadState.OPEN
        }.forEach { recorder.resolve(it.id, ThreadState.REJECTED) }
        if (enable) recorder.confirm(context.getString(R.string.suggestions_confirmation),
            context.getString(R.string.suggestions_turn_on), CONFIRM_REF)
        else recorder.sentence(context.getString(R.string.suggestions_stopped))
    }

    /** True when this is our reference, including an already answered row. */
    suspend fun confirm(recorder: ThreadRecorder, itemId: Long, yes: Boolean): Boolean {
        val item = recorder.get(itemId) ?: return false
        if (item.kind != ThreadKind.CONFIRM || item.title != CONFIRM_REF) return false
        if (item.state == ThreadState.OPEN) {
            if (yes) enabled = true
            recorder.resolve(itemId, if (yes) ThreadState.ANSWERED else ThreadState.REJECTED)
        }
        return true
    }

    companion object {
        const val PREFS = "proactive"
        const val KEY = "suggest_from_notifications"
        /** Stored CONFIRM reference, handled before the other home confirmations. */
        const val CONFIRM_REF = "notification_suggestions"

        fun phrase(text: String): Boolean? = when (text.lowercase(java.util.Locale.ROOT)) {
            "suggest tasks from my notifications", "turn on suggestions" -> true
            "stop suggesting tasks", "turn off suggestions" -> false
            else -> null
        }
    }
}
