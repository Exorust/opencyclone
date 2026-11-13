package ai.opencyclon.miniapp

import ai.opencyclon.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The JavaScript interface named [NAME] ("phone") given to a built app. It offers exactly four
 * functions: per-app key-value storage, public HTTPS fetch, and posting a notification.
 *
 * [deliver] runs a JavaScript snippet in the page; the caller posts it to the WebView's thread.
 */
class Bridge(
    context: Context,
    private val appId: Long,
    private val deliver: (String) -> Unit,
    private val allowNotify: Boolean = true,
    /** False for a received app: [fetch] answers with [NO_INTERNET] and sends no request. */
    private val allowFetch: Boolean = true,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }
) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("$PREFS_PREFIX$appId", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .callTimeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(false) // never leave https
        .dns(object : Dns {
            // Checked at connect time too, so a redirect or a changed DNS answer cannot reach a local address.
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = resolve(hostname)
                if (addresses.isEmpty() || addresses.any { isLocal(it) }) throw UnknownHostException(LOCAL_ADDRESS)
                return addresses
            }
        })
        .build()

    @JavascriptInterface
    fun get(key: String?): String? = key?.let { prefs.getString(it, null) }

    /** Returns false when the value would take this app's storage above [MAX_STORAGE_BYTES]. */
    @JavascriptInterface
    fun set(key: String?, value: String?): Boolean = synchronized(prefs) {
        if (key == null) return false
        if (value == null) { prefs.edit().remove(key).apply(); return true }
        val others = prefs.all.entries.filter { it.key != key }
            .sumOf { bytes(it.key) + bytes(it.value?.toString().orEmpty()) }
        if (others + bytes(key) + bytes(value) > MAX_STORAGE_BYTES) return false
        prefs.edit().putString(key, value).apply()
        true
    }

    /**
     * Fetches [url] in the background. [optionsJson] may hold id, method (GET or POST), headers and body.
     * The result arrives as window.phoneFetchResult(id, text, error); exactly one of text and error is null.
     */
    @JavascriptInterface
    fun fetch(url: String?, optionsJson: String?) {
        val options = try { JSONObject(optionsJson?.takeIf { it.isNotBlank() } ?: "{}") } catch (_: Exception) { JSONObject() }
        val id = options.optString("id", "")
        executor.execute {
            val (text, error) = if (!allowFetch) null to NO_INTERNET else try {
                download(url.orEmpty(), options) to null
            } catch (e: Exception) {
                null to (e.message ?: FETCH_FAILED)
            }
            deliver("window.phoneFetchResult && window.phoneFetchResult(${JSONObject.quote(id)}, " +
                "${text?.let { JSONObject.quote(it) } ?: "null"}, ${error?.let { JSONObject.quote(it) } ?: "null"});")
        }
    }

    @JavascriptInterface
    fun notify(title: String?, text: String?) {
        if (!allowNotify) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.orEmpty().take(MAX_NOTIFY_CHARS))
            .setContentText(text.orEmpty().take(MAX_NOTIFY_CHARS))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify((NOTIFY_BASE + appId).toInt(), notification)
        } catch (_: SecurityException) {
            // Notification permission not granted; nothing to do.
        }
    }

    /** A GET of [url] under the same rules as [fetch], without headers or credentials (widgets' `web:` source). Blocking. */
    fun getText(url: String): String {
        if (!allowFetch) throw IOException(NO_INTERNET)
        return download(url, JSONObject())
    }

    private fun download(url: String, options: JSONObject): String {
        if (!isAllowedUrl(url, resolve)) throw IOException(REFUSED_URL)
        val method = options.optString("method", "GET").uppercase()
        val body = options.optString("body", "")
        val builder = Request.Builder().url(url)
        options.optJSONObject("headers")?.let { headers -> headers.keys().forEach { builder.header(it, headers.optString(it)) } }
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(body.toRequestBody(options.optJSONObject("headers")?.optString("Content-Type")?.toMediaTypeOrNull()))
            else -> throw IOException(BAD_METHOD)
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source = response.body?.source() ?: return ""
            if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException(TOO_LARGE)
            return source.buffer.readUtf8()
        }
    }

    companion object {
        const val NAME = "phone"
        const val MAX_STORAGE_BYTES = 1024 * 1024L
        const val MAX_RESPONSE_BYTES = 1024 * 1024L
        const val FETCH_TIMEOUT_MS = 10_000L
        const val PREFS_PREFIX = "miniapp_bridge_"
        const val CHANNEL_ID = "miniapps"
        const val CHANNEL_NAME = "Built apps"
        const val REFUSED_URL = "Only https addresses on the public internet are allowed"
        const val LOCAL_ADDRESS = "Address is on the local network"
        const val BAD_METHOD = "Only GET and POST are allowed"
        const val TOO_LARGE = "Response is larger than 1 MB"
        const val FETCH_FAILED = "No connection"
        const val NO_INTERNET = "This app cannot use the internet."
        private const val MAX_NOTIFY_CHARS = 200
        private const val NOTIFY_BASE = 40_000L
        private val executor = Executors.newCachedThreadPool()

        private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size.toLong()

        /** True only for an https URL whose host resolves to public addresses. */
        fun isAllowedUrl(url: String, resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            if (parsed.scheme != "https") return false
            val host = parsed.host
            if (host.equals("localhost", true) || host.endsWith(".localhost", true)) return false
            val addresses = try { resolve(host) } catch (_: Exception) { return false }
            return addresses.isNotEmpty() && addresses.none { isLocal(it) }
        }

        fun isLocal(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
                address.isAnyLocalAddress || address.isMulticastAddress) return true
            val b = address.address.map { it.toInt() and 0xff }
            return when (address) {
                is Inet4Address -> b[0] == 0 || (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
                    (b[0] == 192 && b[1] == 0 && b[2] == 0) || b[0] >= 240
                is Inet6Address -> (b[0] and 0xfe) == 0xfc || // unique local fc00::/7
                    (b.take(10).all { it == 0 } && b[10] == 0xff && b[11] == 0xff &&
                        isLocal(InetAddress.getByAddress(b.drop(12).map { it.toByte() }.toByteArray())))
                else -> true
            }
        }
    }
}
