package ai.opencyclon.engine

import ai.opencyclon.accessibility.ElementList
import ai.opencyclon.accessibility.UiElement
import ai.opencyclon.action.Action
import ai.opencyclon.action.ActionExecutor
import ai.opencyclon.action.LinkCheck
import ai.opencyclon.action.TypedTextCheck
import ai.opencyclon.capture.ScreenCapture
import ai.opencyclon.fixtures.FakeLlmClient
import ai.opencyclon.fixtures.buildFinishResponse
import ai.opencyclon.fixtures.buildKeyEventResponse
import ai.opencyclon.fixtures.buildOpenAppResponse
import ai.opencyclon.fixtures.buildSwipeResponse
import ai.opencyclon.fixtures.buildTypeTextResponse
import ai.opencyclon.gate.ApprovalGate
import ai.opencyclon.gate.HeldAction
import ai.opencyclon.gate.HoldResult
import ai.opencyclon.gate.ScreenFacts
import ai.opencyclon.gate.ScreenReader
import ai.opencyclon.gate.ElementInfo
import io.mockk.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Collections

/** F18.1 to F18.4 through the engine loop: taps by number, the quiet wait, checks in code, direct links. */
class F18EngineTest {
    private val executor = mockk<ActionExecutor>(relaxed = true)
    private val executed = Collections.synchronizedList(mutableListOf<Action>())
    private var engine: AgentEngine? = null

    @Before fun setup() {
        mockkObject(ScreenCapture)
        every { ScreenCapture.captureBase64() } returns "/9j/4AAQSkZJRg=="
        every { ScreenCapture.captureBase64(any(), any()) } returns "/9j/4AAQSkZJRg=="
        coEvery { executor.execute(any(), any()) } answers {
            executed += firstArg<Action>()
            StepResult(secondArg(), firstArg<Action>().typeName, "", true, "ok", 0, false)
        }
    }

    @After fun teardown() {
        engine?.stop()
        unmockkObject(ScreenCapture)
    }

    private fun el(n: Int, label: String, top: Int) = UiElement(n, 0, top, 1000, top + 100, label, "Button", 0, "app")
    private fun screen(vararg e: UiElement, text: String = e.joinToString("\n") { "[${it.number}] Button \"${it.label}\"" }) =
        ElementList(text, e.associateBy { it.number }, 1000, 2000)

    private fun tapNumber(n: Int) = mapOf<String, Any?>("action_type" to "tap", "thought" to "tap", "index" to n)

    private fun start(
        llm: FakeLlmClient, elements: () -> ElementList?, maxSteps: Int = 10,
        gate: ApprovalGate? = null, onHold: (suspend (HeldAction) -> HoldResult)? = null,
    ): AgentEngine = AgentEngine(llm, executor, maxSteps = maxSteps, approvalGate = gate, onHold = onHold,
        elementsProvider = { _, _, _ -> elements() }, contentChanges = { 0L },
        appLabel = { if (it == "com.android.settings") "Settings" else it }).also {
        engine = it
        it.start("walk")
    }

    private fun awaitEnd(e: AgentEngine): TaskEnd = runBlocking { withTimeout(20_000) { e.end.filterNotNull().first() } }

    private fun requestText(llm: FakeLlmClient, i: Int) = llm.requestHistory[i].last().toString()

    @Test fun `a tap by number taps the element's centre and is recorded as by number`() {
        val llm = FakeLlmClient().apply { enqueue(tapNumber(1), buildFinishResponse()) }
        val chat = screen(el(1, "Chat", 200))
        val e = start(llm, { chat })
        assertTrue(awaitEnd(e) is TaskEnd.Done)
        val tap = executed.single() as Action.Tap
        assertEquals(500 to 125, tap.x to tap.y)
        assertEquals(1, tap.index)
        assertEquals("Chat", tap.label)
        val fact = StepLog.lastTask().first { it.action == "tap" }
        assertTrue(fact.byNumber)
        assertEquals("Chat", fact.label)
        assertNotNull(fact.waitMs)
        assertTrue("wait ${fact.waitMs}", fact.waitMs!! in 300L..800L)
    }

    @Test fun `an unknown number taps nothing and tells the model`() {
        val llm = FakeLlmClient().apply { enqueue(tapNumber(9), buildFinishResponse()) }
        val e = start(llm, { screen(el(1, "Chat", 200)) })
        assertTrue(awaitEnd(e) is TaskEnd.Done)
        assertTrue(executed.isEmpty())
        assertTrue(requestText(llm, 1).contains("There is no element 9 on this screen."))
        assertEquals("There is no element 9 on this screen.", StepLog.lastTask().first().feedback)
    }

    @Test fun `a changed label at that number taps nothing`() {
        var reads = 0
        val llm = FakeLlmClient().apply { enqueue(tapNumber(1), buildFinishResponse()) }
        val e = start(llm, { if (reads++ == 0) screen(el(1, "Chat", 200)) else screen(el(1, "Delete", 900)) })
        assertTrue(awaitEnd(e) is TaskEnd.Done)
        assertTrue(executed.isEmpty())
        assertTrue(requestText(llm, 1).contains("The screen changed. Look again."))
    }

    @Test fun `a moved element with the same label is tapped where it is now`() {
        var reads = 0
        val llm = FakeLlmClient().apply { enqueue(tapNumber(1), buildFinishResponse()) }
        val e = start(llm, { if (reads++ == 0) screen(el(1, "Moving", 200)) else screen(el(1, "Moving", 1200)) })
        awaitEnd(e)
        val tap = executed.single() as Action.Tap
        assertEquals(625, tap.y)
    }

    @Test fun `a tap by number on Send is held like a tap by position`() {
        val reader = object : ScreenReader {
            override fun elementAt(displayId: Int, xPx: Int, yPx: Int) =
                ScreenReader.Result.Ok(ElementInfo("SEND", null, null, "Button", 0, 0, 1000, 100, false))
            override fun focusedElement(displayId: Int) = ScreenReader.Result.Ok(null)
            override fun screenFacts(displayId: Int) = ScreenReader.Result.Ok(ScreenFacts("app", "Alex", null))
        }
        val gate = ApprovalGate(reader, { emptySet() }, { 0 }) { x, y -> x to y }
        val holds = mutableListOf<HeldAction>()
        val llm = FakeLlmClient().apply { enqueue(tapNumber(1)) }
        val e = start(llm, { screen(el(1, "SEND", 200)) }, gate = gate, onHold = { holds += it; HoldResult.Approve })
        awaitEnd(e)
        assertEquals("SEND", holds.single().label)
        assertEquals(1, executed.size)
    }

    @Test fun `finish right after opening an app gets one more look`() {
        val llm = FakeLlmClient().apply { enqueue(buildOpenAppResponse("Gate Test"), buildFinishResponse("first"), buildFinishResponse("second")) }
        val e = start(llm, { screen(el(1, "Chat", 200)) })
        assertEquals(TaskEnd.Done("second"), awaitEnd(e))
        assertEquals(3, llm.requestHistory.size)
        assertTrue(requestText(llm, 2).contains("Look at the screen once more before you finish."))
        assertTrue(StepLog.lastTask().any { it.action == "finish" && it.feedback == "Look at the screen once more before you finish." })
    }

    @Test fun `finish right after an action that changed the screen gets one more look, then is accepted`() {
        var reads = 0
        val llm = FakeLlmClient().apply { enqueue(tapNumber(1), buildFinishResponse("a"), buildFinishResponse("b")) }
        val e = start(llm, { if (reads++ < 2) screen(el(1, "Chat", 200)) else screen(el(1, "Back", 200), text = "chat screen") })
        assertEquals(TaskEnd.Done("b"), awaitEnd(e))
        assertEquals(3, llm.requestHistory.size)
    }

    @Test fun `finish after an action that changed nothing is accepted at once`() {
        val llm = FakeLlmClient().apply { enqueue(buildSwipeResponse("down"), buildFinishResponse("done")) }
        val e = start(llm, { screen(el(1, "Chat", 200)) })
        assertEquals(TaskEnd.Done("done"), awaitEnd(e))
        assertEquals(2, llm.requestHistory.size)
    }

    @Test fun `three repeats of the same three actions end the task as going in circles`() {
        val llm = FakeLlmClient().apply {
            repeat(4) { enqueue(buildSwipeResponse("up"), buildSwipeResponse("down"), buildKeyEventResponse("back")) }
        }
        val e = start(llm, { screen(el(1, "Chat", 200)) }, maxSteps = 30)
        assertEquals(TaskEnd.Failed(CycleDetector.MESSAGE), awaitEnd(e))
        assertEquals(9, executed.size)
        assertEquals(9, llm.requestHistory.size)
    }

    @Test fun `a taskbar that changes between cycles does not hide the cycle`() {
        // On the phone the swipe moved, then stashed, the launcher taskbar: the list text (and its numbers) changed
        // between identical cycles, the app's own screen did not.
        val llm = FakeLlmClient().apply {
            repeat(4) { enqueue(buildSwipeResponse("up"), buildSwipeResponse("down"), buildKeyEventResponse("back")) }
        }
        val e = start(llm, {
            val chat = screen(el(1, "Chat", 200))
            // The taskbar is different after every action, and stays still while the engine waits.
            chat.copy(text = "[1] TextView \"Phone\" (${executed.size},950)\n" + chat.text, identity = 42)
        }, maxSteps = 30)
        assertEquals(TaskEnd.Failed(CycleDetector.MESSAGE), awaitEnd(e))
        assertEquals(9, executed.size)
        val facts = StepLog.lastTask()
        assertEquals("swipe up @2a", facts.first { it.step == 1 }.cycle)
        assertEquals("key_event back @2a", facts.first { it.step == 9 }.cycle)
    }

    @Test fun `the same actions on a changing screen are not a cycle`() {
        var reads = 0
        val llm = FakeLlmClient().apply { repeat(6) { enqueue(buildSwipeResponse("down")) } }
        val e = start(llm, { screen(el(1, "Row ${reads++}", 200)) }, maxSteps = 6)
        assertEquals(TaskEnd.Failed(AgentEngine.STEP_LIMIT_MESSAGE), awaitEnd(e))
    }

    @Test fun `typed text that did not arrive is told to the model in one sentence`() {
        coEvery { executor.execute(match { it is Action.TypeText }, any()) } returns
            StepResult(1, "type_text", "", false, TypedTextCheck.MISSING_FEEDBACK, 0, false)
        val llm = FakeLlmClient().apply { enqueue(buildTypeTextResponse("hello"), buildFinishResponse()) }
        val e = start(llm, { screen(el(1, "Field", 200)) })
        awaitEnd(e)
        assertTrue(requestText(llm, 1).contains("[System Feedback] The text did not arrive in the field."))
        assertFalse(StepLog.lastTask().first().success)
    }

    private fun openLink(uri: String, call: Boolean = false) =
        mapOf<String, Any?>("action_type" to "open_link", "thought" to "link", "uri" to uri, "call" to call)

    @Test fun `a link that no app opens is feedback, not a failure`() {
        every { executor.checkLink(any()) } returns LinkCheck.Refused(LinkCheck.NO_APP)
        val llm = FakeLlmClient().apply { enqueue(openLink("zzwalk://x"), buildFinishResponse()) }
        val e = start(llm, { screen(el(1, "Chat", 200)) })
        assertTrue(awaitEnd(e) is TaskEnd.Done)
        assertTrue(executed.isEmpty())
        assertTrue(requestText(llm, 1).contains("No app on this phone opens that link."))
    }

    @Test fun `a link into a protected app ends the task with the protected sentence`() {
        every { executor.checkLink(any()) } returns LinkCheck.Blocked("com.android.settings")
        val llm = FakeLlmClient().apply { enqueue(openLink("intent:#Intent;action=android.settings.SETTINGS;end")) }
        val e = start(llm, { screen(el(1, "Chat", 200)) })
        assertEquals(TaskEnd.Failed("I do not open Settings. It is on your protected list."), awaitEnd(e))
        assertTrue(executed.isEmpty())
    }

    @Test fun `a web link runs without a hold and a call is held, Not now places nothing`() {
        every { executor.checkLink(any()) } returns LinkCheck.Opens("dialer", "Phone")
        val reader = object : ScreenReader {
            override fun elementAt(displayId: Int, xPx: Int, yPx: Int) = ScreenReader.Result.Ok(null)
            override fun focusedElement(displayId: Int) = ScreenReader.Result.Ok(null)
            override fun screenFacts(displayId: Int) = ScreenReader.Result.Ok(ScreenFacts("app", null, null))
        }
        val gate = ApprovalGate(reader, { emptySet() }, { 0 }) { x, y -> x to y }
        val holds = mutableListOf<HeldAction>()
        val llm = FakeLlmClient().apply { enqueue(openLink("https://example.com"), openLink("tel:+14155550100", call = true)) }
        val e = start(llm, { screen(el(1, "Chat", 200)) }, gate = gate, onHold = { holds += it; HoldResult.Reject })
        assertEquals(TaskEnd.Rejected, awaitEnd(e))
        assertEquals(listOf("https://example.com"), executed.map { (it as Action.OpenLink).uri })
        assertEquals("Call", holds.single().label)
        assertEquals("+14155550100", holds.single().facts.title)
    }
}
