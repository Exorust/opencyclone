package ai.opencyclone.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class MicLevelTest {
    @Test fun silenceIsZero() {
        assertEquals(0f, MicLevel.rms(FloatArray(1600)), 0f)
        assertEquals(0f, MicLevel.rms(FloatArray(0)), 0f)
    }

    @Test fun rootMeanSquareOfTheBlock() {
        assertEquals(0.5f, MicLevel.rms(floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f)), 1e-6f)
        assertEquals(Math.sqrt(0.5).toFloat(), MicLevel.rms(floatArrayOf(1f, 0f)), 1e-6f)
    }

    @Test fun onlyTheFirstCountSamplesCount() {
        assertEquals(1f, MicLevel.rms(floatArrayOf(1f, 1f, 0f, 0f), count = 2), 1e-6f)
        assertEquals(1f, MicLevel.rms(floatArrayOf(1f), count = 5), 1e-6f)
    }

    @Test fun clampedToOne() {
        assertEquals(1f, MicLevel.rms(floatArrayOf(3f, -3f)), 0f)
    }
}
