package ai.opencyclone.ui.widget

import ai.opencyclone.R
import ai.opencyclone.ui.theme.Motion
import android.content.Context
import android.graphics.Color
import android.os.Looper
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import org.junit.After
import org.junit.Before
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WordRevealTextViewTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val ink get() = ContextCompat.getColor(context, R.color.ink)
    private val ink3 get() = ContextCompat.getColor(context, R.color.ink_3)

    // Frames follow the test clock instead of advancing it on their own.
    @Before fun pauseFrames() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(10))
    }

    @After fun resetFrames() = ShadowChoreographer.reset()

    private fun view() = WordRevealTextView(context).apply { setTextColor(ink) }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** The colour the spans give character [index], applied in the order the text layout uses. */
    private fun drawnColor(v: WordRevealTextView, index: Int): Int {
        val s = v.text as Spanned
        val tp = TextPaint().apply { color = v.currentTextColor }
        s.getSpans(index, index + 1, CharacterStyle::class.java).forEach { it.updateDrawState(tp) }
        return tp.color
    }

    @Test fun progressFollowsTheTiming() {
        assertEquals(100L, Motion.WORD_MS)
        assertEquals(250L, Motion.WORD_COLOUR_MS)
        assertEquals(0f, WordRevealTextView.progressAt(0, 0)!!, 0f)
        assertNull(WordRevealTextView.progressAt(1, 99))
        assertEquals(0f, WordRevealTextView.progressAt(1, 100)!!, 0f)
        assertEquals(0.5f, WordRevealTextView.progressAt(1, 225)!!, 0.001f)
        assertEquals(1f, WordRevealTextView.progressAt(1, 350)!!, 0f)
        // the long-sentence rate
        assertNull(WordRevealTextView.progressAt(2, 139, wordMs = 70))
        assertEquals(0f, WordRevealTextView.progressAt(2, 140, wordMs = 70)!!, 0f)
        assertEquals(450L, WordRevealTextView.revealDurationMs(3))
        assertEquals(390L, WordRevealTextView.revealDurationMs(3, wordMs = 70))
        assertEquals(0L, WordRevealTextView.revealDurationMs(0))
    }

    @Test fun wordRangesSplitOnSpaces() {
        assertEquals(listOf(0..1, 4..6, 8..12), WordRevealTextView.wordRanges("Hi  you there"))
        assertEquals(listOf(0..3, 5..9), WordRevealTextView.wordRanges("Sent Alex."))
    }

    @Test fun revealShowsWordsOneByOne() {
        val v = view()
        var ended = 0
        v.onRevealEnd = { ended++ }
        v.reveal("one two three")
        assertTrue(v.isRevealing)
        advance(50)
        val early = v.wordProgress()
        assertTrue("early $early", early[0]!! > 0f && early[0]!! < 1f)
        assertNull(early[1])
        assertNull(early[2])
        // a hidden word draws fully transparent
        assertEquals(0, Color.alpha(drawnColor(v, 4)))
        advance(100) // ~150 ms: word 2 has started
        val mid = v.wordProgress()
        assertTrue(mid[1] != null)
        assertNull(mid[2])
        advance(400)
        assertEquals(listOf(1f, 1f, 1f), v.wordProgress())
        assertFalse(v.isRevealing)
        assertEquals(1, ended)
        assertEquals(ink, drawnColor(v, 0))
    }

    @Test fun newWordStartsAtInk3() {
        val v = view()
        v.reveal("alpha beta")
        advance(1)
        // first word has just started: close to ink-3
        val c = drawnColor(v, 0)
        assertTrue(Math.abs(Color.red(c) - Color.red(ink3)) <= 3)
    }

    @Test fun showAllCompletesAtOnce() {
        val v = view()
        var ended = 0
        v.onRevealEnd = { ended++ }
        v.reveal("one two three four")
        advance(20)
        v.showAll()
        assertEquals(listOf(1f, 1f, 1f, 1f), v.wordProgress())
        assertFalse(v.isRevealing)
        assertEquals(1, ended)
        advance(1000)
        assertEquals(1, ended)
        v.showAll("new text")
        assertEquals("new text", v.text.toString())
        assertEquals(listOf(1f, 1f), v.wordProgress())
    }

    @Test fun unconfirmedRangeDrawsInInk3AndClears() {
        val v = view()
        v.showAll("I want the photos")
        v.markUnconfirmed(7, 17)
        assertEquals(7 until 17, v.unconfirmedRange())
        assertEquals(ink, drawnColor(v, 0))
        assertEquals(ink3, drawnColor(v, 8))
        assertEquals(ink3, drawnColor(v, 16))
        v.markUnconfirmed(11, 17) // replaces the earlier mark
        assertEquals(11 until 17, v.unconfirmedRange())
        assertEquals(ink, drawnColor(v, 8))
        v.clearUnconfirmed()
        assertNull(v.unconfirmedRange())
        assertEquals(ink, drawnColor(v, 16))
    }

    @Test fun unconfirmedRangeIsClampedToTheText() {
        val v = view()
        v.showAll("short")
        v.markUnconfirmed(2, 99)
        assertEquals(2 until 5, v.unconfirmedRange())
    }
}
