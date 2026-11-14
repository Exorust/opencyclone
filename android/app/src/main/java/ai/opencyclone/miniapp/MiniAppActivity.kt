package ai.opencyclone.miniapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.Window
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/** Shows one built app, read from [MiniAppStore.currentFile], in its own web origin. */
class MiniAppActivity : Activity() {
    private val scope = MainScope()
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        // Edge to edge on the built app's own background, with dark bar icons; content is inset below.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(BACKGROUND))
        @Suppress("DEPRECATION") window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION") window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val appId = intent.getLongExtra(EXTRA_APP_ID, -1L)
        val store = MiniAppStore(this)
        scope.launch {
            val html = if (appId > 0) store.currentHtml(appId) else null
            if (html == null) {
                setContentView(inset(TextView(this@MiniAppActivity).apply { text = NOT_FOUND; setPadding(48, 48, 48, 48) }))
                return@launch
            }
            val app = store.list().firstOrNull { it.id == appId }
            app?.let { title = it.name }
            val view = WebView(this@MiniAppActivity).apply { setBackgroundColor(BACKGROUND) }
            webView = view
            setContentView(inset(view))
            load(view, appId.toString(), html, Bridge(this@MiniAppActivity, appId,
                deliver = { js -> view.post { view.evaluateJavascript(js, null) } }, allowFetch = app?.received != true))
        }
    }

    /** Wraps [content] in a frame padded by the system bars and the keyboard. */
    private fun inset(content: View): View = FrameLayout(this).apply {
        setBackgroundColor(BACKGROUND)
        addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(this) { frame, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            frame.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onDestroy() {
        scope.cancel()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_APP_ID = "ai.opencyclone.miniapp.APP_ID"
        const val NOT_FOUND = "This app is no longer available."
        const val HOST_SUFFIX = ".miniapp.invalid"
        const val BACKGROUND = 0xFFFFFFFF.toInt()
        const val FONT_ASSET = "fonts/inter.woff2"

        /** Each app has its own origin, so web storage of one app cannot be read by another. */
        fun origin(key: String) = "https://app-$key$HOST_SUFFIX/"

        fun intent(context: Context, appId: Long): Intent =
            Intent(context, MiniAppActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                // Distinct data per app, so each app gets its own recents entry and reopening reuses it.
                .setData(Uri.parse("opencyclone-miniapp:$appId"))
                .putExtra(EXTRA_APP_ID, appId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

        /**
         * Configures [view] for a built app and loads [html] at [origin] (key). Script and DOM storage on,
         * file and content access off, every other origin blocked. Used by the activity and by [Tester].
         */
        @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
        fun load(view: WebView, key: String, html: String, bridge: Bridge, client: WebViewClient? = null) {
            val origin = origin(key)
            val host = Uri.parse(origin).host
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setGeolocationEnabled(false)
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
            }
            view.addJavascriptInterface(bridge, Bridge.NAME)
            val context = view.context.applicationContext
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    respond(context, host, html, request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != host

                override fun onPageFinished(view: WebView, url: String) {
                    client?.onPageFinished(view, url)
                }
            }
            view.loadUrl(origin)
        }

        /**
         * The answer to one request of a built app at [host]: the page for "/", the packaged Inter font for
         * [BuiltAppStyle.FONT_PATH], and 403 for everything else.
         */
        fun respond(context: Context, host: String?, html: String, url: Uri): WebResourceResponse {
            if (url.scheme == "https" && url.host == host) {
                if (url.path.isNullOrEmpty() || url.path == "/") {
                    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                }
                if (url.path == BuiltAppStyle.FONT_PATH) {
                    return WebResourceResponse("font/woff2", null, 200, "OK", mapOf("Cache-Control" to "max-age=31536000"),
                        context.assets.open(FONT_ASSET))
                }
            }
            // No other origin and no other file: network goes through phone.fetch only.
            return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        /** Asks the launcher to add a home-screen icon. Throws when the launcher cannot pin shortcuts. */
        fun pinShortcut(context: Context, appId: Long, name: String) {
            check(ShortcutManagerCompat.isRequestPinShortcutSupported(context)) { "Launcher cannot pin shortcuts" }
            val shortcut = ShortcutInfoCompat.Builder(context, "miniapp-$appId")
                .setShortLabel(name.take(25))
                .setLongLabel(name)
                .setIcon(IconCompat.createWithResource(context, context.applicationInfo.icon))
                .setIntent(intent(context, appId))
                .build()
            check(ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)) { "Launcher refused the shortcut" }
        }
    }
}
