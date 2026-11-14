package ai.opencyclone.feed

import ai.opencyclone.engine.AgentState
import ai.opencyclone.engine.EngineEvent
import ai.opencyclone.miniapp.BuiltAppStyle
import ai.opencyclone.miniapp.MiniAppEntity
import ai.opencyclone.tasks.TaskEntity
import ai.opencyclone.tasks.WindowEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * [Feed.live]: one value built from the task table (running and queued), the window table, the built apps,
 * and what the engine reported in memory (caption, apps operated, phase, the waiting approval and question).
 */
class LiveStateSource(
    scope: CoroutineScope,
    occupied: Flow<TaskEntity?>,
    queuedCount: Flow<Int>,
    latestWindow: Flow<WindowEntity?>,
    apps: Flow<List<MiniAppEntity>>,
    /** All task rows; read again whenever the queued count changes, for the goals of the waiting tasks. */
    private val allTasks: suspend () -> List<TaskEntity> = { emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_QUEUED_GOAL = 80

        /** Goals of the queued rows, oldest first, each at most [MAX_QUEUED_GOAL] characters. */
        fun queuedGoals(tasks: List<TaskEntity>): List<String> = tasks.filter { it.state == "queued" }
            .sortedWith(compareBy<TaskEntity>({ it.createdAt }, { it.id }))
            .map { t -> t.goal.trim().let { if (it.length <= MAX_QUEUED_GOAL) it else it.take(MAX_QUEUED_GOAL - 1).trimEnd() + "\u2026" } }
    }

    /** What the engine reported for one task. [caption] is null until the model gave one. */
    data class Progress(val startedAt: Long, val packages: List<String> = emptyList(), val caption: String? = null,
                        val phase: Phase = Phase.RUNNING)

    private data class PendingApproval(val view: ApprovalView, val status: StatusSentence.Approval)

    private val progress = MutableStateFlow<Map<Long, Progress>>(emptyMap())
    private val approval = MutableStateFlow<PendingApproval?>(null)
    private val question = MutableStateFlow<QuestionView?>(null)
    private val updating = MutableStateFlow<Set<Long>>(emptySet())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val activeWindow: Flow<WindowEntity?> = latestWindow.flatMapLatest { window ->
        flow {
            if (window == null || window.endsAt <= now()) {
                emit(null)
            } else {
                emit(window)
                delay(window.endsAt - now())
                emit(null)
            }
        }
    }

    private data class Queued(val count: Int, val goals: List<String>)
    private data class Stored(val occupied: TaskEntity?, val queued: Queued, val window: WindowEntity?, val apps: List<MiniAppEntity>)

    private val queued: Flow<Queued> = queuedCount.map { n -> Queued(n, if (n == 0) emptyList() else queuedGoals(allTasks())) }
    private data class Memory(val progress: Map<Long, Progress>, val approval: PendingApproval?, val question: QuestionView?,
                              val updating: Set<Long>)

    val state: StateFlow<LiveState> = combine(
        combine(occupied, queued, activeWindow, apps, ::Stored),
        combine(progress, approval, question, updating, ::Memory),
        // No privileged connection after the start is done: "Not connected. Tap to reconnect."
        ai.opencyclone.AgentConnection.notConnected,
    ) { stored, memory, lost -> build(stored, memory, lost) }
        .stateIn(scope, SharingStarted.Eagerly, LiveState())

    private fun build(stored: Stored, memory: Memory, notConnected: Boolean): LiveState {
        val running = stored.occupied?.let { task ->
            val p = memory.progress[task.id]
            val phase = if (task.state == "awaiting_approval") Phase.WAITING_APPROVAL else p?.phase ?: Phase.RUNNING
            RunningTask(task.id, task.goal, p?.caption ?: FeedText.WORKING, p?.packages.orEmpty(),
                p?.startedAt ?: task.updatedAt, phase)
        }
        val window = stored.window?.let { WindowView(it.endsAt, it.handled, it.hidden, it.needsYou) }
        val status = StatusSentence.line(
            approval = memory.approval?.status,
            questionWaiting = memory.question != null,
            runningCaption = running?.let { memory.progress[it.taskId]?.caption },
            running = running != null,
            windowEndsAt = window?.endsAt,
            notConnected = notConnected,
        )
        return LiveState(
            running = listOfNotNull(running),
            queued = stored.queued.count,
            queuedGoals = stored.queued.goals,
            window = window,
            approval = memory.approval?.view,
            question = memory.question,
            status = status.text,
            statusEmphasis = status.emphasis,
            apps = stored.apps.map { AppTile(it.id, it.name, it.accent ?: BuiltAppStyle.DEFAULT_ACCENT, it.id in memory.updating) },
        )
    }

    // ── Engine reports ───────────────────────────────────────────────────

    fun onEvent(taskId: Long, event: EngineEvent) {
        when (event) {
            is EngineEvent.Started -> progress.update { it + (taskId to Progress(now())) }
            is EngineEvent.Step -> change(taskId) { p ->
                val pkg = event.topPackage
                p.copy(packages = if (pkg == null) p.packages else p.packages.filter { it != pkg } + pkg,
                    caption = event.caption ?: p.caption)
            }
            is EngineEvent.Asking -> change(taskId) { it.copy(phase = Phase.WAITING_ANSWER) }
            is EngineEvent.Answered -> change(taskId) { it.copy(phase = Phase.RUNNING) }
            is EngineEvent.Held -> change(taskId) { it.copy(phase = Phase.WAITING_APPROVAL) }
            is EngineEvent.Resolved -> change(taskId) { it.copy(phase = Phase.RUNNING) }
            is EngineEvent.Executed -> Unit
            is EngineEvent.Finished -> progress.update { it - taskId }
        }
    }

    /** Paused (takeover, reconnect) and handoff are states, not events. */
    fun onEngineState(taskId: Long, state: AgentState) {
        when (state) {
            AgentState.Paused, is AgentState.WaitingForHandoff -> change(taskId) { it.copy(phase = Phase.PAUSED) }
            is AgentState.Running -> change(taskId) { if (it.phase == Phase.PAUSED) it.copy(phase = Phase.RUNNING) else it }
            else -> Unit
        }
    }

    private fun change(taskId: Long, block: (Progress) -> Progress) =
        progress.update { it + (taskId to block(it[taskId] ?: Progress(now()))) }

    fun setApproval(view: ApprovalView, status: StatusSentence.Approval) {
        approval.value = PendingApproval(view, status)
    }

    fun clearApproval(taskId: Long) = approval.update { if (it?.view?.taskId == taskId) null else it }

    fun setQuestion(view: QuestionView) {
        question.value = view
    }

    fun clearQuestion(taskId: Long?) = question.update { if (it?.taskId == taskId) null else it }

    fun setUpdating(appId: Long, on: Boolean) = updating.update { if (on) it + appId else it - appId }

    /** The task ended: nothing of it waits any more. */
    fun taskEnded(taskId: Long) {
        progress.update { it - taskId }
        clearApproval(taskId)
        clearQuestion(taskId)
    }
}
