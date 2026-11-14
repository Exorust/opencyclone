package ai.opencyclone.gate

import ai.opencyclone.action.Action
import org.junit.Assert.*
import org.junit.Test

class ApprovalCardTitleTest {
    private fun held(reason: HoldReason, title: String?, label: String? = "Send", count: Int? = null) =
        HeldAction(Action.Tap(10, 20), reason, label, null, 10, 20, ScreenFacts("com.whatsapp", title, count))

    @Test fun `commit word with title`() =
        assertEquals("Send in WhatsApp to Alex Test", ApprovalCard.title(held(HoldReason.COMMIT_WORD, "Alex Test"), "WhatsApp"))

    @Test fun `commit word without title`() =
        assertEquals("Send in WhatsApp", ApprovalCard.title(held(HoldReason.COMMIT_WORD, null), "WhatsApp"))

    @Test fun `unreadable target with title`() =
        assertEquals("Tap on an unreadable spot in Test App",
            ApprovalCard.title(held(HoldReason.UNREADABLE_TARGET, "Counter", null), "Test App"))

    @Test fun `unreadable target without title`() =
        assertEquals("Tap on an unreadable spot in Test App",
            ApprovalCard.title(held(HoldReason.UNREADABLE_TARGET, null, null), "Test App"))

    @Test fun `enter in text field with title`() =
        assertEquals("Submit the typed text in WhatsApp",
            ApprovalCard.title(held(HoldReason.ENTER_IN_TEXT_FIELD, "Alex Test", "Message"), "WhatsApp"))

    @Test fun `enter in text field without title`() =
        assertEquals("Submit the typed text in WhatsApp",
            ApprovalCard.title(held(HoldReason.ENTER_IN_TEXT_FIELD, null, "Message"), "WhatsApp"))

    @Test fun `commit word with title and known item count`() =
        assertEquals("3 items to Alex Test. Send them?", ApprovalCard.title(held(HoldReason.COMMIT_WORD, "Alex Test", count = 3), "WhatsApp"))

    // No title: the count alone does not name a recipient, so the question names the app (as the home page does).
    @Test fun `commit word with known item count and no title`() =
        assertEquals("Send in WhatsApp", ApprovalCard.title(held(HoldReason.COMMIT_WORD, null, count = 3), "WhatsApp"))

    @Test fun `unreadable target ignores the item count`() =
        assertEquals("Tap on an unreadable spot in Test App",
            ApprovalCard.title(held(HoldReason.UNREADABLE_TARGET, "Counter", null, count = 2), "Test App"))
}
