package ai.opencyclon.gate

import ai.opencyclon.action.ActionExecutor
import ai.opencyclon.capture.ScreenCapture
import ai.opencyclon.engine.AgentEngine
import ai.opencyclon.engine.AgentState
import ai.opencyclon.engine.StepResult
import ai.opencyclon.engine.TaskEnd
import ai.opencyclon.fixtures.FakeLlmClient
import ai.opencyclon.fixtures.buildFailResponse
import ai.opencyclon.fixtures.buildFinishResponse
import ai.opencyclon.fixtures.buildTapResponse
import io.mockk.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The engine's end is a kept value: it is known without ever observing the Running state. */
class TaskEndEngineTest {
    private val reader = FakeScreenReader()
    private val executor = mockk<ActionExecutor>(relaxed = true)
    private val gate = ApprovalGate(reader, { emptySet() }, { 7 }) { x, y -> x to y }
    private var engine: AgentEngine? = null

    @Before fun setup() {
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64() } returns "/9j/4AAQSkZJRg=="
        every { ScreenCapture.captureBase64(any(), any()) } returns "/9j/4AAQSkZJRg=="
        coEvery { executor.execute(any(), any()) } returns StepResult(1, "tap", "", true, "", 0, false)
    }
    @After fun teardown() {
        engine?.stop()
        unmockkObject(ScreenCapture)
    }

    private fun run(llm: FakeLlmClient, maxSteps: Int = 3, onHold: (suspend (HeldAction) -> HoldResult)? = null): TaskEnd = runBlocking {
        val e = AgentEngine(llm, executor, maxSteps = maxSteps, approvalGate = gate, onHold = onHold).also { engine = it }
        e.start("task")
        // Nobody collects e.state, so no Running state is ever observed.
        withTimeout(10000) { e.end.filterNotNull().first() }
    }

    @Test fun `fast finish is reported as done`() {
        val end = run(FakeLlmClient().apply { enqueue(buildFinishResponse("Opened Clock")) })
        assertEquals(TaskEnd.Done("Opened Clock"), end)
        assertEquals("done" to "Opened Clock", TaskEnd.toTaskState(end))
    }

    @Test fun `fail is reported as failed`() {
        val end = run(FakeLlmClient().apply { enqueue(buildFailResponse("DoorDash is not installed")) })
        assertEquals("failed" to "DoorDash is not installed", TaskEnd.toTaskState(end))
    }

    @Test fun `rejected hold is a typed end, not a matched text`() {
        val end = run(FakeLlmClient().apply { enqueue(buildTapResponse(10, 20)) }) { HoldResult.Reject }
        assertSame(TaskEnd.Rejected, end)
        assertEquals("cancelled" to TaskEnd.REJECTED_MESSAGE, TaskEnd.toTaskState(end))
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }

    @Test fun `reaching the step limit is a failure`() {
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element("Next"))
        val end = run(FakeLlmClient().apply { enqueue(buildTapResponse(10, 20)) }, maxSteps = 1)
        assertTrue(end is TaskEnd.Failed)
        assertEquals("failed", TaskEnd.toTaskState(end).first)
    }

    @Test fun `model error is a failure`() {
        val end = run(FakeLlmClient()) // no response queued: the model call throws
        assertTrue(end is TaskEnd.Failed)
    }

    @Test fun `stop is a cancellation`() = runBlocking {
        val held = kotlinx.coroutines.CompletableDeferred<Unit>()
        val e = AgentEngine(FakeLlmClient().apply { enqueue(buildTapResponse(10, 20)) }, executor, maxSteps = 3,
            approvalGate = gate, onHold = { held.complete(Unit); kotlinx.coroutines.awaitCancellation() }).also { engine = it }
        e.start("task")
        withTimeout(10000) { held.await() }
        e.stop()
        assertEquals(TaskEnd.Cancelled(null), withTimeout(10000) { e.end.filterNotNull().first() })
        assertTrue(e.state.value is AgentState.Idle)
    }

    @Test fun `stop before start is a cancellation`() {
        val e = AgentEngine(FakeLlmClient(), executor).also { engine = it }
        e.stop()
        assertEquals(TaskEnd.Cancelled(null), e.end.value)
    }

    @Test fun `end is set once`() {
        val end = run(FakeLlmClient().apply { enqueue(buildFinishResponse("ok")) })
        engine!!.stop()
        assertEquals(end, engine!!.end.value)
    }
}
