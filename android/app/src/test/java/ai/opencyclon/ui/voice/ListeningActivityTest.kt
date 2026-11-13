package ai.opencyclon.ui.voice

import ai.opencyclon.R
import ai.opencyclon.feed.FakeFeed
import ai.opencyclon.feed.FeedLocator
import ai.opencyclon.ui.QuickAskTileService
import ai.opencyclon.ui.widget.SoundWaveView
import ai.opencyclon.ui.widget.WordRevealTextView
import ai.opencyclon.voice.SpeechInputEngine
import ai.opencyclon.voice.VoiceErrors
import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListeningActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var feed: FakeFeed
    private lateinit var engine: FakeEngine

    /** A recognizer driven by the test. [finishNow] returns the last partial result, as the real one does. */
    private class FakeEngine : SpeechInputEngine {
        var partials = true
        override val showsPartials: Boolean get() = partials
        var listener: SpeechInputEngine.Listener? = null
        var starts = 0
        var finishes = 0
        var lastPartial = ""
        override fun startListening(listener: SpeechInputEngine.Listener) { this.listener = listener; starts++ }
        override fun stopListening() = Unit
        override fun destroy() = Unit
        override fun finishNow() {
            finishes++
            if (lastPartial.isEmpty()) listener?.onEnd() else listener?.onFinalResult(lastPartial)
        }
        fun partial(text: String) { lastPartial = text; listener!!.onPartialResult(text) }
    }

    private class FakeSpeechOut(context: Context) : SpeechOut(context) {
        var listener: Listener? = null
        var spoken: String? = null
        var stops = 0
        override val rate: Float get() = 1f
        override fun speak(text: String, listener: Listener) { spoken = text; this.listener = listener }
        override fun stop() { stops++ }
        override fun shutdown() = Unit
    }

    @Before fun setUp() {
        feed = FakeFeed()
        FeedLocator.feed = feed
        FeedLocator.commands = feed
        engine = FakeEngine()
        VoiceFactory.engine = { _, _ -> engine }
        VoiceFactory.speechOut = { null }
        ListeningActivity.hintShownThisProcess = false
        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        keyguard().setIsDeviceLocked(false)
    }

    @After fun tearDown() = VoiceFactory.reset()

    private fun keyguard() = shadowOf(context.getSystemService(KeyguardManager::class.java))

    private fun start(intent: Intent = ListeningActivity.listenIntent(context)): ActivityController<ListeningActivity> =
        Robolectric.buildActivity(ListeningActivity::class.java, intent).setup()

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun ListeningActivity.transcript() = findViewById<WordRevealTextView>(R.id.voice_transcript)
    private fun ListeningActivity.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test fun listeningScreenShowsTheTopLineHintAndTimer() {
        val a = start().get()
        assertEquals("Listening · stops when you pause", a.text(R.id.voice_top_line))
        assertEquals(a.getString(R.string.voice_hint_first, a.getString(R.string.app_name)), a.text(R.id.voice_hint))
        assertEquals("0:00", a.text(R.id.voice_timer))
        assertEquals(1, engine.starts)
        assertTrue(a.findViewById<SoundWaveView>(R.id.voice_wave).isFlat)
        advance(1000)
        assertEquals("0:01", a.text(R.id.voice_timer))
        // Later in the same process the hint is the short one.
        val b = start().get()
        assertEquals("Nothing is sent until you stop.", b.text(R.id.voice_hint))
    }

    @Test fun partialResultsColourTheText() {
        val a = start().get()
        engine.partial("Handle my")
        engine.partial("Handle my notifications")
        val t = a.transcript()
        assertTrue(t.text.toString().startsWith("Handle my notifications"))
        assertEquals(9 until 23, t.unconfirmedRange())
        assertTrue(feed.submitted.isEmpty())
    }

    @Test fun theWaveMovesOnceSpeechIsHeard() {
        val a = start().get()
        val wave = a.findViewById<SoundWaveView>(R.id.voice_wave)
        engine.listener!!.onLevel(0.001f) // room noise before speech
        assertTrue(wave.isFlat)
        engine.listener!!.onLevel(0.1f)
        assertFalse(wave.isFlat)
        engine.listener!!.onFinalResult("Call Sam")
        assertTrue(wave.isFlat)
    }

    @Test fun aPauseEndsTheTurnAndSubmits() {
        val a = start().get()
        engine.partial("Call Sam")
        engine.listener!!.onFinalResult("Call Sam back")
        assertEquals(listOf("Call Sam back"), feed.submitted)
        assertTrue(a.isFinishing)
    }

    @Test fun stopSubmitsOnce() {
        val a = start().get()
        engine.partial("Send the photos")
        a.findViewById<View>(R.id.voice_stop).performClick()
        a.findViewById<View>(R.id.voice_stop).performClick()
        engine.listener!!.onFinalResult("Send the photos")
        advance(ListeningActivity.STOP_TIMEOUT_MS)
        assertEquals(1, engine.finishes)
        assertEquals(listOf("Send the photos"), feed.submitted)
        assertTrue(a.isFinishing)
    }

    @Test fun stopDoesNotWaitForASlowEngine() {
        val slow = object : SpeechInputEngine {
            var listener: SpeechInputEngine.Listener? = null
            override fun startListening(listener: SpeechInputEngine.Listener) { this.listener = listener }
            override fun stopListening() = Unit
            override fun destroy() = Unit
            override fun finishNow() = Unit // never answers
        }
        VoiceFactory.engine = { _, _ -> slow }
        val a = start().get()
        slow.listener!!.onPartialResult("Open the map")
        a.findViewById<View>(R.id.voice_stop).performClick()
        assertTrue(feed.submitted.isEmpty())
        advance(ListeningActivity.STOP_TIMEOUT_MS)
        assertEquals(listOf("Open the map"), feed.submitted)
    }

    @Test fun backSubmitsNothing() {
        val a = start().get()
        engine.partial("Delete everything")
        a.onBackPressedDispatcher.onBackPressed()
        engine.listener!!.onFinalResult("Delete everything")
        assertTrue(feed.submitted.isEmpty())
        assertTrue(a.isFinishing)
    }

    @Test fun anEmptyResultShowsTheSentenceThenCloses() {
        val a = start().get()
        engine.listener!!.onEnd()
        assertEquals("I did not hear anything.", a.transcript().text.toString())
        assertFalse(a.isFinishing)
        advance(ListeningActivity.MESSAGE_MS)
        assertTrue(a.isFinishing)
        assertTrue(feed.submitted.isEmpty())
    }

    @Test fun stopWithNothingHeardShowsTheSentence() {
        val a = start().get()
        a.findViewById<View>(R.id.voice_stop).performClick()
        assertEquals("I did not hear anything.", a.transcript().text.toString())
        // Lane X2 (F1.3, F1.11): the same sentence, once, in the thread.
        assertEquals(listOf("I did not hear anything."), feed.notes)
    }

    @Test fun nothingHeardWritesOneSentenceToTheThreadAndNoRequest() {
        val a = start().get()
        engine.listener!!.onEnd()
        engine.listener!!.onEnd()
        assertEquals(listOf("I did not hear anything."), feed.notes)
        assertTrue(feed.submitted.isEmpty())
        assertEquals("I did not hear anything.", a.transcript().text.toString())
    }

    @Test fun aVolumeKeyEndsTheTurn() {
        val a = start().get()
        engine.partial("Reply yes")
        val consumed = a.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(consumed)
        assertEquals(listOf("Reply yes"), feed.submitted)
    }

    @Test fun aLockedPhoneDoesNotSubmitUntilUnlocked() {
        keyguard().setIsDeviceLocked(true)
        val c = start()
        val a = c.get()
        engine.partial("Text Nina I am late")
        engine.listener!!.onFinalResult("Text Nina I am late")
        assertTrue(feed.submitted.isEmpty())
        assertEquals("Unlock to send.", a.text(R.id.voice_hint))
        assertEquals("Text Nina I am late", a.transcript().text.toString())
        assertFalse(a.isFinishing)

        keyguard().setIsDeviceLocked(false)
        c.pause().resume()
        assertEquals(listOf("Text Nina I am late"), feed.submitted)
        c.pause().resume()
        assertEquals(1, feed.submitted.size)
    }

    @Test fun backOnALockedPhoneDropsTheWaitingWords() {
        keyguard().setIsDeviceLocked(true)
        val c = start()
        engine.listener!!.onFinalResult("Call Sam")
        c.get().onBackPressedDispatcher.onBackPressed()
        keyguard().setIsDeviceLocked(false)
        c.pause().resume()
        assertTrue(feed.submitted.isEmpty())
    }

    @Test fun withoutTheMicrophoneItAsksOnceThenExplains() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val a = start().get()
        val request = shadowOf(a).lastRequestedPermission
        assertNotNull(request)
        assertEquals(Manifest.permission.RECORD_AUDIO, request.requestedPermissions.single())
        assertEquals(0, engine.starts)
        @Suppress("DEPRECATION")
        a.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.voice_permission).visibility)
        assertEquals("I need the microphone to listen.", a.text(R.id.voice_permission_text))
        assertEquals(View.GONE, a.findViewById<View>(R.id.voice_stop).visibility)
        // Lane X2 (F1.10): the home page gets the sentence and the "Allow" row, once.
        assertEquals(1, feed.microphoneOffCalls)

        a.findViewById<View>(R.id.voice_open_settings).performClick()
        val next = shadowOf(a).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, next.action)
    }

    @Test fun grantingTheMicrophoneStartsListening() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val a = start().get()
        val request = shadowOf(a).lastRequestedPermission
        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        @Suppress("DEPRECATION")
        a.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_GRANTED))
        assertEquals(1, engine.starts)
    }

    @Test fun speakingWithoutASpeechEngineShowsTheQuestionAndListens() {
        val a = start(ListeningActivity.speakIntent(context, "For your Barcelona trip?", "Build me an app")).get()
        assertEquals("Listening · stops when you pause", a.text(R.id.voice_top_line))
        assertEquals("For your Barcelona trip?", a.text(R.id.voice_hint))
        assertEquals(1, engine.starts)
        engine.listener!!.onFinalResult("Yes")
        assertEquals(listOf("Yes"), feed.submitted)
    }

    @Test fun speakingDarkensEachWordAsItIsSaid() {
        var out: FakeSpeechOut? = null
        VoiceFactory.speechOut = { FakeSpeechOut(it).also { o -> out = o } }
        val a = start(ListeningActivity.speakIntent(context, "For your Barcelona trip?", "Build me an app")).get()
        assertEquals("Speaking · tap to interrupt", a.text(R.id.voice_top_line))
        assertEquals("“Build me an app”", a.text(R.id.voice_hint))
        assertEquals(View.GONE, a.findViewById<View>(R.id.voice_stop).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.voice_timer).visibility)
        assertEquals("For your Barcelona trip?", out!!.spoken)
        val t = a.transcript()
        assertEquals(0 until 24, t.unconfirmedRange())

        out!!.listener!!.onStart()
        out!!.listener!!.onRange(4) // "your"
        assertEquals(8 until 24, t.unconfirmedRange())
        assertEquals(0, engine.starts)

        out!!.listener!!.onDone()
        assertNull(t.unconfirmedRange())
        assertEquals("Listening · stops when you pause", a.text(R.id.voice_top_line))
        assertEquals(1, engine.starts)
    }

    @Test fun withoutRangeEventsTheTimingFollowsTheSpeechRate() {
        var out: FakeSpeechOut? = null
        VoiceFactory.speechOut = { FakeSpeechOut(it).also { o -> out = o } }
        val a = start(ListeningActivity.speakIntent(context, "For your Barcelona trip?")).get()
        assertEquals(View.GONE, a.findViewById<View>(R.id.voice_hint).visibility)
        out!!.listener!!.onStart()
        advance(SpeechTiming.RANGE_WAIT_MS + 100) // 400 ms: second word
        assertEquals(8 until 24, a.transcript().unconfirmedRange())
        assertFalse(a.findViewById<SoundWaveView>(R.id.voice_wave).isFlat)
        advance(400) // 800 ms: third word
        assertEquals(18 until 24, a.transcript().unconfirmedRange())
    }

    @Test fun aTapInterruptsTheSpeechAndListens() {
        var out: FakeSpeechOut? = null
        VoiceFactory.speechOut = { FakeSpeechOut(it).also { o -> out = o } }
        val a = start(ListeningActivity.speakIntent(context, "Which one?")).get()
        out!!.listener!!.onStart()
        a.findViewById<View>(R.id.voice_root).performClick()
        assertEquals(1, out!!.stops)
        assertEquals("Listening · stops when you pause", a.text(R.id.voice_top_line))
        assertEquals(1, engine.starts)
        out!!.listener!!.onDone() // the engine's late callback changes nothing
        assertEquals(1, engine.starts)
    }

    @Test fun aBusyMicrophoneIsSaidInPlainWords() {
        val a = start().get()
        engine.listener!!.onError(VoiceErrors.MIC_BUSY)
        assertEquals("The microphone is in use by another app.", a.transcript().text.toString())
        assertTrue(feed.submitted.isEmpty())
        advance(ListeningActivity.MESSAGE_MS)
        assertTrue(a.isFinishing)
    }

    @Test fun noRecognizerAtAllIsSaidInPlainWords() {
        val a = start().get()
        engine.listener!!.onUnavailable("none")
        assertEquals("Voice is not available on this phone.", a.transcript().text.toString())
    }

    @Test fun otherErrorsSayListeningFailed() {
        val a = start().get()
        engine.listener!!.onError(VoiceErrors.FAILED)
        assertEquals("I could not listen this time.", a.transcript().text.toString())
    }

    @Test fun aPermissionErrorFromTheEngineShowsTheSettingsBlock() {
        val a = start().get()
        engine.listener!!.onError(VoiceErrors.NO_PERMISSION)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.voice_permission).visibility)
        assertFalse(a.isFinishing)
    }

    @Test fun theTileOpensTheListeningScreen() {
        val intent = QuickAskTileService.tileIntent(context)
        assertEquals(ListeningActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val speak = ListeningActivity.speakIntent(context, "Which one?", "Send it")
        assertEquals("Which one?", speak.getStringExtra(ListeningActivity.EXTRA_QUESTION))
        assertEquals("Send it", speak.getStringExtra(ListeningActivity.EXTRA_OWNER_WORDS))
    }

    // ── A service on the internet (F17.3 to F17.5) ──────────────────────

    @Test fun anEngineWithoutPartialsSaysWhenTheWordsCome() {
        engine.partials = false
        val a = start().get()
        assertEquals("I show the words when you stop.", a.text(R.id.voice_hint))
    }

    @Test fun theServiceErrorsHaveTheirSentences() {
        assertEquals(R.string.voice_service_unreachable, ListeningActivity.errorSentence(VoiceErrors.SERVICE_UNREACHABLE))
        assertEquals(R.string.voice_service_refused, ListeningActivity.errorSentence(VoiceErrors.SERVICE_REFUSED))
        assertEquals(R.string.voice_service_failed, ListeningActivity.errorSentence(VoiceErrors.SERVICE_FAILED))
        val a = start().get()
        engine.listener!!.onError(VoiceErrors.SERVICE_REFUSED)
        assertEquals("The voice service refused the key.", a.transcript().text.toString())
        assertTrue(feed.submitted.isEmpty())
    }

    @Test fun aFallbackFromTheServiceIsSaidAndTheWordsAreSentAfterAMoment() {
        val a = start().get()
        engine.listener!!.onNotice(VoiceErrors.SERVICE_FELL_BACK)
        assertEquals("I could not reach the voice service. I used the recognizer on this phone.", a.text(R.id.voice_hint))
        engine.listener!!.onFinalResult("Call Sam")
        assertEquals(listOf("Call Sam"), feed.submitted)
        assertFalse(a.isFinishing)
        advance(ListeningActivity.MESSAGE_MS)
        assertTrue(a.isFinishing)
    }
}
