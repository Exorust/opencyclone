package ai.opencyclone.engine

/**
 * How one engine run ended. Set exactly once per engine, so a watcher that subscribes late
 * (or never sees the Running state) still learns the end.
 */
sealed class TaskEnd {
    data class Done(val message: String?) : TaskEnd()
    data class Failed(val message: String) : TaskEnd()
    data class Cancelled(val message: String?) : TaskEnd()
    /** The owner rejected a held action, or its approval expired. */
    object Rejected : TaskEnd()

    companion object {
        /** Result text shown for [Rejected], and the engine's Idle message after a rejection. */
        const val REJECTED_MESSAGE = "Cancelled by the user"

        /** Task table state and result text for an end. */
        fun toTaskState(end: TaskEnd): Pair<String, String?> = when (end) {
            is Done -> "done" to end.message
            is Failed -> "failed" to end.message
            is Cancelled -> "cancelled" to end.message
            Rejected -> "cancelled" to REJECTED_MESSAGE
        }

        /** The end implied by the engine's last state when no explicit end was recorded. */
        fun fromFinalState(state: AgentState): TaskEnd = when (state) {
            is AgentState.Error -> Failed(state.message)
            is AgentState.Idle -> if (state.resultMessage != null) Done(state.resultMessage) else Cancelled(null)
            else -> Cancelled(null)
        }
    }
}
