package ai.opencyclone.llm

import android.content.Context
import ai.opencyclone.config.SecretStore
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The ChatGPT sign-in tokens: the tokens and account id in [SecretStore], the expiry time in the private
 * preferences file `chatgpt_plan`. Tokens saved in plain text by an older version move there on first use.
 * Never log a token or any part of one.
 */
class ChatGptTokenStore(context: Context, private val secrets: SecretStore = SecretStore(context)) {
    companion object {
        const val PREFS_NAME = "chatgpt_plan"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_ACCOUNT = "account_id"
        private const val KEY_EXPIRES = "expires_at_ms"
        /** Old plain-text key in [PREFS_NAME] to its name in [SecretStore]. */
        private val SECRET_NAMES = mapOf(
            KEY_ACCESS to "chatgpt_access_token",
            KEY_REFRESH to "chatgpt_refresh_token",
            KEY_ACCOUNT to "chatgpt_account_id",
        )

        /** One refresh at a time across every client in the process: refresh tokens rotate. */
        val REFRESH_LOCK = Any()
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        val plain = SECRET_NAMES.keys.filter { prefs.contains(it) }
        if (plain.isNotEmpty()) {
            val edit = prefs.edit()
            for (key in plain) {
                secrets.put(SECRET_NAMES.getValue(key), prefs.getString(key, "") ?: "")
                edit.remove(key)
            }
            edit.commit()
        }
    }

    private fun secret(key: String): String = secrets.get(SECRET_NAMES.getValue(key)) ?: ""

    val accessToken: String get() = secret(KEY_ACCESS)
    val refreshToken: String get() = secret(KEY_REFRESH)
    val accountId: String get() = secret(KEY_ACCOUNT)
    val expiresAtMs: Long get() = prefs.getLong(KEY_EXPIRES, 0L)

    fun hasRefreshToken(): Boolean = refreshToken.isNotBlank()

    fun save(accessToken: String, refreshToken: String, accountId: String, expiresAtMs: Long) {
        secrets.put(SECRET_NAMES.getValue(KEY_ACCESS), accessToken)
        secrets.put(SECRET_NAMES.getValue(KEY_REFRESH), refreshToken)
        secrets.put(SECRET_NAMES.getValue(KEY_ACCOUNT), accountId)
        prefs.edit().putLong(KEY_EXPIRES, expiresAtMs).commit()
    }

    fun clear() {
        SECRET_NAMES.values.forEach { secrets.remove(it) }
        prefs.edit().clear().commit()
    }
}

/** JWT and token-response helpers shared by the client, the sign-in, and the import path. */
object ChatGptTokens {
    private const val AUTH_CLAIM = "https://api.openai.com/auth"
    /** Used only when neither `expires_in` nor the access token's `exp` is readable. */
    private const val FALLBACK_LIFETIME_MS = 55 * 60 * 1000L

    /** The payload of a JWT, or null. Never logged. */
    fun jwtClaims(jwt: String): JSONObject? = try {
        val part = jwt.split('.').getOrNull(1) ?: throw IllegalArgumentException()
        JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    /** `chatgpt_account_id` from the `https://api.openai.com/auth` claim of the id token, else the access token. */
    fun accountIdFrom(idToken: String?, accessToken: String?): String {
        for (jwt in listOf(idToken, accessToken)) {
            if (jwt.isNullOrBlank()) continue
            val id = jwtClaims(jwt)?.optJSONObject(AUTH_CLAIM)?.optString("chatgpt_account_id", "").orEmpty()
            if (id.isNotBlank()) return id
        }
        return ""
    }

    fun expiresAtMs(accessToken: String, expiresInSeconds: Long?, now: Long): Long {
        if (expiresInSeconds != null && expiresInSeconds > 0) return now + expiresInSeconds * 1000
        val exp = jwtClaims(accessToken)?.optLong("exp", 0L) ?: 0L
        return if (exp > 0) exp * 1000 else now + FALLBACK_LIFETIME_MS
    }
}

/**
 * The agent's model client for a ChatGPT subscription, the way the Codex CLI talks to it:
 * POST {baseUrl}/responses on chatgpt.com/backend-api/codex with the OAuth access token and
 * the `chatgpt-account-id` header, a Responses-format body with store=false and stream=true.
 * The engine's chat-style messages are translated to Responses input items; one forced call of
 * the phone_action function comes back and is returned as its argument map, like [LLMClient].
 */
class ChatGptPlanClient(
    private val store: ChatGptTokenStore,
    private val model: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val tokenUrl: String = PlanSignIn.CHATGPT_TOKEN_URL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = 1000L
) : LLMClientInterface {

    companion object {
        private const val TAG = "ChatGptPlanClient"
        const val DEFAULT_BASE_URL = "https://chatgpt.com/backend-api/codex"
        /** codex-rs/login/src/auth/default_client.rs DEFAULT_ORIGINATOR. */
        const val ORIGINATOR = "codex_cli_rs"
        /** The Codex version we present; the backend hides models whose `minimal_client_version` is newer. */
        const val CLIENT_VERSION = "0.155.0"
        const val USER_AGENT = "codex_cli_rs/$CLIENT_VERSION (Android; arm64) opencyclone"
        const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
        private const val MAX_ATTEMPTS = 3
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val READ_TIMEOUT_MS = 90_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    /** One id per client, sent as `session-id` like Codex does per conversation. */
    private val sessionId = UUID.randomUUID().toString()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val textHttpClient by lazy {
        httpClient.newBuilder().readTimeout(TextCompletion.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    }

    override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            val body = buildRequest(messages, withTools = true).toString()
            val stream = send(httpClient, body)
            parseStream(stream).let { (args, text) ->
                args?.let { parseArgs(it) }
                    ?: text.takeIf { it.isNotEmpty() }?.let { ResponseParser.extractJsonFromText(it) }
                    ?: throw LLMException("ChatGPT reply had no action")
            }
        }

    override suspend fun completeText(prompt: String): String = withContext(Dispatchers.IO) {
        val messages = listOf(mapOf<String, Any>("role" to "user", "content" to prompt))
        val body = buildRequest(messages, withTools = false).toString()
        val (_, text) = parseStream(send(textHttpClient, body))
        text.ifEmpty { throw LLMException("ChatGPT reply has no text") }
    }

    /** The Responses request body. System text goes in as a developer message, as CLIProxyAPI does. */
    internal fun buildRequest(messages: List<Map<String, Any>>, withTools: Boolean): JSONObject {
        val input = JSONArray()
        for (msg in messages) {
            val role = msg["role"] as? String ?: continue
            when (role) {
                "tool" -> input.put(JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", msg["tool_call_id"]?.toString() ?: "")
                    .put("output", contentText(msg["content"])))
                "assistant" -> {
                    val text = contentText(msg["content"])
                    if (text.isNotEmpty()) {
                        input.put(message("assistant", JSONArray().put(JSONObject().put("type", "output_text").put("text", text))))
                    }
                    (msg["tool_calls"] as? List<*>)?.forEach { call ->
                        val c = call as? Map<*, *> ?: return@forEach
                        val fn = c["function"] as? Map<*, *> ?: return@forEach
                        input.put(JSONObject()
                            .put("type", "function_call")
                            .put("call_id", c["id"]?.toString() ?: "")
                            .put("name", fn["name"]?.toString() ?: "")
                            .put("arguments", fn["arguments"]?.toString() ?: "{}"))
                    }
                }
                else -> input.put(message(if (role == "system") "developer" else role, inputParts(msg["content"])))
            }
        }
        return JSONObject().apply {
            put("model", model)
            put("instructions", "")
            put("input", input)
            if (withTools) {
                val fn = ToolSchema.phoneActionTool().getJSONObject("function")
                put("tools", JSONArray().put(JSONObject()
                    .put("type", "function")
                    .put("name", fn.getString("name"))
                    .put("description", fn.optString("description"))
                    .put("parameters", fn.getJSONObject("parameters"))
                    .put("strict", false)))
                put("tool_choice", "required")
                put("parallel_tool_calls", false)
            }
            put("reasoning", JSONObject().put("effort", "low"))
            put("store", false)
            put("stream", true)
        }
    }

    private fun message(role: String, content: JSONArray) =
        JSONObject().put("type", "message").put("role", role).put("content", content)

    private fun inputParts(content: Any?): JSONArray {
        val parts = JSONArray()
        when (content) {
            is List<*> -> for (item in content) {
                val block = item as? Map<*, *> ?: continue
                when (block["type"]) {
                    "image_url" -> {
                        val url = when (val image = block["image_url"]) {
                            is Map<*, *> -> image["url"]?.toString()
                            is String -> image
                            else -> null
                        } ?: continue
                        parts.put(JSONObject().put("type", "input_image").put("image_url", url))
                    }
                    else -> (block["text"] as? String)?.let {
                        parts.put(JSONObject().put("type", "input_text").put("text", it))
                    }
                }
            }
            null -> {}
            else -> parts.put(JSONObject().put("type", "input_text").put("text", content.toString()))
        }
        return parts
    }

    private fun contentText(content: Any?): String = when (content) {
        null -> ""
        is String -> content
        is List<*> -> content.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("\n")
        else -> content.toString()
    }

    /** Sends with a fresh token; one refresh and retry on 401; retries 429, 5xx, and network errors. */
    private suspend fun send(client: OkHttpClient, body: String): Response {
        var refreshedOn401 = false
        var attempt = 0
        while (true) {
            ensureFreshToken()
            try {
                val response = client.newCall(request(body)).execute()
                val code = response.code
                if (code in 200..299) return response
                val error = response.body?.string()?.take(300) ?: ""
                response.close()
                if (code == 401 && !refreshedOn401) {
                    refreshedOn401 = true
                    refresh("unauthorized_recovery")
                    continue
                }
                if ((code in 400..499 && code != 429) || attempt == MAX_ATTEMPTS - 1) {
                    throw modelException("ChatGPT error ($code): $error", ModelError.fromHttp(code, error, model))
                }
                Log.w(TAG, "HTTP $code, retrying")
            } catch (e: IOException) {
                if (attempt == MAX_ATTEMPTS - 1) throw modelException("ChatGPT unreachable: ${e.message}", ModelError.unreachable(model), e)
                Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
            }
            delay((1L shl attempt) * retryDelayMs)
            attempt++
        }
    }

    private fun request(body: String): Request = authorized(Request.Builder()
        .url("${baseUrl.trimEnd('/')}/responses")
        .post(body.toRequestBody(JSON))
        .header("Accept", "text/event-stream"))

    /** The sign-in headers the Codex CLI sends on every backend request. */
    private fun authorized(builder: Request.Builder): Request {
        builder
            .header("Authorization", "Bearer ${store.accessToken}")
            .header("originator", ORIGINATOR)
            .header("User-Agent", USER_AGENT)
            .header("session-id", sessionId)
        if (store.accountId.isNotBlank()) builder.header("chatgpt-account-id", store.accountId)
        return builder.build()
    }

    /**
     * The models this account may use: GET {baseUrl}/models?client_version=..., as codex-rs/codex-api/src/endpoint/models.rs
     * does. Keeps models with `visibility` "list" that take images, in the backend's `priority` order. Throws on failure.
     */
    suspend fun listModels(): List<ModelChoice> = withContext(Dispatchers.IO) {
        ensureFreshToken()
        val url = "${baseUrl.trimEnd('/')}/models?client_version=$CLIENT_VERSION"
        try {
            httpClient.newCall(authorized(Request.Builder().url(url).get())).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw modelException("ChatGPT models error (${response.code})", ModelError.fromHttp(response.code, body, null))
                }
                ModelLists.parseChatGpt(body)
            }
        } catch (e: IOException) {
            throw modelException("ChatGPT unreachable: ${e.message}", ModelError.unreachable(null), e)
        }
    }

    private fun ensureFreshToken() {
        if (!store.hasRefreshToken()) throw modelException("Not signed in to ChatGPT. Sign in on the setup screen.", ModelError(ModelErrorKind.AUTH, model))
        if (store.accessToken.isBlank() || store.expiresAtMs - clock() < REFRESH_MARGIN_MS) refresh("proactive_expiry")
    }

    /**
     * POST {tokenUrl} as JSON {grant_type: refresh_token, client_id, refresh_token}, as
     * codex-rs/login/src/auth/manager.rs request_chatgpt_token_refresh does. Saves a rotated refresh token.
     */
    private fun refresh(reason: String) {
        synchronized(ChatGptTokenStore.REFRESH_LOCK) {
            val used = store.refreshToken
            val payload = JSONObject()
                .put("grant_type", "refresh_token")
                .put("client_id", PlanSignIn.CHATGPT_CLIENT_ID)
                .put("refresh_token", used)
                .put("refresh_reason", reason)
                .toString()
            val request = Request.Builder().url(tokenUrl).post(payload.toRequestBody(JSON)).build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw modelException(
                            "ChatGPT sign-in could not be refreshed (HTTP ${response.code}). Sign in again.",
                            ModelError(if (response.code >= 500) ModelErrorKind.SERVER else ModelErrorKind.AUTH, model, response.code)
                        )
                    }
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val access = json.optString("access_token", "")
                    if (access.isEmpty()) throw LLMException("ChatGPT token refresh returned no access token")
                    val rotated = json.optString("refresh_token", "").ifEmpty { used }
                    val account = ChatGptTokens.accountIdFrom(json.optString("id_token", ""), access).ifEmpty { store.accountId }
                    val expiresIn = if (json.has("expires_in")) json.optLong("expires_in") else null
                    store.save(access, rotated, account, ChatGptTokens.expiresAtMs(access, expiresIn, clock()))
                    Log.i(TAG, "ChatGPT token refreshed ($reason)")
                }
            } catch (e: IOException) {
                throw modelException("ChatGPT token refresh failed: ${e.message}", ModelError.unreachable(model), e)
            }
        }
    }

    /**
     * Reads the Responses SSE stream. Returns the function-call arguments (if any) and the output text.
     * Events: response.function_call_arguments.delta/.done, response.output_item.done (function_call),
     * response.output_text.delta, response.completed, response.failed, error.
     */
    internal fun parseStream(response: Response): Pair<String?, String> {
        val args = StringBuilder()
        var doneArgs: String? = null
        val text = StringBuilder()
        response.use {
            val body = it.body ?: return null to ""
            BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val event = try { JSONObject(data) } catch (_: Exception) { continue }
                    when (event.optString("type")) {
                        "response.function_call_arguments.delta" -> args.append(event.optString("delta"))
                        "response.function_call_arguments.done" -> doneArgs = event.optString("arguments").ifEmpty { null }
                        "response.output_item.done" -> {
                            val item = event.optJSONObject("item")
                            if (item?.optString("type") == "function_call" && doneArgs == null) {
                                doneArgs = item.optString("arguments").ifEmpty { null }
                            }
                        }
                        "response.output_text.delta" -> text.append(event.optString("delta"))
                        "response.failed", "error" -> {
                            val err = event.optJSONObject("response")?.optJSONObject("error") ?: event.optJSONObject("error") ?: event
                            val kind = when (err.optString("code")) {
                                "model_not_found" -> ModelErrorKind.MODEL_NOT_FOUND
                                "rate_limit_exceeded", "usage_limit_reached", "insufficient_quota" -> ModelErrorKind.RATE_LIMIT
                                "server_error" -> ModelErrorKind.SERVER
                                else -> ModelErrorKind.OTHER
                            }
                            throw modelException("ChatGPT error: ${err.optString("message", "request failed").take(300)}", ModelError(kind, model))
                        }
                        "response.completed", "response.incomplete" -> break
                    }
                }
            }
        }
        return (doneArgs ?: args.toString().ifEmpty { null }) to text.toString()
    }

    private fun parseArgs(args: String): Map<String, Any?>? = try {
        ResponseParser.jsonObjectToMap(JSONObject(args))
    } catch (_: Exception) {
        ResponseParser.extractFieldsFromMalformedJson(args)
    }

    override fun shutdown() {
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (_: Exception) {}
    }
}
