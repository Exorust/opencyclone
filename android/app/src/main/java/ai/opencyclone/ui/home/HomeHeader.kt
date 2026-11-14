package ai.opencyclone.ui.home

import ai.opencyclone.R
import ai.opencyclone.ui.theme.Motion
import ai.opencyclone.ui.widget.WordRevealTextView
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The home header (UI.md section 4 with section 10): time and date, the greeting by the hour, and the status
 * sentence. A changed status fades to `ink_3`, then the new sentence appears word by word.
 */
class HomeHeader(root: View) {
    private val context = root.context
    private val time: TextView = root.findViewById(R.id.header_time)
    private val date: TextView = root.findViewById(R.id.header_date)
    val greeting: TextView = root.findViewById(R.id.header_greeting)
    val status: WordRevealTextView = root.findViewById(R.id.header_status)

    private val ink = ContextCompat.getColor(context, R.color.ink)
    private val ink2 = ContextCompat.getColor(context, R.color.ink_2)
    private val ink3 = ContextCompat.getColor(context, R.color.ink_3)
    private var fade: ValueAnimator? = null
    private var shownStatus: String? = null
    private var emphasis: IntRange? = null
    private var working = false

    /** Number of status changes that started the fade and reveal (tests). */
    var statusChanges = 0
        private set

    /** Time ("1:41 PM", or "13:41" with the 24-hour setting), date ("Fri, Oct 23"), and greeting at [now]. */
    fun setClock(now: Long) {
        val d = Date(now)
        time.text = DateFormat.getTimeFormat(context).format(d)
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd")
        date.text = DateFormat.format(pattern, d)
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        greeting.setText(HomeRules.greeting(hour))
    }

    /**
     * Shows [text]: `ink` while [isWorking], `ink_2` when idle with the characters of [emphasis] in `ink` (the noun,
     * UI.md section 10). The first sentence shows at once; a changed one fades the old text to `ink_3` over 160 ms,
     * then reveals the new one.
     */
    fun setStatus(text: String, isWorking: Boolean, emphasis: IntRange? = null) {
        val colourChanged = working != isWorking
        val emphasisChanged = this.emphasis != emphasis
        working = isWorking
        this.emphasis = emphasis
        val target = if (isWorking) ink else ink2
        val previous = shownStatus
        if (previous == text) {
            if ((colourChanged || emphasisChanged) && fade == null) {
                status.setTextColor(target)
                // Only a change of the span needs the text again; a colour change alone keeps a running reveal.
                if (emphasisChanged || emphasis != null) status.showAll(styled(text))
            }
            return
        }
        shownStatus = text
        if (previous == null) {
            status.setTextColor(target)
            status.showAll(styled(text))
            return
        }
        statusChanges++
        fade?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            status.setTextColor(target)
            status.reveal(styled(text))
            return
        }
        val from = status.currentTextColor
        fade = ValueAnimator.ofObject(ArgbEvaluator(), from, ink3).apply {
            duration = Motion.LEAVE_MS
            addUpdateListener { status.setTextColor(it.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (fade === animation) fade = null
                    if (cancelled) return
                    status.setTextColor(if (working) ink else ink2)
                    status.reveal(styled(shownStatus.orEmpty()))
                }
            })
            start()
        }
    }

    /** [text] with the emphasis span when idle; plain while working (the whole sentence is `ink`). */
    internal fun styled(text: String): CharSequence {
        val range = emphasis?.takeIf { !working && it.first >= 0 && it.last < text.length && !it.isEmpty() } ?: return text
        return SpannableString(text).apply {
            setSpan(ForegroundColorSpan(ink), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun cancel() {
        fade?.cancel()
        fade = null
    }
}
