package ai.opencyclone.widgets

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The one JavaScript interface of a widget ([WidgetHtml.NATIVE]; pages use `window.phone` from the shim).
 * It reads only the sources in [allowed], and it can send one request in the owner's name after a tap.
 * It has no storage, no notifications, and no fetch of its own.
 */
class WidgetBridge(
    private val widgetKey: String,
    private val allowed: Set<String>,
    private val read: suspend (request: String) -> String?,
    /** Runs a JavaScript snippet in the page; the caller posts it to the WebView's thread. */
    private val deliver: (String) -> Unit,
    private val scope: CoroutineScope,
    /** True when the owner touched this widget a moment ago (a click-time flag). */
    private val userGesture: () -> Boolean = { false },
    /** Sends the text as the owner's request (null: asking is off, for example in the load test). */
    private val ask: ((String) -> Unit)? = null,
    private val onFailed: (String) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var lastAsk = Long.MIN_VALUE / 2

    /** Answers with `window.__widgetResult(callbackId, json)`; json is null for a refused or empty read. */
    @JavascriptInterface
    fun read(request: String?, callbackId: String?) {
        val id = callbackId.orEmpty()
        val wanted = request.orEmpty().trim()
        if (WidgetSources.sourceOf(wanted) !in allowed) {
            log("widget $widgetKey refused read $wanted")
            respond(id, null)
            return
        }
        scope.launch { respond(id, read(wanted)) }
    }

    /** Returns true when the request was sent. Needs a tap on this widget and at most one each [ASK_INTERVAL_MS]. */
    @JavascriptInterface
    fun ask(text: String?): Boolean {
        val words = text.orEmpty().trim().take(MAX_ASK_CHARS)
        val send = ask ?: return false
        if (words.isEmpty() || !userGesture()) {
            log("widget $widgetKey ask refused: no tap")
            return false
        }
        synchronized(this) {
            val at = now()
            if (at - lastAsk < ASK_INTERVAL_MS) {
                log("widget $widgetKey ask refused: too soon")
                return false
            }
            lastAsk = at
        }
        log("widget $widgetKey ask")
        send(words)
        return true
    }

    @JavascriptInterface
    fun failed(message: String?) {
        log("widget $widgetKey stopped: ${message.orEmpty().take(200)}")
        onFailed(message.orEmpty())
    }

    private fun respond(id: String, json: String?) {
        deliver("window.__widgetResult && window.__widgetResult(${JSONObject.quote(id)}, ${json?.let { JSONObject.quote(it) } ?: "null"});")
    }

    companion object {
        const val ASK_INTERVAL_MS = 2_000L
        const val MAX_ASK_CHARS = 300
        const val WALK_TAG = "WALK"
        private const val TAG = "Widgets"

        /** One log line: tag WALK in a debuggable build (the PM's walk reads it), a normal log otherwise. */
        fun logger(context: Context): (String) -> Unit {
            val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            return { line -> Log.i(if (debuggable) WALK_TAG else TAG, line) }
        }
    }
}
