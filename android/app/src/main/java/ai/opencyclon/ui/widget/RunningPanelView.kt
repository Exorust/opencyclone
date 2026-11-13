package ai.opencyclon.ui.widget

import ai.opencyclon.R
import ai.opencyclon.feed.Feed
import ai.opencyclon.feed.RunningTask
import ai.opencyclon.ui.theme.Motion
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The running panel (UI.md section 10, section 9 item 3): a header band in `surface` over a body in
 * `panel_body` with one live preview of the running task, its badge "Live 0:34", the app and the caption,
 * a pale "Stop", and one line for each waiting task.
 *
 * The host places it 14 dp under the pill at the page width, draws the scrim, and calls [open] and [close].
 * It starts hidden. Call [collectPreviews] once so it shows pictures while open.
 */
class RunningPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val header: View
    private val subtitle: WordRevealTextView
    private val body: View
    private val taskGroup: View
    private val preview: LivePreviewView
    private val live: TextView
    private val icon: ImageView
    private val app: TextView
    private val caption: WordRevealTextView
    private val stop: Button
    private val waiting: LinearLayout

    private var task: RunningTask? = null
    private var unroll: ValueAnimator? = null
    private var scope: CoroutineScope? = null
    private var feed: Feed? = null
    private var job: Job? = null
    private var collectingTaskId: Long? = null
    // View.isAttachedToWindow is still true inside onDetachedFromWindow, so track it here.
    private var attached = false

    /** Called with the task id when the owner taps "Stop". */
    var onStop: ((Long) -> Unit)? = null

    /** Called with the task id when the owner taps the preview. */
    var onOpenLive: ((Long) -> Unit)? = null

    /** Clock for the live badge (tests replace it). */
    @VisibleForTesting internal var clock: () -> Long = System::currentTimeMillis

    /** True between [open] and [close]. */
    var isOpen: Boolean = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            updateLive()
            val t = task ?: return
            if (isOpen && attached) postDelayed(this, TICK_MS - (clock() - t.startedAt).mod(TICK_MS))
        }
    }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_running_panel, this, true)
        header = findViewById(R.id.running_panel_header)
        subtitle = findViewById(R.id.running_panel_subtitle)
        body = findViewById(R.id.running_panel_body)
        taskGroup = findViewById(R.id.running_panel_task)
        preview = findViewById(R.id.running_panel_preview)
        live = findViewById(R.id.running_panel_live)
        icon = findViewById(R.id.running_panel_icon)
        app = findViewById(R.id.running_panel_app)
        caption = findViewById(R.id.running_panel_caption)
        stop = findViewById(R.id.running_panel_stop)
        waiting = findViewById(R.id.running_panel_waiting)

        val radius = resources.getDimension(R.dimen.radius_panel)
        header.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.surface))
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
        val dotSize = resources.getDimensionPixelSize(R.dimen.gap_chip)
        live.setCompoundDrawablesRelative(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, R.color.ok))
            setBounds(0, 0, dotSize, dotSize)
        }, null, null, null)
        live.compoundDrawablePadding = dotSize / 2
        preview.setOnClickListener { task?.let { onOpenLive?.invoke(it.taskId) } }
        stop.setOnClickListener { task?.let { onStop?.invoke(it.taskId) } }
        visibility = GONE
        bind(null, emptyList())
    }

    /** Shows [task] (null for none) and one line for each of [queuedGoals]. */
    fun bind(task: RunningTask?, queuedGoals: List<String>) {
        val previous = this.task
        this.task = task
        if (task == null || previous?.taskId != task.taskId) preview.setBitmap(null)
        taskGroup.visibility = if (task == null) GONE else VISIBLE
        if (task != null) {
            val pkg = task.packages.lastOrNull()
            val name = RunningPillView.appName(context, pkg)
            AppIcons.into(icon, pkg, name)
            app.text = name
            app.visibility = if (name.isEmpty()) GONE else VISIBLE
            preview.contentDescription = context.getString(R.string.running_preview_description, name.ifEmpty { task.caption })
            if (caption.text.toString() != task.caption) {
                if (isOpen && unroll == null && ValueAnimator.areAnimatorsEnabled()) caption.reveal(task.caption)
                else caption.showAll(task.caption)
            }
            updateLive()
        }
        bindWaiting(queuedGoals)
        updateCollection()
        restartTick()
    }

    /** Shows [bitmap] in the preview; null returns to the grey rectangle. */
    fun setPreview(bitmap: Bitmap?) = preview.setBitmap(bitmap)

    /**
     * Collects `feed.previews(taskId, previewWidthPx)` into the preview while the panel is open and attached,
     * and cancels the collection when it closes or detaches (the engine captures only while collected).
     * [scope] must run on the main thread, for example `lifecycleScope`; its cancellation also ends collection.
     */
    fun collectPreviews(scope: CoroutineScope, feed: Feed) {
        job?.cancel()
        job = null
        collectingTaskId = null
        this.scope = scope
        this.feed = feed
        updateCollection()
    }

    /** Opens the panel with the motion of UI.md section 10; with animations off everything shows at once. */
    fun open() {
        if (isOpen) return
        isOpen = true
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        val t = task
        if (ValueAnimator.areAnimatorsEnabled()) {
            header.alpha = 0f
            header.animate().alpha(1f).setDuration(HEADER_MS).start()
            subtitle.reveal(subtitle.text.toString())
            caption.text = ""
            startUnroll { task?.let { caption.reveal(it.caption) } }
        } else {
            header.alpha = 1f
            subtitle.showAll()
            if (t != null) caption.showAll(t.caption)
        }
        updateCollection()
        restartTick()
    }

    /** Closes the panel: stops the preview collection at once and fades out over 160 ms. */
    fun close() {
        if (!isOpen) return
        isOpen = false
        unroll?.cancel()
        unroll = null
        body.clipBounds = null
        removeCallbacks(tick)
        updateCollection()
        animate().cancel()
        animate().alpha(0f).setDuration(Motion.LEAVE_MS).withEndAction { if (!isOpen) visibility = GONE }.start()
    }

    /** The badge text, "Live 0:34" (tests). */
    @VisibleForTesting internal fun liveText(): String = live.text.toString()

    /** The waiting lines as shown (tests). */
    @VisibleForTesting internal fun waitingLines(): List<String> =
        (0 until waiting.childCount).map { (waiting.getChildAt(it) as TextView).text.toString() }

    @VisibleForTesting internal fun previewView(): LivePreviewView = preview
    @VisibleForTesting internal fun stopButton(): Button = stop
    @VisibleForTesting internal fun captionText(): String = caption.text.toString()
    @VisibleForTesting internal fun appText(): String = app.text.toString()
    @VisibleForTesting internal fun hasTask(): Boolean = taskGroup.visibility == VISIBLE
    @VisibleForTesting internal val isCollecting: Boolean get() = job?.isActive == true

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        updateCollection()
        restartTick()
    }

    override fun onDetachedFromWindow() {
        attached = false
        removeCallbacks(tick)
        unroll?.cancel()
        unroll = null
        super.onDetachedFromWindow()
        updateCollection()
    }

    private fun startUnroll(then: () -> Unit) {
        unroll?.cancel()
        body.clipBounds = Rect(0, 0, body.width, 0)
        unroll = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = UNROLL_MS
            addUpdateListener {
                body.clipBounds = Rect(0, 0, body.width, (body.height * (it.animatedValue as Float)).toInt())
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    body.clipBounds = null
                    if (unroll === animation) unroll = null
                    if (!cancelled) then()
                }
            })
            start()
        }
    }

    private fun bindWaiting(goals: List<String>) {
        waiting.removeAllViews()
        waiting.visibility = if (goals.isEmpty()) GONE else VISIBLE
        val gap = resources.getDimensionPixelSize(R.dimen.gap_chip)
        goals.forEachIndexed { i, goal ->
            waiting.addView(TextView(context).apply {
                setTextAppearance(R.style.TextAppearance_App_Small)
                text = context.getString(R.string.running_waiting_line, goal)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = when {
                    i > 0 -> gap / 2
                    task != null -> resources.getDimensionPixelSize(R.dimen.gap_heading)
                    else -> 0
                }
            })
        }
    }

    private fun updateLive() {
        val t = task ?: return
        live.text = context.getString(R.string.running_live, liveTime(clock() - t.startedAt))
    }

    private fun restartTick() {
        removeCallbacks(tick)
        if (isOpen && attached && task != null) tick.run()
    }

    private fun updateCollection() {
        val t = task
        val s = scope
        val f = feed
        if (!isOpen || !attached || t == null || s == null || f == null) {
            job?.cancel()
            job = null
            collectingTaskId = null
            return
        }
        if (job?.isActive == true && collectingTaskId == t.taskId) return
        job?.cancel()
        val id = t.taskId
        collectingTaskId = id
        val width = resources.getDimensionPixelSize(R.dimen.preview_width)
        job = s.launch { f.previews(id, width).collect { if (collectingTaskId == id) setPreview(it) } }
    }

    companion object {
        const val HEADER_MS = 120L
        const val UNROLL_MS = 300L
        private const val TICK_MS = 1000L

        /** "0:34", "12:05", "1:02:09" for an elapsed time; negative counts as zero. */
        fun liveTime(elapsedMs: Long): String {
            val total = (elapsedMs.coerceAtLeast(0) / 1000)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}
