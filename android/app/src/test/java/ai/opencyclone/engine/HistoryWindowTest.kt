package ai.opencyclone.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryWindowTest {
    private val system = mapOf<String, Any>("role" to "system", "content" to "SYSTEM")

    private fun user(step: Int, extra: String = "") = mapOf<String, Any>("role" to "user", "content" to listOf(
        mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/jpeg;base64,IMG$step")),
        mapOf("type" to "text", "text" to "${extra}REQUEST\n### Current UI Elements ###\nSCREEN$step"),
    ))

    private fun turns(n: Int, words: Map<Int, List<String>> = emptyMap(), results: Map<Int, String> = emptyMap()) =
        (1..n).map { step ->
            Turn(step, user(step, words[step].orEmpty().joinToString("") { "$it\n" }), words[step].orEmpty()).apply {
                // The newest turn is still waiting for the model.
                if (step < n) {
                    assistant = mapOf("role" to "assistant", "content" to """{"action_type":"tap","x":$step}""")
                    action = "tap x=$step y=1"
                    result = results[step] ?: "done"
                }
            }
        }

    private fun text(msg: Map<String, Any>): String = when (val c = msg["content"]) {
        is String -> c
        is List<*> -> c.joinToString("\n") { (it as Map<*, *>)["text"]?.toString().orEmpty() }
        else -> ""
    }

    private fun images(messages: List<Map<String, Any>>) = messages.sumOf { m ->
        (m["content"] as? List<*>)?.count { (it as Map<*, *>)["type"] == "image_url" } ?: 0
    }

    private fun assertAlternates(messages: List<Map<String, Any>>) {
        assertEquals("system", messages[0]["role"])
        messages.drop(1).forEachIndexed { i, m -> assertEquals(if (i % 2 == 0) "user" else "assistant", m["role"]) }
    }

    @Test fun threeTurnsAreSentInFull() {
        val out = HistoryWindow.build(system, turns(3))
        assertEquals(1 + 3 + 2, out.size)
        assertAlternates(out)
        assertTrue(out.none { text(it).contains(HistoryWindow.EARLIER_HEADER) })
        (1..3).forEach { s -> assertTrue(out.any { text(it).contains("SCREEN$s") }) }
        assertEquals(1, images(out))
        assertTrue((out.last()["content"] as List<*>).any { (it as Map<*, *>)["type"] == "image_url" })
    }

    @Test fun nineTurnsCutTheOldestToOneLine() {
        val out = HistoryWindow.build(system, turns(9))
        assertEquals(1 + 8 + 7, out.size) // system, 8 users, 7 answers; the one-line summary sits inside the first user
        assertAlternates(out)
        val all = out.joinToString("\n") { text(it) }
        assertFalse(all.contains("SCREEN1"))
        assertTrue(text(out[1]).startsWith(HistoryWindow.EARLIER_HEADER + "Step 1: tap x=1 y=1 -> done\n"))
        (2..9).forEach { s -> assertTrue(all.contains("SCREEN$s")) }
        assertEquals(1, images(out))
        assertEquals("SYSTEM", out[0]["content"])
    }

    @Test fun thirtyTurnsKeepEightFullAndTwentyTwoLines() {
        val out = HistoryWindow.build(system, turns(30))
        assertAlternates(out)
        assertEquals(1 + 8 + 7, out.size)
        val summary = text(out[1]).substringBefore("\n\n")
        assertEquals(1 + 22, summary.lines().size)
        (1..22).forEach { s -> assertTrue(summary.contains("Step $s: tap x=$s y=1 -> done")) }
        assertFalse(out.joinToString { text(it) }.contains("SCREEN22"))
        assertTrue(text(out.last()).contains("SCREEN30"))
        assertTrue(text(out.last()).contains("REQUEST"))
        assertEquals(1, images(out))
    }

    @Test fun ownerAnswersAreNeverDropped() {
        val out = HistoryWindow.build(system, turns(30, words = mapOf(3 to listOf("Owner answered: the beach ones"))))
        assertTrue(text(out[1]).contains("Step 3: Owner answered: the beach ones"))
    }

    @Test fun approvalEditIsNeverDropped() {
        val t = turns(20, words = mapOf(5 to listOf("Owner added: send it to Sam instead")),
            results = mapOf(4 to "Changed by the user"))
        val summary = text(HistoryWindow.build(system, t)[1])
        assertTrue(summary.contains("Step 4: tap x=4 y=1 -> Changed by the user"))
        assertTrue(summary.contains("Step 5: Owner added: send it to Sam instead"))
    }

    @Test fun describeIsOneLine() {
        assertEquals("type_text text=\"hello world\"",
            HistoryWindow.describe(mapOf("action_type" to "type_text", "thought" to "x", "text" to "hello\nworld")))
        assertEquals("tap x=500 y=300", HistoryWindow.describe(mapOf("action_type" to "tap", "x" to 500, "y" to 300)))
    }
}
