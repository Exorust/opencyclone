package ai.opencyclon.gate

import ai.opencyclon.action.Action
import org.junit.Assert.*
import org.junit.Test

/** F18.1 and F18.4 rows of the gate: taps by element number and direct links. */
class TapByNumberGateTest {
    private val reader = FakeScreenReader()
    private val gate = ApprovalGate(reader, { BlockedApps.FIXED }, { 9 }) { x, y -> x * 2 to y * 3 }
    private fun numbered(label: String?, pkg: String? = "chat") =
        Action.Tap(10, 20, index = 4, label = label, targetPackage = pkg, targetKey = "Button|$label|0,0,1,1")
    private fun decision(action: Action) = gate.evaluate(action, false)
    private fun held(action: Action) = (decision(action) as GateDecision.Hold).held

    @Test fun `a tap by number on Send is held like a tap by position at its centre`() {
        val byPosition = held(Action.Tap(10, 20))
        val byNumber = held(numbered("Send"))
        assertEquals(HoldReason.COMMIT_WORD, byNumber.reason)
        assertEquals("Send", byNumber.label)
        assertEquals(Triple(9, 20, 60), reader.lastPoint)
        assertEquals(byPosition.label, byNumber.label)
        assertEquals(byPosition.tapX to byPosition.tapY, byNumber.tapX to byNumber.tapY)
        assertEquals(byPosition.facts, byNumber.facts)
    }

    @Test fun `the element's own label is used when the hit element carries no text`() {
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element(null))
        val held = held(numbered("SEND"))
        assertEquals(HoldReason.COMMIT_WORD, held.reason)
        assertEquals("SEND", held.label)
    }

    @Test fun `an icon-only element is held as unreadable`() {
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element(null))
        assertEquals(HoldReason.UNREADABLE_TARGET, held(numbered("")).reason)
    }

    @Test fun `nothing at the centre is held as unreadable even with a commit label`() {
        reader.target = ScreenReader.Result.Ok(null)
        assertEquals(HoldReason.UNREADABLE_TARGET, held(numbered("Send")).reason)
    }

    @Test fun `a tap by number in a blocked app fails`() {
        reader.facts = ScreenReader.Result.Ok(ScreenFacts("com.android.settings", null, null))
        assertEquals(GateDecision.Fail("Cannot open protected app"), decision(numbered("Next")))
    }

    @Test fun `a harmless label in the expected window is allowed`() {
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element("Next"))
        assertSame(GateDecision.Allow, decision(numbered("Next")))
    }

    @Test fun `a tap that lands in another window than the element's is held as unreadable`() {
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element("Next"))
        assertEquals(HoldReason.UNREADABLE_TARGET, held(numbered("Next", pkg = "other.app")).reason)
    }

    @Test fun `the re-check on approval compares a tap by number the same way`() {
        val held = held(numbered("Send"))
        assertTrue(gate.stillMatches(held))
        reader.target = ScreenReader.Result.Ok(FakeScreenReader.element("Delete"))
        assertFalse(gate.stillMatches(held))
    }

    @Test fun `view links of the plain schemes are allowed`() {
        listOf("https://example.com", "http://example.com", "geo:0,0?q=coffee", "sms:+14155550100?body=Hi",
            "smsto:+14155550100", "mailto:a@example.com", "tel:+14155550100").forEach {
            assertSame(it, GateDecision.Allow, decision(Action.OpenLink(it, targetPackage = "browser")))
        }
    }

    @Test fun `a direct call is always held with Call and the number`() {
        val held = held(Action.OpenLink("tel:+14155550100", call = true, targetPackage = "com.google.android.dialer"))
        assertEquals(HoldReason.COMMIT_WORD, held.reason)
        assertEquals("Call", held.label)
        assertEquals("+14155550100", held.facts.title)
        assertEquals("com.google.android.dialer", held.facts.topPackage)
        assertEquals("Call +14155550100", ApprovalCard.title(held, "Phone"))
        assertTrue(gate.stillMatches(held))
    }

    @Test fun `a call is held even when the screen cannot be read`() {
        reader.facts = ScreenReader.Result.Unreadable
        assertEquals("Call", held(Action.OpenLink("tel:5550100", call = true)).label)
        assertSame(GateDecision.Allow, decision(Action.OpenLink("https://example.com")))
    }

    @Test fun `a custom scheme is held with Open link and the link`() {
        val link = Action.OpenLink("whatsapp://send?phone=1&text=hi", targetPackage = "com.whatsapp", targetLabel = "WhatsApp")
        val held = held(link)
        assertEquals(HoldReason.OPEN_LINK, held.reason)
        assertEquals("Open link", held.label)
        assertEquals("whatsapp://send?phone=1&text=hi", held.facts.title)
        assertEquals("whatsapp link in WhatsApp", ApprovalCard.title(held, "WhatsApp"))
        assertEquals(HoldReason.OPEN_LINK, held(Action.OpenLink("intent:#Intent;component=a/.B;end")).reason)
    }

    @Test fun `a link into a blocked app fails`() {
        assertEquals(GateDecision.Fail("Cannot open protected app"),
            decision(Action.OpenLink("https://example.com", targetPackage = "com.android.settings")))
    }
}
