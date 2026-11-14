package ai.opencyclone.llm

import ai.opencyclone.config.ConfigRepository

/** The model client for the saved provider. One place, used by the agent, triage, the app builder, and the first-run test. */
object ModelClients {
    fun forConfig(config: ConfigRepository): LLMClientInterface = when (config.apiProvider) {
        ConfigRepository.PROVIDER_ANTHROPIC -> AnthropicClient(apiKey = config.apiKey, model = config.model, baseUrl = config.baseUrl)
        ConfigRepository.PROVIDER_OLLAMA -> OllamaClient(model = config.model, baseUrl = config.baseUrl)
        ConfigRepository.PROVIDER_CHATGPT -> ChatGptFallback(config) { model ->
            ChatGptPlanClient(
                store = ChatGptTokenStore(config.appContext),
                model = model,
                baseUrl = config.baseUrl.ifBlank { ConfigRepository.DEFAULT_CHATGPT_BASE_URL }
            )
        }
        else -> LLMClient(
            apiKey = config.apiKey,
            model = config.model,
            baseUrl = config.baseUrl,
            temperature = if (config.apiProvider == ConfigRepository.PROVIDER_KIMI) 0.6 else 0.1,
            disableThinking = config.apiProvider == ConfigRepository.PROVIDER_KIMI
        )
    }

    /** The local model provider needs no key; the ChatGPT plan signs in instead of using a key. */
    fun needsKey(provider: String): Boolean =
        provider != ConfigRepository.PROVIDER_OLLAMA && provider != ConfigRepository.PROVIDER_CHATGPT

    /** True when a task can reach a model: a key is saved, the provider needs none, or the ChatGPT sign-in is saved. */
    fun isConfigured(config: ConfigRepository): Boolean = when (config.apiProvider) {
        ConfigRepository.PROVIDER_CHATGPT -> ChatGptTokenStore(config.appContext).hasRefreshToken()
        else -> config.apiKey.isNotBlank() || !needsKey(config.apiProvider)
    }
}

/**
 * The ChatGPT client with one automatic recovery: when the account refuses the model (model-not-found) and the
 * owner never chose that model by hand, it tries the models of [ConfigRepository.CHATGPT_FALLBACK] once each,
 * stores the first that answers, and leaves the sentence "I switched to <name>, because this account cannot use
 * <old>." in [ConfigRepository.modelSwitchNotice]. A model chosen by hand is never replaced.
 */
internal class ChatGptFallback(
    private val config: ConfigRepository,
    private val build: (String) -> LLMClientInterface,
) : LLMClientInterface {

    private var model = config.model.ifBlank { ConfigRepository.DEFAULT_CHATGPT_MODEL }
    private var client = build(model)

    override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> = recover { it.chatWithTools(messages) }

    override suspend fun completeText(prompt: String): String = recover { it.completeText(prompt) }

    private suspend fun <T> recover(call: suspend (LLMClientInterface) -> T): T {
        try {
            return call(client)
        } catch (e: LLMException) {
            if (e.error?.kind != ModelErrorKind.MODEL_NOT_FOUND || config.modelChosenByHand) throw e
            val refused = model
            for (candidate in ConfigRepository.CHATGPT_FALLBACK.filter { it != refused }) {
                val next = build(candidate)
                try {
                    val result = call(next)
                    client.shutdown()
                    client = next
                    model = candidate
                    config.model = candidate
                    config.modelSwitchNotice = switchSentence(candidate, refused)
                    return result
                } catch (again: LLMException) {
                    next.shutdown()
                    if (again.error?.kind != ModelErrorKind.MODEL_NOT_FOUND) throw again
                }
            }
            throw e
        }
    }

    override fun shutdown() = client.shutdown()

    companion object {
        fun switchSentence(to: String, from: String) = "I switched to $to, because this account cannot use $from."
    }
}
