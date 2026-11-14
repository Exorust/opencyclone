package ai.opencyclone.ui

/** Kind of a stored conversation message; AgentService and ChatHistoryRepository save it by name. */
enum class MessageType {
    USER_INPUT,
    AGENT_STATUS,
    AGENT_DEBUG,
    AGENT_QUESTION,
    USER_ANSWER,
    USER_SUPPLEMENT,
    AGENT_RESULT,
    AGENT_CYCLE,
    SYSTEM
}
