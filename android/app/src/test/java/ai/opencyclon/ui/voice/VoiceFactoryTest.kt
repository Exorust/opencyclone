package ai.opencyclon.ui.voice

import ai.opencyclon.voice.FallbackSpeechInputEngine
import ai.opencyclon.voice.ServiceSpeechInputEngine
import ai.opencyclon.voice.SpeechInputEngine
import ai.opencyclon.voice.VoiceErrors
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceFactoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class Engine(val name: String, val unavailable: Boolean = false) : SpeechInputEngine {
        var unavailableWith: String? = null
        var starts = 0
        var destroyed = false
        var listener: SpeechInputEngine.Listener? = null
        override fun startListening(listener: SpeechInputEngine.Listener) {
            starts++
            this.listener = listener
            if (unavailable) listener.onUnavailable("$name missing")
            unavailableWith?.let { listener.onUnavailable(it) }
        }
        override fun stopListening() = Unit
        override fun destroy() { destroyed = true }
    }

    private class Recorder : SpeechInputEngine.Listener {
        val events = mutableListOf<String>()
        override fun onReady() { events += "ready" }
        override fun onPartialResult(text: String) { events += "partial $text" }
        override fun onFinalResult(text: String) { events += "final $text" }
        override fun onError(message: String) { events += "error $message" }
        override fun onEnd() { events += "end" }
        override fun onNotice(message: String) { events += "notice $message" }
    }

    @After fun tearDown() {
        VoiceFactory.setChoice(context, VoiceFactory.ANDROID)
        VoiceFactory.reset()
    }

    @Test fun androidIsTheDefaultAndOnlyKnownValuesAreStored() {
        assertEquals(VoiceFactory.ANDROID, VoiceFactory.choice(context))
        assertTrue(VoiceFactory.setChoice(context, VoiceFactory.PHONE_MODEL))
        assertEquals(VoiceFactory.PHONE_MODEL, VoiceFactory.choice(context))
        assertFalse(VoiceFactory.setChoice(context, "cloud"))
        assertEquals(VoiceFactory.PHONE_MODEL, VoiceFactory.choice(context))
        assertTrue(VoiceFactory.setChoice(context, VoiceFactory.SERVICE))
        assertEquals(VoiceFactory.SERVICE, VoiceFactory.choice(context))
    }

    @Test fun eachChoiceHasItsFallbackOrderAndTheServiceIsNeverAFallback() {
        assertEquals(listOf("android", "phone_model"), VoiceFactory.order(VoiceFactory.ANDROID))
        assertEquals(listOf("phone_model", "android"), VoiceFactory.order(VoiceFactory.PHONE_MODEL))
        assertEquals(listOf("service", "phone_model", "android"), VoiceFactory.order(VoiceFactory.SERVICE))
        assertEquals(listOf("android", "phone_model"), VoiceFactory.order("unknown"))
    }

    @Test fun withALocalChoiceNoServiceEngineIsEverBuilt() {
        for (choice in listOf(VoiceFactory.ANDROID, VoiceFactory.PHONE_MODEL)) {
            val chain = VoiceFactory.chain(choice, { Engine("a", true) }, { Engine("p", true) },
                service = { fail("the service engine was built for $choice"); Engine("s") })
            val rec = Recorder()
            chain.startListening(rec)
            assertEquals(listOf("error ${VoiceErrors.UNAVAILABLE}"), rec.events)
        }
    }

    @Test fun theRealFactoryNeverConstructsTheServiceEngineForALocalChoice() {
        VoiceFactory.reset()
        val before = ServiceSpeechInputEngine.instances.get()
        for (choice in listOf(VoiceFactory.ANDROID, VoiceFactory.PHONE_MODEL)) {
            VoiceFactory.setChoice(context, choice)
            val engine = VoiceFactory.engine(context) { _, _ -> }
            val rec = Recorder()
            engine.startListening(rec) // no microphone permission here: every engine stops at once
            shadowOf(Looper.getMainLooper()).idle()
            engine.destroy()
        }
        assertEquals(before, ServiceSpeechInputEngine.instances.get())
    }

    @Test fun theServiceChoiceFallsBackToThePhoneModelThenAndroidWithTheNotice() {
        val service = Engine("service")
        val phone = Engine("phone")
        val chain = VoiceFactory.chain(VoiceFactory.SERVICE, { Engine("android") }, { phone }, { service })
        val rec = Recorder()
        chain.startListening(rec)
        service.listener!!.onUnavailable("${VoiceErrors.SERVICE_UNREACHABLE}: no network")
        assertEquals(VoiceFactory.PHONE_MODEL, chain.activeName)
        phone.listener!!.onFinalResult("Call Sam")
        assertEquals(listOf("notice ${VoiceErrors.SERVICE_FELL_BACK}", "final Call Sam"), rec.events)

        val android = Engine("android")
        val chain2 = VoiceFactory.chain(VoiceFactory.SERVICE, { android }, { Engine("p", true) },
            { Engine("s").also { s -> s.unavailableWith = "${VoiceErrors.SERVICE_UNREACHABLE}: no network" } })
        val rec2 = Recorder()
        chain2.startListening(rec2)
        assertEquals(VoiceFactory.ANDROID, chain2.activeName)
        assertEquals(1, android.starts)
        assertEquals(listOf("notice ${VoiceErrors.SERVICE_FELL_BACK}"), rec2.events)
    }

    @Test fun whenNothingCanTakeTheTurnAfterTheServiceTheScreenSaysTheServiceWasNotReached() {
        val chain = VoiceFactory.chain(VoiceFactory.SERVICE, { Engine("a", true) }, { Engine("p", true) },
            { Engine("s").also { it.unavailableWith = "${VoiceErrors.SERVICE_UNREACHABLE}: no network" } })
        val rec = Recorder()
        chain.startListening(rec)
        assertEquals(listOf("notice ${VoiceErrors.SERVICE_FELL_BACK}", "error ${VoiceErrors.SERVICE_UNREACHABLE}"), rec.events)
    }

    @Test fun aRefusedKeyNeverFallsBack() {
        val service = Engine("service")
        var phoneBuilt = false
        val chain = VoiceFactory.chain(VoiceFactory.SERVICE, { Engine("a") }, { phoneBuilt = true; Engine("p") }, { service })
        val rec = Recorder()
        chain.startListening(rec)
        service.listener!!.onError(VoiceErrors.SERVICE_REFUSED)
        assertEquals(listOf("error ${VoiceErrors.SERVICE_REFUSED}"), rec.events)
        assertFalse(phoneBuilt)
    }

    @Test fun theServiceEngineShowsNoPartialsAndTheChainSaysSo() {
        val chain = VoiceFactory.chain(VoiceFactory.SERVICE, { Engine("a") }, { Engine("p") }, {
            object : SpeechInputEngine {
                override val showsPartials get() = false
                override fun startListening(listener: SpeechInputEngine.Listener) = Unit
                override fun stopListening() = Unit
                override fun destroy() = Unit
            }
        })
        chain.startListening(Recorder())
        assertFalse(chain.showsPartials)
    }

    @Test fun withAndroidChosenAndAvailableThePhoneModelIsNeverBuilt() {
        var phoneModelBuilt = false
        val android = Engine("android")
        val chain = VoiceFactory.chain(VoiceFactory.ANDROID, { android }, { phoneModelBuilt = true; Engine("phone") })
        val rec = Recorder()
        chain.startListening(rec)
        android.listener!!.onFinalResult("Call Sam")
        assertEquals(1, android.starts)
        assertFalse(phoneModelBuilt) // no 64 MB download can start
        assertEquals(listOf("final Call Sam"), rec.events)
        assertEquals(VoiceFactory.ANDROID, chain.activeName)
    }

    @Test fun anUnavailableAndroidRecognizerHandsTheTurnToThePhoneModel() {
        val android = Engine("android", unavailable = true)
        val phone = Engine("phone")
        val chain = VoiceFactory.chain(VoiceFactory.ANDROID, { android }, { phone })
        val rec = Recorder()
        chain.startListening(rec)
        assertTrue(android.destroyed)
        assertEquals(1, phone.starts)
        assertEquals(VoiceFactory.PHONE_MODEL, chain.activeName)
        phone.listener!!.onPartialResult("Open")
        assertEquals(listOf("partial Open"), rec.events)
    }

    @Test fun thePhoneModelChoiceFallsBackToAndroid() {
        val phone = Engine("phone", unavailable = true)
        val android = Engine("android")
        val chain = VoiceFactory.chain(VoiceFactory.PHONE_MODEL, { android }, { phone })
        chain.startListening(Recorder())
        assertEquals(1, android.starts)
        assertEquals(VoiceFactory.ANDROID, chain.activeName)
    }

    @Test fun whenNeitherEngineCanListenTheScreenIsTold() {
        val chain = FallbackSpeechInputEngine(listOf("a", "b"), listOf({ Engine("a", true) }, { Engine("b", true) }))
        val rec = Recorder()
        chain.startListening(rec)
        assertEquals(listOf("error ${VoiceErrors.UNAVAILABLE}"), rec.events)
    }
}
