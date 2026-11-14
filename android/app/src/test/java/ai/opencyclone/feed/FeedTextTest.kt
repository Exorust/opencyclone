package ai.opencyclone.feed

import ai.opencyclone.action.Action
import ai.opencyclone.gate.HeldAction
import ai.opencyclone.gate.HoldReason
import ai.opencyclone.gate.ScreenFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTextTest {
    private fun held(label: String? = "SEND", title: String? = "Alex", count: Int? = null,
                     reason: HoldReason = HoldReason.COMMIT_WORD, pkg: String? = "com.whatsapp") =
        HeldAction(Action.Tap(500, 900), reason, label, intArrayOf(0, 0, 10, 10), 5, 5,
            ScreenFacts(pkg, title, count, attachmentNoun = count?.let { "photos" }))

    @Test fun headingNamesTheRecipientInEveryForm() {
        assertEquals("9 photos to Alex. Send them?", FeedText.heading(held(count = 9), "WhatsApp"))
        assertEquals("1 photo to Alex. Send it?", FeedText.heading(held(count = 1), "WhatsApp"))
        assertEquals("Send in WhatsApp to Alex?", FeedText.heading(held(), "WhatsApp"))
        assertEquals("Send to Alex?", FeedText.heading(held(), null))
        assertEquals("Send in WhatsApp?", FeedText.heading(held(title = null), "WhatsApp"))
        assertEquals("Tap in WhatsApp?", FeedText.heading(held(label = null, title = null, reason = HoldReason.UNREADABLE_TARGET), "WhatsApp"))
        val tool = HeldAction(Action.CallTool("calendar", "create_event", "{}"), HoldReason.TOOL_CALL, "calendar.create_event",
            null, null, null, ScreenFacts(null, null, null))
        assertEquals("Use create_event from calendar?", FeedText.heading(tool, null))
    }

    @Test fun actionWordNamesTheTargetWhenKnown() {
        assertEquals("Send to Alex", FeedText.actionWord(held()))
        assertEquals("Send", FeedText.actionWord(held(title = null)))
        assertEquals("Place order", FeedText.actionWord(held(label = "PLACE ORDER", title = null)))
    }

    @Test fun approveNeedsTheTitleButNotTheCount() {
        assertTrue(FeedText.canApprove(held(count = null)))
        assertFalse(FeedText.canApprove(held(title = null, count = 3)))
    }

    @Test fun receiptVerbTable() {
        mapOf("Send" to "Sent", "SEND" to "Sent", "Send message" to "Sent", "Post" to "Posted", "Pay" to "Paid",
            "Buy now" to "Bought", "Delete" to "Deleted", "Place order" to "Ordered", "Confirm" to "Confirmed",
            "Install" to "Installed", "Submit" to "Submitted", "Checkout" to "Checked out", "Wave" to "Done",
            "" to "Done").forEach { (label, past) -> assertEquals(label, past, FeedText.receiptVerb(label)) }
        assertEquals("Done", FeedText.receiptVerb(null))
    }

    @Test fun receiptDetailForms() {
        assertEquals("9 photos to Alex", FeedText.receiptDetail(9, "Alex", "WhatsApp", "photos"))
        assertEquals("to Alex", FeedText.receiptDetail(null, "Alex", "WhatsApp"))
        assertEquals("WhatsApp", FeedText.receiptDetail(null, null, "WhatsApp"))
    }

    @Test fun resultIsTheFirstSentenceAtMost120Characters() {
        assertEquals("Sent Alex 9 photos on WhatsApp.", FeedText.firstSentence("Sent Alex 9 photos on WhatsApp. Then I closed the app."))
        assertEquals("Done.", FeedText.firstSentence("  "))
        assertEquals("Opened the settings.", FeedText.firstSentence("Opened the settings"))
        val long = FeedText.firstSentence("word ".repeat(60))
        assertTrue(long, long.length <= 120)
        assertTrue(long.endsWith("."))
        assertEquals("Done.", FeedText.firstSentence(null))
    }

    @Test fun sentencesEndWithAFullStopAndNoExclamation() {
        assertEquals("Rule saved: hide x.", FeedText.asSentence("Rule saved: hide x"))
        assertEquals("All done.", FeedText.asSentence("All done!"))
        assertEquals("Which one?", FeedText.asSentence("Which one?"))
        assertEquals("a\nb", FeedText.asSentence("a\nb"))
    }

    @Test fun failuresHaveNoErrorCodes() {
        // An LLM error without a typed tag (none of the clients sends one now) gets the general model sentence.
        assertEquals("The model gave an error. Try again.", FeedText.failure("LLM error: HTTP 401 unauthorized"))
        assertEquals("I could not finish this task.", FeedText.failure(null))
        assertEquals("The app stopped before this task finished.", FeedText.failure("The app stopped before this task finished."))
        assertEquals("Something broke.", FeedText.failure("Something broke"))
    }

    @Test fun typedModelErrorsGetHonestSentences() {
        fun tagged(kind: ai.opencyclone.llm.ModelErrorKind, code: Int? = null, model: String? = null) =
            "LLM error: ChatGPT error ($code): {\"detail\":\"x\"} " + ai.opencyclone.llm.ModelError(kind, model, code).tag()
        assertEquals("I could not reach the model. Check the connection.",
            FeedText.failure(tagged(ai.opencyclone.llm.ModelErrorKind.UNREACHABLE)))
        assertEquals("This account cannot use the model gpt-5.5. Choose another in Settings, Model.",
            FeedText.failure(tagged(ai.opencyclone.llm.ModelErrorKind.MODEL_NOT_FOUND, 400, "gpt-5.5")))
        assertEquals("The model refused the sign-in. Sign in again in Set up.",
            FeedText.failure(tagged(ai.opencyclone.llm.ModelErrorKind.AUTH, 401)))
        assertEquals("The model's usage limit is reached. Try again later.",
            FeedText.failure(tagged(ai.opencyclone.llm.ModelErrorKind.RATE_LIMIT, 429)))
        assertEquals("The model service has a problem. Try again in a minute.",
            FeedText.failure(tagged(ai.opencyclone.llm.ModelErrorKind.SERVER, 502)))
        assertEquals("The model gave an error. Try again.",
            FeedText.failure(tagged(ai.opencyclone.llm.ModelErrorKind.OTHER, 400)))
        for (kind in ai.opencyclone.llm.ModelErrorKind.values()) {
            val sentence = FeedText.failure(tagged(kind, 418, "m"))
            assertFalse(sentence, sentence.contains("{") || sentence.contains("418") || sentence.contains("!"))
        }
    }

    @Test fun triageFallbackNamesTheSender() {
        assertEquals("Alex asked for something. I'm working on it.", FeedText.triageFallback("Alex"))
        assertEquals("Someone asked for something. I'm working on it.", FeedText.triageFallback(" "))
    }
}
