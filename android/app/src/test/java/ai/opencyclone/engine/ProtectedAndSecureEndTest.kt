package ai.opencyclone.engine

import ai.opencyclone.accessibility.VdAccessibilityService
import ai.opencyclone.action.ActionExecutor
import ai.opencyclone.capture.ScreenCapture
import ai.opencyclone.fixtures.FakeLlmClient
import ai.opencyclone.fixtures.buildTapResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Flows F11.4 and F13.4: a protected app or a secure screen ends the task with one sentence. The engine never
 * waits for a handoff, so nothing is forced over the owner's app.
 */
class ProtectedAndSecureEndTest {
    private val executor = mockk<ActionExecutor>(relaxed = true)
    private var engine: AgentEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() {
        mockkObject(ScreenCapture)
        mockkObject(VdAccessibilityService.Companion)
        every { ScreenCapture.captureBase64() } returns "/9j/4AAQSkZJRg=="
        every { ScreenCapture.captureBase64(any(), any()) } returns "/9j/4AAQSkZJRg=="
        coEvery { executor.execute(any(), any()) } returns StepResult(1, "tap", "", true, "", 0, false)
    }

    @After fun tearDown() {
        engine?.stop()
        scope.cancel()
        unmockkObject(VdAccessibilityService.Companion)
        unmockkObject(ScreenCapture)
    }

    private fun runToEnd(e: AgentEngine, states: MutableList<AgentState>): TaskEnd = runBlocking {
        engine = e
        scope.launch(start = CoroutineStart.UNDISPATCHED) { e.state.collect { states += it } }
        e.start("pay the bill")
        withTimeout(15_000) { e.end.filterNotNull().first() }
    }

    @Test fun protectedAppEndsAsFailedWithTheAppName() {
        every { VdAccessibilityService.getTopPackageOnDisplay(any()) } returns "com.bank"
        val llm = FakeLlmClient().apply { repeat(3) { enqueue(buildTapResponse(10, 20)) } }
        val states = Collections.synchronizedList(mutableListOf<AgentState>())
        val end = runToEnd(AgentEngine(llm, executor, maxSteps = 5, blacklistedPackages = setOf("com.bank"),
            appLabel = { if (it == "com.bank") "Bank" else it }), states)
        assertEquals(TaskEnd.Failed("I do not open Bank. It is on your protected list."), end)
        assertTrue(states.none { it is AgentState.WaitingForHandoff })
        assertEquals(0, llm.requestHistory.size)
    }

    @Test fun secureScreenEndsAsFailedWithTheAppName() {
        every { VdAccessibilityService.getTopPackageOnDisplay(any()) } returns "com.wallet"
        val llm = FakeLlmClient().apply { repeat(10) { enqueue(buildTapResponse(10 + it, 20 + it)) } }
        var frame = 0
        val states = Collections.synchronizedList(mutableListOf<AgentState>())
        // The first frame is readable, then the app marks its screen secure and every frame is black.
        val end = runToEnd(AgentEngine(llm, executor, maxSteps = 10, appLabel = { if (it == "com.wallet") "Wallet" else it },
            isSecureFrame = { frame++ > 0 }), states)
        assertEquals(TaskEnd.Failed("I cannot read this screen in Wallet, so I stopped."), end)
        assertTrue(states.none { it is AgentState.WaitingForHandoff })
        assertTrue(states.last() is AgentState.Error)
    }

    @Test fun blackFramesFromTheStartAreNotASecureScreen() {
        every { VdAccessibilityService.getTopPackageOnDisplay(any()) } returns "com.wallet"
        val llm = FakeLlmClient().apply { repeat(4) { enqueue(buildTapResponse(10 + it * 50, 20 + it * 50)) } }
        val states = Collections.synchronizedList(mutableListOf<AgentState>())
        val end = runToEnd(AgentEngine(llm, executor, maxSteps = 4, isSecureFrame = { true }), states)
        assertEquals(TaskEnd.Failed(AgentEngine.STEP_LIMIT_MESSAGE), end)
    }
}
