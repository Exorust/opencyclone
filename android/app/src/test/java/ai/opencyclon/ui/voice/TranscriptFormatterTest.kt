package ai.opencyclon.ui.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptFormatterTest {

    @Test fun firstPartialIsAllUnconfirmed() {
        val s = TranscriptFormatter.split("", "Handle")
        assertEquals("Handle", s.text)
        assertEquals("", s.confirmed)
        assertEquals("Handle", s.unconfirmed)
    }

    @Test fun growingTextConfirmsTheWordsBothResultsShare() {
        val s = TranscriptFormatter.split("Handle my", "Handle my notifications")
        assertEquals("Handle my", s.confirmed)
        assertEquals(" notifications", s.unconfirmed)
    }

    @Test fun theLastWordStaysUnconfirmedWhileItMayStillGrow() {
        val s = TranscriptFormatter.split("Handle my notif", "Handle my notifications for")
        assertEquals("Handle my", s.confirmed)
        assertEquals(" notifications for", s.unconfirmed)
        val same = TranscriptFormatter.split("Handle my", "Handle my")
        assertEquals("Handle", same.confirmed)
    }

    @Test fun aRevisedEarlierWordEndsTheConfirmedPart() {
        val s = TranscriptFormatter.split("Handle by notifications for", "Handle my notifications for the")
        assertEquals("Handle", s.confirmed)
        assertEquals(" my notifications for the", s.unconfirmed)
    }

    @Test fun emptyResultsGiveNothing() {
        assertEquals(TranscriptFormatter.Split("", 0), TranscriptFormatter.split("", ""))
        assertEquals(TranscriptFormatter.Split("", 0), TranscriptFormatter.split("Handle my", "   "))
    }

    @Test fun spacesAroundTheResultAreTrimmed() {
        val s = TranscriptFormatter.split(" Handle my ", "  Handle my notifications ")
        assertEquals("Handle my notifications", s.text)
        assertEquals("Handle my", s.confirmed)
    }

    @Test fun aFinalResultIsAllConfirmed() {
        val s = TranscriptFormatter.final(" Handle my notifications ")
        assertEquals("Handle my notifications", s.confirmed)
        assertEquals("", s.unconfirmed)
    }
}
