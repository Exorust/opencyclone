package ai.opencyclone.llm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import ai.opencyclone.config.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Sign-in with ChatGPT (the Codex CLI's OAuth + PKCE flow) and with OpenRouter (its OAuth PKCE flow).
 *
 * Both open the provider's page in the browser and wait for one redirect on a loopback listener the
 * app serves itself at 127.0.0.1:1455. The listener runs in a process-wide scope, so it keeps running
 * while the browser is in front; the app's foreground AgentService keeps the process alive. It closes
 * after one callback or five minutes. The page it answers links back to the app with opencyclone://signed-in.
 *
 * Cleartext: the browser makes the plain-HTTP request to the app's socket. The app's network security
 * config only governs the app's own outgoing connections, so it is not involved.
 */
object PlanSignIn {
    private const val TAG = "PlanSignIn"

    // codex-rs/login/src/auth/manager.rs CLIENT_ID, server.rs DEFAULT_ISSUER / DEFAULT_PORT
    const val CHATGPT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val CHATGPT_ISSUER = "https://auth.openai.com"
    const val CHATGPT_TOKEN_URL = "$CHATGPT_ISSUER/oauth/token"
    const val CALLBACK_PORT = 1455
    const val CHATGPT_CALLBACK_PATH = "/auth/callback"
    // server.rs run_login_server: "http://127.0.0.1:{port}/auth/callback" (localhost before #47927)
    const val CHATGPT_REDIRECT_URI = "http://127.0.0.1:$CALLBACK_PORT$CHATGPT_CALLBACK_PATH"
    // server.rs build_authorize_url
    const val CHATGPT_SCOPE = "openid profile email offline_access api.connectors.read api.connectors.invoke"

    // OpenRouter docs "OAuth PKCE": localhost callbacks on any port are accepted
    const val OPENROUTER_AUTH_URL = "https://openrouter.ai/auth"
    const val OPENROUTER_KEYS_URL = "https://openrouter.ai/api/v1/auth/keys"
    const val OPENROUTER_CALLBACK_PATH = "/openrouter/callback"
    const val OPENROUTER_REDIRECT_URI = "http://localhost:$CALLBACK_PORT$OPENROUTER_CALLBACK_PATH"

    const val RETURN_URI = "opencyclone://signed-in"
    const val LISTEN_TIMEOUT_MS = 5 * 60 * 1000L
    const val IMPORT_FILE = "chatgpt_auth.json"

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Outlives the activity: the browser is in front while the listener waits. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    /** Called on the main thread after each sign-in attempt ends (null message = success). */
    @Volatile var onFinished: ((provider: String, error: String?) -> Unit)? = null

    data class Pkce(val verifier: String, val challenge: String)

    /** 64 random bytes as URL-safe base64 without padding; challenge = BASE64URL(SHA256(verifier)) (codex-rs/login/src/oauth/pkce.rs). */
    fun generatePkce(random: SecureRandom = SecureRandom()): Pkce {
        val bytes = ByteArray(64).also { random.nextBytes(it) }
        val verifier = b64url(bytes)
        return Pkce(verifier, challengeFor(verifier))
    }

    fun challengeFor(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun randomState(random: SecureRandom = SecureRandom()): String = b64url(ByteArray(32).also { random.nextBytes(it) })

    private fun b64url(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    /** Same parameters and order as codex-rs build_authorize_url + build_authorization_url. */
    fun chatGptAuthorizeUrl(pkce: Pkce, state: String, issuer: String = CHATGPT_ISSUER): String {
        val params = listOf(
            "response_type" to "code",
            "client_id" to CHATGPT_CLIENT_ID,
            "redirect_uri" to CHATGPT_REDIRECT_URI,
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "scope" to CHATGPT_SCOPE,
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to ChatGptPlanClient.ORIGINATOR
        )
        return "$issuer/oauth/authorize?" + params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    fun openRouterAuthorizeUrl(pkce: Pkce, state: String, authUrl: String = OPENROUTER_AUTH_URL): String =
        "$authUrl?callback_url=${enc(OPENROUTER_REDIRECT_URI)}&code_challenge=${enc(pkce.challenge)}" +
            "&code_challenge_method=S256&state=${enc(state)}"

    /** The code from callback parameters after the state check, as codex-rs CallbackParameters.validate. */
    fun codeFromCallback(params: Map<String, String>, expectedState: String): String {
        if (params["state"] != expectedState) throw IOException("Sign-in failed: state mismatch")
        params["error"]?.let { throw IOException("Sign-in refused: ${params["error_description"] ?: it}") }
        return params["code"]?.takeIf { it.isNotEmpty() } ?: throw IOException("Sign-in failed: no code")
    }

    data class ChatGptTokenSet(val accessToken: String, val refreshToken: String, val accountId: String, val expiresAtMs: Long)

    /** Form POST grant_type=authorization_code, client_id, code, redirect_uri, code_verifier (codex-rs oauth/client.rs exchange_code). */
    fun exchangeChatGptCode(
        http: OkHttpClient, code: String, verifier: String,
        tokenUrl: String = CHATGPT_TOKEN_URL, now: Long = System.currentTimeMillis()
    ): ChatGptTokenSet {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CHATGPT_CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", CHATGPT_REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()
        http.newCall(Request.Builder().url(tokenUrl).post(form).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Token exchange failed (HTTP ${response.code})")
            val json = JSONObject(response.body?.string() ?: "{}")
            val access = json.optString("access_token", "")
            val refresh = json.optString("refresh_token", "")
            if (access.isEmpty() || refresh.isEmpty()) throw IOException("Token exchange returned no tokens")
            val expiresIn = if (json.has("expires_in")) json.optLong("expires_in") else null
            return ChatGptTokenSet(access, refresh,
                ChatGptTokens.accountIdFrom(json.optString("id_token", ""), access),
                ChatGptTokens.expiresAtMs(access, expiresIn, now))
        }
    }

    /** JSON POST {code, code_verifier, code_challenge_method} to /api/v1/auth/keys; returns `key`. */
    fun exchangeOpenRouterCode(http: OkHttpClient, code: String, verifier: String, keysUrl: String = OPENROUTER_KEYS_URL): String {
        val body = JSONObject().put("code", code).put("code_verifier", verifier).put("code_challenge_method", "S256").toString()
        http.newCall(Request.Builder().url(keysUrl).post(body.toRequestBody(JSON)).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("OpenRouter key exchange failed (HTTP ${response.code})")
            return JSONObject(response.body?.string() ?: "{}").optString("key", "").ifEmpty {
                throw IOException("OpenRouter returned no key")
            }
        }
    }

    fun saveChatGpt(context: Context, tokens: ChatGptTokenSet) {
        ChatGptTokenStore(context).save(tokens.accessToken, tokens.refreshToken, tokens.accountId, tokens.expiresAtMs)
        val config = ConfigRepository(context)
        config.apiProvider = ConfigRepository.PROVIDER_CHATGPT
        config.model = ConfigRepository.DEFAULT_CHATGPT_MODEL
        config.modelChosenByHand = false
        config.baseUrl = ConfigRepository.DEFAULT_CHATGPT_BASE_URL
    }

    fun saveOpenRouter(context: Context, key: String) {
        val config = ConfigRepository(context)
        config.apiProvider = ConfigRepository.PROVIDER_OPENAI
        config.apiKey = key
        config.baseUrl = ConfigRepository.OPENROUTER_BASE_URL
        config.model = ConfigRepository.DEFAULT_OPENROUTER_MODEL
        config.modelChosenByHand = false
    }

    fun isChatGptConnected(config: ConfigRepository): Boolean =
        config.apiProvider == ConfigRepository.PROVIDER_CHATGPT && ChatGptTokenStore(config.appContext).hasRefreshToken()

    fun isOpenRouterConnected(config: ConfigRepository): Boolean =
        config.apiProvider == ConfigRepository.PROVIDER_OPENAI &&
            config.baseUrl.trimEnd('/') == ConfigRepository.OPENROUTER_BASE_URL && config.apiKey.isNotBlank()

    fun startChatGpt(context: Context) {
        val pkce = generatePkce()
        val state = randomState()
        start(context, ConfigRepository.PROVIDER_CHATGPT, CHATGPT_CALLBACK_PATH, chatGptAuthorizeUrl(pkce, state)) { http, params ->
            saveChatGpt(context.applicationContext, exchangeChatGptCode(http, codeFromCallback(params, state), pkce.verifier))
        }
    }

    fun startOpenRouter(context: Context) {
        val pkce = generatePkce()
        val state = randomState()
        start(context, "openrouter", OPENROUTER_CALLBACK_PATH, openRouterAuthorizeUrl(pkce, state)) { http, params ->
            saveOpenRouter(context.applicationContext, exchangeOpenRouterCode(http, codeFromCallback(params, state), pkce.verifier))
        }
    }

    private fun start(
        context: Context, provider: String, path: String, authorizeUrl: String,
        finish: (OkHttpClient, Map<String, String>) -> Unit
    ) {
        val app = context.applicationContext
        running?.cancel()
        running = scope.launch {
            var error: String? = null
            try {
                val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                LoopbackListener(CALLBACK_PORT).use { listener ->
                    withContext(Dispatchers.Main) {
                        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    listener.awaitOne(path, LISTEN_TIMEOUT_MS) { params ->
                        error = try {
                            finish(http, params)
                            null
                        } catch (e: Exception) {
                            e.message ?: e.javaClass.simpleName
                        }
                        resultPage(error)
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            Log.i(TAG, "Sign-in $provider finished: ${if (error == null) "connected" else "failed"}")
            withContext(Dispatchers.Main) { onFinished?.invoke(provider, error) }
        }
    }

    fun resultPage(error: String?): String {
        val title = if (error == null) "Signed in" else "Sign-in failed"
        val detail = if (error == null) "You can return to the app." else escapeHtml(error)
        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width\"><title>$title</title></head>" +
            "<body style=\"font-family:sans-serif;padding:24px\"><h2>$title</h2><p>$detail</p>" +
            "<p><a href=\"$RETURN_URI\" style=\"font-size:20px\">Back to the app</a></p></body></html>"
    }

    private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Test import: if files/chatgpt_auth.json exists, store its tokens (flat, or the Codex CLI's
     * {"tokens": {...}} shape), select the ChatGPT provider, and delete the file. Returns true if imported.
     */
    fun importAuthFileIfPresent(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val file = File(context.filesDir, IMPORT_FILE)
        if (!file.exists()) return false
        try {
            val root = JSONObject(file.readText())
            val tokens = root.optJSONObject("tokens") ?: root
            val access = tokens.optString("access_token", "")
            val refresh = tokens.optString("refresh_token", "")
            if (refresh.isEmpty()) {
                Log.w(TAG, "$IMPORT_FILE has no refresh token; ignored")
                return false
            }
            val account = tokens.optString("account_id", "").ifEmpty {
                ChatGptTokens.accountIdFrom(tokens.optString("id_token", ""), access)
            }
            val expires = if (access.isEmpty()) 0L else ChatGptTokens.expiresAtMs(access, null, now)
            saveChatGpt(context, ChatGptTokenSet(access, refresh, account, expires))
            Log.i(TAG, "Imported ChatGPT sign-in from $IMPORT_FILE")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Could not import $IMPORT_FILE: ${e.javaClass.simpleName}")
            return false
        } finally {
            file.delete()
        }
    }
}

/** A minimal HTTP listener on 127.0.0.1 for one OAuth redirect. */
internal class LoopbackListener(port: Int) : AutoCloseable {
    private val server = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }

    val port: Int get() = server.localPort

    /**
     * Waits for one GET on [path] (other paths such as /favicon.ico get 404), parses its query,
     * answers with the HTML that [handle] returns, then returns. Throws on timeout.
     */
    fun awaitOne(path: String, timeoutMs: Long, handle: (Map<String, String>) -> String) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw IOException("Sign-in timed out")
            server.soTimeout = left.toInt()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { throw IOException("Sign-in timed out") }
            socket.use { s ->
                s.soTimeout = 10_000
                val requestLine = s.getInputStream().bufferedReader().readLine() ?: return@use
                val target = requestLine.split(' ').getOrNull(1) ?: ""
                val out = s.getOutputStream()
                if (target.substringBefore('?') != path) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return@use
                }
                val html = handle(parseQuery(target.substringAfter('?', ""))).toByteArray(Charsets.UTF_8)
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(html)
                out.flush()
                return
            }
        }
    }

    override fun close() {
        try { server.close() } catch (_: Exception) {}
    }

    companion object {
        fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
    }
}
