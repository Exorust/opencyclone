package ai.opencyclon.gate

import ai.opencyclon.action.Action
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lane N5: the new facts (text to be sent, item count and noun) in the re-check on Approve and in stored JSON. */
class EvidenceRecheckTest {
    private val reader = FakeScreenReader()
    private val gate = ApprovalGate(reader, { BlockedApps.FIXED }, { 9 }) { x, y -> x to y }
    private val tap = Action.Tap(10, 20)
    private val base = ScreenFacts("chat", "Alex", 3, composeText = "See you at 6", attachmentNoun = "photos",
        attachmentBounds = listOf(Box(0, 1000, 200, 1200), Box(220, 1000, 420, 1200)))

    private fun holdWith(facts: ScreenFacts): HeldAction {
        reader.facts = ScreenReader.Result.Ok(facts)
        return (gate.evaluate(tap, false) as GateDecision.Hold).held
    }

    @Test fun `unchanged facts allow approval`() {
        val held = holdWith(base)
        assertTrue(gate.stillMatches(held))
    }

    @Test fun `a changed item count is a changed screen`() {
        val held = holdWith(base)
        reader.facts = ScreenReader.Result.Ok(base.copy(attachmentCount = 4))
        assertFalse(gate.stillMatches(held))
    }

    @Test fun `a count that became unreadable is a changed screen`() {
        val held = holdWith(base)
        reader.facts = ScreenReader.Result.Ok(base.copy(attachmentCount = null, attachmentNoun = null))
        assertFalse(gate.stillMatches(held))
    }

    @Test fun `a changed text to be sent is a changed screen`() {
        val held = holdWith(base)
        reader.facts = ScreenReader.Result.Ok(base.copy(composeText = "See you at 7"))
        assertFalse(gate.stillMatches(held))
        reader.facts = ScreenReader.Result.Ok(base.copy(composeText = null))
        assertFalse(gate.stillMatches(held))
    }

    @Test fun `a changed title still is a changed screen`() {
        val held = holdWith(base)
        reader.facts = ScreenReader.Result.Ok(base.copy(title = "Sam"))
        assertFalse(gate.stillMatches(held))
    }

    @Test fun `moved item positions alone do not block approval`() {
        val held = holdWith(base)
        reader.facts = ScreenReader.Result.Ok(base.copy(attachmentBounds = listOf(Box(0, 990, 200, 1190))))
        assertTrue(gate.stillMatches(held))
    }

    @Test fun `a typed line break holds with the text that will be sent`() {
        reader.focus = ScreenReader.Result.Ok(FakeScreenReader.element(text = "Hi", editable = true))
        reader.facts = ScreenReader.Result.Ok(ScreenFacts("chat", "Alex", null, composeText = "Hi"))
        val held = (gate.evaluate(Action.TypeText(" there\nmore"), false) as GateDecision.Hold).held
        assertEquals(HoldReason.ENTER_IN_TEXT_FIELD, held.reason)
        assertEquals("Hi there", held.facts.composeText)
        assertTrue(gate.stillMatches(held))
    }

    @Test fun `new facts round trip through json`() {
        val back = HeldActionJson.factsFromJson(HeldActionJson.factsToJson(base))
        assertEquals(base.copy(attachmentBounds = emptyList()), back)
    }

    @Test fun `json written before lane N5 still parses`() {
        val old = """{"top_package":"com.whatsapp","title":"Alex","attachment_count":2}"""
        val facts = HeldActionJson.factsFromJson(old)
        assertEquals(ScreenFacts("com.whatsapp", "Alex", 2), facts)
        assertNull(facts.composeText)
        assertNull(facts.attachmentNoun)
    }

    @Test fun `held action json carries the new facts`() {
        val held = HeldAction(tap, HoldReason.COMMIT_WORD, "Send", null, 10, 20, base)
        val facts = JSONObject(HeldActionJson.toJson(held)).getJSONObject("facts")
        assertEquals("See you at 6", facts.getString("compose_text"))
        assertEquals("photos", facts.getString("attachment_noun"))
    }

    @Test fun `compose text is capped at 500 characters with an ellipsis`() {
        val capped = ScreenFacts.capCompose("a".repeat(600))
        assertEquals(500, capped.length)
        assertTrue(capped.endsWith("…"))
        assertEquals("short", ScreenFacts.capCompose("short"))
    }
}
