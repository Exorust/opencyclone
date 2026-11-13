package ai.opencyclon.engine

import ai.opencyclon.action.ActionExecutor
import ai.opencyclon.capture.ScreenCapture
import ai.opencyclon.fixtures.FakeLlmClient
import ai.opencyclon.fixtures.buildAskUserResponse
import ai.opencyclon.fixtures.buildTapResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** stop() must end in Idle at every point of the loop, and the loop must not overwrite Idle afterwards. */
class StopRaceTest {
    private lateinit var actionExecutor: ActionExecutor

    @Before fun setUp() {
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64() } returns "/9j/4AAQSkZJRg=="
        every { ScreenCapture.captureBase64(any(), any()) } returns "/9j/4AAQSkZJRg=="
        actionExecutor = mockk(relaxed = true)
        coEvery { actionExecutor.execute(any(), any()) } returns StepResult(1, "tap", "tap", true, "tapped", 1, false)
    }

    @After fun tearDown() = unmockkObject(ScreenCapture)

    @Test fun stopAtManyPointsAlwaysEndsIdle() {
        repeat(200) { i ->
            val llm = FakeLlmClient().apply { repeat(20) { enqueue(buildTapResponse(100, 200)) } }
            val engine = AgentEngine(llm, actionExecutor)
            engine.start("long task")
            // Spread the stop over the first steps: 0 to 3 ms after start, sometimes right away.
            val waitNanos = (i % 16) * 200_000L
            if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
            engine.stop()
            assertEquals("iteration $i right after stop", AgentState.Idle(), engine.state.value)
            Thread.sleep(if (i % 20 == 0) 30 else 2)
            assertEquals("iteration $i later", AgentState.Idle(), engine.state.value)
            val until = System.currentTimeMillis() + 1000
            while (engine.end.value == null && System.currentTimeMillis() < until) Thread.sleep(1)
            assertTrue("iteration $i end ${engine.end.value}", engine.end.value is TaskEnd.Cancelled)
            engine.destroy()
        }
    }

    @Test fun stopWhileAskingEndsIdleAndFinishedIsEmittedOnce() = runBlocking {
        val llm = FakeLlmClient().apply { enqueue(buildAskUserResponse("Which Alex?")) }
        val engine = AgentEngine(llm, actionExecutor)
        val scope = CoroutineScope(Dispatchers.Default)
        val events = mutableListOf<EngineEvent>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { synchronized(events) { events += it } } }
        engine.start("ask")
        val deadline = System.currentTimeMillis() + 3000
        while (engine.state.value !is AgentState.WaitingForUser && System.currentTimeMillis() < deadline) Thread.sleep(5)
        engine.stop()
        Thread.sleep(100)
        scope.cancel()
        assertEquals(AgentState.Idle(), engine.state.value)
        val kinds = synchronized(events) { events.map { it::class.simpleName } }
        assertEquals("Started", kinds.first())
        assertTrue(kinds.toString(), "Asking" in kinds)
        assertEquals(1, kinds.count { it == "Finished" })
        assertEquals(EngineEvent.Finished(TaskEnd.Cancelled(null)), synchronized(events) { events.last { it is EngineEvent.Finished } })
    }
}
