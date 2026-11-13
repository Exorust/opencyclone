package ai.opencyclon.feed

import ai.opencyclon.miniapp.BuiltAppStyle
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * A [Feed] and [HomeCommands] backed by mutable flows, for tests and for showing screens before the
 * real feed exists. Set [state], [items], or emit into [previewFlow]; read what the screens asked for
 * from [submitted], [approved], [rejected], [stopped], [openedLive].
 */
class FakeFeed : Feed, HomeCommands {
    val state = MutableStateFlow(LiveState())
    val items = MutableStateFlow<List<ThreadItem>>(emptyList())
    val previewFlow = MutableSharedFlow<Bitmap>(replay = 1, extraBufferCapacity = 4)

    val submitted = mutableListOf<String>()
    val approved = mutableListOf<Long>()
    val rejected = mutableListOf<Long>()
    val stopped = mutableListOf<Long>()
    val openedLive = mutableListOf<Long>()

    /** What [approve] returns. */
    var approveResult = true

    override val live: StateFlow<LiveState> get() = state.asStateFlow()
    override fun today(): Flow<List<ThreadItem>> = items
    override fun previews(taskId: Long, widthPx: Int): Flow<Bitmap> = previewFlow

    override fun submit(text: String) { submitted += text }
    override suspend fun approve(taskId: Long): Boolean { approved += taskId; return approveResult }
    override fun reject(taskId: Long) { rejected += taskId }
    override fun stop(taskId: Long) { stopped += taskId }
    override fun openLive(context: Context, taskId: Long) { openedLive += taskId }

    /** Fills this feed with the content of the reference frames (UI.md section 5), with invented names. */
    fun fillSample(now: Long = System.currentTimeMillis()): FakeFeed {
        val min = 60_000L
        val photos = Evidence(count = 9)
        items.value = listOf(
            ThreadItem(1, now - 50 * min, ThreadKind.NOTIFICATION, appPackage = WHATSAPP, title = "Sam",
                text = "Can you send me the photos from yesterday?"),
            ThreadItem(2, now - 49 * min, ThreadKind.OWNER,
                text = "Sam wants yesterday's photos. Can you send them?"),
            ThreadItem(3, now - 48 * min, ThreadKind.SENTENCE, taskId = 11,
                text = "Found 12 in your chats and gallery."),
            ThreadItem(4, now - 47 * min, ThreadKind.APPROVAL, ThreadState.APPROVED, taskId = 11, appPackage = WHATSAPP,
                text = "9 photos to Sam. Send them?", actionWord = "Send", evidence = photos),
            ThreadItem(5, now - 46 * min, ThreadKind.SENTENCE, taskId = 11,
                text = "Sent Sam 9 photos on WhatsApp."),
            ThreadItem(6, now - 46 * min, ThreadKind.RECEIPT, taskId = 11, appPackage = WHATSAPP,
                actionWord = "Sent", text = "9 photos to Sam"),
            ThreadItem(7, now - 30 * min, ThreadKind.NOTIFICATION, appPackage = X, title = "New follower",
                text = "Jordan Lee followed you."),
            ThreadItem(8, now - 29 * min, ThreadKind.NOTIFICATION, appPackage = WHATSAPP, title = "Dinner club",
                text = "Nina: Are you coming tonight? Shall we book for 7:30?"),
            ThreadItem(9, now - 28 * min, ThreadKind.NOTIFICATION, appPackage = AMAZON, title = "Amazon",
                text = "You have 2 days left to return your desk lamp."),
            ThreadItem(10, now - 27 * min, ThreadKind.OWNER,
                text = "Follow Jordan back on X.\nTell the dinner club I'm in for 7:30.\nThen start the lamp return for me."),
            ThreadItem(11, now - 25 * min, ThreadKind.SENTENCE,
                text = "Done. I handled all three in the background."),
            ThreadItem(12, now - 25 * min, ThreadKind.RECEIPT, taskId = 12, appPackage = X,
                actionWord = "Followed Jordan back", text = "@jordanlee"),
            ThreadItem(13, now - 25 * min, ThreadKind.RECEIPT, taskId = 13, appPackage = WHATSAPP,
                actionWord = "Replied in Dinner club", text = "I'm in for 7:30"),
            ThreadItem(14, now - 25 * min, ThreadKind.RECEIPT, taskId = 14, appPackage = AMAZON,
                actionWord = "Started the return", text = "pickup on Monday"),
            ThreadItem(15, now - 10 * min, ThreadKind.OWNER,
                text = "Add Portuguese to my language app."),
            ThreadItem(16, now - 9 * min, ThreadKind.PROGRESS, ThreadState.OPEN, taskId = 15,
                text = "Updating Lingo", detail = "~1 min", openAppId = 1),
            ThreadItem(17, now - 5 * min, ThreadKind.RESULT, taskId = 15,
                text = "Lingo is updated", detail = "Portuguese and weekly streaks · tap to open", openAppId = 1),
            ThreadItem(18, now - 2 * min, ThreadKind.SHARED_APP, ThreadState.OPEN, title = "Priya",
                text = "Priya made a trip planner. It keeps your bookings in one place.", openAppId = 3),
        )
        state.value = LiveState(
            running = listOf(
                RunningTask(21, "Send Sam the photos from yesterday", "Picking the photos",
                    listOf(PHOTOS, WHATSAPP), now - 34_000L, Phase.RUNNING),
            ),
            queued = 1,
            window = WindowView(endsAt = now + 60 * min, handled = 3, hidden = 2, needsYou = 1),
            approval = ApprovalView(21, "9 photos to Sam. Send them?", "Send", WHATSAPP, photos, canApprove = true),
            question = null,
            status = "Lisbon in 3 weeks. Nothing else needs you.",
            apps = listOf(
                AppTile(1, "Lingo", BuiltAppStyle.DEFAULT_ACCENT),
                AppTile(2, "Budget", "#2F6FDB", updating = false),
            ),
            queuedGoals = listOf("Reply to Maya about Saturday"),
            statusEmphasis = 0 until 6, // "Lisbon"
        )
        return this
    }

    companion object {
        const val WHATSAPP = "com.whatsapp"
        const val PHOTOS = "com.google.android.apps.photos"
        const val X = "com.twitter.android"
        const val AMAZON = "com.amazon.mShop.android.shopping"

        /** A new [FakeFeed] filled with the reference-frame content. */
        fun sample(): FakeFeed = FakeFeed().fillSample()
    }

    // ── Task history (lane H1) ──────────────────────────────────────────

    /** Every entry; [history] filters it by title like the real feed (results and receipts: see [searchText]). */
    val historyEntries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    /** Extra text that a search also finds, by task id (result, receipts). */
    val searchText = mutableMapOf<Long, String>()
    /** Rows of a record, by task id. */
    val records = MutableStateFlow<Map<Long, List<ThreadItem>>>(emptyMap())
    val deleted = mutableListOf<Long>()
    val askedAgain = mutableListOf<Long>()

    override fun history(query: String): Flow<List<HistoryEntry>> = historyEntries.map { list ->
        val q = query.trim()
        if (q.isEmpty()) list.map { it.copy(match = null) } else list.mapNotNull { e ->
            val i = e.title.indexOf(q, ignoreCase = true)
            when {
                i >= 0 -> e.copy(match = i until i + q.length)
                searchText[e.taskId]?.contains(q, ignoreCase = true) == true -> e.copy(match = null)
                else -> null
            }
        }
    }
    override fun record(taskId: Long): Flow<List<ThreadItem>> = records.map { it[taskId].orEmpty() }
    override fun deleteTask(taskId: Long) {
        deleted += taskId
        historyEntries.value = historyEntries.value.filter { it.taskId != taskId }
    }
    override fun askAgain(taskId: Long) {
        askedAgain += taskId
        historyEntries.value.firstOrNull { it.taskId == taskId }?.let { submit(it.title) }
    }
    /** CONFIRM answers and widget draft-card taps, as (item id, yes). */
    val confirmed = mutableListOf<Pair<Long, Boolean>>()
    override fun confirm(itemId: Long, yes: Boolean) { confirmed += itemId to yes }
    /** Sentences from other screens (lane X2). */
    val notes = mutableListOf<String>()
    override fun note(text: String) { notes += text }
    var microphoneOffCalls = 0
    override fun microphoneOff() { microphoneOffCalls++ }
}
