package ai.opencyclone.voice

import ai.opencyclone.voice.SherpaOnnxSpeechInputEngine.Companion.errorMessage
import ai.opencyclone.voice.SherpaOnnxSpeechInputEngine.Companion.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SherpaListeningLogicTest {

    @Test fun theModelsCapitalsBecomeASentence() {
        assertEquals("Open the clock app", normalize("OPEN THE CLOCK APP"))
        assertEquals("Can I see my calendar", normalize("CAN I SEE MY CALENDAR"))
        assertEquals("", normalize(""))
        // Text that already has lower case is left alone.
        assertEquals("Call Sam", normalize("Call Sam"))
    }

    // Defect: the library's rule 1 ended a turn after 2.4 s of silence, before many people begin to speak.
    @Test fun aTurnWithNothingSaidWaitsEightSecondsAndAPauseAfterWordsEndsIt() {
        val c = SherpaOnnxSpeechInputEngine.endpointConfig()
        assertFalse(c.rule1.mustContainNonSilence)
        assertEquals(8f, c.rule1.minTrailingSilence, 0f)
        assertTrue(c.rule2.mustContainNonSilence)
        assertEquals(1.2f, c.rule2.minTrailingSilence, 0f)
        assertEquals(20f, c.rule3.minUtteranceLength, 0f)
    }

    @Test fun failuresBecomeTheScreensErrors() {
        assertEquals(VoiceErrors.MIC_BUSY, errorMessage(IOException("Audio recording did not start")))
        assertEquals(VoiceErrors.MIC_BUSY, errorMessage(IOException("Audio recording initialization failed")))
        assertEquals(VoiceErrors.NO_PERMISSION, errorMessage(SecurityException("no")))
        assertEquals(VoiceErrors.FAILED, errorMessage(IOException("Audio recording error -3")))
        assertEquals(VoiceErrors.FAILED, errorMessage(IllegalStateException()))
    }

    @Test fun theTurnLogReportsNewPartialsAndEachMilestoneOnce() {
        val lines = mutableListOf<String>()
        val turn = TurnLog { lines += it }
        assertFalse(turn.block(0.001f, "", false).partialChanged)
        assertTrue(turn.block(0.1f, "OPEN", false).partialChanged)
        assertFalse(turn.block(0.1f, "OPEN", false).partialChanged)
        assertTrue(turn.block(0.1f, "OPEN THE", false).partialChanged)
        assertTrue(turn.block(0.001f, "OPEN THE", true).endpoint)
        assertEquals(1, lines.count { it.startsWith("First non-silent block") })
        assertEquals(1, lines.count { it.startsWith("First partial result") })
        assertEquals(1, lines.count { it.startsWith("Pause detected") })
        // Lengths only, never the words.
        assertTrue(lines.none { it.contains("OPEN") })
    }
}
