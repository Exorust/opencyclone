package ai.opencyclone.ui.widget

import ai.opencyclone.R
import ai.opencyclone.ui.theme.Motion
import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.Choreographer
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.util.AttributeSet
import androidx.annotation.ColorInt
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Shows text word by word (UI.md section 10, motion): word i appears at i * wordMs in ink-3 and reaches
 * its own colour in [Motion.WORD_COLOUR_MS]. A character range can be marked "not yet confirmed"; it stays
 * ink-3 until the mark is cleared (live transcript, words not yet spoken).
 */
class WordRevealTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    @ColorInt private val ink3 = ContextCompat.getColor(context, R.color.ink_3)
    private var words: List<WordSpan> = emptyList()
    private var startedAt = 0L
    private var wordMs = Motion.WORD_MS
    private var totalMs = 0L
    private var running = false
    // Driven by frame callbacks on the uptime clock, so it also runs before the view is attached.
    private val frame: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val elapsed = SystemClock.uptimeMillis() - startedAt
            if (elapsed >= totalMs) {
                running = false
                finish()
            } else {
                applyElapsed(elapsed, wordMs)
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    /** Called once when a reveal has finished or was completed by [showAll]. */
    var onRevealEnd: (() -> Unit)? = null

    val isRevealing: Boolean get() = running

    /** Starts a word-by-word reveal of [text]. Use [Motion.WORD_LONG_SENTENCE_MS] for a long result sentence. */
    fun reveal(text: CharSequence, wordMs: Long = Motion.WORD_MS) {
        cancelAnimation()
        install(text)
        if (words.isEmpty()) {
            finish()
            return
        }
        // The owner turned animations off: show everything at once.
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finish()
            return
        }
        this.wordMs = wordMs
        totalMs = revealDurationMs(words.size, wordMs)
        startedAt = SystemClock.uptimeMillis()
        applyElapsed(0, wordMs)
        running = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /** Shows [text] (or the present text) at once, all words in their own colour. */
    fun showAll(text: CharSequence = this.text) {
        cancelAnimation()
        if (text !== this.text) install(text)
        finish()
    }

    /** Marks [start, end) as not yet confirmed: drawn in ink-3. Replaces an earlier mark. */
    fun markUnconfirmed(start: Int, end: Int) {
        val s = text as? Spannable ?: return
        s.getSpans(0, s.length, UnconfirmedSpan::class.java).forEach { s.removeSpan(it) }
        val from = start.coerceIn(0, s.length)
        val to = end.coerceIn(from, s.length)
        if (from < to) {
            // Higher priority: applied before the word spans, so a word span keeps it at ink-3.
            s.setSpan(UnconfirmedSpan(ink3), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or UNCONFIRMED_PRIORITY)
        }
        invalidate()
    }

    /** Removes the "not yet confirmed" mark. */
    fun clearUnconfirmed() = markUnconfirmed(0, 0)

    /** The marked range, or null. */
    fun unconfirmedRange(): IntRange? {
        val s = text as? Spanned ?: return null
        val span = s.getSpans(0, s.length, UnconfirmedSpan::class.java).firstOrNull() ?: return null
        return s.getSpanStart(span) until s.getSpanEnd(span)
    }

    /** Progress of each word for tests: null while hidden, 0 to 1 while turning from ink-3 to its colour. */
    internal fun wordProgress(): List<Float?> = words.map { it.progress }

    override fun onDetachedFromWindow() {
        cancelAnimation()
        super.onDetachedFromWindow()
    }

    private fun install(text: CharSequence) {
        super.setText(SpannableString(text), BufferType.SPANNABLE)
        val s = this.text as Spannable
        words = wordRanges(s).map { r ->
            WordSpan(ink3).also { s.setSpan(it, r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }

    private fun applyElapsed(elapsedMs: Long, wordMs: Long) {
        words.forEachIndexed { i, w -> w.progress = progressAt(i, elapsedMs, wordMs) }
        invalidate()
    }

    private fun finish() {
        words.forEach { it.progress = 1f }
        invalidate()
        onRevealEnd?.invoke()
    }

    private fun cancelAnimation() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    /** Draws one word: hidden before its turn, then a blend from ink-3 to the colour underneath. */
    private class WordSpan(@ColorInt private val ink3: Int) : CharacterStyle(), UpdateAppearance {
        var progress: Float? = 1f
        override fun updateDrawState(tp: TextPaint) {
            val p = progress
            tp.color = if (p == null) tp.color and 0x00FFFFFF else ColorUtils.blendARGB(ink3, tp.color, p)
        }
    }

    private class UnconfirmedSpan(@ColorInt private val ink3: Int) : CharacterStyle(), UpdateAppearance {
        override fun updateDrawState(tp: TextPaint) { tp.color = ink3 }
    }

    companion object {
        private const val UNCONFIRMED_PRIORITY = 1 shl Spanned.SPAN_PRIORITY_SHIFT
        private val WORD = Regex("\\S+")

        /** Character ranges of the words of [text]. */
        fun wordRanges(text: CharSequence): List<IntRange> = WORD.findAll(text).map { it.range }.toList()

        /** Progress of word [index] after [elapsedMs]: null before its turn, then 0 to 1 over the colour time. */
        fun progressAt(
            index: Int,
            elapsedMs: Long,
            wordMs: Long = Motion.WORD_MS,
            colourMs: Long = Motion.WORD_COLOUR_MS,
        ): Float? {
            val start = index * wordMs
            if (elapsedMs < start) return null
            return ((elapsedMs - start).toFloat() / colourMs).coerceIn(0f, 1f)
        }

        /** Time until the last of [wordCount] words has its colour. */
        fun revealDurationMs(wordCount: Int, wordMs: Long = Motion.WORD_MS, colourMs: Long = Motion.WORD_COLOUR_MS): Long =
            if (wordCount <= 0) 0 else (wordCount - 1) * wordMs + colourMs
    }
}
