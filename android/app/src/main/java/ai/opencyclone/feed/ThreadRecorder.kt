package ai.opencyclone.feed

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar

/**
 * The one writer of today's thread. Every row the home page shows is written here.
 *
 * Privacy (F13): at the change of day every notification row from before local midnight that did not become
 * a task is deleted, so the text of such a notification never outlives its day. The rows of a task and the owner's
 * and the phone's words stay: the history keeps them (F15.5). Only "today" is shown on the home page.
 */
class ThreadRecorder(
    private val dao: ThreadItemDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val startOfDay: (Long) -> Long = ::localMidnight,
    /**
     * ponytail: no longer called. Evidence pictures belong to tasks, and tasks are kept for the history, so the
     * change of day deletes none; deleting a task deletes its pictures (TaskHistory.delete). The runtime still
     * passes EvidenceStore.deleteBefore here; remove the parameter when that wiring is next edited.
     */
    @Suppress("unused") private val onPruned: (cutoff: Long) -> Unit = {},
) {
    private val mutex = Mutex()
    @Volatile private var prunedFor = Long.MIN_VALUE

    /** Today's rows, oldest first. Emits on every change and again after midnight. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun today(): Flow<List<ThreadItem>> = midnights().flatMapLatest { since ->
        dao.since(since).map { rows -> rows.mapNotNull { runCatching { it.toItem() }.getOrNull() } }
    }

    private fun midnights(): Flow<Long> = flow {
        while (true) {
            val start = startOfDay(now())
            pruneIfNewDay()
            emit(start)
            delay((start + DAY_MS - now()).coerceAtLeast(MIN_TICK_MS))
        }
    }.distinctUntilChanged()

    /** Deletes the notification rows of earlier days that never became a task, once per day. Safe to call often. */
    suspend fun pruneIfNewDay() {
        val cutoff = startOfDay(now())
        if (prunedFor == cutoff) return
        mutex.withLock {
            if (prunedFor == cutoff) return
            dao.pruneBefore(cutoff)
            prunedFor = cutoff
        }
    }

    private suspend fun insert(item: ThreadItem): Long {
        pruneIfNewDay()
        return mutex.withLock { dao.insert(ThreadItemEntity.from(item.copy(id = 0, at = now()))) }
    }

    /** Changes one row; does nothing when the row is gone (for example pruned at midnight). */
    suspend fun update(id: Long, change: (ThreadItem) -> ThreadItem) = mutex.withLock {
        val row = dao.get(id) ?: return@withLock
        val item = runCatching { row.toItem() }.getOrNull() ?: return@withLock
        val next = change(item)
        if (next != item) dao.update(ThreadItemEntity.from(next.copy(id = row.id)))
    }

    suspend fun get(id: Long): ThreadItem? = dao.get(id)?.let { runCatching { it.toItem() }.getOrNull() }

    suspend fun delete(id: Long) = mutex.withLock { dao.delete(id) }

    // ── One function for each kind ───────────────────────────────────────

    /** The owner's request, as typed or spoken. Several instructions stay one row. */
    suspend fun owner(text: String): Long = insert(ThreadItem(0, 0, ThreadKind.OWNER, text = text.trim()))

    /** A notification that passed the privacy filter and the hide rules; open until triage or its task is done. */
    suspend fun notification(appPackage: String, sender: String, text: String): Long =
        insert(ThreadItem(0, 0, ThreadKind.NOTIFICATION, ThreadState.OPEN, appPackage = appPackage,
            title = sender.ifBlank { null }, text = text))

    /** Triage dropped it (ignored or hidden): the row goes. */
    suspend fun dropNotification(id: Long) = delete(id)

    /** Triage kept it: done, or still open and linked to the task that works on it. */
    suspend fun notificationTriaged(id: Long, taskId: Long?) = update(id) {
        if (taskId != null) it.copy(taskId = taskId) else it.copy(state = ThreadState.DONE)
    }

    suspend fun sentence(text: String, taskId: Long? = null, detail: String? = null): Long =
        insert(ThreadItem(0, 0, ThreadKind.SENTENCE, taskId = taskId, text = FeedText.asSentence(text), detail = detail))

    suspend fun question(text: String, taskId: Long?): Long =
        insert(ThreadItem(0, 0, ThreadKind.QUESTION, ThreadState.OPEN, taskId = taskId, text = FeedText.asSentence(text)))

    suspend fun approval(taskId: Long, heading: String, actionWord: String, appPackage: String?,
                         evidence: Evidence, canApprove: Boolean): Long =
        insert(ThreadItem(0, 0, ThreadKind.APPROVAL, ThreadState.OPEN, taskId = taskId, appPackage = appPackage,
            text = heading, actionWord = actionWord, evidence = evidence, canApprove = canApprove))

    /** A task ended as done: [text] is already the short result sentence. */
    suspend fun result(taskId: Long?, text: String): Long =
        insert(ThreadItem(0, 0, ThreadKind.RESULT, taskId = taskId, text = text))

    suspend fun receipt(taskId: Long?, appPackage: String?, actionWord: String, text: String, openAppId: Long? = null): Long =
        insert(ThreadItem(0, 0, ThreadKind.RECEIPT, taskId = taskId, appPackage = appPackage, actionWord = actionWord,
            text = text, openAppId = openAppId))

    /** App build or change progress, updated in place later. */
    suspend fun progress(text: String, detail: String?, appName: String?, openAppId: Long?): Long =
        insert(ThreadItem(0, 0, ThreadKind.PROGRESS, ThreadState.OPEN, title = appName, text = text, detail = detail,
            openAppId = openAppId))

    /** The progress row becomes the result card (ThreadAdapter: RESULT with detail and openAppId). */
    suspend fun progressToResultCard(id: Long, appName: String, text: String, detail: String, appId: Long) = update(id) {
        it.copy(kind = ThreadKind.RESULT, state = ThreadState.DONE, title = appName, text = text, detail = detail,
            openAppId = appId)
    }

    /** The progress row becomes one sentence that says what was not done. */
    suspend fun progressToSentence(id: Long, text: String) = update(id) {
        it.copy(kind = ThreadKind.SENTENCE, state = ThreadState.FAILED, title = null, detail = null, openAppId = null,
            text = FeedText.asSentence(text))
    }

    /**
     * A yes-or-no question of the phone: open until the owner answers it. [ref] (stored in `title`, [ConfirmRef])
     * says what the answer does; null is a widget's sources. A proposal's row also carries its [taskId].
     */
    suspend fun confirm(text: String, actionWord: String, ref: String? = null, taskId: Long? = null): Long =
        insert(ThreadItem(0, 0, ThreadKind.CONFIRM, ThreadState.OPEN, taskId = taskId, title = ref, text = text,
            actionWord = actionWord))

    /** The open CONFIRM row of a proposal, or null. */
    suspend fun openConfirmOfTask(taskId: Long): ThreadItem? = mutex.withLock {
        dao.openForTask(taskId).firstOrNull { it.kind == ThreadKind.CONFIRM.name }?.let { runCatching { it.toItem() }.getOrNull() }
    }

    suspend fun setState(id: Long, state: ThreadState) = update(id) { it.copy(state = state) }

    /** Sets [state] only while the row is still open, so a later, weaker update cannot undo an earlier one. */
    suspend fun resolve(id: Long, state: ThreadState) = update(id) { if (it.state == ThreadState.OPEN) it.copy(state = state) else it }

    /** A task ended: its waiting approval expired, its question failed, its notification is done. */
    suspend fun closeTask(taskId: Long) {
        val open = mutex.withLock { dao.openForTask(taskId) }
        for (row in open) closeOpen(row)
    }

    /**
     * Process start: no engine and no builder survived the old process, so every waiting approval expired,
     * every waiting question failed, a build in progress stopped, and a notification still waiting for
     * triage will not be triaged.
     */
    suspend fun expireOpenAfterRestart() {
        val open = mutex.withLock { dao.open() }
        for (row in open) {
            if (row.kind == ThreadKind.NOTIFICATION.name && row.taskId != null) continue // its task row is still queued
            if (row.kind == ThreadKind.CONFIRM.name && row.taskId != null) continue // a proposal survives a restart
            if (row.kind == ThreadKind.PROGRESS.name) progressToSentence(row.id, BUILD_STOPPED) else closeOpen(row)
        }
    }

    private suspend fun closeOpen(row: ThreadItemEntity) {
        val state = when (row.kind) {
            ThreadKind.APPROVAL.name, ThreadKind.CONFIRM.name -> ThreadState.EXPIRED
            ThreadKind.QUESTION.name -> ThreadState.FAILED
            else -> ThreadState.DONE
        }
        resolve(row.id, state)
    }

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val MIN_TICK_MS = 1_000L
        const val BUILD_STOPPED = "The app stopped before the build finished."

        fun localMidnight(at: Long): Long = Calendar.getInstance().apply {
            timeInMillis = at
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
