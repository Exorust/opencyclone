package ai.opencyclone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyPauseTest {
    private val loud = 0.1f
    private val quiet = 0.001f

    /** Feeds 100 ms blocks until the rule ends the turn; returns the milliseconds that took. */
    private fun msUntilEnd(rule: EnergyPause, level: (Int) -> Float): Long {
        var ms = 0L
        var i = 0
        while (ms < 60_000) {
            ms += 100
            if (rule.block(level(i++), 100)) return ms
        }
        error("never ended")
    }

    @Test fun theRulesAreThePhoneModelsRules() {
        assertEquals(1.2f, SherpaOnnxSpeechInputEngine.PAUSE_AFTER_WORDS_SECONDS, 0f)
        assertEquals(8f, SherpaOnnxSpeechInputEngine.NOTHING_HEARD_SECONDS, 0f)
        assertEquals(20f, SherpaOnnxSpeechInputEngine.MAX_TURN_SECONDS, 0f)
    }

    @Test fun nothingSaidEndsAfterEightSeconds() {
        val rule = EnergyPause()
        assertEquals(8000, msUntilEnd(rule) { quiet })
        assertFalse(rule.heardSpeech)
    }

    @Test fun aPauseOfOnePointTwoSecondsAfterSpeechEndsTheTurn() {
        val rule = EnergyPause()
        // 2 s of speech, then quiet: ends 1.2 s after the last loud block.
        assertEquals(3200, msUntilEnd(rule) { if (it < 20) loud else quiet })
        assertTrue(rule.heardSpeech)
    }

    @Test fun aShortGapBetweenWordsDoesNotEndTheTurn() {
        val rule = EnergyPause()
        // speech 1 s, gap 1.1 s, speech 1 s, then quiet.
        val ms = msUntilEnd(rule) { i -> if (i < 10 || i in 21..30) loud else quiet }
        assertEquals(4300, ms)
    }

    @Test fun twentySecondsIsTheMost() {
        assertEquals(20_000, msUntilEnd(EnergyPause()) { loud })
    }

    @Test fun speechAfterLongSilenceStillCountsBeforeEightSeconds() {
        val rule = EnergyPause()
        // quiet 7 s, then speech 1 s, then quiet: the nothing-said rule no longer applies.
        assertEquals(9200, msUntilEnd(rule) { i -> if (i in 70..79) loud else quiet })
    }
}
