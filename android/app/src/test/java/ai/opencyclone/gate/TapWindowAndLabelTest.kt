package ai.opencyclone.gate

import ai.opencyclone.action.Action
import org.junit.Assert.*
import org.junit.Test

class TapWindowAndLabelTest {
    private class TapReader : ScreenReader {
        var display: ScreenFacts = ScreenFacts("ai.opencyclone.testapp", "Alex", null)
        var atPoint: ScreenFacts = ScreenFacts("ai.opencyclone.testapp", "Alex", null)
        var target: ElementInfo? = ElementInfo("SEND", null, null, "Button", 0, 0, 100, 100, false)
        override fun screenFacts(displayId: Int) = ScreenReader.Result.Ok(display)
        override fun screenFactsAt(displayId: Int, xPx: Int, yPx: Int) = ScreenReader.Result.Ok(atPoint)
        override fun elementAt(displayId: Int, xPx: Int, yPx: Int) = ScreenReader.Result.Ok(target)
        override fun focusedElement(displayId: Int): ScreenReader.Result<ElementInfo?> = ScreenReader.Result.Ok(null)
    }

    private val reader = TapReader()
    private val gate = ApprovalGate(reader, { BlockedApps.FIXED }, { 9 }) { x, y -> x to y }
    private fun held(action: Action = Action.Tap(10, 20)) = (gate.evaluate(action, false) as GateDecision.Hold).held

    @Test fun `upper-case send holds and the card says Send in Gate Test to Alex`() {
        val held = held()
        assertEquals(HoldReason.COMMIT_WORD, held.reason)
        assertEquals("SEND", held.label)
        assertEquals("Send", held.displayLabel)
        assertEquals("ai.opencyclone.testapp", held.facts.topPackage)
        assertEquals("Send in Gate Test to Alex", ApprovalCard.title(held, "Gate Test"))
        assertTrue(gate.stillMatches(held))
    }

    @Test fun `a tap takes its facts from the window it lands in`() {
        reader.target = ElementInfo(null, "Delete", null, "ImageView", 0, 0, 100, 100, false)
        reader.atPoint = ScreenFacts("com.example.launcher", null, null)
        assertEquals(ScreenFacts("com.example.launcher", null, null), held().facts)
    }

    @Test fun `a tap landing in a blocked app fails`() {
        reader.atPoint = ScreenFacts("com.android.settings", null, null)
        assertEquals(GateDecision.Fail(ApprovalGate.PROTECTED_MESSAGE), gate.evaluate(Action.Tap(10, 20), false))
    }

    @Test fun `a non-tap action keeps the display facts`() {
        reader.atPoint = ScreenFacts("com.example.launcher", null, null)
        reader.display = ScreenFacts("ai.opencyclone.testapp", "Alex", null)
        val held = (gate.evaluate(Action.Note("x"), false))
        assertSame(GateDecision.Allow, held)
    }

    @Test fun `sentence case applies only to all-capital labels longer than one letter`() {
        assertEquals("Send", HeldAction.sentenceCaseIfShouting("SEND"))
        assertEquals("Place order", HeldAction.sentenceCaseIfShouting("PLACE ORDER"))
        assertEquals("Send now", HeldAction.sentenceCaseIfShouting("SEND NOW"))
        assertEquals("X", HeldAction.sentenceCaseIfShouting("X"))
        assertEquals("Send", HeldAction.sentenceCaseIfShouting("Send"))
        assertEquals("iPhone SEND", HeldAction.sentenceCaseIfShouting("iPhone SEND"))
        assertEquals("btnSendMessage", HeldAction.sentenceCaseIfShouting("btnSendMessage"))
        assertEquals("发送", HeldAction.sentenceCaseIfShouting("发送"))
    }

    @Test fun `commit words match case-insensitively`() {
        assertTrue(CommitWords.matches("SEND"))
        assertTrue(CommitWords.matches("PLACE ORDER"))
    }

    @Test fun `the re-check compares the raw label`() {
        val held = held()
        reader.target = ElementInfo("Send", null, null, "Button", 0, 0, 100, 100, false)
        assertFalse(gate.stillMatches(held))
    }
}
