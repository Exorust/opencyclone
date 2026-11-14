package ai.opencyclone.ui.voice

import kotlin.math.PI
import kotlin.math.sin

/**
 * Word timing for the speaking screen. The speech engine reports the word it is saying through
 * `onRangeStart`; engines that never call it get a timing estimated from the speech rate.
 */
object SpeechTiming {
    /** Estimated speaking speed at rate 1.0. */
    const val WORDS_PER_MINUTE = 160
    /** Without an `onRangeStart` this long after `onStart`, the estimate takes over. */
    const val RANGE_WAIT_MS = 300L

    /** Milliseconds for one word at [rate] (1.0 is normal; 375 ms). */
    fun msPerWord(rate: Float): Long {
        val r = if (rate > 0f) rate else 1f
        return (60_000.0 / (WORDS_PER_MINUTE * r)).toLong().coerceAtLeast(1)
    }

    /**
     * The word being spoken [elapsedMs] after the speech started, by the estimate: 0 for the first word,
     * [wordCount] when every word has been spoken.
     */
    fun fallbackWordIndex(elapsedMs: Long, wordCount: Int, rate: Float): Int {
        if (wordCount <= 0) return 0
        if (elapsedMs < 0) return 0
        return (elapsedMs / msPerWord(rate)).toInt().coerceAtMost(wordCount)
    }

    /** The index of the word that contains or follows character [charStart], from [ranges] of the words. */
    fun wordIndexForChar(ranges: List<IntRange>, charStart: Int): Int {
        val i = ranges.indexOfFirst { charStart <= it.last }
        return if (i < 0) ranges.size else i
    }

    /**
     * A level for the wave while speaking, from the time into the current word: it rises and falls once
     * per word, with a height that differs from word to word.
     */
    fun syntheticLevel(msIntoWord: Long, msPerWord: Long, wordIndex: Int): Float {
        if (msPerWord <= 0 || msIntoWord < 0 || msIntoWord >= msPerWord) return 0.1f
        val shape = sin(PI * msIntoWord / msPerWord).toFloat()
        val height = 0.55f + 0.45f * (((wordIndex * 7919) % 100) / 100f)
        return (0.1f + 0.9f * shape * height).coerceIn(0f, 1f)
    }
}
