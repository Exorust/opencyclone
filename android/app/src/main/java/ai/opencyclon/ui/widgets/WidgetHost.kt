package ai.opencyclon.ui.widgets

import ai.opencyclon.R
import ai.opencyclon.feed.FeedLocator
import ai.opencyclon.ui.ControlPanelActivity
import ai.opencyclon.widgets.WidgetBridge
import ai.opencyclon.widgets.WidgetHtml
import ai.opencyclon.widgets.WidgetDimensions
import ai.opencyclon.widgets.WidgetSize
import ai.opencyclon.widgets.WidgetSources
import ai.opencyclon.widgets.WidgetTester
import ai.opencyclon.widgets.WidgetWeb
import ai.opencyclon.widgets.Widgets
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope

/**
 * One widget shown in one WebView inside [container]: the page's bridge, the tap flag for `phone.ask`, refresh,
 * and destroy. Every WebView of a widget is made by [create] and ended by [destroy], so tests can count them.
 */
@SuppressLint("ClickableViewAccessibility")
class WidgetHost(
    private val container: FrameLayout,
    key: String,
    html: String,
    allowed: Collection<String>,
    scope: CoroutineScope,
    onStopped: () -> Unit,
    onAsk: (String) -> Unit,
    private val size: WidgetSize = WidgetSize.SMALL,
) {
    private val view: WebView = create(container.context)
    @Volatile private var touchedAt = Long.MIN_VALUE / 2
    @Volatile private var destroyed = false

    private val measureHeight = Runnable {
        if (!destroyed && size == WidgetSize.TALL) {
            view.evaluateJavascript("Math.ceil(document.body ? document.body.scrollHeight : 0)") { result ->
                if (!destroyed) result?.toIntOrNull()?.takeIf { it > 0 }?.let { cssHeight ->
                    val card = container.parent as? WidgetCardView ?: return@let
                    val height = (WidgetDimensions.height(size, cssHeight) * container.resources.displayMetrics.density).toInt()
                    if (card.layoutParams.height != height) {
                        card.layoutParams = card.layoutParams.apply { this.height = height }
                    }
                }
            }
        }
    }

    init {
        view.setBackgroundColor(ContextCompat.getColor(container.context, R.color.surface))
        // A long press belongs to the card (move, change, remove), not to text selection in the page.
        view.isLongClickable = false
        view.setOnLongClickListener { true }
        view.isHapticFeedbackEnabled = false
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) touchedAt = SystemClock.uptimeMillis()
            false
        }
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val log = WidgetBridge.logger(container.context)
        val bridge = WidgetBridge(
            widgetKey = key,
            allowed = allowed.toSet() + WidgetSources.CLOCK,
            read = { request -> Widgets.sources.read(request, WidgetHtml.toolArgs(html, WidgetSources.sourceOf(request))) },
            deliver = { js -> view.post { if (!destroyed) view.evaluateJavascript(js) { scheduleHeight() } } },
            scope = scope,
            userGesture = { SystemClock.uptimeMillis() - touchedAt < GESTURE_MS },
            ask = { text -> view.post { if (!destroyed) onAsk(text) } },
            onFailed = { view.post { if (!destroyed) onStopped() } },
            log = log,
        )
        WidgetWeb.load(view, key, html, bridge, onFinished = { scheduleHeight() }, onError = { view.post { if (!destroyed) onStopped() } })
    }

    private fun scheduleHeight() {
        if (destroyed || size != WidgetSize.TALL) return
        view.removeCallbacks(measureHeight)
        // Wait for promise callbacks, layout and the packaged font after loading or receiving data.
        view.postDelayed(measureHeight, WidgetTester.RENDER_MS)
    }

    fun refresh() {
        if (!destroyed) view.evaluateJavascript(WidgetWeb.REFRESH_JS) { scheduleHeight() }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        view.removeCallbacks(measureHeight)
        container.removeView(view)
        view.stopLoading()
        view.destroy()
        live--
    }

    companion object {
        const val GESTURE_MS = 1_500L

        /** Makes each widget WebView; tests replace it. */
        @Volatile var webViewFactory: (Context) -> WebView = { WebView(it) }
        /** WebViews made by [create] and not yet destroyed. */
        @Volatile var live = 0; private set
        /** WebViews made since the process started (tests). */
        @Volatile var created = 0; private set

        private fun create(context: Context): WebView {
            live++
            created++
            return webViewFactory(context)
        }

        /** `surface`, radius 20 dp; the shadow is the card's elevation. */
        fun cardBackground(context: Context) = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.surface))
            cornerRadius = context.resources.getDimension(R.dimen.radius_card)
        }

        /** Card width in pixels for [size] when the content area is [contentWidth] wide with [gap] between columns. */
        fun cardWidth(size: WidgetSize, contentWidth: Int, gap: Int): Int =
            WidgetDimensions.width(size, contentWidth, gap)

        fun cardHeight(context: Context, size: WidgetSize): Int = (size.heightDp * context.resources.displayMetrics.density).toInt()

        /**
         * A widget's request in the owner's name: the home page comes to the front (through [showHome], or an intent
         * to the home activity), then the words go through the normal request path and its approval gate.
         */
        fun sendAsOwner(context: Context, text: String, showHome: (() -> Unit)?) {
            if (showHome != null) showHome() else context.startActivity(homeIntent(context, PAGE_HOME))
            if (FeedLocator.isReady) FeedLocator.commands.submit(text)
        }

        fun homeIntent(context: Context, page: String): Intent = Intent(context, ControlPanelActivity::class.java)
            .putExtra(EXTRA_OPEN_PAGE, page)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** Read by the home activity (lane H1): which page to show. */
        const val EXTRA_OPEN_PAGE = "ai.opencyclon.extra.OPEN_PAGE"
        const val PAGE_WIDGETS = "widgets"
        const val PAGE_HOME = "home"
    }
}
