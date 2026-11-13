package ai.opencyclon.ui.start

import ai.opencyclon.R
import ai.opencyclon.backend.SetupActivity
import ai.opencyclon.config.ConfigRepository
import ai.opencyclon.feed.FakeFeed
import ai.opencyclon.feed.FeedLocator
import ai.opencyclon.ui.ControlPanelActivity
import ai.opencyclon.ui.home.StartChecks
import ai.opencyclon.ui.voice.ListeningActivity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartFlowTest {

    private class Fake : StartConditions {
        var isConnected = false
        var model = false
        val grants = mutableSetOf<Grant>()
        var mic = false
        var voiceFiles = false
        var isMetered = false
        var android = false
        /** True by default, so the optional home-screen step stays out of the older tests' plans. */
        var home = true
        override fun connected() = isConnected
        override fun modelConfigured() = model
        override fun granted(grant: Grant) = grant in grants
        override fun micGranted() = mic
        override fun voiceModelPresent() = voiceFiles
        override fun metered() = isMetered
        override fun androidRecognizer() = android
        override fun homeRoleHeld() = home
    }

    /** Records the callbacks so a test can drive the download. */
    private class FakeDownload : VoiceDownload {
        var starts = 0
        var progress: ((Int) -> Unit)? = null
        var done: ((Throwable?) -> Unit)? = null
        override fun start(context: Context, progress: (Int) -> Unit, done: (Throwable?) -> Unit) {
            starts++
            this.progress = progress
            this.done = done
        }
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var fake: Fake
    private lateinit var download: FakeDownload

    @Before fun setUp() {
        fake = Fake()
        download = FakeDownload()
        StartActivity.conditionsFor = { fake }
        StartActivity.voiceDownload = download
        StartActivity.consumeInterrupted()
        context.getSharedPreferences(StartPrefs.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        StartChecks.backendNameOverride = null
    }

    @After fun tearDown() {
        StartActivity.conditionsFor = { DeviceStartConditions(it) }
        StartActivity.voiceDownload = ModelVoiceDownload
        StartChecks.backendNameOverride = null
        ConfigRepository(context).apiKey = ""
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun start(): ActivityController<StartActivity> =
        Robolectric.buildActivity(StartActivity::class.java).setup().also { idle() }

    private fun allViews(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else listOf(v)

    private fun StartActivity.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun StartActivity.shown(id: Int) = findViewById<View>(id).visibility == View.VISIBLE
    private fun StartActivity.tap(id: Int) {
        val v = findViewById<View>(id)
        assertTrue("view $id is shown", v.visibility == View.VISIBLE)
        v.performClick()
        idle()
    }
    private fun StartActivity.resume(c: ActivityController<StartActivity>) {
        c.pause().resume()
        idle()
    }

    private fun startedActivities(): List<Intent> {
        val app = shadowOf(context as Application)
        return generateSequence { app.nextStartedActivity }.toList()
    }

    // ── Which steps ─────────────────────────────────────────────────────

    @Test fun nothingDoneShowsAllSixStepsInOrder() {
        assertEquals(StartStep.entries.toList() - StartStep.MAKE_HOME, StartPlan.steps(fake, welcomeSeen = false))
        val a = start().get()
        assertEquals(StartStep.entries.toList() - StartStep.MAKE_HOME, a.plan)
        assertEquals(StartStep.WELCOME, a.step)
        assertEquals("Step 1 of 6", a.text(R.id.start_counter))
        assertEquals(context.getString(R.string.app_name), a.text(R.id.start_title))
    }

    @Test fun afterTheSetupScriptOnlyWelcomeModelAndTryItRemain() {
        fake.isConnected = true
        fake.grants += Grant.entries
        fake.mic = true
        fake.voiceFiles = true
        assertEquals(listOf(StartStep.WELCOME, StartStep.MODEL, StartStep.TRY_IT), StartPlan.steps(fake, false))
        val a = start().get()
        assertEquals("Step 1 of 3", a.text(R.id.start_counter))
        a.tap(R.id.start_primary)
        assertEquals(StartStep.MODEL, a.step)
        assertEquals("Step 2 of 3", a.text(R.id.start_counter))
    }

    @Test fun allDoneIsNotShown() {
        StartPrefs(context).done = true
        val a = start().get()
        assertTrue(a.isFinishing)
        assertFalse(StartActivity.consumeInterrupted())
    }

    @Test fun counterAdvancesWithEachStep() {
        val a = start().get()
        a.tap(R.id.start_primary) // Start
        assertEquals(StartStep.CONNECT, a.step)
        assertEquals("Step 2 of 6", a.text(R.id.start_counter))
        assertEquals("Connect once", a.text(R.id.start_title))
    }

    // ── Connect ─────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    @Test fun connectOpensThePairingScreenAndStaysWhenNotConnected() {
        val c = start()
        val a = c.get()
        a.tap(R.id.start_primary)
        startedActivities()
        a.tap(R.id.start_primary) // Connect
        val pairing = shadowOf(a).nextStartedActivityForResult
        assertEquals(SetupActivity::class.java.name, pairing.intent.component?.className)
        shadowOf(a).receiveResult(pairing.intent, android.app.Activity.RESULT_CANCELED, null)
        idle()
        assertEquals(StartStep.CONNECT, a.step)
        assertEquals("Not connected yet.", a.text(R.id.start_status))

        a.tap(R.id.start_primary)
        val again = shadowOf(a).nextStartedActivityForResult
        fake.isConnected = true
        shadowOf(a).receiveResult(again.intent, SetupActivity.RESULT_BACKEND_READY, null)
        idle()
        assertEquals(StartStep.MODEL, a.step)
    }

    // ── Model ───────────────────────────────────────────────────────────

    @Test fun modelStepShowsContinueOnceAModelIsConfigured() {
        fake.isConnected = true
        StartPrefs(context).welcomeSeen = true
        val c = start()
        val a = c.get()
        assertEquals(StartStep.MODEL, a.step)
        assertEquals("I think with a language model. Sign in, or use your own key.", a.text(R.id.start_text))
        assertTrue(a.shown(R.id.start_signin_chatgpt))
        assertTrue(a.shown(R.id.start_signin_openrouter))
        assertTrue(a.shown(R.id.start_use_key))
        // "Continue" stays visible and disabled until a model is configured (UI.md 11).
        assertEquals("Continue", a.text(R.id.start_primary))
        assertFalse(a.findViewById<Button>(R.id.start_primary).isEnabled)

        fake.model = true
        a.resume(c)
        assertEquals("Connected.", a.text(R.id.start_text))
        assertEquals("Continue", a.text(R.id.start_primary))
        assertTrue(a.findViewById<Button>(R.id.start_primary).isEnabled)
        a.tap(R.id.start_primary)
        assertEquals(StartStep.PERMISSIONS, a.step)
    }

    @Test fun useAKeyOpensTheSetUpPageAndGoesOnWhenAModelIsSaved() {
        fake.isConnected = true
        StartPrefs(context).welcomeSeen = true
        val c = start()
        val a = c.get()
        startedActivities()
        a.tap(R.id.start_use_key)
        val keyPage = startedActivities().single()
        assertEquals(ai.opencyclon.ui.FirstRunActivity::class.java.name, keyPage.component?.className)
        assertTrue(keyPage.getBooleanExtra(ai.opencyclon.ui.FirstRunActivity.EXTRA_SHOW_KEY_FIELDS, false))
        fake.model = true
        a.resume(c)
        assertEquals(StartStep.PERMISSIONS, a.step)
    }

    // ── Permissions ─────────────────────────────────────────────────────

    @Test fun eachPermissionSubStepIsSkippedWhenGranted() {
        fake.isConnected = true
        fake.model = true
        fake.grants += Grant.ACCESSIBILITY
        val c = start()
        val a = c.get()
        assertEquals(StartStep.PERMISSIONS, a.step)
        assertEquals("Let me see and act", a.text(R.id.start_title))
        // Accessibility is on: the first sub-step is notification access.
        assertEquals("So I can read incoming notifications.", a.text(R.id.start_status))
        assertEquals("Open settings", a.text(R.id.start_primary))
        startedActivities()
        a.tap(R.id.start_primary)
        assertTrue(startedActivities().isNotEmpty())

        fake.grants += Grant.NOTIFICATION_ACCESS
        a.resume(c)
        assertEquals(StartStep.PERMISSIONS, a.step)
        assertEquals("So I can ask you before I act.", a.text(R.id.start_status))

        fake.grants += Grant.POST_NOTIFICATIONS
        a.tap(R.id.start_primary)
        a.onRequestPermissionsResult(StartActivity.REQUEST_POST_NOTIFICATIONS, emptyArray(), IntArray(0))
        idle()
        assertEquals(StartStep.VOICE, a.step)
    }

    @Test fun accessibilityIsAskedFirst() {
        fake.isConnected = true
        fake.model = true
        val a = start().get()
        assertEquals("So I can read and press buttons in your apps.", a.text(R.id.start_status))
        assertEquals(context.getString(R.string.h_perm_accessibility), a.text(R.id.start_detail_name))
    }

    @Test fun allPermissionsGrantedSkipsTheWholeStep() {
        fake.isConnected = true
        fake.model = true
        fake.grants += Grant.entries
        assertFalse(StartStep.PERMISSIONS in StartPlan.steps(fake, false))
    }

    // ── Voice ───────────────────────────────────────────────────────────

    private fun atVoice(): Pair<ActivityController<StartActivity>, StartActivity> {
        fake.isConnected = true
        fake.model = true
        fake.grants += Grant.entries
        fake.mic = true
        val c = start()
        assertEquals(StartStep.VOICE, c.get().step)
        return c to c.get()
    }

    @Test fun voiceShowsProgressThenReady() {
        val (_, a) = atVoice()
        assertEquals("Talk to me", a.text(R.id.start_title))
        assertEquals("Get voice ready", a.text(R.id.start_primary))
        assertEquals("Not now", a.text(R.id.start_pale))
        a.tap(R.id.start_primary)
        assertEquals(1, download.starts)
        assertEquals("Getting voice ready. 0 percent.", a.text(R.id.start_status))
        download.progress!!(42)
        idle()
        assertEquals("Getting voice ready. 42 percent.", a.text(R.id.start_status))
        assertTrue(a.shown(R.id.start_progress))
        assertEquals(42, a.findViewById<android.widget.ProgressBar>(R.id.start_progress).progress)
        // primary on surface_2 (Widget.App.LinearProgress)
        val bar = a.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.start_progress)
        assertEquals(androidx.core.content.ContextCompat.getColor(a, R.color.primary), bar.indicatorColor.single())
        assertEquals(androidx.core.content.ContextCompat.getColor(a, R.color.surface_2), bar.trackColor)
        assertFalse(a.shown(R.id.start_primary))

        fake.voiceFiles = true
        download.done!!(null)
        idle()
        assertEquals("Voice is ready.", a.text(R.id.start_status))
        assertEquals("Continue", a.text(R.id.start_primary))
        assertFalse(a.shown(R.id.start_progress))
        a.tap(R.id.start_primary)
        assertEquals(StartStep.TRY_IT, a.step)
        assertTrue(a.findViewById<Button>(R.id.start_primary).isEnabled)
    }

    @Test fun voiceFailureOffersTryAgain() {
        val (_, a) = atVoice()
        a.tap(R.id.start_primary)
        download.done!!(java.io.IOException("Download failed: HTTP 503"))
        idle()
        assertEquals("The download failed. Check the connection and try again.", a.text(R.id.start_status))
        assertEquals("Try again", a.text(R.id.start_primary))
        a.tap(R.id.start_primary)
        assertEquals(2, download.starts)
    }

    @Test fun voiceWarnsBeforeADownloadOnAMeteredConnection() {
        fake.isMetered = true
        val (_, a) = atVoice()
        a.tap(R.id.start_primary)
        assertEquals(0, download.starts)
        assertEquals("You are not on Wi-Fi. The download is 64 MB.", a.text(R.id.start_status))
        assertEquals("Download anyway", a.text(R.id.start_primary))
        assertEquals("Not now", a.text(R.id.start_pale))
        a.tap(R.id.start_primary)
        assertEquals(1, download.starts)
    }

    @Test fun voiceNotNowGoesOnWithTalkDisabled() {
        val (_, a) = atVoice()
        a.tap(R.id.start_pale)
        assertEquals(StartStep.TRY_IT, a.step)
        assertEquals(0, download.starts)
        assertFalse(a.findViewById<Button>(R.id.start_primary).isEnabled)
        assertEquals("Type", a.text(R.id.start_pale))
    }

    @Test fun voiceAsksForTheMicrophoneFirst() {
        val (_, a) = atVoice()
        fake.mic = false
        a.tap(R.id.start_primary)
        assertEquals(0, download.starts)
        a.onRequestPermissionsResult(StartActivity.REQUEST_MIC, emptyArray(), IntArray(0))
        idle()
        assertEquals("The microphone is off. Turn it on in settings.", a.text(R.id.start_status))
        assertEquals("Open settings", a.text(R.id.start_primary))
    }

    @Test fun withAndroidsRecognizerTheVoiceStepOnlyAsksForTheMicrophone() {
        fake.isConnected = true
        fake.model = true
        fake.grants += Grant.entries
        fake.android = true
        val a = start().get()
        assertEquals(StartStep.VOICE, a.step)
        assertEquals("I listen with the recognizer that Android provides.", a.text(R.id.start_text))
        assertEquals("Allow", a.text(R.id.start_primary))
        a.tap(R.id.start_primary)
        // The microphone is granted: ready, and no 64 MB download.
        fake.mic = true
        a.onRequestPermissionsResult(StartActivity.REQUEST_MIC, emptyArray(), IntArray(0))
        idle()
        assertEquals("Voice is ready.", a.text(R.id.start_status))
        assertEquals(0, download.starts)
        a.tap(R.id.start_primary)
        assertEquals(StartStep.TRY_IT, a.step)
        assertTrue(a.findViewById<Button>(R.id.start_primary).isEnabled)
    }

    @Test fun withAndroidsRecognizerAndTheMicrophoneTheVoiceStepIsSkipped() {
        fake.android = true
        fake.mic = true
        assertFalse(StartStep.VOICE in StartPlan.steps(fake, welcomeSeen = true))
        fake.android = false
        assertTrue(StartStep.VOICE in StartPlan.steps(fake, welcomeSeen = true))
    }

    // ── Try it, back, recreation ────────────────────────────────────────

    @Test fun tryItTalkMarksTheStartDoneAndOpensListening() {
        fake.isConnected = true
        fake.model = true
        fake.grants += Grant.entries
        fake.mic = true
        fake.voiceFiles = true
        val a = start().get()
        assertEquals(StartStep.TRY_IT, a.step)
        assertEquals("Step 1 of 1", a.text(R.id.start_counter))
        assertEquals("Say or type: Open the clock app.", a.text(R.id.start_text))
        assertTrue(a.shown(R.id.start_hint))
        startedActivities()
        a.tap(R.id.start_primary)
        assertEquals(ListeningActivity::class.java.name, startedActivities().single().component?.className)
        assertTrue(StartPrefs(context).done)
        assertTrue(a.isFinishing)
        assertFalse(StartActivity.consumeInterrupted())
    }

    @Test fun tryItTypeMarksTheStartDoneAndAsksHomeForTheTypeInput() {
        fake.isConnected = true
        fake.model = true
        fake.grants += Grant.entries
        val a = start().get()
        a.tap(R.id.start_pale) // voice: Not now
        startedActivities()
        a.tap(R.id.start_pale) // Type
        val home = startedActivities().single()
        assertEquals(ControlPanelActivity::class.java.name, home.component?.className)
        assertTrue(home.getBooleanExtra(StartActivity.EXTRA_OPEN_TYPE, false))
        assertTrue(StartPrefs(context).done)
    }

    @Test fun backGoesToThePreviousStepAndLeavesOnTheFirst() {
        val a = start().get()
        a.tap(R.id.start_primary) // -> Connect
        fake.isConnected = true
        a.next() // -> Model
        assertEquals(StartStep.MODEL, a.step)
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertEquals(StartStep.CONNECT, a.step)
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertEquals(StartStep.WELCOME, a.step)
        assertFalse(a.isFinishing)
        a.onBackPressedDispatcher.onBackPressed(); idle()
        assertTrue(a.isFinishing)
        assertFalse(StartActivity.consumeInterrupted())
    }

    @Test fun theStepSurvivesRecreationAndALeftStartResumes() {
        val c = start()
        c.get().tap(R.id.start_primary) // -> Connect
        c.recreate(); idle()
        assertEquals(StartStep.CONNECT, c.get().step)
        assertEquals("Step 2 of 6", c.get().text(R.id.start_counter))
        c.pause().stop().destroy()

        // Opened again later: Welcome was seen, so the start resumes at Connect.
        val again = start().get()
        assertEquals(StartStep.CONNECT, again.step)
    }

    // ── Make this your home screen (lane X2) ────────────────────────────

    private fun atMakeHome(): Pair<ActivityController<StartActivity>, StartActivity> {
        fake.isConnected = true
        fake.model = true
        fake.grants += Grant.entries
        fake.mic = true
        fake.voiceFiles = true
        fake.home = false
        StartPrefs(context).welcomeSeen = true
        val c = start()
        return c to c.get()
    }

    @Test fun theHomeStepComesAfterVoiceAndBeforeTryItAndIsSkippedWhenTheRoleIsHeld() {
        fake.home = false
        assertEquals(StartStep.entries.toList(), StartPlan.steps(fake, false))
        fake.home = true
        assertFalse(StartStep.MAKE_HOME in StartPlan.steps(fake, false))
        fake.isConnected = true; fake.model = true; fake.grants += Grant.entries; fake.mic = true; fake.voiceFiles = true
        fake.home = false
        assertEquals(listOf(StartStep.MAKE_HOME, StartStep.TRY_IT), StartPlan.steps(fake, true))
    }

    @Test fun theHomeStepShowsItsWordsAndNotNowGoesOn() {
        val (_, a) = atMakeHome()
        assertEquals(StartStep.MAKE_HOME, a.step)
        assertEquals("Make this your home screen", a.text(R.id.start_title))
        assertEquals("Then the Home button shows this page. A swipe up finds your apps. You can change it back in Set up.",
            a.text(R.id.start_text))
        assertEquals("Make it home", a.text(R.id.start_primary))
        assertEquals("Not now", a.text(R.id.start_pale))
        a.tap(R.id.start_pale)
        assertEquals(StartStep.TRY_IT, a.step)
    }

    @Test fun makeItHomeOpensAndroidsHomePageAndContinuesWhenTheRoleIsHeld() {
        val (c, a) = atMakeHome()
        startedActivities()
        a.tap(R.id.start_primary)
        assertEquals(android.provider.Settings.ACTION_HOME_SETTINGS, startedActivities().last().action)
        // Back without choosing: the step stays, and "Not now" still finishes the start.
        a.resume(c)
        assertEquals(StartStep.MAKE_HOME, a.step)
        fake.home = true
        a.resume(c)
        assertEquals(StartStep.TRY_IT, a.step)
    }

    @Test fun startDoneSurvivesRecreation() {
        StartPrefs(context).done = true
        assertTrue(StartPrefs(context).done)
        val c = Robolectric.buildActivity(StartActivity::class.java).setup()
        assertTrue(c.get().isFinishing)
    }

    @Test fun noTextBoxAndAtMostOnePrimaryButtonOnEveryStep() {
        val a = start().get()
        repeat(StartStep.entries.size) {
            val views = allViews(a.window.decorView)
            assertTrue(views.none { it is EditText })
            val primaryBg = context.getDrawable(R.drawable.bg_btn_primary)!!.constantState
            val primaries = views.filter { it is Button && it.isShown && it.background?.constantState == primaryBg }
            assertTrue("step ${a.step}", primaries.size <= 1)
            if (a.step != StartStep.TRY_IT) a.next()
        }
        assertEquals(StartStep.TRY_IT, a.step)
    }

    @Test fun startStringsHaveNoEmojiAndNoExclamationMarks() {
        val file = listOf("src/main/res/values/strings_start.xml", "app/src/main/res/values/strings_start.xml")
            .map(::File).first { it.exists() }
        val values = Regex("<string name=\"[^\"]+\">([^<]*)</string>").findAll(file.readText()).map { it.groupValues[1] }.toList()
        assertTrue(values.isNotEmpty())
        for (v in values) {
            assertFalse(v, v.contains('!'))
            assertTrue(v, v.codePoints().allMatch { it < 0x2000 })
        }
    }

    // ── StartChecks (the home page's routing) ───────────────────────────

    private fun home(): ActivityController<ControlPanelActivity> {
        val feed = FakeFeed()
        FeedLocator.feed = feed
        FeedLocator.commands = feed
        return Robolectric.buildActivity(ControlPanelActivity::class.java).setup().also { idle() }
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun classes(intents: List<Intent>) = intents.mapNotNull { it.component?.className }

    @Test fun homeOpensTheStartOnceWhenNotDone() {
        val c = home()
        advance(StartChecks.BACKEND_CHECK_DELAY_MS + 500)
        val started = classes(startedActivities())
        assertEquals(1, started.count { it == StartActivity::class.java.name })
        assertFalse(SetupActivity::class.java.name in started)
        assertFalse(ai.opencyclon.ui.FirstRunActivity::class.java.name in started)

        c.pause().resume(); idle()
        advance(StartChecks.BACKEND_CHECK_DELAY_MS + 500)
        assertFalse(StartActivity::class.java.name in classes(startedActivities()))
    }

    @Test fun homeOpensTheStartAtOnceOnANewInstallation() {
        home() // no model, start never begun: no wait for the backend check
        assertEquals(listOf(StartActivity::class.java.name), classes(startedActivities()))
    }

    @Test fun aReturningOwnerWhoseConnectionArrivesLateDoesNotSeeTheStart() {
        val config = ConfigRepository(context)
        config.apiProvider = ConfigRepository.PROVIDER_OPENAI
        config.apiKey = "sk-test"
        var backend: String? = null
        StartChecks.backendNameOverride = { backend }
        home()
        advance(1000)
        assertFalse(StartActivity::class.java.name in classes(startedActivities()))
        backend = "shizuku"
        advance(StartChecks.BACKEND_CHECK_DELAY_MS)
        assertFalse(StartActivity::class.java.name in classes(startedActivities()))
        assertTrue(StartPrefs(context).done)
    }

    @Test fun homeOpensTheStartAgainWhenAnotherScreenClearedItAway() {
        val c = home()
        advance(StartChecks.BACKEND_CHECK_DELAY_MS + 500)
        startedActivities()
        val s = start()
        s.get().finish()
        s.pause().stop().destroy()
        c.pause().resume(); idle()
        assertEquals(listOf(StartActivity::class.java.name), classes(startedActivities()))
    }

    @Test fun aReturningOwnerDoesNotSeeTheStart() {
        val config = ConfigRepository(context)
        config.apiProvider = ConfigRepository.PROVIDER_OPENAI
        config.apiKey = "sk-test"
        StartChecks.backendNameOverride = { "shizuku" }
        home()
        advance(StartChecks.BACKEND_CHECK_DELAY_MS + 500)
        assertFalse(StartActivity::class.java.name in classes(startedActivities()))
        assertTrue(StartPrefs(context).done)
    }

    @Test fun whenDoneTheHomeKeepsThePairingPromptForALostConnection() {
        StartPrefs(context).done = true
        val config = ConfigRepository(context)
        config.apiProvider = ConfigRepository.PROVIDER_OPENAI
        config.apiKey = "sk-test"
        home()
        advance(StartChecks.BACKEND_CHECK_DELAY_MS + 500)
        val started = classes(startedActivities())
        assertFalse(StartActivity::class.java.name in started)
        assertTrue(SetupActivity::class.java.name in started)
    }
}
