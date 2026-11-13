package ai.opencyclon.widgets

import ai.opencyclon.miniapp.MiniAppActivity
import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/** Shows a widget file in a WebView, treated like a built app but stricter. */
object WidgetWeb {
    /** Each widget (and each draft) has its own origin, so no page can read another one's storage. */
    fun origin(key: String) = "https://widget-$key${MiniAppActivity.HOST_SUFFIX}/"

    /** JavaScript that asks the page to read its data again. */
    const val REFRESH_JS = "try{window.dispatchEvent(new Event('phonerefresh'));if(window.phone&&typeof phone.onrefresh==='function')phone.onrefresh()}catch(e){window.widgetNative&&widgetNative.failed(String(e&&e.message||e))}"

    /**
     * Script on; storage, file and content access off; no window opening; the bridge is the only interface; every
     * request except the page and the packaged font is refused. [onError] runs when the page cannot load.
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun load(view: WebView, key: String, html: String, bridge: WidgetBridge,
             onFinished: () -> Unit = {}, onError: () -> Unit = {}) {
        val document = WidgetHtml.forDisplay(html)
        val origin = origin(key)
        val host = Uri.parse(origin).host
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportZoom(false)
            builtInZoomControls = false
        }
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.addJavascriptInterface(bridge, WidgetHtml.NATIVE)
        val context = view.context.applicationContext
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                MiniAppActivity.respond(context, host, document, request.url)

            // A widget never navigates; a link does nothing.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageFinished(view: WebView, url: String) = onFinished()

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onError()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onError()
                return true
            }
        }
        view.loadUrl(origin)
    }
}
