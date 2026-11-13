package ai.opencyclon.voice

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServiceSpeechInputEngineTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val config = VoiceServiceConfig("https://example.com/v1", "m", "k")

    private class Recorder : SpeechInputEngine.Listener {
        val events = mutableListOf<String>()
        override fun onReady() { events += "ready" }
        override fun onPartialResult(text: String) { events += "partial $text" }
        override fun onFinalResult(text: String) { events += "final $text" }
        override fun onError(message: String) { events += "error $message" }
        override fun onEnd() { events += "end" }
        override fun onUnavailable(reason: String) { events += "unavailable $reason" }
        override fun onNotice(message: String) { events += "notice $message" }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun engine(local: ((ShortArray) -> String)? = null, network: Boolean = true) =
        ServiceSpeechInputEngine(context, config, localTranscriber = local, networkAvailable = { network })

    private fun deliver(e: ServiceSpeechInputEngine, result: VoiceServiceClient.Result): List<String> {
        val rec = Recorder()
        e.deliver(result, ShortArray(1600), rec)
        idle()
        return rec.events
    }

    @Test fun noWordsWhileListening() {
        assertFalse(engine().showsPartials)
    }

    @Test fun withoutANetworkTheEngineIsUnavailableAndRecordsNothing() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val rec = Recorder()
        engine(network = false).startListening(rec)
        assertEquals(1, rec.events.size)
        assertEquals(true, rec.events.single().startsWith("unavailable ${VoiceErrors.SERVICE_UNREACHABLE}"))
    }

    @Test fun withoutTheMicrophonePermissionTheScreenIsTold() {
        val rec = Recorder()
        engine().startListening(rec)
        assertEquals(listOf("error ${VoiceErrors.NO_PERMISSION}"), rec.events)
    }

    @Test fun theRepliesTextIsTheFinalResult() {
        assertEquals(listOf("final Open the clock app"), deliver(engine(), VoiceServiceClient.Result.Text(" Open the clock app ")))
        assertEquals(listOf("end"), deliver(engine(), VoiceServiceClient.Result.Text("")))
    }

    @Test fun aRefusedKeyIsAnErrorEvenWithAModelOnThePhone() {
        var localUsed = false
        val e = engine(local = { localUsed = true; "x" })
        assertEquals(listOf("error ${VoiceErrors.SERVICE_REFUSED}"), deliver(e, VoiceServiceClient.Result.Refused))
        assertEquals(listOf("error ${VoiceErrors.SERVICE_FAILED}"), deliver(e, VoiceServiceClient.Result.Failed("HTTP 500")))
        assertFalse(localUsed)
    }

    @Test fun anUnreachableServiceUsesTheModelOnThePhoneForTheSameRecording() {
        var got: ShortArray? = null
        val e = engine(local = { got = it; "Call Sam" })
        assertEquals(
            listOf("notice ${VoiceErrors.SERVICE_FELL_BACK}", "final Call Sam"),
            deliver(e, VoiceServiceClient.Result.Unreachable),
        )
        assertEquals(1600, got!!.size)
    }

    @Test fun anUnreachableServiceWithoutAModelOnThePhoneSendsNothing() {
        assertEquals(listOf("error ${VoiceErrors.SERVICE_UNREACHABLE}"), deliver(engine(), VoiceServiceClient.Result.Unreachable))
        val failing = engine(local = { error("model broken") })
        assertEquals(listOf("error ${VoiceErrors.SERVICE_UNREACHABLE}"), deliver(failing, VoiceServiceClient.Result.Unreachable))
    }
}
