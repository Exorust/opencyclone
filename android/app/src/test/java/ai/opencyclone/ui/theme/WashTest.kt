package ai.opencyclone.ui.theme

import ai.opencyclone.R
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.TransitionDrawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WashTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun color(id: Int) = ContextCompat.getColor(context, id)

    @Test fun washColoursMatchUiSection11AndColorsXml() {
        for (w in Wash.values()) assertEquals("${w.name} base is the white page", Color.WHITE, w.base)
        assertEquals(color(R.color.wash_warm_base), Wash.Warm.base)
        assertEquals(color(R.color.wash_cool_base), Wash.Cool.base)
        assertEquals(color(R.color.wash_voice_base), Wash.Voice.base)

        assertEquals(Color.parseColor("#ECEBFA"), Wash.Warm.spots[0].color)
        assertEquals(color(R.color.wash_warm_peach), Wash.Warm.spots[0].color)

        assertEquals(Color.parseColor("#E2E4FB"), Wash.Cool.spots[0].color)
        assertEquals(Color.parseColor("#EEE3F8"), Wash.Cool.spots[1].color)
        assertEquals(color(R.color.wash_cool_violet), Wash.Cool.spots[0].color)
        assertEquals(color(R.color.wash_cool_middle), Wash.Cool.spots[1].color)

        assertEquals(Color.parseColor("#F1F0FA"), Wash.Voice.spots[0].color)
        assertEquals(color(R.color.wash_voice_green), Wash.Voice.spots[0].color)
    }

    @Test fun spotPositionsFollowTheTable() {
        // one area for Warm and Voice, two for Cool
        assertEquals(1, Wash.Warm.spots.size)
        assertEquals(2, Wash.Cool.spots.size)
        assertEquals(1, Wash.Voice.spots.size)
        // the main area of each wash is a band from the top edge
        for (w in Wash.values()) {
            assertTrue("${w.name} band", w.spots[0].band)
            assertEquals(0f, w.spots[0].cy, 0f)
        }
        // Cool: the second area at the upper right
        assertTrue(!Wash.Cool.spots[1].band && Wash.Cool.spots[1].cx > 0.5f && Wash.Cool.spots[1].cy < 0.5f)
        assertEquals(0.4f, Wash.FADE_OF_HEIGHT, 0f)
    }

    @Test fun pageIsWhiteBelowTheFade() {
        // Robolectric draws with a shadow canvas, so check the geometry: below 40% of the height no area reaches.
        for (w in Wash.values()) for (s in w.spots) assertTrue(s.cy + Wash.FADE_OF_HEIGHT <= 0.4f + 1e-6f)
    }

    @Test fun drawableKeepsAlphaForCrossFade() {
        val d = WashDrawable(Wash.Cool)
        d.setBounds(0, 0, 400, 800)
        assertEquals(255, d.alpha)
        d.alpha = 100
        assertEquals(100, d.alpha)
    }

    @Test fun crossFadeBetweenWashes() {
        val v = View(context)
        assertNull(v.currentWash)
        v.crossFadeWash(Wash.Warm) // no wash yet: set at once
        assertEquals(Wash.Warm, v.currentWash)
        assertTrue(v.background is WashDrawable)

        v.crossFadeWash(Wash.Cool)
        val t = v.background as TransitionDrawable
        assertEquals(2, t.numberOfLayers)
        assertEquals(Wash.Warm, (t.getDrawable(0) as WashDrawable).wash)
        assertEquals(Wash.Cool, (t.getDrawable(1) as WashDrawable).wash)
        assertEquals(Wash.Cool, v.currentWash)

        val before = v.background
        v.crossFadeWash(Wash.Cool) // same wash: nothing changes
        assertSame(before, v.background)
        assertEquals(600, Motion.WASH_MS)

        v.setWash(Wash.Voice)
        assertEquals(Wash.Voice, v.currentWash)
    }
}
