package ai.opencyclon.gate

import ai.opencyclon.action.Action
import org.junit.Assert.*
import org.junit.Test

internal class FakeScreenReader : ScreenReader {
    var facts: ScreenReader.Result<ScreenFacts> = ScreenReader.Result.Ok(ScreenFacts("chat", "Alex", 1))
    var target: ScreenReader.Result<ElementInfo?> = ScreenReader.Result.Ok(element())
    var focus: ScreenReader.Result<ElementInfo?> = ScreenReader.Result.Ok(null)
    var lastPoint: Triple<Int, Int, Int>? = null
    override fun screenFacts(displayId: Int) = facts
    override fun elementAt(displayId: Int, xPx: Int, yPx: Int): ScreenReader.Result<ElementInfo?> {
        lastPoint = Triple(displayId, xPx, yPx)
        return target
    }
    override fun focusedElement(displayId: Int) = focus

    companion object {
        fun element(text: String? = "Send", id: String? = null, editable: Boolean = false) =
            ElementInfo(text, null, id, "Button", 0, 0, 100, 100, editable)
    }
}

class ApprovalGateTest {
    private val reader = FakeScreenReader()
    private val gate = ApprovalGate(reader, { BlockedApps.FIXED }, { 9 }) { x, y -> x * 2 to y * 3 }
    private val tap = Action.Tap(10, 20)
    private fun decision(action: Action = tap) = gate.evaluate(action, false)
    private fun held(action: Action = tap) = (decision(action) as GateDecision.Hold).held

    @Test fun `approved action bypasses all checks`() {
        reader.facts = ScreenReader.Result.Unreadable
        assertSame(GateDecision.Allow, gate.evaluate(tap, true))
    }
    @Test fun `unreadable screen fails`() {
        reader.facts = ScreenReader.Result.Unreadable
        assertEquals(GateDecision.Fail("Cannot read the screen"), decision())
    }
    @Test fun `startup exceptions allow an unreadable screen`() {
        reader.facts = ScreenReader.Result.Unreadable
        listOf(Action.OpenApp("chat"), Action.Wait(), Action.Note("a"), Action.Remember("a", "b"),
            Action.ListApps(), Action.AskUser("a"), Action.HandoffUser("a"), Action.Fail("a")).forEach {
            assertSame(it.typeName, GateDecision.Allow, decision(it))
        }
    }
    @Test fun `blocked top package fails even for startup actions`() {
        reader.facts = ScreenReader.Result.Ok(ScreenFacts("com.android.settings", null, null))
        assertEquals(GateDecision.Fail("Cannot open protected app"), decision(Action.Wait()))
    }
    @Test fun `send holds and records converted coordinates`() {
        val held = held()
        assertEquals(HoldReason.COMMIT_WORD, held.reason)
        assertEquals("Send", held.label)
        assertEquals(Triple(9, 20, 60), reader.lastPoint)
        assertEquals(20, held.tapX)
        assertEquals(60, held.tapY)
    }
    @Test fun `camel case identifier holds`() {
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element(null, "btnSendMessage"))
        assertEquals("btnSendMessage", held().label)
    }
    @Test fun `description holds`() {
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element(null).copy(description = "Pay"))
        assertEquals("Pay", held().label)
    }
    @Test fun `resend code is allowed`() {
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element("Resend code"))
        assertSame(GateDecision.Allow, decision())
    }
    @Test fun `missing target holds`() {
        reader.target = ScreenReader.Result.Ok(null)
        assertEquals(HoldReason.UNREADABLE_TARGET, held().reason)
        assertNull(held().bounds)
    }
    @Test fun `unreadable target fails`() {
        reader.target = ScreenReader.Result.Unreadable
        assertEquals(GateDecision.Fail("Cannot read the screen"), decision())
    }
    @Test fun `long press delete holds`() {
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element("Delete"))
        assertEquals(HoldReason.COMMIT_WORD, held(Action.LongPress(10, 20)).reason)
    }
    @Test fun `enter in editable focus holds`() {
        reader.focus = ScreenReader.Result.Ok(FakeScreenReader.element("draft", editable = true))
        assertEquals(HoldReason.ENTER_IN_TEXT_FIELD, held(Action.KeyEvent("EnTeR")).reason)
    }
    @Test fun `enter without focus holds`() {
        assertEquals(HoldReason.UNREADABLE_TARGET, held(Action.KeyEvent("enter")).reason)
    }
    @Test fun `enter with focused Send button holds`() {
        reader.focus = reader.target
        assertEquals(HoldReason.COMMIT_WORD, held(Action.KeyEvent("enter")).reason)
    }
    @Test fun `activation keys on Send hold with the focused target facts`() {
        reader.focus = reader.target
        listOf("SPACE", "EnTeR", "DPAD_CENTER", "NUMPAD_ENTER").forEach { key ->
            val held = held(Action.KeyEvent(key))
            assertEquals(key, HoldReason.COMMIT_WORD, held.reason)
            assertEquals("Send", held.label)
            assertArrayEquals(intArrayOf(0, 0, 100, 100), held.bounds)
            assertEquals((reader.facts as ScreenReader.Result.Ok).value, held.facts)
            assertNull(held.tapX)
            assertNull(reader.lastPoint)
            assertTrue(gate.stillMatches(held))
        }
    }
    @Test fun `activation keys on Chat allow`() {
        reader.focus = ScreenReader.Result.Ok(FakeScreenReader.element("Chat"))
        listOf("space", "enter", "dpad_center", "numpad_enter").forEach {
            assertSame(it, GateDecision.Allow, decision(Action.KeyEvent(it)))
        }
    }
    @Test fun `activation keys without a readable label hold`() {
        listOf(null, FakeScreenReader.element(null, "button1"), FakeScreenReader.element(" ")).forEach { element ->
            reader.focus = ScreenReader.Result.Ok(element)
            listOf("space", "enter", "dpad_center", "numpad_enter").forEach {
                assertEquals(it, HoldReason.UNREADABLE_TARGET, held(Action.KeyEvent(it)).reason)
            }
        }
    }
    @Test fun `activation keys use commit descriptions and identifiers like taps`() {
        listOf(FakeScreenReader.element(null, "btnSend"),
            FakeScreenReader.element(null).copy(description = "Send")).forEach { element ->
            reader.focus = ScreenReader.Result.Ok(element)
            listOf("space", "enter", "dpad_center", "numpad_enter").forEach {
                assertEquals(it, HoldReason.COMMIT_WORD, held(Action.KeyEvent(it)).reason)
            }
        }
    }
    @Test fun `activation keys fail when focus cannot be read`() {
        reader.focus = ScreenReader.Result.Unreadable
        listOf("space", "enter", "dpad_center", "numpad_enter").forEach {
            assertEquals(it, GateDecision.Fail(ApprovalGate.UNREADABLE_MESSAGE), decision(Action.KeyEvent(it)))
        }
    }
    @Test fun `editable focus holds both Enter keys but allows space and center`() {
        reader.focus = ScreenReader.Result.Ok(FakeScreenReader.element("draft", editable = true))
        listOf("enter", "numpad_enter").forEach {
            assertEquals(HoldReason.ENTER_IN_TEXT_FIELD, held(Action.KeyEvent(it)).reason)
        }
        listOf("space", "dpad_center").forEach {
            assertSame(GateDecision.Allow, decision(Action.KeyEvent(it)))
        }
    }
    @Test fun `changed focus invalidates key approval and keeps harmless new target held`() {
        reader.focus = reader.target
        val held = held(Action.KeyEvent("space"))
        reader.focus = ScreenReader.Result.Ok(FakeScreenReader.element("Chat"))
        assertFalse(gate.stillMatches(held))
        assertEquals("Chat", (gate.refresh(held) as GateDecision.Hold).held.label)
        reader.focus = ScreenReader.Result.Ok(FakeScreenReader.element().copy(right = 101))
        assertFalse(gate.stillMatches(held))
    }
    @Test fun `unreadable focus fails`() {
        reader.focus = ScreenReader.Result.Unreadable
        assertEquals(GateDecision.Fail("Cannot read the screen"), decision(Action.KeyEvent("enter")))
    }
    @Test fun `swipe allows`() { assertSame(GateDecision.Allow, decision(Action.Swipe("up"))) }

    // ── Typed line breaks act as the Enter key ──────────────────────────

    @Test fun `typing without a line break allows`() {
        reader.focus = ScreenReader.Result.Ok(FakeScreenReader.element("", editable = true))
        assertSame(GateDecision.Allow, decision(Action.TypeText("hi there")))
    }
    @Test fun `typing a newline in a text field holds like enter`() {
        reader.focus = ScreenReader.Result.Ok(FakeScreenReader.element("draft", editable = true))
        val held = held(Action.TypeText("hi\n"))
        assertEquals(HoldReason.ENTER_IN_TEXT_FIELD, held.reason)
        assertEquals(Action.TypeText("hi\n"), held.action)
    }
    @Test fun `typing a carriage return holds`() {
        reader.focus = ScreenReader.Result.Ok(FakeScreenReader.element("draft", editable = true))
        assertEquals(HoldReason.ENTER_IN_TEXT_FIELD, held(Action.TypeText("hi\r")).reason)
    }
    @Test fun `multi line typing holds even without a readable focused field`() {
        assertEquals(HoldReason.ENTER_IN_TEXT_FIELD, held(Action.TypeText("line one\nline two")).reason)
        reader.focus = ScreenReader.Result.Ok(FakeScreenReader.element("label"))
        assertEquals(HoldReason.ENTER_IN_TEXT_FIELD, held(Action.TypeText("a\nb")).reason)
    }
    @Test fun `typing a newline with unreadable focus fails`() {
        reader.focus = ScreenReader.Result.Unreadable
        assertEquals(GateDecision.Fail("Cannot read the screen"), decision(Action.TypeText("hi\n")))
    }
    @Test fun `typing a newline in a blocked app fails`() {
        reader.facts = ScreenReader.Result.Ok(ScreenFacts("com.android.settings", null, null))
        assertEquals(GateDecision.Fail("Cannot open protected app"), decision(Action.TypeText("hi\n")))
    }
    @Test fun `held newline typing still matches and runs once approved`() {
        reader.focus = ScreenReader.Result.Ok(FakeScreenReader.element("draft", editable = true))
        val held = held(Action.TypeText("hi\n"))
        assertTrue(gate.stillMatches(held))
        assertSame(GateDecision.Allow, gate.evaluate(Action.TypeText("hi\n"), true))
    }
    @Test fun `matching snapshot allows approval`() { assertTrue(gate.stillMatches(held())) }
    @Test fun `title change invalidates approval`() {
        val held = held()
        reader.facts = ScreenReader.Result.Ok(held.facts.copy(title = "Bob"))
        assertFalse(gate.stillMatches(held))
    }
    @Test fun `bounds change invalidates approval`() {
        val held = held()
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element().copy(right = 101))
        assertFalse(gate.stillMatches(held))
    }
    @Test fun `label change invalidates approval and still requires a hold`() {
        val held = held()
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element("Next"))
        assertFalse(gate.stillMatches(held))
        assertEquals("Next", (gate.refresh(held) as GateDecision.Hold).held.label)
    }
    @Test fun `attachment count and package changes invalidate approval`() {
        val held = held()
        reader.facts = ScreenReader.Result.Ok(held.facts.copy(attachmentCount = 2))
        assertFalse(gate.stillMatches(held))
        reader.facts = ScreenReader.Result.Ok(held.facts.copy(topPackage = "other"))
        assertFalse(gate.stillMatches(held))
    }
    @Test fun `unreadable recheck rejects approval`() {
        val held = held()
        reader.facts = ScreenReader.Result.Unreadable
        assertFalse(gate.stillMatches(held))
    }
    @Test fun `commit words use whole words phrases and translations`() {
        listOf("SEND", "send_button", "btnSendMessage", "Place order", "Order now", "enviar", "pagar",
            "eliminar", "confirmar", "भेजें", "भुगतान करें", "हटाएं", "पुष्टि करें", "发送", "支付", "删除", "确认").forEach {
            assertTrue(it, CommitWords.matches(it))
        }
        listOf(null, "", "Resend code", "callback", "repayment", "orders", "placement order").forEach {
            assertFalse(it, CommitWords.matches(it))
        }
    }
    @Test fun `owner additions cannot remove fixed packages`() {
        assertTrue(BlockedApps.FIXED.size >= 44)
        assertEquals(BlockedApps.FIXED + "my.app" + "owner.bank", BlockedApps.all(setOf("owner.bank"), "my.app"))
        assertTrue(BlockedApps.all(emptySet(), "my.app").containsAll(BlockedApps.FIXED))
    }
    @Test fun `this app is blocked under its real application id and alipay is fixed`() {
        assertTrue("ai.opencyclon.renamed" in BlockedApps.all(emptySet(), "ai.opencyclon.renamed"))
        assertTrue("com.eg.android.AlipayGphone" in BlockedApps.FIXED)
        val gate = ApprovalGate(reader, { BlockedApps.all(emptySet(), "ai.opencyclon.renamed") }, { 9 }) { x, y -> x to y }
        reader.facts = ScreenReader.Result.Ok(ScreenFacts("ai.opencyclon.renamed", null, null))
        assertEquals(GateDecision.Fail("Cannot open protected app"), gate.evaluate(Action.Wait(), false))
    }

    // ── Rule 2: unlabeled buttons ───────────────────────────────────────

    @Test fun `icon only element with plain identifier holds as unreadable target`() {
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element(null, "button1"))
        val held = held()
        assertEquals(HoldReason.UNREADABLE_TARGET, held.reason)
        assertNotNull(held.bounds)
    }
    @Test fun `icon only element with blank text and no identifier holds`() {
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element("  ").copy(description = ""))
        assertEquals(HoldReason.UNREADABLE_TARGET, held().reason)
        assertEquals(HoldReason.UNREADABLE_TARGET, held(Action.LongPress(10, 20)).reason)
    }
    @Test fun `icon only element with commit identifier holds as commit word`() {
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element(null, "btnSend"))
        val held = held()
        assertEquals(HoldReason.COMMIT_WORD, held.reason)
        assertEquals("btnSend", held.label)
    }
    @Test fun `empty text field is allowed`() {
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element(null, "input1", editable = true))
        assertSame(GateDecision.Allow, decision())
    }
    @Test fun `element with a description is allowed`() {
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element(null, "button1").copy(description = "Back"))
        assertSame(GateDecision.Allow, decision())
    }

    // ── Rule 1: tool calls ──────────────────────────────────────────────

    private val toolGate = ApprovalGate(reader, { BlockedApps.FIXED }, { 9 },
        { server, tool -> server == "notes" && tool == "list_notes" }) { x, y -> x to y }

    @Test fun `read only tool call is allowed`() {
        assertSame(GateDecision.Allow, toolGate.evaluate(Action.CallTool("notes", "list_notes", "{}"), false))
    }
    @Test fun `tool call that is not read only holds`() {
        val held = (toolGate.evaluate(Action.CallTool("notes", "delete_note", "{\"title\":\"pm-test\"}"), false)
            as GateDecision.Hold).held
        assertEquals(HoldReason.TOOL_CALL, held.reason)
        assertEquals("notes.delete_note", held.label)
        assertNull(held.bounds)
        assertNull(held.tapX)
        assertNull(held.tapY)
    }
    @Test fun `tool call holds by default when read only is unknown`() {
        assertEquals(HoldReason.TOOL_CALL, held(Action.CallTool("notes", "list_notes", "{}")).reason)
    }
    @Test fun `tool call ignores an unreadable screen`() {
        reader.facts = ScreenReader.Result.Unreadable
        reader.target = ScreenReader.Result.Unreadable
        assertSame(GateDecision.Allow, toolGate.evaluate(Action.CallTool("notes", "list_notes", "{}"), false))
        assertEquals(HoldReason.TOOL_CALL,
            (toolGate.evaluate(Action.CallTool("notes", "add_note", "{}"), false) as GateDecision.Hold).held.reason)
    }
    @Test fun `tool call ignores a blocked top package`() {
        reader.facts = ScreenReader.Result.Ok(ScreenFacts("com.android.settings", null, null))
        assertSame(GateDecision.Allow, toolGate.evaluate(Action.CallTool("notes", "list_notes", "{}"), false))
    }
    @Test fun `held tool call still matches on approval even with an unreadable screen`() {
        val held = held(Action.CallTool("notes", "add_note", "{}"))
        reader.facts = ScreenReader.Result.Unreadable
        assertTrue(gate.stillMatches(held))
    }
}
