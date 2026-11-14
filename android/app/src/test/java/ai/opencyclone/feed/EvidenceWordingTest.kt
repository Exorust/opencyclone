package ai.opencyclone.feed

import ai.opencyclone.action.Action
import ai.opencyclone.gate.ApprovalCard
import ai.opencyclone.gate.HeldAction
import ai.opencyclone.gate.HoldReason
import ai.opencyclone.gate.ScreenFacts
import ai.opencyclone.tasks.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lane N5: heading, status, receipt, and notification wording for every form of evidence; emphasis; waiting goals. */
class EvidenceWordingTest {
    private fun held(title: String? = "Alex", count: Int? = null, noun: String? = null, text: String? = null) =
        HeldAction(Action.Tap(5, 5), HoldReason.COMMIT_WORD, "Send", null, 5, 5,
            ScreenFacts("com.whatsapp", title, count, composeText = text, attachmentNoun = noun))

    private fun status(count: Int? = null, title: String? = "Alex", noun: String? = null, hasText: Boolean = false) =
        StatusSentence.line(StatusSentence.Approval(count, title, "Send", noun, hasText), false, null, false, null)

    private val longTitle = "The family group with everyone from the wedding in Lisbon"

    @Test fun headings() {
        assertEquals("3 photos to Alex. Send them?", FeedText.heading(held(count = 3, noun = "photos"), "WhatsApp"))
        assertEquals("1 photo to Alex. Send it?", FeedText.heading(held(count = 1, noun = "photos"), "WhatsApp"))
        assertEquals("2 items to Alex. Send them?", FeedText.heading(held(count = 2), "WhatsApp"))
        assertEquals("1 file to Alex. Send it?", FeedText.heading(held(count = 1, noun = "files"), "WhatsApp"))
        assertEquals("Send this to Alex?", FeedText.heading(held(text = "See you at 6"), "WhatsApp"))
        // The count wins over the text when both are known.
        assertEquals("3 photos to Alex. Send them?", FeedText.heading(held(count = 3, noun = "photos", text = "Hi"), "WhatsApp"))
        assertEquals("Send in WhatsApp to Alex?", FeedText.heading(held(), "WhatsApp"))
        assertEquals("Send in WhatsApp?", FeedText.heading(held(title = null, count = 3, text = "Hi"), "WhatsApp"))
    }

    @Test fun approveNeedsOnlyTheTitle() {
        assertTrue(FeedText.canApprove(held(count = null)))
        assertTrue(FeedText.canApprove(held(text = "Hi")))
        assertFalse(FeedText.canApprove(held(title = null, count = 3, text = "Hi")))
    }

    @Test fun statusForms() {
        assertEquals("3 photos are ready to send.", status(3, noun = "photos").text)
        assertEquals("1 photo is ready to send.", status(1, noun = "photos").text)
        assertEquals("4 items are ready to send.", status(4).text)
        assertEquals("A message to Alex is ready.", status(hasText = true).text)
        assertEquals("Ready to send to Alex.", status().text)
        assertEquals("Waiting for your approval.", status(title = null, hasText = true).text)
    }

    @Test fun statusShortensTheTitleWithAnEllipsis() {
        listOf(status(hasText = true, title = longTitle), status(title = longTitle), status(999, longTitle, "photos")).forEach {
            assertTrue("${it.text} (${it.text.length})", it.text.length <= StatusSentence.MAX)
            assertTrue(it.text, it.text.endsWith("."))
        }
        val message = status(hasText = true, title = longTitle).text
        assertTrue(message, message.startsWith("A message to The family") && message.endsWith("… is ready."))
    }

    @Test fun statusEmphasis() {
        val clock: (Long) -> String = { "2:41" }
        assertEquals("3 photos".length, status(3, noun = "photos").emphasis!!.count())
        assertEquals(0, status(3, noun = "photos").emphasis!!.first)
        status(hasText = true).let { assertEquals("Alex", it.text.substring(it.emphasis!!)) }
        status().let { assertEquals("Alex", it.text.substring(it.emphasis!!)) }
        status(hasText = true, title = longTitle).let { assertTrue(it.text.substring(it.emphasis!!).endsWith("…")) }
        StatusSentence.line(null, false, null, false, 1L, clock).let {
            assertEquals("Handling your notifications until 2:41.", it.text)
            assertEquals("2:41", it.text.substring(it.emphasis!!))
        }
        assertNull(StatusSentence.line(null, false, null, false, null, clock).emphasis)
        assertEquals("All caught up.", StatusSentence.line(null, false, null, false, null, clock).text)
        assertNull(StatusSentence.line(null, true, null, false, null, clock).emphasis)
        assertNull(StatusSentence.line(null, false, "Picking the photos", true, null, clock).emphasis)
        assertNull(status(title = null).emphasis)
    }

    @Test fun receiptForms() {
        assertEquals("3 photos to Alex", FeedText.receiptDetail(3, "Alex", "WhatsApp", "photos"))
        assertEquals("1 photo to Alex", FeedText.receiptDetail(1, "Alex", "WhatsApp", "photos"))
        assertEquals("2 items to Alex", FeedText.receiptDetail(2, "Alex", "WhatsApp"))
        assertEquals("a message to Alex", FeedText.receiptDetail(null, "Alex", "WhatsApp", hasText = true))
        assertEquals("to Alex", FeedText.receiptDetail(null, "Alex", "WhatsApp"))
        assertEquals("WhatsApp", FeedText.receiptDetail(null, null, "WhatsApp", hasText = true))
    }

    @Test fun notificationMatchesTheHomePage() {
        listOf(held(count = 3, noun = "photos"), held(count = 1, noun = "files"), held(text = "See you")).forEach {
            assertEquals(FeedText.heading(it, "WhatsApp"), ApprovalCard.title(it, "WhatsApp"))
        }
        // The plain forms keep the notification wording the walk scenarios check.
        assertEquals("Send in WhatsApp to Alex", ApprovalCard.title(held(), "WhatsApp"))
        assertEquals("Send in WhatsApp", ApprovalCard.title(held(title = null, count = 2, text = "Hi"), "WhatsApp"))
        assertEquals("To: Alex  3 photos", ApprovalCard.text(held(count = 3, noun = "photos")))
        assertEquals("To: Alex\n“See you”", ApprovalCard.text(held(text = "See you")))
        assertEquals("Check the screen before approving.", ApprovalCard.text(held(title = null)))
    }

    @Test fun queuedGoalsOldestFirstAndCut() {
        fun t(id: Long, state: String, created: Long, goal: String) =
            TaskEntity(id = id, origin = "user", goal = goal, state = state, createdAt = created, updatedAt = created)
        val goals = LiveStateSource.queuedGoals(listOf(
            t(5, "queued", 30, "Third"),
            t(2, "running", 5, "Running now"),
            t(3, "queued", 10, "First " + "x".repeat(100)),
            t(4, "queued", 20, "Second"),
            t(1, "done", 1, "Old")))
        assertEquals(3, goals.size)
        assertTrue(goals[0].startsWith("First "))
        assertEquals(80, goals[0].length)
        assertTrue(goals[0].endsWith("…"))
        assertEquals(listOf("Second", "Third"), goals.drop(1))
    }
}
