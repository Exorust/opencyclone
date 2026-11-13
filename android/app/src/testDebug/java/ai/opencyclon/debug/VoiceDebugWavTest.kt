package ai.opencyclon.debug

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceDebugWavTest {
    private fun wav(rate: Int, channels: Int, samples: ShortArray): ByteArray {
        val data = samples.size * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort()); putInt(rate)
            putInt(rate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(data)
            samples.forEach { putShort(it) }
        }.array()
    }

    @Test fun readsMono16BitPcm() {
        val a = VoiceDebug.Wav.parse(wav(24000, 1, shortArrayOf(0, 16384, -16384)))
        assertEquals(24000, a.rate)
        assertEquals(3, a.mono.size)
        assertEquals(0.5f, a.mono[1], 1e-4f)
    }

    @Test fun stereoIsAveraged() {
        val a = VoiceDebug.Wav.parse(wav(16000, 2, shortArrayOf(16384, 0, -16384, -16384)))
        assertEquals(2, a.mono.size)
        assertEquals(0.25f, a.mono[0], 1e-4f)
        assertEquals(-0.5f, a.mono[1], 1e-4f)
    }

    @Test fun resamples24kTo16k() {
        val out = VoiceDebug.Wav.resample(FloatArray(2400) { 0.1f }, 24000, 16000)
        assertEquals(1600, out.size)
        assertEquals(0.1f, out[800], 1e-6f)
    }
}
