package ai.opencyclone.tasks

import ai.opencyclone.proactive.AllowlistDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TaskRunner {
    fun start(taskId: Long, goal: String)
    fun stop(taskId: Long)
}

interface ProposalPresenter {
    fun showDoThis(taskId: Long, goal: String, sourceLabel: String)
    fun dismiss(taskId: Long)
}

data class WindowSummary(val handled: Int, val hidden: Int, val needsYou: Int)

class TaskCoordinator(
    private val dao: TaskDao, private val windows: WindowDao, private val allowlist: AllowlistDao,
    private val runner: TaskRunner, private val presenter: ProposalPresenter,
    private val now: () -> Long,
    /** True for the process's own coordinator: the first locked call closes rows left by a stopped process. */
    recoverOnFirstUse: Boolean = false,
    /**
     * Called inside the coordinator lock after a task row reached a terminal state, with the row as it was
     * before. It must not call back into the coordinator; the runtime only queues thread rows from it.
     */
    private val onFinished: (before: TaskEntity, state: String, result: String?) -> Unit = { _, _, _ -> }
) {
    // Shared across coordinators so callers cannot accidentally create two task slots.
    companion object {
        private val mutex = Mutex()
        const val EXPIRY_MS = 30 * 60 * 1000L
        private val terminal = setOf("done", "failed", "cancelled")
        /** The states that hold the single task slot (TaskDao.occupied). */
        private val slotStates = setOf("running", "paused", "awaiting_approval")
        const val STOPPED_RESULT = "The app stopped before this task finished."
    }

    // Null until restart recovery ran; then the ids it closed.
    private var recovered: List<Long>? = if (recoverOnFirstUse) null else emptyList()

    // Every locked call recovers first, so no request can start before stale rows are closed.
    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock {
        recoverOnce()
        block()
    }

    /**
     * Call when the process starts. No engine can be alive in a new process, so a row that still
     * holds the task slot was left by a process that stopped (crash, system kill, update, force
     * stop). Those rows end as failed. Queued and proposed rows stay. Returns the closed ids; the
     * work runs once per coordinator, later calls return the same ids.
     */
    suspend fun recoverAfterProcessStart(): List<Long> = locked { recovered.orEmpty() }

    private suspend fun recoverOnce(): List<Long> {
        recovered?.let { return it }
        val stale = dao.all().filter { it.state in slotStates }
        stale.forEach { finish(it, "failed", STOPPED_RESULT) }
        return stale.map { it.id }.also { recovered = it }
    }

    suspend fun submitUserRequest(goal: String): Long = locked {
        expire()
        val at = now()
        val id = dao.insert(TaskEntity(origin = "user", goal = goal, state = "queued", createdAt = at, updatedAt = at))
        drain()
        id
    }

    suspend fun submitProposal(goal: String, sourcePackage: String, sourceKey: String, sourceLabel: String): Long? = locked {
        expire()
        val existing = dao.openProposal(sourceKey)
        if (existing != null) {
            dao.update(existing.copy(goal = goal, updatedAt = now()))
            if (existing.state == "proposed") presenter.showDoThis(existing.id, goal, sourceLabel)
            drain()
            return@locked existing.id
        }
        val at = now()
        val automatic = (windows.latest()?.endsAt ?: Long.MIN_VALUE) > at || allowlist.contains(sourcePackage)
        val id = dao.insert(TaskEntity(origin = "notification", goal = goal,
            state = if (automatic) "queued" else "proposed", sourceKey = sourceKey, createdAt = at, updatedAt = at))
        if (!automatic) {
            presenter.showDoThis(id, goal, sourceLabel)
            windows.count(at, needsYou = 1)
        }
        drain()
        id
    }

    suspend fun acceptProposal(taskId: Long) = locked {
        expire()
        val task = dao.get(taskId)
        if (task?.state == "proposed") {
            dao.update(task.copy(state = "queued", updatedAt = now()))
            presenter.dismiss(taskId)
        }
        drain()
    }

    /**
     * The owner said "Not now" to a proposal (home page or the card's Dismiss): it ends for good. Its source key is
     * cleared, so a later message from the same sender makes a new proposal. Not counted again in the window: it
     * was counted as "needs you" when it was proposed. Returns false when it was no longer a proposal.
     */
    suspend fun declineProposal(taskId: Long): Boolean = locked {
        val task = dao.get(taskId)
        if (task?.state != "proposed") return@locked false
        dao.update(task.copy(state = "cancelled", sourceKey = null, updatedAt = now()))
        onFinished(task, "cancelled", null)
        presenter.dismiss(taskId)
        true
    }

    suspend fun onTaskFinished(taskId: Long, state: String, result: String?) = locked {
        require(state in terminal)
        val task = dao.get(taskId) ?: return@locked
        if (task.state in terminal) return@locked
        finish(task, state, result)
        expire()
        drain()
    }

    suspend fun onAwaitingApproval(taskId: Long, heldActionJson: String, heldFactsJson: String) = locked {
        val task = dao.get(taskId) ?: return@locked
        if (task.state !in setOf("running", "paused")) return@locked
        dao.update(task.copy(state = "awaiting_approval", heldAction = heldActionJson, heldFacts = heldFactsJson, updatedAt = now()))
    }

    /** The owner approved or edited the held action: the task runs again and can be held again. */
    suspend fun onApprovalResolved(taskId: Long) = locked {
        val task = dao.get(taskId) ?: return@locked
        if (task.state != "awaiting_approval") return@locked
        dao.update(task.copy(state = "running", heldAction = null, heldFacts = null, updatedAt = now()))
    }

    suspend fun startWindow(durationMinutes: Int): Long = locked {
        require(durationMinutes > 0)
        val at = now()
        windows.latest()?.takeIf { it.endsAt > at }?.let { windows.end(it.id, at) }
        windows.insert(WindowEntity(endsAt = at + durationMinutes * 60_000L, startedAt = at))
    }

    suspend fun stopWindow(): WindowSummary? = locked {
        val window = windows.latest() ?: return@locked null
        if (window.endsAt > now()) windows.end(window.id, now())
        WindowSummary(window.handled, window.hidden, window.needsYou)
    }

    suspend fun expireOld() = locked { expire(); drain() }

    private suspend fun expire() {
        val cutoff = now() - EXPIRY_MS
        dao.all().filter {
            (it.origin == "notification" && it.state in setOf("proposed", "queued") && it.createdAt <= cutoff) ||
                (it.state == "awaiting_approval" && it.updatedAt <= cutoff)
        }.forEach { finish(it, "cancelled", null) }
    }

    private suspend fun finish(task: TaskEntity, state: String, result: String?) {
        dao.update(task.copy(state = state, sourceKey = null, heldAction = null, heldFacts = null, result = result, updatedAt = now()))
        onFinished(task, state, result)
        presenter.dismiss(task.id)
        if (state == "cancelled" && task.state in setOf("running", "paused", "awaiting_approval")) runner.stop(task.id)
        if (task.origin == "notification" && state == "done") windows.count(now(), handled = 1)
        if (state in setOf("failed", "cancelled")) windows.count(now(), needsYou = 1)
    }

    private suspend fun drain() {
        if (dao.occupied() != null) return
        val task = dao.next() ?: return
        // Only open proposals retain keys, so the unique index permits later conversations.
        dao.update(task.copy(state = "running", sourceKey = null, updatedAt = now()))
        try {
            runner.start(task.id, task.goal)
        } catch (error: Exception) {
            finish(task.copy(state = "running"), "failed", error.message)
            drain()
        }
    }
}
