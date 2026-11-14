package ai.opencyclone

import ai.opencyclone.action.Action
import ai.opencyclone.engine.TaskEnd
import ai.opencyclone.feed.FeedText
import ai.opencyclone.feed.StatusSentence
import ai.opencyclone.gate.ApprovalCard
import ai.opencyclone.gate.HeldAction
import ai.opencyclone.gate.HoldReason
import ai.opencyclone.gate.ScreenFacts
import ai.opencyclone.ui.home.StartChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lane X1's words and rules as pure functions: status priority, pairing rule, notification texts, labels. */
class X1WordsTest {

    // ── Status sentence (item 1) ──────────────────────────────────────────

    private fun status(approval: StatusSentence.Approval? = null, question: Boolean = false, running: Boolean = false,
                       window: Long? = null, notConnected: Boolean = false) =
        StatusSentence.of(approval, question, "Picking the photos", running, window, { "2:41" }, notConnected)

    @Test fun notConnectedComesRightAfterAWaitingApproval() {
        assertEquals("Not connected. Tap to reconnect.", status(notConnected = true))
        assertEquals("Not connected. Tap to reconnect.",
            status(question = true, running = true, window = 1L, notConnected = true))
        assertEquals("Ready to send to Alex.",
            status(StatusSentence.Approval(null, "Alex", "Send"), question = true, notConnected = true))
        assertEquals("Picking the photos.", status(running = true))
        assertTrue(StatusSentence.NOT_CONNECTED.length <= StatusSentence.MAX)
    }

    // ── Pairing screen (item 2) ───────────────────────────────────────────

    private val hour = 60 * 60 * 1000L

    @Test fun pairingWaitsTwelveHoursAfterTheOwnerLeftIt() {
        val left = 1_000_000_000L
        assertTrue("never left", StartChecks.mayOpenPairing(0L, ownerDisconnected = false, now = left))
        assertFalse(StartChecks.mayOpenPairing(left, false, left + 1))
        assertFalse(StartChecks.mayOpenPairing(left, false, left + 12 * hour - 1))
        assertTrue(StartChecks.mayOpenPairing(left, false, left + 12 * hour))
        assertEquals(12 * hour, StartChecks.PAIRING_QUIET_MS)
    }

    @Test fun ownerDisconnectStopsTheAutomaticPairing() {
        assertFalse(StartChecks.mayOpenPairing(0L, ownerDisconnected = true, now = 5L))
        assertFalse(StartChecks.mayOpenPairing(1L, ownerDisconnected = true, now = 100 * hour))
    }

    // ── Approval notification (item 3) ────────────────────────────────────

    private fun held(label: String?, title: String? = "Alex", action: Action = Action.Tap(10, 20)) =
        HeldAction(action, HoldReason.COMMIT_WORD, label, null, 10, 20, ScreenFacts("com.whatsapp", title, null))

    @Test fun approveButtonIsTheActionWordWithItsTarget() {
        assertEquals("Send to Alex", ApprovalCard.approveLabel(held("SEND")))
        assertEquals("Send to Alex", ApprovalCard.approveLabel(held("Send")))
        assertEquals("Approve", ApprovalCard.approveLabel(held(null)))
        assertEquals("Approve", ApprovalCard.approveLabel(held(" ")))
        assertEquals("Not now", ApprovalCard.ACTION_REJECT)
        assertEquals("Change", ApprovalCard.ACTION_EDIT)
    }

    @Test fun approvalIsQuietWhileTheHomePageIsResumed() {
        assertEquals(ApprovalCard.QUIET_CHANNEL_ID, ApprovalCard.channelFor(homeResumed = true))
        assertEquals(ApprovalCard.CHANNEL_ID, ApprovalCard.channelFor(homeResumed = false))
    }

    // ── Working and completion notifications (item 5) ─────────────────────

    @Test fun workingNotificationTexts() {
        assertEquals("Working: Send Alex the photos", AgentService.workingText("  Send Alex\nthe photos "))
        val long = AgentService.workingText("word ".repeat(40))
        assertTrue(long.startsWith("Working: word"))
        assertTrue(long.endsWith("…"))
        assertEquals(AgentService.WORKING_PREFIX.length + AgentService.MAX_GOAL, long.length)
        assertEquals("App is ready", AgentService.readyText("App"))
    }

    @Test fun completionNotificationUsesTheResultSentence() {
        assertEquals("Sent the photos to Alex.", AgentService.completionText(TaskEnd.Done("Sent the photos to Alex. Bye")))
        assertEquals(FeedText.CONNECTION_LOST, AgentService.completionText(TaskEnd.Failed(FeedText.CONNECTION_LOST)))
        assertNull(AgentService.completionText(TaskEnd.Cancelled(null)))
        assertNull(AgentService.completionText(TaskEnd.Rejected))
        val all = listOf(AgentService.completionText(TaskEnd.Done(null))!!, AgentService.workingText("x"),
            AgentService.readyText("App"), AgentService.NOT_CONNECTED_NOTICE)
        // No emoji, no exclamation mark (UI.md section 6).
        all.forEach { t -> assertTrue(t, t.none { it == '!' || Character.isSurrogate(it) }) }
    }

    // ── Failure sentences (items 1 and 6) ─────────────────────────────────

    @Test fun failureSentencesPassThroughTheThreadUnchanged() {
        assertEquals("I lost the connection to this phone. Nothing more was sent.", FeedText.CONNECTION_LOST)
        assertEquals("I do not open Bank. It is on your protected list.", FeedText.protectedApp("Bank"))
        assertEquals("I cannot read this screen in Bank, so I stopped.", FeedText.secureScreen("Bank"))
        for (s in listOf(FeedText.CONNECTION_LOST, FeedText.protectedApp("Bank"), FeedText.secureScreen("Bank"))) {
            assertEquals(s, FeedText.failure(s))
        }
    }
}
