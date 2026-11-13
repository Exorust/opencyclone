package ai.opencyclon.gate

import ai.opencyclon.action.ActionExecutor
import ai.opencyclon.capture.ScreenCapture
import ai.opencyclon.engine.AgentEngine
import ai.opencyclon.engine.AgentState
import ai.opencyclon.engine.EngineEvent
import ai.opencyclon.engine.StepResult
import ai.opencyclon.engine.TaskEnd
import ai.opencyclon.fixtures.FakeLlmClient
import ai.opencyclon.fixtures.buildFinishResponse
import ai.opencyclon.fixtures.buildTapResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The engine's event flow: start, steps with captions, a hold as its own state, the executed action, the end. */
class EngineEventsTest {
    private val reader = FakeScreenReader()
    private val executor = mockk<ActionExecutor>(relaxed = true)
    private val gate = ApprovalGate(reader, { emptySet() }, { 7 }) { x, y -> x to y }
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before fun setup() {
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64() } returns "/9j/4AAQSkZJRg=="
        every { ScreenCapture.captureBase64(any(), any()) } returns "/9j/4AAQSkZJRg=="
        coEvery { executor.execute(any(), any()) } returns StepResult(1, "tap", "", true, "", 0, false)
    }

    @After fun teardown() {
        scope.cancel()
        unmockkObject(ScreenCapture)
    }

    @Test fun approvedHoldEmitsHeldResolvedExecutedAndIsNotAQuestion() = runBlocking {
        val llm = FakeLlmClient().apply {
            enqueue(buildTapResponse(10, 20) + ("caption" to "Sending the photos"), buildFinishResponse("Sent."))
        }
        val approval = CompletableDeferred<HoldResult>()
        val engine = AgentEngine(llm, executor, maxSteps = 5, approvalGate = gate, onHold = { approval.await() })
        val events = mutableListOf<EngineEvent>()
        val seen = CompletableDeferred<Unit>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { synchronized(events) { events += it }; if (it is EngineEvent.Finished) seen.complete(Unit) }
        }
        engine.start("send the photos")
        val waiting = withTimeout(10_000) { engine.state.first { it is AgentState.WaitingForApproval } }
        assertEquals(AgentState.WaitingForApproval(1), waiting)
        approval.complete(HoldResult.Approve)
        withTimeout(10_000) { seen.await() }
        val list = synchronized(events) { events.toList() }
        assertEquals(EngineEvent.Started("send the photos"), list[0])
        assertEquals(EngineEvent.Step(1, null, "Sending the photos"), list[1])
        assertTrue(list[2] is EngineEvent.Held)
        assertEquals(HoldResult.Approve, (list[3] as EngineEvent.Resolved).result)
        val executed = list[4] as EngineEvent.Executed
        assertEquals("Send", executed.held!!.label)
        assertTrue(executed.success)
        assertEquals(EngineEvent.Step(2, null, null), list[5])
        assertEquals(EngineEvent.Finished(TaskEnd.Done("Sent.")), list.last())
        assertTrue(list.none { it is EngineEvent.Asking })
    }
}
