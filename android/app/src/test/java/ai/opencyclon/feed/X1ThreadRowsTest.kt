package ai.opencyclon.feed

import ai.opencyclon.AgentConnection
import ai.opencyclon.action.Action
import ai.opencyclon.db.AppDatabase
import ai.opencyclon.engine.EngineEvent
import ai.opencyclon.fixtures.eventually
import ai.opencyclon.gate.HeldAction
import ai.opencyclon.gate.HoldReason
import ai.opencyclon.gate.HoldResult
import ai.opencyclon.gate.ScreenFacts
import ai.opencyclon.tasks.TaskEntity
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

/** Lane X1: the "Opened" receipt, the approval row's result, the unreadable-target sentence, "Not connected". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class X1ThreadRowsTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recorder = ThreadRecorder(db.threadItemDao())
    private val occupied = MutableStateFlow<TaskEntity?>(
        TaskEntity(id = 7, origin = "user", goal = "Open the clock", state = "running", createdAt = 1, updatedAt = 1))
    private val live = LiveStateSource(scope, occupied, MutableStateFlow(0), MutableStateFlow(null), MutableStateFlow(emptyList()))
    private val labels = mapOf("com.google.android.deskclock" to "Clock", "com.whatsapp" to "WhatsApp")
    private val sink = TaskEventSink(recorder, live) { labels[it] }

    @After fun close() {
        AgentConnection.setNotConnected(false)
        scope.cancel()
        db.close()
    }

    private suspend fun rows() = recorder.today().first()
    private fun held(title: String? = "Alex") =
        HeldAction(Action.Tap(10, 20), HoldReason.COMMIT_WORD, "Send", null, 100, 200, ScreenFacts("com.whatsapp", title, null))

    private suspend fun openClockAndFinish(taskId: Long = 7) {
        sink.onEvent(taskId, EngineEvent.Started("Open the clock"))
        sink.onEvent(taskId, EngineEvent.Step(1, "com.android.launcher", null))
        sink.onEvent(taskId, EngineEvent.Executed(null, Action.OpenApp("Clock"), success = true))
        sink.onEvent(taskId, EngineEvent.Step(2, "com.google.android.deskclock", null))
    }

    @Test fun anOpenedAppWithoutApprovalGetsOneReceipt() = runBlocking {
        openClockAndFinish()
        sink.onTaskEnded(7, "running", "done", "Opened the clock.", expired = false)
        val receipt = rows().single { it.kind == ThreadKind.RECEIPT }
        assertEquals("Opened", receipt.actionWord)
        assertEquals("Clock", receipt.text)
        assertEquals("com.google.android.deskclock", receipt.appPackage)
        assertEquals(listOf(ThreadKind.RECEIPT, ThreadKind.RESULT), rows().map { it.kind })
    }

    @Test fun noReceiptWhenTheTaskEndedInAnotherAppOrFailed() = runBlocking {
        openClockAndFinish()
        sink.onEvent(7, EngineEvent.Step(3, "com.whatsapp", null)) // went on to an app it did not open
        sink.onTaskEnded(7, "running", "done", "Done.", expired = false)
        assertEquals(listOf(ThreadKind.RESULT), rows().map { it.kind })

        openClockAndFinish(8)
        sink.onTaskEnded(8, "running", "failed", "x", expired = false)
        assertEquals(0, rows().count { it.kind == ThreadKind.RECEIPT })
    }

    @Test fun noOpenedReceiptForAHeldTaskAndTheApprovalRowGetsTheResult() = runBlocking {
        openClockAndFinish()
        sink.onEvent(7, EngineEvent.Held(held()))
        sink.onHold(7, held(), null)
        sink.onHoldResolved(7, HoldResult.Approve, expired = false)
        sink.onEvent(7, EngineEvent.Executed(held(), Action.Tap(10, 20), success = true))
        sink.onTaskEnded(7, "running", "done", "Sent the message to Alex. It was delivered.", expired = false)
        val approval = rows().single { it.kind == ThreadKind.APPROVAL }
        assertEquals(ThreadState.APPROVED, approval.state)
        assertEquals("Sent the message to Alex.", approval.detail)
        // The receipt is the approved action's, not "Opened".
        assertEquals(listOf("Sent"), rows().filter { it.kind == ThreadKind.RECEIPT }.map { it.actionWord })
    }

    @Test fun rejectedApprovalGetsNoResultDetail() = runBlocking {
        sink.onHold(7, held(), null)
        sink.onHoldResolved(7, HoldResult.Reject, expired = false)
        sink.onTaskEnded(7, "awaiting_approval", "cancelled", null, expired = false)
        assertNull(rows().single { it.kind == ThreadKind.APPROVAL }.detail)
    }

    @Test fun unreadableTargetWritesOneSentenceForEachHold() = runBlocking {
        sink.onHold(7, held(title = null), null)
        sink.onHold(7, held(title = "Alex"), null) // a readable hold: no sentence
        val sentences = rows().filter { it.kind == ThreadKind.SENTENCE }
        assertEquals(listOf(FeedText.UNREADABLE_TARGET), sentences.map { it.text })
    }

    @Test fun notConnectedReachesTheStatusSentence() = runBlocking {
        occupied.value = null
        eventually(2000, 20) { assertEquals(StatusSentence.IDLE, live.state.value.status) }
        AgentConnection.setNotConnected(true)
        eventually(2000, 20) { assertEquals("Not connected. Tap to reconnect.", live.state.value.status) }
        AgentConnection.setNotConnected(false)
        eventually(2000, 20) { assertEquals(StatusSentence.IDLE, live.state.value.status) }
    }
}
