package ai.opencyclone.gate

import ai.opencyclone.action.Action
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HeldActionJsonTest {
    @Test fun `facts round trip`() {
        val facts = ScreenFacts("com.whatsapp", "Alex Test", 3)
        assertEquals(facts, HeldActionJson.factsFromJson(HeldActionJson.factsToJson(facts)))
    }

    @Test fun `facts with nulls round trip`() {
        val facts = ScreenFacts(null, null, null)
        assertEquals(facts, HeldActionJson.factsFromJson(HeldActionJson.factsToJson(facts)))
    }

    @Test fun `held action json carries reason, target and facts`() {
        val facts = ScreenFacts("com.whatsapp", "Alex Test", 3)
        val held = HeldAction(Action.Tap(10, 20), HoldReason.COMMIT_WORD, "Send", intArrayOf(1, 2, 3, 4), 50, 60, facts)
        val json = JSONObject(HeldActionJson.toJson(held))
        assertEquals("tap", json.getString("action_type"))
        assertEquals("COMMIT_WORD", json.getString("reason"))
        assertEquals("Send", json.getString("label"))
        assertEquals(4, json.getJSONArray("bounds").getInt(3))
        assertEquals(50, json.getInt("tap_x"))
        assertEquals(facts, HeldActionJson.factsFromJson(json.getJSONObject("facts").toString()))
    }
}
