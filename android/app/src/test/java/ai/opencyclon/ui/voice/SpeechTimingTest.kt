package ai.opencyclon.ui.voice

import ai.opencyclon.ui.widget.WordRevealTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTimingTest {

    @Test fun oneWordTakes375MsAtNormalRate() {
        assertEquals(160, SpeechTiming.WORDS_PER_MINUTE)
        assertEquals(300L, SpeechTiming.RANGE_WAIT_MS)
        assertEquals(375L, SpeechTiming.msPerWord(1f))
        assertEquals(187L, SpeechTiming.msPerWord(2f))
        assertEquals(750L, SpeechTiming.msPerWord(0.5f))
        assertEquals(375L, SpeechTiming.msPerWord(0f)) // an unset rate counts as normal
    }

    @Test fun fallbackAdvancesOneWordPerWordTime() {
        assertEquals(0, SpeechTiming.fallbackWordIndex(0, 6, 1f))
        assertEquals(0, SpeechTiming.fallbackWordIndex(374, 6, 1f))
        assertEquals(1, SpeechTiming.fallbackWordIndex(375, 6, 1f))
        assertEquals(3, SpeechTiming.fallbackWordIndex(1200, 6, 1f))
        assertEquals(6, SpeechTiming.fallbackWordIndex(60_000, 6, 1f)) // done: every word spoken
        assertEquals(2, SpeechTiming.fallbackWordIndex(400, 6, 2f))
        assertEquals(0, SpeechTiming.fallbackWordIndex(-50, 6, 1f))
        assertEquals(0, SpeechTiming.fallbackWordIndex(1000, 0, 1f))
    }

    @Test fun rangeStartMapsToTheWordAtThatCharacter() {
        val ranges = WordRevealTextView.wordRanges("For your Barcelona trip")
        assertEquals(0, SpeechTiming.wordIndexForChar(ranges, 0))
        assertEquals(1, SpeechTiming.wordIndexForChar(ranges, 4))
        assertEquals(1, SpeechTiming.wordIndexForChar(ranges, 3)) // the space before a word
        assertEquals(2, SpeechTiming.wordIndexForChar(ranges, 9))
        assertEquals(4, SpeechTiming.wordIndexForChar(ranges, 99))
    }

    @Test fun syntheticLevelRisesAndFallsWithinAWord() {
        val mid = SpeechTiming.syntheticLevel(187, 375, 0)
        val edge = SpeechTiming.syntheticLevel(5, 375, 0)
        assertTrue(mid > edge)
        assertTrue(mid in 0f..1f)
        assertEquals(0.1f, SpeechTiming.syntheticLevel(400, 375, 0), 0f)
    }
}
