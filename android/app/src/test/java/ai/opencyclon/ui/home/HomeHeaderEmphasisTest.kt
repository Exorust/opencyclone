package ai.opencyclon.ui.home

import ai.opencyclon.R
import ai.opencyclon.ui.widget.WordRevealTextView
import android.content.Context
import android.text.Spanned
import android.view.ContextThemeWrapper
import android.text.style.ForegroundColorSpan
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lane N5: the idle status draws its noun in `ink`; a working status is all `ink` with no span. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeHeaderEmphasisTest {
    private val context: Context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_OpenCyclon)
    private val root = LinearLayout(context).apply {
        addView(TextView(context).apply { id = R.id.header_time })
        addView(TextView(context).apply { id = R.id.header_date })
        addView(TextView(context).apply { id = R.id.header_greeting })
        addView(WordRevealTextView(context).apply { id = R.id.header_status })
    }
    private val header = HomeHeader(root)
    private val ink = ContextCompat.getColor(context, R.color.ink)
    private val ink2 = ContextCompat.getColor(context, R.color.ink_2)

    private fun spans(): List<IntRange> {
        val s = header.status.text as? Spanned ?: return emptyList()
        return s.getSpans(0, s.length, ForegroundColorSpan::class.java)
            .filter { it.foregroundColor == ink }
            .map { s.getSpanStart(it) until s.getSpanEnd(it) }
    }

    @Test fun idleSentenceHasTheNounInInk() {
        header.setStatus("Handling your notifications until 2:41.", isWorking = false, emphasis = 34 until 38)
        assertEquals(ink2, header.status.currentTextColor)
        assertEquals(listOf(34 until 38), spans())
    }

    @Test fun workingSentenceHasNoSpan() {
        header.setStatus("A message to Sam is ready.", isWorking = true, emphasis = 13 until 16)
        assertEquals(ink, header.status.currentTextColor)
        assertTrue(spans().isEmpty())
    }

    @Test fun spanFollowsTheChangeBetweenIdleAndWorking() {
        header.setStatus("A message to Sam is ready.", isWorking = true, emphasis = 13 until 16)
        header.setStatus("A message to Sam is ready.", isWorking = false, emphasis = 13 until 16)
        assertEquals(listOf(13 until 16), spans())
        header.setStatus("A message to Sam is ready.", isWorking = true, emphasis = 13 until 16)
        assertTrue(spans().isEmpty())
    }

    @Test fun rangeOutsideTheTextIsIgnored() {
        header.setStatus("All caught up.", isWorking = false, emphasis = 10 until 40)
        assertTrue(spans().isEmpty())
        assertEquals("All caught up.", header.status.text.toString())
    }
}
