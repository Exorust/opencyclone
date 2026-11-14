package ai.opencyclone.llm

import org.json.JSONObject
import java.io.IOException

/** What went wrong with a model request, in a form the owner-facing sentences can map without reading prose. */
enum class ModelErrorKind { UNREACHABLE, MODEL_NOT_FOUND, AUTH, RATE_LIMIT, SERVER, OTHER }

data class ModelError(val kind: ModelErrorKind, val model: String? = null, val httpCode: Int? = null) {

    /**
     * A machine-readable tag appended to [LLMException.message]. The engine passes only the message text on
     * to the feed (`"LLM error: <message>"`), so the tag is how the kind reaches `FeedText.failure`.
     */
    fun tag(): String = buildString {
        append("[model-error kind=").append(kind.name)
        if (httpCode != null) append(" code=").append(httpCode)
        if (!model.isNullOrBlank()) append(" model=").append(model)
        append(']')
    }

    companion object {
        private val TAG = Regex("\\[model-error kind=([A-Z_]+)(?: code=(\\d+))?(?: model=([^\\s\\]]+))?]")

        /** The tag inside [text], or null. */
        fun find(text: String?): ModelError? {
            val m = TAG.find(text ?: return null) ?: return null
            val kind = runCatching { ModelErrorKind.valueOf(m.groupValues[1]) }.getOrNull() ?: return null
            return ModelError(kind, m.groupValues[3].ifEmpty { null }, m.groupValues[2].toIntOrNull())
        }

        /** [text] without the tag, for logs and details. */
        fun strip(text: String): String = text.replace(TAG, "").trim()

        /**
         * The kind of an HTTP failure. Model-not-found follows codex-rs/codex-api/src/error.rs: `error.code ==
         * "model_not_found"` with 400, 403 or 404; a 404 that names the model (Anthropic, Ollama) counts too.
         */
        fun fromHttp(code: Int, body: String, model: String?): ModelError {
            val kind = when {
                code in setOf(400, 403, 404) && isModelNotFound(code, body) -> ModelErrorKind.MODEL_NOT_FOUND
                code == 401 || code == 403 -> ModelErrorKind.AUTH
                code == 429 || isUsageLimit(body) -> ModelErrorKind.RATE_LIMIT
                code >= 500 -> ModelErrorKind.SERVER
                else -> ModelErrorKind.OTHER
            }
            return ModelError(kind, model, code)
        }

        fun unreachable(model: String?) = ModelError(ModelErrorKind.UNREACHABLE, model)

        private fun isModelNotFound(code: Int, body: String): Boolean {
            val json = runCatching { JSONObject(body) }.getOrNull()
            val error = json?.optJSONObject("error")
            val codes = listOfNotNull(error?.optString("code"), error?.optString("type"), json?.optString("code"))
            if ("model_not_found" in codes) return true
            return code == 404 && body.contains("model", ignoreCase = true)
        }

        private fun isUsageLimit(body: String): Boolean =
            body.contains("usage_limit", ignoreCase = true) || body.contains("rate_limit", ignoreCase = true) ||
                body.contains("insufficient_quota", ignoreCase = true)
    }
}

/** Builds an [LLMException] whose message keeps [text] and ends with the error's tag. */
internal fun modelException(text: String, error: ModelError, cause: Throwable? = null): LLMException =
    LLMException("$text ${error.tag()}", cause, error)

/** True for an [IOException], including a timeout. */
internal fun Throwable.isNetwork(): Boolean = this is IOException
