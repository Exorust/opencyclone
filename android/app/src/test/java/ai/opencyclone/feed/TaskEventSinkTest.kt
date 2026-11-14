package ai.opencyclone.feed

import ai.opencyclone.action.Action
import ai.opencyclone.db.AppDatabase
import ai.opencyclone.engine.EngineEvent
import ai.opencyclone.engine.TaskEnd
import ai.opencyclone.fixtures.eventually
import ai.opencyclone.gate.HeldAction
import ai.opencyclone.gate.HoldReason
import ai.opencyclone.gate.HoldResult
import ai.opencyclone.gate.ScreenFacts
import ai.opencyclone.tasks.TaskCoordinator
import ai.opencyclone.tasks.TaskEntity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskEventSinkTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recorder = ThreadRecorder(db.threadItemDao())
    private val occupied = MutableStateFlow<TaskEntity?>(
        TaskEntity(id = 7, origin = "user", goal = "Send Alex photos", state = "running", createdAt = 1, updatedAt = 1))
    private val live = LiveStateSource(scope, occupied, MutableStateFlow(0), MutableStateFlow(null), MutableStateFlow(emptyList()))
    private val sink = TaskEventSink(recorder, live) { pkg -> if (pkg == "com.whatsapp") "WhatsApp" else null }

    @After fun close() {
        scope.cancel()
        db.close()
    }

    private suspend fun rows() = recorder.today().first()
    private fun held(title: String? = "Alex", count: Int? = 9, label: String? = "SEND") =
        HeldAction(Action.Tap(10, 20), HoldReason.COMMIT_WORD, label, intArrayOf(0, 0, 5, 5), 100, 200,
            ScreenFacts("com.whatsapp", title, count, attachmentNoun = count?.let { "photos" }))

    @Test fun composeTextBecomesEvidenceHeadingStatusAndReceipt() = runBlocking {
        val withText = HeldAction(Action.Tap(10, 20), HoldReason.COMMIT_WORD, "Send", null, 100, 200,
            ScreenFacts("com.whatsapp", "Alex", null, composeText = "See you at 6"))
        sink.onHold(7, withText, null)
        val approval = rows().single()
        assertEquals("Send this to Alex?", approval.text)
        assertEquals("See you at 6", approval.evidence!!.composeText)
        assertEquals(emptyList<String>(), approval.evidence!!.thumbs)
        eventually(2000, 20) { assertEquals("A message to Alex is ready.", live.state.value.status) }
        sink.onHoldResolved(7, HoldResult.Approve, expired = false)
        sink.onEvent(7, EngineEvent.Executed(withText, Action.Tap(10, 20), success = true))
        assertEquals("a message to Alex", rows().last().text)
    }

    @Test fun approvalRowAndLiveStateThenApprovedAndReceipt() = runBlocking {
        sink.onHold(7, held(), "/files/evidence/7/frame.jpg")
        val approval = rows().single()
        assertEquals(ThreadKind.APPROVAL, approval.kind)
        assertEquals(ThreadState.OPEN, approval.state)
        assertEquals("9 photos to Alex. Send them?", approval.text)
        assertEquals("Send to Alex", approval.actionWord)
        assertEquals("/files/evidence/7/frame.jpg", approval.evidence!!.framePath)
        assertEquals(9, approval.evidence!!.count)
        eventually(2000, 20) {
            val view = live.state.value.approval!!
            assertEquals("9 photos to Alex. Send them?", view.heading)
            assertEquals(true, view.canApprove)
            assertEquals("9 photos are ready to send.", live.state.value.status)
        }
        sink.onHoldResolved(7, HoldResult.Approve, expired = false)
        sink.onEvent(7, EngineEvent.Executed(held(), Action.Tap(10, 20), success = true))
        val items = rows()
        assertEquals(ThreadState.APPROVED, items[0].state)
        with(items[1]) {
            assertEquals(ThreadKind.RECEIPT, kind); assertEquals("Sent", actionWord); assertEquals("9 photos to Alex", text)
            assertEquals("com.whatsapp", appPackage)
        }
        eventually(2000, 20) { assertNull(live.state.value.approval) }
        // Something was sent, so a later stop does not claim that nothing was.
        sink.onTaskEnded(7, "running", "cancelled", null, expired = false)
        assertEquals(FeedText.STOPPED_AFTER_SENDING, rows().last().text)
    }

    @Test fun unreadableTitleHidesTheButton() = runBlocking {
        sink.onHold(7, held(title = null, count = null), null)
        val (approval, sentence) = rows()
        with(approval) {
            assertEquals("Send in WhatsApp?", text)
            assertEquals(false, canApprove)
            assertEquals("Send", actionWord)
        }
        // F5.14: one sentence under it (lane X1).
        assertEquals(ThreadKind.SENTENCE, sentence.kind)
        assertEquals("I can't read this screen, so I won't press it.", sentence.text)
        assertEquals(2, rows().size)
    }

    @Test fun rejectedAndExpiredHolds() = runBlocking {
        sink.onHold(7, held(), null)
        sink.onHoldResolved(7, HoldResult.Reject, expired = false)
        sink.onTaskEnded(7, "awaiting_approval", "cancelled", TaskEnd.REJECTED_MESSAGE, expired = false)
        assertEquals(listOf(ThreadState.REJECTED, ThreadState.DONE), rows().map { it.state })
        assertEquals(FeedText.STOPPED, rows().last().text)

        sink.onHold(7, held(), null)
        // The coordinator expires the task first, then the session ends the hold.
        sink.onTaskEnded(7, "awaiting_approval", "cancelled", null, expired = true)
        sink.onHoldResolved(7, HoldResult.Reject, expired = true)
        val last = rows().takeLast(2)
        assertEquals(ThreadState.EXPIRED, last[0].state)
        assertEquals(FeedText.EXPIRED, last[1].text)
    }

    @Test fun aSecondHoldReplacesAnOpenOne() = runBlocking {
        sink.onHold(7, held(), null)
        sink.onHold(7, held(title = "Sam"), null)
        assertEquals(listOf(ThreadState.EXPIRED, ThreadState.OPEN), rows().map { it.state })
    }

    @Test fun questionOpensAndIsAnswered() = runBlocking {
        sink.onEvent(7, EngineEvent.Asking("Which Alex?"))
        assertEquals(ThreadState.OPEN, rows().single().state)
        eventually(2000, 20) { assertEquals(QuestionView(7, "Which Alex?"), live.state.value.question) }
        sink.onEvent(7, EngineEvent.Answered("The one from work"))
        assertEquals(ThreadState.ANSWERED, rows().single().state)
        eventually(2000, 20) { assertNull(live.state.value.question) }
        sink.onEvent(7, EngineEvent.Asking("Again?"))
        sink.onTaskEnded(7, "running", "failed", "Something broke", expired = false)
        assertEquals(ThreadState.FAILED, rows()[1].state)
        assertEquals("Something broke.", rows().last().text)
    }

    @Test fun doneTaskGetsTheShortResult() = runBlocking {
        sink.onTaskEnded(7, "running", "done", "Sent Alex 9 photos on WhatsApp. I then went back to the chat list.", false)
        with(rows().single()) {
            assertEquals(ThreadKind.RESULT, kind)
            assertEquals("Sent Alex 9 photos on WhatsApp.", text)
            assertEquals(7L, taskId)
        }
    }

    @Test fun restartSentenceForATaskTheOldProcessLeftOpen() = runBlocking {
        recorder.approval(7, "Send?", "Send", null, Evidence(), true)
        sink.onTaskEnded(7, "awaiting_approval", "failed", TaskCoordinator.STOPPED_RESULT, expired = false)
        assertEquals(ThreadState.EXPIRED, rows()[0].state)
        assertEquals(TaskCoordinator.STOPPED_RESULT, rows()[1].text)
    }

    @Test fun droppedProposalIsSilentAndTriageSentenceWaitsForTheStart() = runBlocking {
        sink.sayWhenStarted(8, "Alex asked for yesterday's photos. I'm finding them.")
        sink.onTaskEnded(9, "proposed", "cancelled", null, expired = false)
        assertEquals(emptyList<ThreadItem>(), rows())
        sink.onEvent(8, EngineEvent.Started("Send Alex photos"))
        assertEquals("Alex asked for yesterday's photos. I'm finding them.", rows().single().text)
    }

    @Test fun toolCallReceipt() = runBlocking {
        sink.onEvent(7, EngineEvent.Executed(null, Action.CallTool("calendar", "list_events", "{}"), success = true))
        sink.onEvent(7, EngineEvent.Executed(null, Action.CallTool("calendar", "broken", "{}"), success = false))
        with(rows().single()) { assertEquals("Used calendar", actionWord); assertEquals("list_events", text) }
    }
}
