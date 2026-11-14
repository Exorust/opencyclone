package ai.opencyclone.engine

import ai.opencyclone.action.ActionExecutor
import ai.opencyclone.capture.ScreenCapture
import ai.opencyclone.db.AppDatabase
import ai.opencyclone.feed.FeedText
import ai.opencyclone.fixtures.FakeLlmClient
import ai.opencyclone.fixtures.buildTapResponse
import ai.opencyclone.tasks.ProposalPresenter
import ai.opencyclone.tasks.TaskCoordinator
import ai.opencyclone.tasks.TaskRunner
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Flows F3.5 and F12.3: a lost connection ends the task as failed within the limit and frees the slot. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionLossTest {

    @Before fun setUp() {
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64() } returns "/9j/4AAQSkZJRg=="
        every { ScreenCapture.captureBase64(any(), any()) } returns "/9j/4AAQSkZJRg=="
    }

    @After fun tearDown() = unmockkObject(ScreenCapture)

    // ── The timer ────────────────────────────────────────────────────────

    @Test fun noConnectionForSixtySecondsEndsOnce() = runTest {
        var lost = 0
        val loss = ConnectionLoss(backgroundScope) { lost++ }
        loss.dropped()
        loss.dropped() // a second report of the same drop does not restart the clock
        advanceTimeBy(ConnectionLoss.LIMIT_MS - 1)
        runCurrent()
        assertEquals(0, lost)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, lost)
        loss.reconnectFailed() // already ended: nothing more
        assertEquals(1, lost)
    }

    @Test fun failedReconnectEndsBeforeTheLimit() = runTest {
        var lost = 0
        val loss = ConnectionLoss(backgroundScope) { lost++ }
        loss.dropped()
        advanceTimeBy(45_000)
        loss.reconnectFailed()
        assertEquals(1, lost)
        advanceTimeBy(ConnectionLoss.LIMIT_MS)
        runCurrent()
        assertEquals(1, lost)
    }

    @Test fun shortDropKeepsTheTask() = runTest {
        var lost = 0
        val loss = ConnectionLoss(backgroundScope) { lost++ }
        loss.dropped()
        advanceTimeBy(10_000)
        loss.restored()
        advanceTimeBy(ConnectionLoss.LIMIT_MS * 2)
        runCurrent()
        assertEquals(0, lost)
    }

    @Test fun failedReconnectWithoutATaskEndsNothing() = runTest {
        var lost = 0
        ConnectionLoss(backgroundScope) { lost++ }.reconnectFailed()
        assertEquals(0, lost)
    }

    // ── The engine end and the coordinator ─────────────────────────────────

    @Test fun pausedEngineEndsAsFailedWithTheSentenceAndTheNextTaskStarts() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val starts = mutableListOf<Long>()
            val coordinator = TaskCoordinator(db.taskDao(), db.windowDao(), db.allowlistDao(), object : TaskRunner {
                override fun start(taskId: Long, goal: String) { starts += taskId }
                override fun stop(taskId: Long) {}
            }, object : ProposalPresenter {
                override fun showDoThis(taskId: Long, goal: String, sourceLabel: String) {}
                override fun dismiss(taskId: Long) {}
            }, { 1_000L })
            val first = coordinator.submitUserRequest("send the photos")
            val second = coordinator.submitUserRequest("set an alarm")
            assertEquals(listOf(first), starts)

            val llm = FakeLlmClient().apply { repeat(5) { enqueue(buildTapResponse(10, 20)) } }
            val executor = mockk<ActionExecutor>(relaxed = true)
            coEvery { executor.execute(any(), any()) } returns StepResult(1, "tap", "", true, "", 0, false)
            val engine = AgentEngine(llm, executor, maxSteps = 30)
            engine.start("send the photos")
            engine.pause() // the connection dropped: the engine waits
            delay(300)
            assertTrue(engine.isRunning)

            engine.fail(FeedText.CONNECTION_LOST) // the limit passed or the reconnect attempts failed
            val end = withTimeout(5_000) { engine.end.filterNotNull().first() }
            assertEquals(TaskEnd.Failed("I lost the connection to this phone. Nothing more was sent."), end)
            assertTrue(engine.state.value is AgentState.Error)

            // The service reports the end the same way as every other end (ProactiveRuntime.watchTask).
            val (state, result) = TaskEnd.toTaskState(end)
            coordinator.onTaskFinished(first, state, result)
            assertEquals("failed", db.taskDao().get(first)!!.state)
            assertEquals(FeedText.CONNECTION_LOST, db.taskDao().get(first)!!.result)
            assertEquals("the slot is free and the queued task starts", listOf(first, second), starts)
            assertEquals(FeedText.CONNECTION_LOST, FeedText.failure(result))
        } finally {
            db.close()
        }
    }
}
