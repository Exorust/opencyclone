package ai.opencyclon.feed

// Contract between the interface and the engine, pm/briefs/CONTRACT.md version 1. Types only, no logic.

enum class ThreadKind { OWNER, NOTIFICATION, SENTENCE, QUESTION, APPROVAL, RESULT, RECEIPT, PROGRESS, SHARED_APP, CONFIRM }
enum class ThreadState { OPEN, APPROVED, REJECTED, EXPIRED, ANSWERED, DONE, FAILED }

/** One row of today's thread. */
data class ThreadItem(
    val id: Long,
    val at: Long,                 // epoch milliseconds
    val kind: ThreadKind,
    val state: ThreadState = ThreadState.DONE,
    val taskId: Long? = null,
    val appPackage: String? = null, // the app this item is about (icon source)
    val title: String? = null,      // sender of a notification, or the app name
    val text: String = "",          // the sentence, the owner's words, the notification text, the receipt detail
    val detail: String? = null,     // second line (result card caption, progress "~1 min")
    val actionWord: String? = null, // APPROVAL: "Send". RECEIPT: "Sent".
    val evidence: Evidence? = null,
    val canApprove: Boolean = true, // APPROVAL only: false hides the primary button
    val openAppId: Long? = null,    // built app to open on tap (RESULT, PROGRESS, SHARED_APP)
)

enum class Phase { RUNNING, PAUSED, WAITING_APPROVAL, WAITING_ANSWER }

data class RunningTask(
    val taskId: Long,
    val goal: String,
    val caption: String,            // 3 to 6 words for the owner: "Picking the photos"
    val packages: List<String>,     // apps this task has operated, the current one last
    val startedAt: Long,
    val phase: Phase,
)

data class WindowView(val endsAt: Long, val handled: Int, val hidden: Int, val needsYou: Int)

data class ApprovalView(
    val taskId: Long,
    val heading: String,            // "9 photos to Alex. Send them?" It names the recipient.
    val actionWord: String,         // "Send"
    val appPackage: String?,
    val evidence: Evidence,
    val canApprove: Boolean,        // false when the recipient or target title is unreadable
)

data class QuestionView(val taskId: Long?, val text: String)

data class AppTile(val id: Long, val name: String, val accent: String /* "#RRGGBB" */, val updating: Boolean = false)

data class LiveState(
    val running: List<RunningTask> = emptyList(), // version 1: at most one
    val queued: Int = 0,
    val window: WindowView? = null,
    val approval: ApprovalView? = null,
    val question: QuestionView? = null,
    val status: String = "All caught up.",       // at most 60 characters, ends with a full stop
    val apps: List<AppTile> = emptyList(),
    val queuedGoals: List<String> = emptyList(), // goals of the waiting tasks, oldest first, each at most 80 characters
    val statusEmphasis: IntRange? = null,        // characters of `status` drawn in ink when the rest is ink_2 (idle)
)

interface Feed {
    val live: kotlinx.coroutines.flow.StateFlow<LiveState>
    /** Today's items, oldest first. Emits again on every change. */
    fun today(): kotlinx.coroutines.flow.Flow<List<ThreadItem>>
    /** Pictures of the hidden display of a task, at most 2 each second, only while collected. */
    fun previews(taskId: Long, widthPx: Int): kotlinx.coroutines.flow.Flow<android.graphics.Bitmap>
    /** Version 2 (lane H1): every task, newest first, with [query] matched in any letter case; "" lists all. */
    fun history(query: String = ""): kotlinx.coroutines.flow.Flow<List<HistoryEntry>> = kotlinx.coroutines.flow.emptyFlow()
    /** Version 2 (lane H1): the stored rows of one task, oldest first; emits again while the task runs. */
    fun record(taskId: Long): kotlinx.coroutines.flow.Flow<List<ThreadItem>> = kotlinx.coroutines.flow.emptyFlow()
}

interface HomeCommands {
    /** The owner's typed or spoken words. Routes to: answer a waiting question, edit a waiting approval, or a new request. */
    fun submit(text: String)
    /** Returns false when the phone is locked or the approval is gone. */
    suspend fun approve(taskId: Long): Boolean
    fun reject(taskId: Long)        // "Not now"
    fun stop(taskId: Long)
    fun openLive(context: android.content.Context, taskId: Long)
    /** Version 2 (lane H1): deletes a task, its rows, its evidence pictures and its chat log; not a running task. */
    fun deleteTask(taskId: Long) {}
    /** Version 2 (lane H1): the task's words again, as a new request. */
    fun askAgain(taskId: Long) {}
    /** A CONFIRM row's answer ("Build it" or "Not now"), or a tap on a widget draft card (yes). Lane G1. */
    fun confirm(itemId: Long, yes: Boolean) {}
    /** Lane X2: one sentence of the phone in the thread ("I did not hear anything."), from a screen that is not the home page. */
    fun note(text: String) {}
    /** Lane X2: the microphone is off: a sentence and an "Allow" row that opens the app's permission page. */
    fun microphoneOff() {}
}

/** Set once at process start. L0 sets FakeFeed in tests; N4 sets the real one in App.onCreate. */
object FeedLocator {
    @Volatile lateinit var feed: Feed
    @Volatile lateinit var commands: HomeCommands
    val isReady: Boolean get() = ::feed.isInitialized && ::commands.isInitialized
}

// ── Version 2: task history (lane H1) ───────────────────────────────────

/** One row of the history page. A negative [taskId] is a built-app request without a task (its result row's id, negated). */
data class HistoryEntry(
    val taskId: Long,
    val at: Long,
    val title: String,              // the owner's words or the proposal's goal, one line
    val status: HistoryStatus,
    val packages: List<String>,     // apps the task used, at most 3
    val match: IntRange?,           // the part of title that matches the query
)

enum class HistoryStatus { DONE, NOT_SENT, FAILED, STOPPED, RUNNING, WAITING }

/** What a CONFIRM row's answer does; stored in the row's `title`. A row without one is a widget's sources question. */
object ConfirmRef {
    /** "Do this?" for a proposed task; the row's `taskId` is the proposal. */
    const val PROPOSAL = "proposal"
    /** "Set up": opens the "Set up" page (no model is set). */
    const val SET_UP = "set-up"
    /** "Allow": opens the app's permission page (the microphone is off). */
    const val MICROPHONE = "microphone"
}
