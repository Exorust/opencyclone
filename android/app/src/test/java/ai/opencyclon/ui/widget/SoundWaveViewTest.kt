package ai.opencyclon.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SoundWaveViewTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The system setting "remove animations" (hidden framework method, reached by reflection). */
    private fun setDurationScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    @After fun animationsBackOn() = setDurationScale(1f)

    @Test fun startsFlatAndScrollsNewLevelsInFromTheRight() {
        val v = SoundWaveView(context)
        assertTrue(v.isFlat)
        assertEquals(24, v.levels().size)
        v.setLevel(0.5f)
        v.setLevel(1.5f)
        assertFalse(v.isFlat)
        val l = v.levels()
        assertEquals(0.5f, l[22], 0f)
        assertEquals(1f, l[23], 0f) // clamped
        v.setFlat()
        assertTrue(v.isFlat)
        assertTrue(v.levels().all { it == 0f })
    }

    @Test fun barHeightsStayBetween4And54Dp() {
        assertEquals(4f, SoundWaveView.barHeight(0f, 12, 4f, 54f), 0f)
        for (i in 0 until 24) {
            val h = SoundWaveView.barHeight(1f, i, 4f, 54f)
            assertTrue(h in 4f..54f)
        }
        // Taller in the middle than at the edges.
        assertTrue(SoundWaveView.barHeight(1f, 12, 4f, 54f) > SoundWaveView.barHeight(1f, 0, 4f, 54f))
    }

    @Test fun measuresTwentyFourBarsAndFiftyFourDp() {
        val v = SoundWaveView(context)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        v.measure(unspecified, unspecified)
        val d = context.resources.displayMetrics.density
        assertEquals((54 * d).toInt(), v.measuredHeight)
        assertTrue(v.measuredWidth >= ((24 * 3 + 23 * 4) * d).toInt())
    }

    @Test fun microphoneLevelMapsIntoTheBars() {
        assertEquals(0f, SoundWaveView.fromRms(0f), 0f)
        assertTrue(SoundWaveView.fromRms(0.05f) in 0.3f..0.7f)
        assertEquals(1f, SoundWaveView.fromRms(1f), 0f)
    }

    @Test fun staysFlatWhenAnimationsAreOff() {
        setDurationScale(0f)
        assertFalse(ValueAnimator.areAnimatorsEnabled())
        val v = SoundWaveView(context)
        v.setLevel(0.8f)
        assertTrue(v.isFlat)
    }
}
