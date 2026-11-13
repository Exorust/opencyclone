package ai.opencyclon.ui.widget

import ai.opencyclon.R
import ai.opencyclon.feed.Phase
import ai.opencyclon.feed.RunningTask
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * The running pill (UI.md section 10, section 9 item 3): up to 3 app icons of the running task, the
 * current app last and on top with a dark arc while it works, then "1 running · WhatsApp ›".
 * Hidden when nothing runs and nothing waits. A tap dims it to 50% for 120 ms, then calls [onClick].
 *
 * The host gives it wrap_content width and height and places it at the page margin.
 */
class RunningPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val icons: LinearLayout
    private val label: TextView
    private val iconSize = resources.getDimensionPixelSize(R.dimen.icon_pill)
    private val overlap = resources.getDimensionPixelSize(R.dimen.pill_icon_overlap)
    private var pressPending = false
    // Not View.postDelayed: a detached view queues it until attach.
    private val handler = Handler(Looper.getMainLooper())

    /** Called after the tap dim. The host opens the running panel. */
    var onClick: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = resources.getDimensionPixelSize(R.dimen.pill_height)
        setPaddingRelative(resources.getDimensionPixelSize(R.dimen.gap_receipt), 0, resources.getDimensionPixelSize(R.dimen.padding_card), 0)
        setBackgroundResource(R.drawable.bg_pill_surface)
        elevation = resources.getDimension(R.dimen.elevation_card)
        // ponytail: the same shadow approximation as Widget.App.Card
        outlineSpotShadowColor = 0x33000000
        outlineAmbientShadowColor = 0x14000000
        LayoutInflater.from(context).inflate(R.layout.view_running_pill, this, true)
        icons = findViewById(R.id.running_pill_icons)
        label = findViewById(R.id.running_pill_text)
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = GONE
        setOnClickListener {
            if (pressPending) return@setOnClickListener
            pressPending = true
            alpha = 0.5f
            handler.postDelayed({
                pressPending = false
                alpha = 1f
                onClick?.invoke()
            }, PRESS_MS)
        }
    }

    /** Shows the first running task (version 1 runs one at a time) and the number of waiting tasks. */
    fun bind(running: List<RunningTask>, queued: Int) {
        val task = running.firstOrNull()
        if (task == null && queued <= 0) {
            visibility = GONE
            icons.removeAllViews()
            label.text = ""
            contentDescription = null
            return
        }
        visibility = VISIBLE
        bindIcons(task)
        val (lead, detail) = parts(task, queued)
        label.text = styled(lead, detail)
        contentDescription = if (detail != null) {
            context.getString(R.string.running_pill_description_detail, lead, detail)
        } else {
            context.getString(R.string.running_pill_description, lead)
        }
    }

    /** The pill text as shown, without styling (tests). */
    @VisibleForTesting internal fun text(): String = label.text.toString()

    /** The icon views, in drawing order (tests). */
    @VisibleForTesting internal fun iconViews(): List<ImageView> = (0 until icons.childCount).map { icons.getChildAt(it) as ImageView }

    /** Whether icon [index] draws the working arc (tests). */
    @VisibleForTesting internal fun hasArc(index: Int): Boolean = (icons.getChildAt(index) as? ArcIconView)?.working == true

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    /** The weight-500 part and the ink_2 part of the text (without " · " and " ›"). */
    private fun parts(task: RunningTask?, queued: Int): Pair<String, String?> {
        val waiting = if (queued > 0) context.getString(R.string.running_waiting, queued) else null
        if (task == null) return waiting.orEmpty() to null
        return when (task.phase) {
            Phase.RUNNING -> context.getString(R.string.running_count, 1) to (waiting ?: appName(context, task.packages.lastOrNull()))
            Phase.PAUSED -> context.getString(R.string.running_paused, 1) to waiting
            Phase.WAITING_APPROVAL -> context.getString(R.string.running_ready) to null
            Phase.WAITING_ANSWER -> context.getString(R.string.running_question) to null
        }
    }

    private fun styled(lead: String, detail: String?): CharSequence {
        val sb = SpannableStringBuilder(lead)
        sb.setSpan(TypefaceSpan(medium(context)), 0, lead.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val start = sb.length
        if (!detail.isNullOrEmpty()) sb.append(context.getString(R.string.running_detail, detail))
        sb.append(context.getString(R.string.running_chevron))
        sb.setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.ink_2)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun bindIcons(task: RunningTask?) {
        icons.removeAllViews()
        val packages = task?.packages?.takeLast(MAX_ICONS).orEmpty()
        icons.visibility = if (packages.isEmpty()) GONE else VISIBLE
        packages.forEachIndexed { i, pkg ->
            val view = ArcIconView(context).apply {
                layoutParams = LayoutParams(iconSize, iconSize).apply { if (i > 0) marginStart = -overlap }
                working = i == packages.lastIndex && task?.phase == Phase.RUNNING
            }
            icons.addView(view)
            AppIcons.into(view, pkg, appName(context, pkg))
        }
    }

    /** An app icon that can draw a dark 2 dp arc along its top right corner. */
    internal class ArcIconView(context: Context) : AppCompatImageView(context) {
        private val stroke = 2 * resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = ContextCompat.getColor(context, R.color.ink)
        }
        private val path = Path()

        var working = false
            set(value) { field = value; invalidate() }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val half = stroke / 2
            val r = w * AppIcons.CORNER_FRACTION
            path.reset()
            path.moveTo(w * 0.5f, half)
            path.lineTo(w - r, half)
            path.arcTo(RectF(w - 2 * r + half, half, w - half, 2 * r - half), -90f, 90f)
            path.lineTo(w - half, h * 0.5f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (working) canvas.drawPath(path, paint)
        }
    }

    companion object {
        const val MAX_ICONS = 3
        const val PRESS_MS = 120L

        /** The app's label from the package manager, or the package name when it cannot be read. */
        fun appName(context: Context, packageName: String?): String {
            if (packageName.isNullOrEmpty()) return ""
            return try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().ifBlank { packageName }
            } catch (_: PackageManager.NameNotFoundException) {
                packageName
            }
        }

        internal fun medium(context: Context): Typeface {
            val family = runCatching { ResourcesCompat.getFont(context, R.font.inter) }.getOrNull() ?: Typeface.DEFAULT
            return Typeface.create(family, 500, false)
        }
    }
}
