package ai.opencyclon.widgets

import ai.opencyclon.R
import android.content.Context
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The widget load test: the file in a WebView that is never shown, at its real size, with a test bridge that
 * answers each allowed source with sample JSON. It must show text within [RENDER_MS] and report no error.
 */
class WidgetTester(context: Context) {
    private val context = context.applicationContext

    suspend fun test(html: String, size: WidgetSize, samples: Map<String, String>): List<String> = withContext(Dispatchers.Main) {
        val problems = mutableListOf<String>()
        val reads = mutableSetOf<String>()
        val finished = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val view = WebView(context)
        try {
            val density = context.resources.displayMetrics.density
            val width = WidgetDimensions.widthPx(context.resources, size)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((size.heightDp * density).toInt(), View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        problems += "${message.message()} (line ${message.lineNumber()})"
                    }
                    return true
                }
            }
            val bridge = WidgetBridge(TEST_KEY, samples.keys, read = { request ->
                val source = WidgetSources.sourceOf(request)
                reads += source
                samples[source]
            },
                deliver = { js -> view.post { view.evaluateJavascript(js, null) } }, scope = scope,
                onFailed = { problems += "Uncaught: $it" })
            WidgetWeb.load(view, TEST_KEY, html, bridge, onFinished = { finished.complete(Unit) },
                onError = { problems += NOT_LOADED })
            if (withTimeoutOrNull(LOAD_TIMEOUT_MS) { finished.await() } == null) problems += NOT_LOADED
            delay(RENDER_MS)
            val textLength = evaluate(view, "(document.body && document.body.innerText || '').trim().length")
            if ((textLength.toIntOrNull() ?: 0) == 0) problems += BLANK
            problems += measureFit(view)
            // A refresh must not break it either.
            view.evaluateJavascript(WidgetWeb.REFRESH_JS, null)
            delay(REFRESH_WATCH_MS)
            problems += measureFit(view)
            if (missingSourceReads(samples.keys, reads)) problems += context.getString(R.string.x4_widget_no_reads)
            problems.distinct()
        } finally {
            scope.cancel()
            view.stopLoading()
            view.destroy()
        }
    }

    private suspend fun measureFit(view: WebView): List<String> {
        val values = org.json.JSONArray(evaluate(view, FIT_JS))
        return fitProblems(values.getInt(0), values.getInt(1), values.getInt(2), values.getInt(3))
    }

    private suspend fun evaluate(view: WebView, script: String): String = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result.orEmpty()) }
    }

    companion object {
        const val TEST_KEY = "test"
        const val RENDER_MS = 1_000L
        const val REFRESH_WATCH_MS = 300L
        const val LOAD_TIMEOUT_MS = 10_000L
        const val NOT_LOADED = "The widget did not finish loading within 10 seconds"
        const val BLANK = "The widget shows no visible text within 1 second"
        const val FIT_JS = "[document.documentElement.scrollHeight,document.documentElement.scrollWidth,document.documentElement.clientHeight,document.documentElement.clientWidth]"

        // A clock needs no sources. A data widget must actually ask its bridge during the load test.
        fun missingSourceReads(approved: Set<String>, reads: Set<String>): Boolean =
            (approved - WidgetSources.CLOCK).let { sources -> sources.isNotEmpty() && sources.none { it in reads } }

        fun fitProblems(scrollHeight: Int, scrollWidth: Int, clientHeight: Int, clientWidth: Int): List<String> {
            val excess = buildList {
                if (scrollHeight - clientHeight > 4) add("${scrollHeight - clientHeight} px taller")
                if (scrollWidth - clientWidth > 4) add("${scrollWidth - clientWidth} px wider")
            }
            return if (excess.isEmpty()) emptyList() else listOf(
                "The content is ${excess.joinToString(" and ")} than the widget. Everything must be visible without scrolling in $clientWidth by $clientHeight px.")
        }
    }
}
