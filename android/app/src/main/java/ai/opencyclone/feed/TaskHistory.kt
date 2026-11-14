package ai.opencyclone.feed

import ai.opencyclone.db.AppDatabase
import ai.opencyclone.tasks.TaskDao
import ai.opencyclone.tasks.TaskEntity
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The history behind [Feed.history] and [Feed.record] (F15): the task table and the stored thread rows, read
 * together. A built-app request has no task row; it is listed by its result or progress row (negative id).
 */
class TaskHistory(
    private val tasks: TaskDao,
    private val rows: ThreadItemDao,
    /** `files/evidence`: one folder of pictures for each task id. */
    private val evidenceRoot: File,
    /** Deletes the upstream chat log of a task: its goal and the time span of the task. */
    private val deleteChatLog: suspend (goal: String, from: Long, to: Long) -> Unit = { _, _, _ -> },
) {
    fun history(query: String): Flow<List<HistoryEntry>> =
        combine(tasks.observeAll(), rows.observeAll()) { t, r -> entries(t, r.items(), query) }

    fun record(id: Long): Flow<List<ThreadItem>> =
        if (id > 0) combine(tasks.observe(id), rows.observeForTask(id)) { t, r -> recordOf(t, r.items()) }
        else rows.observeAll().map { buildRecord(-id, it.items()) }

    /** The words that "Ask again" sends, or null when the entry is gone. */
    suspend fun title(id: Long): String? =
        if (id > 0) tasks.get(id)?.goal?.let(::oneLine)
        else rows.allNow().items().let { all -> all.firstOrNull { it.id == -id }?.let { titleOfBuild(all, it) } }

    /** Deletes the entry; returns false for a task that has not ended (it is stopped first) or one that is gone. */
    suspend fun delete(id: Long): Boolean {
        if (id < 0) {
            val all = rows.allNow().items()
            val row = all.firstOrNull { it.id == -id } ?: return false
            if (row.kind == ThreadKind.PROGRESS && row.state == ThreadState.OPEN) return false
            ownerBefore(all, row)?.let { rows.delete(it.id) }
            rows.delete(row.id)
            return true
        }
        val task = tasks.get(id) ?: return false
        if (task.state !in ENDED) return false
        rows.deleteForTask(id)
        tasks.delete(id)
        withContext(Dispatchers.IO) { File(evidenceRoot, id.toString()).deleteRecursively() }
        runCatching { deleteChatLog(task.goal, task.createdAt, task.updatedAt) }
        return true
    }

    companion object {
        val ENDED = setOf("done", "failed", "cancelled")
        const val MAX_ICONS = 3
        // ponytail: the chat log has no task id; a conversation with the same words started inside the task's
        // time span (plus a minute for the service start) is taken to be this task's.
        private const val CHAT_SLACK_MS = 60_000L

        fun create(context: Context): TaskHistory {
            val db = AppDatabase.getInstance(context)
            return TaskHistory(db.taskDao(), db.threadItemDao(), File(context.filesDir, "evidence")) { goal, from, to ->
                withContext(Dispatchers.IO) {
                    val chat = db.chatDao()
                    chat.getAllConversations()
                        .filter { it.title == goal && it.createdAt >= from - CHAT_SLACK_MS && it.createdAt <= to + CHAT_SLACK_MS }
                        .forEach { chat.deleteConversation(it.id) }
                }
            }
        }

        private fun List<ThreadItemEntity>.items() = mapNotNull { runCatching { it.toItem() }.getOrNull() }

        fun oneLine(text: String): String = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

        /** Every entry, newest first, that matches [query] in its title, result, or receipts. */
        fun entries(tasks: List<TaskEntity>, rows: List<ThreadItem>, query: String): List<HistoryEntry> {
            val q = query.trim()
            val byTask = rows.filter { it.taskId != null }.groupBy { it.taskId!! }
            val fromTasks = tasks.mapNotNull { task ->
                val own = byTask[task.id].orEmpty()
                val title = oneLine(task.goal)
                val extra = listOfNotNull(task.result) +
                    own.filter { it.kind == ThreadKind.RESULT }.map { it.text } +
                    own.filter { it.kind == ThreadKind.RECEIPT }.flatMap { listOfNotNull(it.actionWord, it.text, it.title) }
                val match = matchIn(title, q)
                if (q.isNotEmpty() && match == null && extra.none { it.contains(q, ignoreCase = true) }) return@mapNotNull null
                HistoryEntry(task.id, task.createdAt, title, status(task, own), packages(own), match)
            }
            val fromBuilds = rows.filter(::isBuild).mapNotNull { row ->
                val title = titleOfBuild(rows, row)
                val match = matchIn(title, q)
                val extra = listOfNotNull(row.text, row.detail)
                if (q.isNotEmpty() && match == null && extra.none { it.contains(q, ignoreCase = true) }) return@mapNotNull null
                val status = if (row.kind == ThreadKind.PROGRESS && row.state == ThreadState.OPEN) HistoryStatus.RUNNING else HistoryStatus.DONE
                HistoryEntry(-row.id, ownerBefore(rows, row)?.at ?: row.at, title, status, emptyList(), match)
            }
            return (fromTasks + fromBuilds).sortedWith(compareByDescending<HistoryEntry> { it.at }.thenByDescending { it.taskId })
        }

        private fun matchIn(title: String, q: String): IntRange? {
            if (q.isEmpty()) return null
            val i = title.indexOf(q, ignoreCase = true)
            return if (i >= 0) i until i + q.length else null
        }

        /**
         * The status word of a task from its row and its thread rows. A cancelled task is "Not sent" when its approval
         * was answered "Not now" (or a proposal was declined), "Failed" when the approval expired, otherwise "Stopped".
         */
        fun status(task: TaskEntity, rows: List<ThreadItem>): HistoryStatus = when (task.state) {
            "done" -> HistoryStatus.DONE
            "failed" -> HistoryStatus.FAILED
            "awaiting_approval", "proposed" -> HistoryStatus.WAITING
            "running", "paused", "queued" ->
                if (rows.any { it.kind == ThreadKind.QUESTION && it.state == ThreadState.OPEN }) HistoryStatus.WAITING
                else HistoryStatus.RUNNING
            "cancelled" -> when {
                rows.any { it.kind == ThreadKind.APPROVAL && it.state == ThreadState.REJECTED } -> HistoryStatus.NOT_SENT
                rows.any { it.kind == ThreadKind.SENTENCE && it.text == FeedText.EXPIRED } -> HistoryStatus.FAILED
                rows.any { it.kind == ThreadKind.SENTENCE && (it.text == FeedText.STOPPED || it.text == FeedText.STOPPED_AFTER_SENDING) } ->
                    HistoryStatus.STOPPED
                // ponytail: a declined proposal and an expired one look the same in storage; both read "Not sent".
                task.origin == "notification" -> HistoryStatus.NOT_SENT
                else -> HistoryStatus.STOPPED
            }
            else -> HistoryStatus.FAILED
        }

        /** The apps a task used: from its approvals, receipts and notification, in order, at most [MAX_ICONS]. */
        fun packages(rows: List<ThreadItem>): List<String> = rows
            .filter { it.kind == ThreadKind.APPROVAL || it.kind == ThreadKind.RECEIPT || it.kind == ThreadKind.NOTIFICATION }
            .mapNotNull { it.appPackage }.distinct().take(MAX_ICONS)

        /** The record of one task; a task from before version 2 has no rows: its goal and its stored result. */
        fun recordOf(task: TaskEntity?, rows: List<ThreadItem>): List<ThreadItem> {
            task ?: return emptyList()
            val out = mutableListOf<ThreadItem>()
            if (task.origin != "notification" || rows.isEmpty()) {
                out += ThreadItem(OWNER_ID, task.createdAt, ThreadKind.OWNER, taskId = task.id, text = task.goal.trim())
            }
            out += rows
            val result = task.result
            if (rows.isEmpty() && !result.isNullOrBlank()) {
                val text = if (task.state == "failed") FeedText.failure(result) else FeedText.asSentence(FeedText.firstSentence(result))
                out += ThreadItem(RESULT_ID, task.updatedAt, ThreadKind.SENTENCE, taskId = task.id, text = text)
            }
            return out
        }

        private const val OWNER_ID = -1L
        private const val RESULT_ID = -2L

        private fun isBuild(row: ThreadItem) = row.taskId == null && row.openAppId != null &&
            (row.kind == ThreadKind.RESULT || row.kind == ThreadKind.PROGRESS)

        /** The owner's row just before a build row: the request that started the build. */
        private fun ownerBefore(rows: List<ThreadItem>, row: ThreadItem): ThreadItem? =
            rows.lastOrNull { it.kind == ThreadKind.OWNER && it.taskId == null && it.id < row.id }

        private fun titleOfBuild(rows: List<ThreadItem>, row: ThreadItem) = oneLine(ownerBefore(rows, row)?.text ?: row.text)

        private fun buildRecord(rowId: Long, rows: List<ThreadItem>): List<ThreadItem> {
            val row = rows.firstOrNull { it.id == rowId } ?: return emptyList()
            return listOfNotNull(ownerBefore(rows, row), row)
        }
    }
}
