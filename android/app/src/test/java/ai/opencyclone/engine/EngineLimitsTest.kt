package ai.opencyclone.engine

import ai.opencyclone.action.ActionExecutor
import ai.opencyclone.capture.ScreenCapture
import ai.opencyclone.fixtures.FakeLlmClient
import ai.opencyclone.fixtures.buildAskUserResponse
import ai.opencyclone.fixtures.buildFinishResponse
import ai.opencyclone.fixtures.buildTapResponse
import ai.opencyclone.llm.LLMClientInterface
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Caption cleaning on the Step event, the time limit, the step limit default, and the history window in a run. */
class EngineLimitsTest {
    private val clock = AtomicLong(0)
    private val minute = 60_000L

    @Before fun setUp() {
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64() } returns "/9j/4AAQSkZJRg=="
        every { ScreenCapture.captureBase64(any(), any()) } returns "/9j/4AAQSkZJRg=="
    }

    @After fun tearDown() = unmockkObject(ScreenCapture)

    private fun executor() = mockk<ActionExecutor>(relaxed = true).also {
        coEvery { it.execute(any(), any()) } returns StepResult(1, "tap", "tap", true, "tapped", 1, false)
    }

    /** A model that answers from [responses] and moves the clock by [advance] on each call. */
    private class ClockLlm(private val clock: AtomicLong, private val advance: Long, vararg responses: Map<String, Any?>) : LLMClientInterface {
        private val queue = ArrayDeque(responses.toList())
        val requests = Collections.synchronizedList(mutableListOf<List<Map<String, Any>>>())
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
            requests += messages
            clock.addAndGet(advance)
            return queue.removeFirstOrNull() ?: error("no response queued")
        }
        override fun shutdown() {}
    }

    private suspend fun awaitEnd(engine: AgentEngine, timeoutMs: Long = 15_000): TaskEnd {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            engine.end.value?.let { return it }
            delay(50)
        }
        error("engine did not end")
    }

    private suspend fun awaitQuestion(engine: AgentEngine, step: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val s = engine.state.value
            if (s is AgentState.WaitingForUser && s.step == step) return
            delay(10)
        }
        error("no question at step $step")
    }

    @Test fun defaultStepLimitIs30() = assertEquals(30, AgentEngine.DEFAULT_MAX_STEPS)

    @Test fun runningPastTenMinutesEndsAsFailed() = runBlocking {
        val llm = ClockLlm(clock, 6 * minute, *Array(5) { buildTapResponse(500, 500) })
        val engine = AgentEngine(llm, executor(), maxSteps = 30, clock = { clock.get() })
        engine.start("a long task")
        assertEquals(TaskEnd.Failed(AgentEngine.TIME_LIMIT_MESSAGE), awaitEnd(engine))
        assertEquals("This took too long, so I stopped. Nothing more was sent.", AgentEngine.TIME_LIMIT_MESSAGE)
        assertEquals(2, llm.requests.size)
    }

    @Test fun reachingTheStepLimitEndsAsFailedWithTheStepSentence() = runBlocking {
        val llm = ClockLlm(clock, 0, *Array(3) { buildTapResponse(200 + it * 200, 300 + it * 200) })
        val engine = AgentEngine(llm, executor(), maxSteps = 3, clock = { clock.get() })
        val results = Collections.synchronizedList(mutableListOf<StepResult>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.stepResults.collect { results += it } }
        engine.start("a task with many steps")
        assertEquals(TaskEnd.Failed(AgentEngine.STEP_LIMIT_MESSAGE), awaitEnd(engine))
        assertEquals("This needed too many steps, so I stopped. Nothing more was sent.", AgentEngine.STEP_LIMIT_MESSAGE)
        delay(100)
        scope.cancel()
        assertEquals(AgentEngine.STEP_LIMIT_MESSAGE, (engine.state.value as AgentState.Idle).resultMessage)
        val last = results.last()
        assertEquals("fail", last.actionType)
        assertTrue(last.completed)
        assertEquals("Task failed: ${AgentEngine.STEP_LIMIT_MESSAGE}", last.detail)
    }

    @Test fun waitingForTheOwnerDoesNotCount() = runBlocking {
        val llm = ClockLlm(clock, 0, buildAskUserResponse("Which photos?"), buildFinishResponse("done"))
        val engine = AgentEngine(llm, executor(), maxSteps = 30, clock = { clock.get() })
        engine.start("send photos")
        awaitQuestion(engine, 1)
        clock.addAndGet(60 * minute) // the owner took an hour to answer
        engine.submitUserResponse("the beach ones")
        assertEquals(TaskEnd.Done("done"), awaitEnd(engine))
    }

    @Test fun captionIsCleanedOnTheStepEvent() = runBlocking {
        val llm = FakeLlmClient()
        llm.enqueue(buildFinishResponse("done") + ("caption" to "  Picking the photos. "))
        val engine = AgentEngine(llm, executor(), maxSteps = 5)
        val events = Collections.synchronizedList(mutableListOf<EngineEvent>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.start("pick photos")
        awaitEnd(engine)
        delay(100)
        scope.cancel()
        val step = events.filterIsInstance<EngineEvent.Step>().single()
        assertEquals("Picking the photos", step.caption)
    }

    @Test fun captionWithAUrlIsDropped() = runBlocking {
        val llm = FakeLlmClient()
        llm.enqueue(buildFinishResponse("done") + ("caption" to "Opening https://x.example"))
        val engine = AgentEngine(llm, executor(), maxSteps = 5)
        val events = Collections.synchronizedList(mutableListOf<EngineEvent>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { events += it } }
        engine.start("x")
        awaitEnd(engine)
        delay(100)
        scope.cancel()
        assertEquals(null, events.filterIsInstance<EngineEvent.Step>().single().caption)
    }

    @Test fun longRunSendsTheWindowAndKeepsEveryAnswer() = runBlocking {
        val llm = ClockLlm(clock, 0, *Array(10) { buildAskUserResponse("Question $it") }, buildFinishResponse("done"))
        val engine = AgentEngine(llm, executor(), maxSteps = 30, clock = { clock.get() })
        engine.start("many questions")
        for (step in 1..10) {
            awaitQuestion(engine, step)
            engine.submitUserResponse("answer $step")
        }
        assertEquals(TaskEnd.Done("done"), awaitEnd(engine))
        val last = llm.requests.last()
        // system + 8 full turns (8 user messages, 7 answers).
        assertEquals(16, last.size)
        val firstUser = last[1]["content"].toString()
        assertTrue(firstUser.contains(HistoryWindow.EARLIER_HEADER))
        for (step in 1..2) assertTrue("answer $step kept", firstUser.contains("Owner answered: answer $step"))
        val all = last.joinToString("\n") { it["content"].toString() }
        for (step in 1..10) assertTrue("answer $step sent", all.contains("answer $step"))
        val images = last.sumOf { m -> (m["content"] as? List<*>)?.count { (it as Map<*, *>)["type"] == "image_url" } ?: 0 }
        assertEquals(1, images)
    }
}
