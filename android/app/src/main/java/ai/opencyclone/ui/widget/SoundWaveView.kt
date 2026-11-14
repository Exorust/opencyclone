package ai.opencyclone.ui.widget

import ai.opencyclone.R
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sound wave of the listening and speaking screens (UI.md section 10): 24 bars, 3 dp wide, 4 dp apart,
 * round ends, 4 to 54 dp high, in ink. [setLevel] pushes a new level at the right and the bars scroll left.
 * [setFlat] draws the flat dotted line (every bar at its least height, so each is a dot).
 * With animations turned off in the system settings, the wave stays flat.
 */
class SoundWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val barWidth = BAR_WIDTH_DP * density
    private val barGap = BAR_GAP_DP * density
    private val minHeight = MIN_HEIGHT_DP * density
    private val maxHeight = MAX_HEIGHT_DP * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink)
        strokeWidth = barWidth
        strokeCap = Paint.Cap.ROUND
    }
    private val levels = FloatArray(BARS)

    /** True while every bar is at its least height (the dotted line). */
    var isFlat: Boolean = true
        private set

    /** Pushes a level from 0 to 1; the oldest bar leaves at the left. */
    fun setLevel(level: Float) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            setFlat()
            return
        }
        System.arraycopy(levels, 1, levels, 0, BARS - 1)
        levels[BARS - 1] = level.coerceIn(0f, 1f)
        isFlat = false
        invalidate()
    }

    /** The flat dotted line: before speech and after the end. */
    fun setFlat() {
        levels.fill(0f)
        isFlat = true
        invalidate()
    }

    /** The current levels, oldest first, for tests. */
    internal fun levels(): FloatArray = levels.copyOf()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (BARS * barWidth + (BARS - 1) * barGap + barWidth).toInt() + paddingLeft + paddingRight
        val h = maxHeight.toInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val total = BARS * barWidth + (BARS - 1) * barGap
        val left = (width - total) / 2f + barWidth / 2f
        val cy = height / 2f
        for (i in 0 until BARS) {
            val h = barHeight(levels[i], i, minHeight, maxHeight)
            // A round cap adds half the width at each end; draw the line shorter by the width.
            val half = ((h - barWidth) / 2f).coerceAtLeast(0f)
            val x = left + i * (barWidth + barGap)
            canvas.drawLine(x, cy - half, x, cy + half, paint)
        }
    }

    companion object {
        const val BARS = 24
        const val BAR_WIDTH_DP = 3f
        const val BAR_GAP_DP = 4f
        const val MIN_HEIGHT_DP = 4f
        const val MAX_HEIGHT_DP = 54f

        /** Height of bar [index] for [level]: the edges are lower, so the wave is tall in the middle. */
        fun barHeight(level: Float, index: Int, min: Float, max: Float): Float {
            val envelope = 0.35f + 0.65f * sin(PI * (index + 0.5) / BARS).toFloat()
            return min + (max - min) * level.coerceIn(0f, 1f) * envelope
        }

        /**
         * Maps a microphone level (root mean square, 0 to 1) to a bar level. Speech is about 0.02 to 0.2
         * root mean square, so the square root spreads it over the bar heights.
         */
        fun fromRms(rms: Float): Float = (sqrt(rms.coerceIn(0f, 1f)) * 2.2f).coerceIn(0f, 1f)
    }
}
