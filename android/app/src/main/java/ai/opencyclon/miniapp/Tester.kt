package ai.opencyclon.miniapp

import android.content.Context
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Loads a built app in a WebView that is never shown, with script on and the bridge attached,
 * collects console errors and uncaught errors for [WATCH_MS], and checks that the body shows text.
 * Pass [test] to [Builder].
 */
class Tester(context: Context) {
    private val context = context.applicationContext

    suspend fun test(html: String): List<String> = test(html, allowFetch = true)

    /** [allowFetch] is false for a received app, which never gets the internet, not even in this test. */
    suspend fun test(html: String, allowFetch: Boolean): List<String> = withContext(Dispatchers.Main) {
        val problems = mutableListOf<String>()
        val finished = CompletableDeferred<Unit>()
        val view = WebView(context)
        try {
            view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, WIDTH, HEIGHT)
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    // Uncaught errors are reported here too, as "Uncaught ..." at level ERROR.
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        problems += "${message.message()} (line ${message.lineNumber()})"
                    }
                    return true
                }
            }
            val bridge = Bridge(context, TEST_APP_ID, deliver = { js -> view.post { view.evaluateJavascript(js, null) } },
                allowNotify = false, allowFetch = allowFetch)
            MiniAppActivity.load(view, TEST_KEY, html, bridge, object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { finished.complete(Unit) }
            })
            if (withTimeoutOrNull(LOAD_TIMEOUT_MS) { finished.await() } == null) problems += NOT_LOADED
            delay(WATCH_MS)
            val textLength = evaluate(view, "(document.body && document.body.innerText || '').trim().length")
            if ((textLength.toIntOrNull() ?: 0) == 0) problems += BLANK
            problems.distinct()
        } finally {
            view.stopLoading()
            view.destroy()
            WebStorage.getInstance().deleteOrigin(MiniAppActivity.origin(TEST_KEY).trimEnd('/'))
            context.getSharedPreferences("${Bridge.PREFS_PREFIX}$TEST_APP_ID", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String = suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result.orEmpty()) }
    }

    companion object {
        const val WATCH_MS = 3_000L
        const val LOAD_TIMEOUT_MS = 10_000L
        const val TEST_APP_ID = 0L
        const val TEST_KEY = "test"
        const val NOT_LOADED = "The page did not finish loading within 10 seconds"
        const val BLANK = "The page shows no visible text"
        private const val WIDTH = 1080
        private const val HEIGHT = 1920
    }
}
