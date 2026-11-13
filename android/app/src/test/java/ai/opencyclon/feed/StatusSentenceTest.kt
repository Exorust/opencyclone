package ai.opencyclon.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusSentenceTest {
    private val clock: (Long) -> String = { "2:41" }
    private fun of(approval: StatusSentence.Approval? = null, question: Boolean = false, caption: String? = null,
                   running: Boolean = false, window: Long? = null) =
        StatusSentence.of(approval, question, caption, running, window, clock)

    @Test fun idle() = assertEquals("All caught up.", of())

    @Test fun windowActive() = assertEquals("Handling your notifications until 2:41.", of(window = 1L))

    @Test fun runningWithAndWithoutCaption() {
        assertEquals("Picking the photos.", of(caption = "Picking the photos", running = true, window = 1L))
        assertEquals("Picking the photos.", of(caption = "Picking the photos.", running = true))
        assertEquals("Working on it.", of(running = true, window = 1L))
    }

    @Test fun questionBeatsRunning() = assertEquals("I have a question.", of(question = true, caption = "x", running = true, window = 1L))

    @Test fun approvalBeatsEverything() {
        assertEquals("9 photos are ready to send.",
            of(StatusSentence.Approval(9, "Alex", "Send", "photos"), question = true, running = true, window = 1L))
        assertEquals("Ready to send to Alex.", of(StatusSentence.Approval(null, "Alex", "Send"), running = true))
        assertEquals("Waiting for your approval.", of(StatusSentence.Approval(null, null, "Send")))
        assertEquals("1 photo is ready to send.", of(StatusSentence.Approval(1, "Alex", "Send", "photos")))
    }

    @Test fun neverLongerThan60AndAlwaysAFullStop() {
        val long = "A really very long name of a group chat that goes on and on"
        val samples = listOf(
            of(StatusSentence.Approval(null, long, "Send")),
            of(caption = long + long, running = true),
            of(window = 1L),
            of(),
            of(question = true),
            of(StatusSentence.Approval(123456, long, "Place order")),
        )
        samples.forEach {
            assertTrue("$it (${it.length})", it.length <= StatusSentence.MAX)
            assertTrue(it, it.endsWith("."))
        }
    }
}
