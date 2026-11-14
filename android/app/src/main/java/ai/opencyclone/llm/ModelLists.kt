package ai.opencyclone.llm

import ai.opencyclone.config.ConfigRepository
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One model the owner can choose. [pricePerMillionIn] is US dollars per million input tokens, when the list gives it. */
data class ModelChoice(val id: String, val label: String = id, val pricePerMillionIn: Double? = null)

/** How the app is connected to a model; decides where the list of models comes from. */
enum class ModelSource { CHATGPT, OPENROUTER, OPENAI_COMPATIBLE, ANTHROPIC, OLLAMA }

/**
 * The list of models for each way of connecting, cached for a day.
 * - ChatGPT sign-in: the Codex backend's `GET /models` (see [ChatGptPlanClient.listModels]); [CHATGPT_STATIC] if it fails.
 * - OpenRouter: the public `GET /models`, only models that take images and support tools.
 * - Another OpenAI-compatible address: `GET <base>/models` with the key; null (type a name) if it fails.
 * - Anthropic: [ANTHROPIC_STATIC]. Ollama: `GET <base>/api/tags`.
 */
class ModelLists(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
    private val chatGptList: suspend (ConfigRepository) -> List<ModelChoice> = { config ->
        ChatGptPlanClient(ChatGptTokenStore(config.appContext), model = "", baseUrl = chatGptBase(config)).let { client ->
            try { client.listModels() } finally { client.shutdown() }
        }
    },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Tests point the fixed OpenRouter address at a local server. */
    private val rewriteUrl: (String) -> String = { it },
) {
    companion object {
        const val CACHE_MS = 24 * 60 * 60 * 1000L
        private const val PREFS = "model_lists"

        /**
         * The ChatGPT plan's models when the backend list cannot be read: the listed text-and-image models of the
         * Codex CLI's bundled codex-rs/models-manager/models.json, in its priority order. Image-only and review-only
         * models are left out.
         */
        val CHATGPT_STATIC = listOf(
            "gpt-6.1-sol", "gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-6.1-terra", "gpt-5.6-terra", "gpt-5.6-luna",
        ).map { ModelChoice(it) }

        /**
         * Anthropic models that accept the forced tool call the Anthropic client sends (`tool_choice` "any").
         * ponytail: Claude Opus 5.5, Sonnet 5.5 and Fable 5.2 reject a forced tool call with HTTP 400, so they are
         * not listed; the owner can still type one under "Other".
         */
        val ANTHROPIC_STATIC = listOf("claude-opus-5", "claude-sonnet-5", "claude-haiku-5-5", "claude-opus-4-8").map { ModelChoice(it) }

        fun sourceOf(config: ConfigRepository): ModelSource = when (config.apiProvider) {
            ConfigRepository.PROVIDER_CHATGPT -> ModelSource.CHATGPT
            ConfigRepository.PROVIDER_ANTHROPIC -> ModelSource.ANTHROPIC
            ConfigRepository.PROVIDER_OLLAMA -> ModelSource.OLLAMA
            else -> if (config.baseUrl.trimEnd('/') == ConfigRepository.OPENROUTER_BASE_URL) ModelSource.OPENROUTER
            else ModelSource.OPENAI_COMPATIBLE
        }

        private fun chatGptBase(config: ConfigRepository) = config.baseUrl.ifBlank { ConfigRepository.DEFAULT_CHATGPT_BASE_URL }

        /** The Codex backend's `{models: [...]}`: `visibility` "list", `input_modalities` with "image", by `priority`. */
        fun parseChatGpt(body: String): List<ModelChoice> {
            val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
            return (0 until models.length()).mapNotNull { models.optJSONObject(it) }
                .filter { it.optString("visibility", "list") == "list" }
                .filter { m -> m.optJSONArray("input_modalities")?.let { "image" in strings(it) } ?: true }
                .filter { m -> m.optString("slug").let { it.isNotBlank() && !it.contains("review") && !it.contains("image") } }
                .sortedBy { it.optInt("priority", Int.MAX_VALUE) }
                .map { ModelChoice(it.getString("slug"), it.optString("display_name").ifBlank { it.getString("slug") }) }
        }

        /** OpenRouter's `{data: [...]}`: models that take images and support tools, with the input price per million tokens. */
        fun parseOpenRouter(body: String): List<ModelChoice> {
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            return (0 until data.length()).mapNotNull { data.optJSONObject(it) }
                .filter { m -> m.optJSONObject("architecture")?.optJSONArray("input_modalities")?.let { "image" in strings(it) } == true }
                .filter { m -> m.optJSONArray("supported_parameters")?.let { "tools" in strings(it) } == true }
                .map { m ->
                    val perToken = m.optJSONObject("pricing")?.optString("prompt")?.toDoubleOrNull()
                    ModelChoice(m.getString("id"), m.optString("name").ifBlank { m.getString("id") }, perToken?.let { it * 1_000_000 })
                }
        }

        /** An OpenAI-compatible `{data: [{id}]}`. */
        fun parseOpenAi(body: String): List<ModelChoice> {
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
                .sorted().map { ModelChoice(it) }
        }

        /** Ollama's `{models: [{name}]}`. */
        fun parseOllama(body: String): List<ModelChoice> {
            val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
            return (0 until models.length()).mapNotNull { models.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
                .map { ModelChoice(it) }
        }

        /** "$0.30 per million input tokens" style figure: "$0.30". */
        fun price(perMillion: Double): String = String.format(Locale.US, "$%.2f", perMillion)

        private fun strings(array: JSONArray) = (0 until array.length()).map { array.optString(it) }

        private fun toJson(list: List<ModelChoice>) = JSONArray().apply {
            list.forEach { c ->
                put(JSONObject().put("id", c.id).put("label", c.label).apply { c.pricePerMillionIn?.let { put("price", it) } })
            }
        }

        private fun fromJson(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }.map {
            ModelChoice(it.getString("id"), it.optString("label", it.getString("id")), if (it.has("price")) it.getDouble("price") else null)
        }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The models for the saved connection, from the day-old cache or the network. Null means the list could not be
     * loaded and the owner types a name. Never throws.
     */
    suspend fun load(config: ConfigRepository, refresh: Boolean = false): List<ModelChoice>? = withContext(Dispatchers.IO) {
        val source = sourceOf(config)
        if (source == ModelSource.ANTHROPIC) return@withContext ANTHROPIC_STATIC
        val key = "${source.name}|${config.baseUrl.trimEnd('/')}"
        if (!refresh) cached(key)?.let { return@withContext it }
        val loaded = try {
            fetch(source, config)
        } catch (e: Exception) {
            null
        }
        when {
            !loaded.isNullOrEmpty() -> { store(key, loaded); loaded }
            source == ModelSource.CHATGPT -> CHATGPT_STATIC
            else -> null
        }
    }

    /** The cached list for the saved connection, without a network request; null when none or older than a day. */
    fun cachedFor(config: ConfigRepository): List<ModelChoice>? {
        val source = sourceOf(config)
        if (source == ModelSource.ANTHROPIC) return ANTHROPIC_STATIC
        return cached("${source.name}|${config.baseUrl.trimEnd('/')}") ?: if (source == ModelSource.CHATGPT) CHATGPT_STATIC else null
    }

    private suspend fun fetch(source: ModelSource, config: ConfigRepository): List<ModelChoice> {
        val base = config.baseUrl.trimEnd('/')
        return when (source) {
            ModelSource.CHATGPT -> chatGptList(config)
            ModelSource.OPENROUTER -> parseOpenRouter(get("$base/models", null))
            ModelSource.OPENAI_COMPATIBLE -> parseOpenAi(get("$base/models", config.apiKey.ifBlank { null }))
            ModelSource.OLLAMA -> parseOllama(get("$base/api/tags", null))
            ModelSource.ANTHROPIC -> ANTHROPIC_STATIC
        }
    }

    private fun get(url: String, key: String?): String {
        val builder = Request.Builder().url(rewriteUrl(url)).get()
        if (key != null) builder.header("Authorization", "Bearer $key")
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) throw LLMException("Model list error (${response.code})")
            return body
        }
    }

    private fun cached(key: String): List<ModelChoice>? {
        val at = prefs.getLong("$key|at", 0L)
        if (at == 0L || clock() - at > CACHE_MS) return null
        return runCatching { fromJson(JSONArray(prefs.getString("$key|list", "[]"))) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun store(key: String, list: List<ModelChoice>) {
        prefs.edit().putString("$key|list", toJson(list).toString()).putLong("$key|at", clock()).apply()
    }
}
