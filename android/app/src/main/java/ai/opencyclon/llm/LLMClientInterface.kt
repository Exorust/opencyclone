package ai.opencyclon.llm

interface LLMClientInterface {
    suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?>

    /**
     * One plain-text completion on the same endpoint and key: no tool schema, no forced tool call.
     * Used by triage and the app builder. Clients that cannot do this throw.
     */
    suspend fun completeText(prompt: String): String =
        throw UnsupportedOperationException("This model client has no plain-text completion")

    fun shutdown()
}
