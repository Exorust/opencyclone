package ai.opencyclone.gate

import kotlinx.coroutines.CompletableDeferred

/** What the owner decided about one held action. */
sealed class HoldResult {
    object Approve : HoldResult()
    object Reject : HoldResult()
    /** Skip the held action and continue the task with this correction from the owner. */
    data class Edit(val text: String) : HoldResult()
}

/** Holds at most one pending approval for the process. */
class ApprovalSession {
    data class Pending(val taskId: Long, val held: HeldAction)

    private class Entry(val pending: Pending, val result: CompletableDeferred<HoldResult>)

    private var current: Entry? = null

    @Synchronized
    fun pending(): Pending? = current?.pending

    /** Suspends until approve, reject, edit, or expire. A second wait while one is pending fails at once. */
    suspend fun awaitResult(taskId: Long, held: HeldAction): HoldResult {
        val entry = synchronized(this) {
            check(current == null) { "An approval is already pending for task ${current?.pending?.taskId}" }
            Entry(Pending(taskId, held), CompletableDeferred()).also { current = it }
        }
        try {
            return entry.result.await()
        } finally {
            synchronized(this) { if (current === entry) current = null }
        }
    }

    suspend fun await(taskId: Long, held: HeldAction): Boolean = awaitResult(taskId, held) == HoldResult.Approve

    fun approve(taskId: Long): Boolean = complete(taskId, HoldResult.Approve)

    fun reject(taskId: Long): Boolean = complete(taskId, HoldResult.Reject)

    fun expire(taskId: Long): Boolean = complete(taskId, HoldResult.Reject)

    fun edit(taskId: Long, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return complete(taskId, HoldResult.Edit(trimmed))
    }

    private fun complete(taskId: Long, result: HoldResult): Boolean {
        val entry = synchronized(this) {
            current?.takeIf { it.pending.taskId == taskId }?.also { current = null }
        } ?: return false
        return entry.result.complete(result)
    }
}
