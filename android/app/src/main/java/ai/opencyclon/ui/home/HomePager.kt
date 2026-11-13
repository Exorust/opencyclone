package ai.opencyclon.ui.home

import ai.opencyclon.ui.theme.Motion
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The three pages side by side (F15, F16): History, Home, Widgets. Each child fills the pager; child i sits at
 * i times the width and the pager scrolls to the shown page. All pages exist from the start, so the home page's
 * views are found in onCreate. Pages that are not shown are invisible (no touches, no accessibility focus).
 *
 * A drag pages only when it is mostly sideways (|dx| > 2 |dy|), when [canPage] allows it, when no child under the
 * finger scrolls sideways, and when it did not start inside the system gesture inset at the left or right edge
 * (that swipe is Android's Back gesture, F15.7). A mostly vertical move is left to the thread.
 */
class HomePager @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    /** The shown page. */
    var page = HOME
        private set

    /** False while the running panel, the type input, or the app search is open. */
    var canPage: () -> Boolean = { true }
    var onPageChanged: (Int) -> Unit = {}

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * FLING_FACTOR
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var refused = false
    private var velocity: VelocityTracker? = null
    private var settle: ValueAnimator? = null

    /** Shows [target] (HISTORY, HOME, or WIDGETS), sliding when [animate]. */
    fun show(target: Int, animate: Boolean = true) {
        val t = target.coerceIn(0, childCount - 1)
        if (!animate || width == 0 || !ValueAnimator.areAnimatorsEnabled()) {
            settle?.cancel()
            settle = null
            scrollTo(t * width, 0)
            settled(t)
            return
        }
        settleTo(t)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (!dragging && settle == null) scrollTo(page * w, 0)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        settled(page)
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        child.visibility = if (indexOfChild(child) == page) View.VISIBLE else View.INVISIBLE
    }

    // ── Touch ───────────────────────────────────────────────────────────

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(ev)
            MotionEvent.ACTION_MOVE -> if (!dragging) maybeStartDrag(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down(ev); return !refused }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) maybeStartDrag(ev)
                if (dragging) {
                    val max = (childCount - 1) * width
                    scrollTo((scrollX - (ev.x - lastX)).roundToInt().coerceIn(0, max), 0)
                    lastX = ev.x
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val v = velocity?.run { computeCurrentVelocity(1000); xVelocity } ?: 0f
                    val target = when {
                        v < -minFling -> page + 1
                        v > minFling -> page - 1
                        else -> (scrollX.toFloat() / width).roundToInt()
                    }.coerceIn(0, childCount - 1)
                    settleTo(target)
                }
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) settleTo(page)
                reset()
            }
        }
        return true
    }

    private fun down(ev: MotionEvent) {
        reset()
        downX = ev.x
        downY = ev.y
        lastX = ev.x
        refused = !canPage() || inEdgeInset(ev.x)
        velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    private fun track(ev: MotionEvent) {
        velocity?.addMovement(ev)
    }

    private fun maybeStartDrag(ev: MotionEvent) {
        if (refused) return
        velocity?.addMovement(ev)
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dy) > slop && abs(dx) <= 2 * abs(dy)) {
            refused = true // a vertical move: the thread scrolls, the page stays
            return
        }
        if (abs(dx) <= slop || abs(dx) <= 2 * abs(dy)) return
        // Toward a page that does not exist, or into a child that scrolls sideways itself: not ours.
        val towards = if (dx > 0) page - 1 else page + 1
        if (towards !in 0 until childCount || childScrolls(getChildAt(page), -dx.toInt(), downX + scrollX - page * width, downY)) {
            refused = true
            return
        }
        settle?.cancel()
        settle = null
        dragging = true
        lastX = ev.x
        parent?.requestDisallowInterceptTouchEvent(true)
        for (i in 0 until childCount) getChildAt(i).visibility = View.VISIBLE
    }

    private fun reset() {
        dragging = false
        velocity?.recycle()
        velocity = null
    }

    /** The system gesture inset at the left and right edge: Android's Back gesture starts there. */
    private fun inEdgeInset(x: Float): Boolean {
        val insets = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.systemGestures()) ?: return false
        return x < insets.left || x > width - insets.right
    }

    /** True when a view under ([x], [y]) inside [v] can scroll sideways by [direction]. */
    private fun childScrolls(v: View, direction: Int, x: Float, y: Float): Boolean {
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val c = v.getChildAt(i)
                if (c.visibility != View.VISIBLE) continue
                val cx = x + v.scrollX - c.left
                val cy = y + v.scrollY - c.top
                if (cx >= 0 && cx < c.width && cy >= 0 && cy < c.height && childScrolls(c, direction, cx, cy)) return true
            }
        }
        return v !== getChildAt(page) && v.canScrollHorizontally(direction)
    }

    private fun settleTo(target: Int) {
        settle?.cancel()
        val from = scrollX
        val to = target * width
        if (from == to || !ValueAnimator.areAnimatorsEnabled()) {
            scrollTo(to, 0)
            settled(target)
            return
        }
        for (i in 0 until childCount) getChildAt(i).visibility = View.VISIBLE
        settle = ValueAnimator.ofInt(from, to).apply {
            duration = Motion.ENTER_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (settle === animation) settle = null
                    if (!cancelled) settled(target)
                }
            })
            start()
        }
    }

    private fun settled(target: Int) {
        val changed = target != page
        page = target
        for (i in 0 until childCount) getChildAt(i).visibility = if (i == target) View.VISIBLE else View.INVISIBLE
        if (changed) onPageChanged(target)
    }

    companion object {
        const val HISTORY = 0
        const val HOME = 1
        const val WIDGETS = 2
        private const val FLING_FACTOR = 4
    }
}
