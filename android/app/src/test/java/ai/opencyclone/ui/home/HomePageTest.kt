package ai.opencyclone.ui.home

import ai.opencyclone.R
import ai.opencyclone.config.ConfigRepository
import ai.opencyclone.feed.Evidence
import ai.opencyclone.feed.FakeFeed
import ai.opencyclone.feed.FeedLocator
import ai.opencyclone.feed.LiveState
import ai.opencyclone.feed.Phase
import ai.opencyclone.feed.QuestionView
import ai.opencyclone.feed.RunningTask
import ai.opencyclone.feed.ThreadItem
import ai.opencyclone.feed.ThreadKind
import ai.opencyclone.feed.ThreadState
import ai.opencyclone.feed.WindowView
import ai.opencyclone.ui.ControlPanelActivity
import ai.opencyclone.ui.theme.Wash
import ai.opencyclone.ui.theme.currentWash
import ai.opencyclone.ui.voice.ListeningActivity
import ai.opencyclone.ui.widget.RunningPanelView
import ai.opencyclone.ui.widget.RunningPillView
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
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
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomePageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var feed: FakeFeed
    private lateinit var config: ConfigRepository

    @Before fun setUp() {
        feed = FakeFeed()
        FeedLocator.feed = feed
        FeedLocator.commands = feed
        ControlPanelActivity.clock = { at(14) }
        ControlPanelActivity.spokenQuestions.clear()
        ControlPanelActivity.dismissed.clear()
        config = ConfigRepository(context)
        config.apiProvider = ConfigRepository.PROVIDER_OPENAI
        config.apiKey = "sk-test"
    }

    @After fun tearDown() {
        ControlPanelActivity.clock = System::currentTimeMillis
        config.apiKey = ""
    }

    private fun at(hour: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 23, hour, 41, 0)
    }.timeInMillis

    private fun start(): ActivityController<ControlPanelActivity> =
        Robolectric.buildActivity(ControlPanelActivity::class.java).setup().also { idle() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun allViews(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else listOf(v)

    // Version 2: the home page is the middle page; the history page beside it has its own search field.
    private fun ControlPanelActivity.views() = allViews(findViewById(R.id.home_root))
    private fun ControlPanelActivity.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun ControlPanelActivity.thread() = findViewById<RecyclerView>(R.id.home_thread)

    /** A later list is diffed on a background thread: wait for it. */
    private fun awaitCount(a: ControlPanelActivity, count: Int) {
        val end = System.currentTimeMillis() + 5000
        while (a.threadAdapter().itemCount != count && System.currentTimeMillis() < end) {
            Thread.sleep(10)
            idle()
        }
    }

    private fun startedActivities(): List<Intent> {
        val app = shadowOf(context as Application)
        return generateSequence { app.nextStartedActivity }.toList()
    }

    // ── Idle page ───────────────────────────────────────────────────────

    @Test fun theStartsTypeExtraOpensTheTypeInput() {
        val intent = Intent(context, ControlPanelActivity::class.java)
            .putExtra(ai.opencyclone.ui.start.StartActivity.EXTRA_OPEN_TYPE, true)
        val a = Robolectric.buildActivity(ControlPanelActivity::class.java, intent).setup().also { idle() }.get()
        assertTrue(a.isInputOpen())
    }

    @Test fun withoutTheExtraTheTypeInputStaysClosedAndANewIntentWithItOpensIt() {
        val c = start()
        val a = c.get()
        assertFalse(a.isInputOpen())
        c.newIntent(Intent(context, ControlPanelActivity::class.java)
            .putExtra(ai.opencyclone.ui.start.StartActivity.EXTRA_OPEN_TYPE, true))
        idle()
        assertTrue(a.isInputOpen())
    }

    @Test fun greetingFollowsTheHour() {
        assertEquals(R.string.home_greeting_morning, HomeRules.greeting(5))
        assertEquals(R.string.home_greeting_morning, HomeRules.greeting(11))
        assertEquals(R.string.home_greeting_afternoon, HomeRules.greeting(12))
        assertEquals(R.string.home_greeting_afternoon, HomeRules.greeting(17))
        assertEquals(R.string.home_greeting_evening, HomeRules.greeting(18))
        assertEquals(R.string.home_greeting_evening, HomeRules.greeting(4))

        for ((hour, words) in listOf(9 to "Good morning.", 14 to "Good afternoon.", 21 to "Good evening.")) {
            ControlPanelActivity.clock = { at(hour) }
            val a = start().get()
            assertEquals(words, a.text(R.id.header_greeting))
        }
    }

    @Test fun idlePageHasNoTextBoxNoButtonAndAFooter() {
        val a = start().get()
        assertEquals("All caught up.", a.text(R.id.header_status))
        assertEquals("2:41 PM", a.text(R.id.header_time))
        assertEquals("Fri, Oct 23", a.text(R.id.header_date))
        assertTrue(a.views().none { it is EditText })
        assertTrue(a.views().none { it is Button && it.isShown })
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.home_footer).visibility)
        // Robolectric holds no assistant role: the footer offers a tap.
        assertEquals("Tap to talk", a.text(R.id.footer_talk))
        assertEquals("type", a.text(R.id.footer_type))
        assertEquals(View.GONE, a.findViewById<View>(R.id.home_pill).visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.home_tiles_scroll).visibility)
        assertEquals(ContextCompat.getColor(a, R.color.ink_2), a.findViewById<TextView>(R.id.header_status).currentTextColor)
    }

    @Test fun tapOnTheVoiceWordsOpensListening() {
        val a = start().get()
        startedActivities()
        a.findViewById<View>(R.id.footer_talk).performClick()
        val started = startedActivities()
        assertEquals(ListeningActivity::class.java.name, started.single().component?.className)
        assertNull(started.single().getStringExtra(ListeningActivity.EXTRA_QUESTION))
    }

    // ── Sample content ──────────────────────────────────────────────────

    @Test fun sampleShowsTilesPillAndEveryThreadItem() {
        feed.fillSample()
        val a = start().get()
        val tiles = a.findViewById<LinearLayout>(R.id.home_tiles)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.home_tiles_scroll).visibility)
        assertEquals(feed.state.value.apps.size, tiles.childCount)
        val pill = a.findViewById<RunningPillView>(R.id.home_pill)
        assertEquals(View.VISIBLE, pill.visibility)
        assertEquals("1 running · 1 waiting ›", pill.text())
        assertEquals(feed.items.value.size, a.threadAdapter().itemCount)
        assertEquals("Lisbon in 3 weeks. Nothing else needs you.", a.text(R.id.header_status))
    }

    @Test fun updatingTileIsDimmedAndATapOpensTheApp() {
        feed.state.value = LiveState(apps = listOf(
            ai.opencyclone.feed.AppTile(1, "Lingo", ai.opencyclone.miniapp.BuiltAppStyle.DEFAULT_ACCENT, updating = true),
            ai.opencyclone.feed.AppTile(2, "Budget", "#2F6FDB"),
        ))
        val a = start().get()
        val tiles = a.findViewById<LinearLayout>(R.id.home_tiles)
        assertEquals(0.6f, tiles.getChildAt(0).alpha, 0.001f)
        assertEquals(1f, tiles.getChildAt(1).alpha, 0.001f)
        startedActivities()
        tiles.getChildAt(1).performClick()
        assertEquals(2L, startedActivities().single().getLongExtra(ai.opencyclone.miniapp.MiniAppActivity.EXTRA_APP_ID, -1))
    }

    @Test fun footerHidesWhileTheThreadEndsInAnAppChange() {
        feed.items.value = listOf(
            ThreadItem(1, at(14), ThreadKind.OWNER, text = "Add Portuguese."),
            ThreadItem(2, at(14), ThreadKind.PROGRESS, ThreadState.OPEN, text = "Updating Lingo", detail = "~1 min", openAppId = 1),
        )
        val a = start().get()
        assertEquals(View.INVISIBLE, a.findViewById<View>(R.id.home_footer).visibility)
    }

    @Test fun headerAndTilesHaveAccessibleTargetsAndIdleStatusHasNoAction() {
        feed.state.value = LiveState(apps = listOf(ai.opencyclone.feed.AppTile(1, "A", "#4733D6")))
        val a = start().get()
        val minimum = a.resources.getDimensionPixelSize(R.dimen.button_height)
        assertTrue(a.findViewById<TextView>(R.id.header_greeting).minHeight >= minimum)
        val status = a.findViewById<TextView>(R.id.header_status)
        assertFalse(status.isClickable)
        val tile = a.findViewById<LinearLayout>(R.id.home_tiles).getChildAt(0) as LinearLayout
        assertTrue(tile.minimumWidth >= minimum)
        assertTrue(tile.minimumHeight >= minimum)
        assertEquals(a.resources.getDimensionPixelSize(R.dimen.icon_tile), tile.getChildAt(0).layoutParams.width)
        feed.state.value = LiveState(status = ai.opencyclone.feed.StatusSentence.NOT_CONNECTED)
        idle()
        assertTrue(status.isClickable)
        assertTrue(status.minimumHeight >= minimum)
        feed.state.value = LiveState()
        idle()
        assertFalse(status.isClickable)
    }

    // ── Status ──────────────────────────────────────────────────────────

    @Test fun statusChangeFadesAndRevealsAndColourFollowsWork() {
        val a = start().get()
        val status = a.findViewById<TextView>(R.id.header_status)
        assertEquals(0, a.statusChanges())
        feed.state.value = LiveState(
            status = "Sam asked for photos. I'm finding them.",
            running = listOf(RunningTask(7, "Send photos", "Picking the photos", listOf(FakeFeed.WHATSAPP), at(14), Phase.RUNNING)),
        )
        idle()
        assertEquals(1, a.statusChanges())
        advance(3000)
        assertEquals("Sam asked for photos. I'm finding them.", status.text.toString())
        assertEquals(ContextCompat.getColor(a, R.color.ink), status.currentTextColor)

        feed.state.value = LiveState(status = "All caught up.")
        idle()
        advance(3000)
        assertEquals(2, a.statusChanges())
        assertEquals(ContextCompat.getColor(a, R.color.ink_2), status.currentTextColor)
    }

    // ── Wash ────────────────────────────────────────────────────────────

    @Test fun washFollowsHourAndWork() {
        val idle = LiveState()
        assertEquals(Wash.Warm, HomeRules.wash(idle, 9, 0))
        assertEquals(Wash.Cool, HomeRules.wash(idle, 19, 0))
        assertEquals(Wash.Cool, HomeRules.wash(idle, 2, 0))
        val working = LiveState(running = listOf(RunningTask(1, "g", "c", emptyList(), 0, Phase.RUNNING)))
        assertEquals(Wash.Cool, HomeRules.wash(working, 9, 0))
        assertEquals(Wash.Cool, HomeRules.wash(LiveState(question = QuestionView(1, "Which one?")), 9, 0))
        assertEquals(Wash.Cool, HomeRules.wash(LiveState(window = WindowView(100, 0, 0, 0)), 9, 50))
        assertEquals(Wash.Warm, HomeRules.wash(LiveState(window = WindowView(100, 0, 0, 0)), 9, 200))

        ControlPanelActivity.clock = { at(9) }
        val morning = start().get()
        assertEquals(Wash.Warm, morning.findViewById<View>(R.id.home_root).currentWash)
        feed.state.value = working
        idle()
        assertEquals(Wash.Cool, morning.findViewById<View>(R.id.home_root).currentWash)

        feed.state.value = idle
        ControlPanelActivity.clock = { at(19) }
        val evening = start().get()
        assertEquals(Wash.Cool, evening.findViewById<View>(R.id.home_root).currentWash)
    }

    // ── Type input ──────────────────────────────────────────────────────

    @Test fun typeOpensTheInputAndSendSubmitsOnce() {
        val a = start().get()
        a.findViewById<View>(R.id.footer_type).performClick()
        idle()
        assertTrue(a.isInputOpen())
        val field = a.views().filterIsInstance<EditText>().single()
        assertEquals(View.INVISIBLE, a.findViewById<View>(R.id.home_footer).visibility)
        field.setText("Send Sam the photos")
        a.findViewById<Button>(R.id.type_send).performClick()
        idle()
        assertEquals(listOf("Send Sam the photos"), feed.submitted)
        assertFalse(a.isInputOpen())
        assertTrue(a.views().none { it is EditText })
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.home_footer).visibility)
    }

    @Test fun emptySendDoesNothingAndBackHidesTheInput() {
        val a = start().get()
        a.findViewById<View>(R.id.footer_type).performClick()
        idle()
        a.findViewById<Button>(R.id.type_send).performClick()
        assertTrue(feed.submitted.isEmpty())
        assertTrue(a.isInputOpen())
        a.onBackPressedDispatcher.onBackPressed()
        idle()
        assertFalse(a.isInputOpen())
        assertTrue(a.views().none { it is EditText })
        assertTrue(feed.submitted.isEmpty())
    }

    @Test fun waitingQuestionChangesTheFooterWords() {
        feed.state.value = LiveState(question = QuestionView(3, "Which album?"))
        val a = start().get()
        assertEquals("Answer by voice", a.text(R.id.footer_talk))
        assertEquals("type an answer", a.text(R.id.footer_type))
    }

    // ── Approval ────────────────────────────────────────────────────────

    private fun approvalItem() = ThreadItem(
        4, at(14), ThreadKind.APPROVAL, ThreadState.OPEN, taskId = 11, appPackage = FakeFeed.WHATSAPP,
        text = "9 photos to Sam. Send them?", actionWord = "Send", evidence = Evidence(count = 9),
    )

    private fun ControlPanelActivity.layoutThread() {
        val t = thread()
        t.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
        t.layout(0, 0, 1080, 1800)
    }

    @Test fun approveCallsApproveAndAFalseResultAsksToUnlock() {
        feed.items.value = listOf(approvalItem())
        val a = start().get()
        a.layoutThread()
        a.findViewById<Button>(R.id.approval_primary).performClick()
        idle()
        assertEquals(listOf(11L), feed.approved)
        assertEquals(View.GONE, a.findViewById<View>(R.id.home_notice).visibility)

        feed.approveResult = false
        a.findViewById<Button>(R.id.approval_primary).performClick()
        idle()
        assertEquals(listOf(11L, 11L), feed.approved)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.home_notice).visibility)
        assertEquals("Unlock the phone to approve.", a.text(R.id.home_notice))
        advance(ControlPanelActivity.NOTICE_MS + 500)
        assertEquals(View.GONE, a.findViewById<View>(R.id.home_notice).visibility)
    }

    @Test fun notNowRejectsAndEvidenceOpensTheLiveView() {
        feed.items.value = listOf(approvalItem())
        val a = start().get()
        a.layoutThread()
        a.findViewById<Button>(R.id.approval_not_now).performClick()
        assertEquals(listOf(11L), feed.rejected)
        a.threadAdapter().onOpen(approvalItem())
        assertEquals(listOf(11L), feed.openedLive)
    }

    @Test fun dismissHidesTheItemForThisSession() {
        feed.items.value = listOf(
            ThreadItem(1, at(14), ThreadKind.SENTENCE, text = "One."),
            ThreadItem(2, at(14), ThreadKind.SHARED_APP, ThreadState.OPEN, title = "Priya", text = "Priya made a planner.", openAppId = 3),
        )
        val a = start().get()
        assertEquals(2, a.threadAdapter().itemCount)
        a.threadAdapter().onDismiss(feed.items.value[1])
        awaitCount(a, 1)
        assertEquals(1, a.threadAdapter().itemCount)
    }

    // ── Running pill and panel ──────────────────────────────────────────

    @Test fun pillOpensPanelAndScrimAndPreviewsRunOnlyWhileOpen() {
        feed.state.value = LiveState(
            running = listOf(RunningTask(21, "Send photos", "Picking the photos", listOf(FakeFeed.WHATSAPP), at(14), Phase.RUNNING)),
            status = "I'm finding them.",
        )
        val a = start().get()
        val pill = a.findViewById<RunningPillView>(R.id.home_pill)
        val panel = a.findViewById<RunningPanelView>(R.id.home_panel)
        val scrim = a.findViewById<View>(R.id.home_scrim)
        assertFalse(panel.isOpen)
        assertFalse(panel.isCollecting)
        assertEquals(View.GONE, scrim.visibility)

        pill.performClick()
        advance(RunningPillView.PRESS_MS + 50)
        assertTrue(panel.isOpen)
        assertTrue(panel.isCollecting)
        assertEquals(View.VISIBLE, scrim.visibility)

        panel.stopButton().performClick()
        assertEquals(listOf(21L), feed.stopped)
        panel.previewView().performClick()
        assertEquals(listOf(21L), feed.openedLive)

        scrim.performClick()
        advance(500)
        assertFalse(panel.isOpen)
        assertFalse(panel.isCollecting)
        assertEquals(View.GONE, scrim.visibility)

        // Back also closes it.
        pill.performClick()
        advance(RunningPillView.PRESS_MS + 50)
        assertTrue(panel.isOpen)
        a.onBackPressedDispatcher.onBackPressed()
        advance(500)
        assertFalse(panel.isOpen)
    }

    // ── Secondary screens ───────────────────────────────────────────────

    @Test fun longPressOnTheGreetingStartsTheListScreen() {
        val a = start().get()
        startedActivities()
        assertTrue(a.findViewById<View>(R.id.header_greeting).performLongClick())
        val intent = startedActivities().single()
        assertEquals(ControlPanelActivity.MORE_ACTIVITY, intent.component?.className)
        assertEquals(a.packageName, intent.component?.packageName)
    }

    @Test fun longPressDoesNotCrashWithoutTheListScreen() {
        val a = start().get()
        shadowOf(context as Application).checkActivities(true)
        try {
            assertTrue(a.findViewById<View>(R.id.header_greeting).performLongClick())
        } finally {
            shadowOf(context as Application).checkActivities(false)
        }
    }

    // ── Speaking a question ─────────────────────────────────────────────

    @Test fun questionThatAppearsWhileResumedIsSpokenOnce() {
        feed.items.value = listOf(ThreadItem(1, at(14), ThreadKind.OWNER, text = "Make me a trip planner."))
        val a = start()
        startedActivities()
        feed.state.value = LiveState(question = QuestionView(5, "Which city first?"))
        idle()
        val spoken = startedActivities().single()
        assertEquals(ListeningActivity::class.java.name, spoken.component?.className)
        assertEquals("Which city first?", spoken.getStringExtra(ListeningActivity.EXTRA_QUESTION))
        assertEquals("Make me a trip planner.", spoken.getStringExtra(ListeningActivity.EXTRA_OWNER_WORDS))

        // The same question again, after it went away, is not spoken twice.
        feed.state.value = LiveState()
        idle()
        feed.state.value = LiveState(question = QuestionView(5, "Which city first?"))
        idle()
        assertTrue(startedActivities().isEmpty())
        a.get()
    }

    @Test fun questionIsNotSpokenWhilePaused() {
        val c = start()
        c.pause()
        startedActivities()
        feed.state.value = LiveState(question = QuestionView(6, "Which album?"))
        idle()
        assertTrue(startedActivities().none { it.hasExtra(ListeningActivity.EXTRA_QUESTION) })
    }

    // ── Feed not ready ──────────────────────────────────────────────────

    @Test fun withoutAFeedThePageIsIdleAndTypeSaysItIsStarting() {
        FeedLocator::class.java.getDeclaredField("feed").apply { isAccessible = true }.set(null, null)
        FeedLocator::class.java.getDeclaredField("commands").apply { isAccessible = true }.set(null, null)
        assertFalse(FeedLocator.isReady)
        val a = start().get()
        assertEquals("All caught up.", a.text(R.id.header_status))
        assertEquals(0, a.threadAdapter().itemCount)
        a.findViewById<View>(R.id.footer_type).performClick()
        idle()
        assertFalse(a.isInputOpen())
        assertTrue(a.views().none { it is EditText })
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.home_notice).visibility)
        assertEquals("I am still starting. Try again in a moment.", a.text(R.id.home_notice))
    }

    // ── Rotation ────────────────────────────────────────────────────────

    @Test fun inputOpenSurvivesRecreation() {
        val c = start()
        c.get().findViewById<View>(R.id.footer_type).performClick()
        idle()
        c.recreate()
        idle()
        assertTrue(c.get().isInputOpen())
        assertNotNull(c.get().typeField())
    }
}
