package ai.opencyclone.gate

import ai.opencyclone.action.ActionExecutor
import ai.opencyclone.capture.ScreenCapture
import ai.opencyclone.engine.AgentEngine
import ai.opencyclone.engine.AgentState
import ai.opencyclone.engine.StepResult
import ai.opencyclone.fixtures.FakeLlmClient
import ai.opencyclone.fixtures.buildTapResponse
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ApprovalGateEngineTest {
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

    private fun start(onHold: (suspend (HeldAction) -> HoldResult)?): AgentEngine =
        start(FakeLlmClient().apply { enqueue(buildTapResponse(10, 20)) }, 1, onHold)

    private fun start(llm: FakeLlmClient, maxSteps: Int, onHold: (suspend (HeldAction) -> HoldResult)?): AgentEngine {
        return AgentEngine(llm, executor, maxSteps = maxSteps, approvalGate = gate, onHold = onHold).also {
            engine = it
            it.start("tap send")
        }
    }

    @Test fun `hold suspends then approval executes exactly once`() = runBlocking {
        val shown = CompletableDeferred<HeldAction>()
        val approval = CompletableDeferred<HoldResult>()
        val engine = start { shown.complete(it); approval.await() }
        withTimeout(10000) { shown.await() }
        coVerify(exactly = 0) { executor.execute(any(), any()) }
        approval.complete(HoldResult.Approve)
        withTimeout(10000) { engine.state.first { it is AgentState.Idle && it.resultMessage != null } }
        coVerify(exactly = 1) { executor.execute(any(), 1) }
    }

    @Test fun `hold then reject never executes`() = runBlocking {
        val engine = start { HoldResult.Reject }
        val state = withTimeout(10000) {
            engine.state.first { it is AgentState.Idle && it.resultMessage != null }
        } as AgentState.Idle
        assertEquals("Cancelled by the user", state.resultMessage)
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }

    @Test fun `three mismatches fail with fresh holds and zero executions`() = runBlocking {
        val titles = mutableListOf<String?>()
        val engine = start { held ->
            titles.add(held.facts.title)
            reader.facts = ScreenReader.Result.Ok(held.facts.copy(title = "Recipient ${titles.size}"))
            HoldResult.Approve
        }
        val state = withTimeout(10000) { engine.state.first { it is AgentState.Error } } as AgentState.Error
        assertEquals("The screen kept changing", state.message)
        assertEquals(listOf("Alex", "Recipient 1", "Recipient 2"), titles)
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }

    @Test fun `changed harmless label still needs fresh approval`() = runBlocking {
        val labels = mutableListOf<String?>()
        val engine = start { held ->
            labels.add(held.label)
            if (labels.size == 1) reader.target = ScreenReader.Result.Ok(FakeScreenReader.element("Next"))
            HoldResult.Approve
        }
        withTimeout(10000) { engine.state.first { it is AgentState.Idle && it.resultMessage != null } }
        assertEquals(listOf("Send", "Next"), labels)
        coVerify(exactly = 1) { executor.execute(any(), any()) }
    }

    @Test fun `gate failure ends task without execution`() = runBlocking {
        reader.facts = ScreenReader.Result.Unreadable
        val engine = start { error("Must not ask for approval") }
        val state = withTimeout(10000) { engine.state.first { it is AgentState.Error } } as AgentState.Error
        assertEquals("Cannot read the screen", state.message)
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }

    @Test fun `missing callback cannot execute held action`() = runBlocking {
        val engine = start(null)
        withTimeout(10000) { engine.state.first { it is AgentState.Idle && it.resultMessage != null } }
        coVerify(exactly = 0) { executor.execute(any(), any()) }
    }

    @Test fun `edit skips the held action and continues with the correction`() = runBlocking {
        val llm = FakeLlmClient().apply { enqueue(buildTapResponse(10, 20), buildTapResponse(10, 20)) }
        var holds = 0
        val engine = start(llm, 2) {
            holds++
            if (holds == 1) HoldResult.Edit("only the first two") else HoldResult.Approve
        }
        withTimeout(10000) { engine.state.first { it is AgentState.Idle && it.resultMessage != null } }
        assertEquals(2, holds)
        coVerify(exactly = 1) { executor.execute(any(), 2) }
        coVerify(exactly = 0) { executor.execute(any(), 1) }
        assertTrue(llm.requestHistory[1].toString().contains("only the first two"))
    }
}
