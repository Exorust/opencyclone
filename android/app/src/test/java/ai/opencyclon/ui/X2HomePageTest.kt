package ai.opencyclon.ui

import ai.opencyclon.R
import ai.opencyclon.config.ConfigRepository
import ai.opencyclon.feed.ConfirmRef
import ai.opencyclon.feed.Evidence
import ai.opencyclon.feed.FakeFeed
import ai.opencyclon.feed.FeedLocator
import ai.opencyclon.feed.ThreadItem
import ai.opencyclon.feed.ThreadKind
import ai.opencyclon.feed.ThreadState
import ai.opencyclon.llm.PlanSignIn
import ai.opencyclon.tasks.BuilderQuestionCard
import ai.opencyclon.ui.home.HomePager
import ai.opencyclon.ui.start.StartActivity
import ai.opencyclon.ui.start.StartPrefs
import ai.opencyclon.ui.thread.ThreadAdapter
import ai.opencyclon.ui.widgets.WidgetHost
import ai.opencyclon.widgets.WidgetFlow
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowAlertDialog

/** Lane X2: the home page's sentence rows, the pager's page extra, the thread's look, the builder's question card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class X2HomePageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var feed: FakeFeed
    private lateinit var config: ConfigRepository

    @Before fun setUp() {
        feed = FakeFeed()
        FeedLocator.feed = feed
        FeedLocator.commands = feed
        config = ConfigRepository(context)
        config.apiProvider = ConfigRepository.PROVIDER_OPENAI
        config.apiKey = "sk-test"
    }

    @After fun tearDown() {
        config.apiKey = ""
        StartPrefs(context).signInFromStart = false
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun start(intent: Intent? = null): ActivityController<ControlPanelActivity> =
        (if (intent == null) Robolectric.buildActivity(ControlPanelActivity::class.java)
        else Robolectric.buildActivity(ControlPanelActivity::class.java, intent)).setup().also { idle() }

    private fun allViews(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else listOf(v)

    private fun startedActivities(): List<Intent> {
        val app = shadowOf(context as Application)
        return generateSequence { app.nextStartedActivity }.toList()
    }

    // ── Item 8: the pager's page extra and the widgets page's activity ──

    @Test fun theOpenPageExtraShowsTheWidgetsPageAndOnlyThenItRuns() {
        val c = start(WidgetHost.homeIntent(context, WidgetHost.PAGE_WIDGETS))
        val a = c.get()
        assertEquals(HomePager.WIDGETS, a.pager().page)
        assertTrue(a.widgetsPage().isRunning())
        c.newIntent(WidgetHost.homeIntent(context, WidgetHost.PAGE_HOME))
        idle()
        assertEquals(HomePager.HOME, a.pager().page)
        assertFalse(a.widgetsPage().isRunning())
        c.newIntent(WidgetHost.homeIntent(context, "history"))
        idle()
        assertEquals(HomePager.HISTORY, a.pager().page)
        assertFalse(a.widgetsPage().isRunning())
    }

    @Test fun pagingToWidgetsAndBackTurnsTheWidgetsOnAndOff() {
        val a = start().get()
        assertFalse(a.widgetsPage().isRunning())
        a.pager().show(HomePager.WIDGETS, animate = false)
        assertTrue(a.widgetsPage().isRunning())
        a.pager().show(HomePager.HOME, animate = false)
        assertFalse(a.widgetsPage().isRunning())
    }

    @Test fun aTapOnAMissingWidgetDraftSaysItIsGone() {
        ai.opencyclon.widgets.Widgets.reset()
        feed.items.value = listOf(ThreadItem(9, System.currentTimeMillis(), ThreadKind.RESULT, title = "Lingo streak",
            text = WidgetFlow.DRAFT_READY, detail = WidgetFlow.TAP_TO_SEE, actionWord = WidgetFlow.DRAFT_CARD))
        val a = start().get()
        idle()
        val card = allViews(a.findViewById(R.id.home_thread)).first { it.id == R.id.result_card }
        card.performClick()
        assertTrue(feed.confirmed.isEmpty())
        assertEquals("That draft is gone.", a.findViewById<TextView>(R.id.home_notice).text.toString())
    }

    @Test fun aTapOnAnExistingWidgetDraftStillAsksTheWidgetFlow() {
        kotlinx.coroutines.runBlocking {
            val fixture = ai.opencyclon.widgets.WidgetFixture(this)
            ai.opencyclon.widgets.Widgets.install(fixture.store, fixture.sources, fixture.flow)
            try {
                fixture.replies += listOf(ai.opencyclon.widgets.plan("Clock", "small", emptyList()),
                    ai.opencyclon.widgets.widgetHtml(sources = "clock"))
                fixture.flow.handle("Make a widget with the time")
                feed.items.value = listOf(ThreadItem(9, System.currentTimeMillis(), ThreadKind.RESULT, title = "Clock",
                    text = WidgetFlow.DRAFT_READY, detail = WidgetFlow.TAP_TO_SEE, actionWord = WidgetFlow.DRAFT_CARD))
                val controller = start()
                val card = allViews(controller.get().findViewById(R.id.home_thread)).first { it.id == R.id.result_card }
                card.performClick()
                assertEquals(listOf(9L to true), feed.confirmed)
                controller.pause().stop().destroy()
            } finally {
                ai.opencyclon.widgets.Widgets.reset()
                fixture.close()
            }
        }
    }

    @Test fun tileLongPressOffersRemoveThenAConfirmationThatCanBeCancelled() {
        feed.state.value = ai.opencyclon.feed.LiveState(apps = listOf(ai.opencyclon.feed.AppTile(7, "Lingo", "#4733D6")))
        val controller = start()
        val a = controller.get()
        val tile = a.findViewById<ViewGroup>(R.id.home_tiles).getChildAt(0)
        assertTrue(tile.performLongClick())
        val menu = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertEquals("Remove", menu.listView.adapter.getItem(0))
        menu.listView.performItemClick(menu.listView.getChildAt(0), 0, 0)
        idle()
        val confirm = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(allViews(confirm.window!!.decorView).filterIsInstance<TextView>()
            .any { it.text.toString() == "Remove the Lingo app and its saved data?" })
        confirm.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(1, a.findViewById<ViewGroup>(R.id.home_tiles).childCount)
        assertFalse(confirm.isShowing)
        assertTrue(feed.submitted.isEmpty())
        controller.pause().stop().destroy()
    }

    // ── Notification cards open their app (PM addition) ─────────────────

    @Test fun aTapOnANotificationCardOpensItOrSaysItCouldNot() {
        val sent = mutableListOf<android.app.PendingIntent>()
        val original = ai.opencyclon.proactive.NotificationIntents.send
        ai.opencyclon.proactive.NotificationIntents.send = { _, intent, _ -> sent += intent }
        try {
            val stored = android.app.PendingIntent.getActivity(context, 3, Intent("x"), android.app.PendingIntent.FLAG_IMMUTABLE)
            ai.opencyclon.proactive.NotificationIntents.put(5, "k|5", stored)
            feed.items.value = listOf(
                ThreadItem(5, System.currentTimeMillis(), ThreadKind.NOTIFICATION, ThreadState.OPEN,
                    appPackage = "com.example.mail", title = "Ana", text = "Lunch tomorrow?"),
                ThreadItem(6, System.currentTimeMillis(), ThreadKind.NOTIFICATION, ThreadState.DONE,
                    appPackage = "com.example.not.installed", title = "Bo", text = "Hi"),
            )
            val a = start().get()
            idle()
            val cards = allViews(a.findViewById(R.id.home_thread)).filter { it.contentDescription?.endsWith("Opens the app.") == true }
            assertEquals(2, cards.size)
            assertTrue(cards[0].contentDescription.toString().startsWith("Ana, "))
            cards[0].performClick()
            assertEquals(listOf(stored), sent)
            assertTrue(feed.submitted.isEmpty() && feed.approved.isEmpty())   // the owner acts: no task, no approval
            cards[1].performClick()
            assertEquals("I could not open that.", a.findViewById<TextView>(R.id.home_notice).text.toString())
        } finally {
            ai.opencyclon.proactive.NotificationIntents.send = original
            ai.opencyclon.proactive.NotificationIntents.clear()
        }
    }

    // ── Item 4: no dialog for a missing model ───────────────────────────

    @Test fun typingWithoutAModelShowsNoDialogAndGoesToTheCommands() {
        config.apiKey = ""
        val a = start().get()
        a.findViewById<View>(R.id.footer_type).performClick()
        idle()
        a.typeField()!!.setText("Turn on the flashlight")
        a.findViewById<Button>(R.id.type_send).performClick()
        idle()
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        // The commands write the sentence and the "Set up" row, the same as for spoken words (HomeConfirmsTest).
        assertEquals(listOf("Turn on the flashlight"), feed.submitted)
    }

    @Test fun theSetUpAndAllowRowsShowOnlyTheirButtonAndAnswerThroughConfirm() {
        feed.items.value = listOf(
            ThreadItem(1, 0, ThreadKind.SENTENCE, text = "No model is set, so I did nothing."),
            ThreadItem(2, 0, ThreadKind.CONFIRM, ThreadState.OPEN, title = ConfirmRef.SET_UP, text = "", actionWord = "Set up"),
        )
        val a = start().get()
        idle()
        val thread = a.findViewById<RecyclerView>(R.id.home_thread)
        val question = allViews(thread).filter { it.id == R.id.approval_question }.single()
        assertEquals(View.GONE, question.visibility)
        val setUp = allViews(thread).filterIsInstance<Button>().single { it.id == R.id.approval_primary }
        assertEquals("Set up", setUp.text.toString())
        setUp.performClick()
        assertEquals(listOf(2L to true), feed.confirmed)
    }

    // ── Item 6: long results and approved approvals ─────────────────────

    private val themed = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_OpenCyclon)
    private val parent = RecyclerView(themed).apply { layoutManager = LinearLayoutManager(themed) }

    private fun bindAll(items: List<ThreadItem>): Pair<ThreadAdapter, List<ThreadAdapter.Holder>> {
        val adapter = ThreadAdapter().apply { submitList(items) }
        return adapter to (0 until adapter.itemCount).map { i ->
            adapter.onCreateViewHolder(parent, adapter.getItemViewType(i)).also { adapter.onBindViewHolder(it, i) }
        }
    }

    @Test fun aShortResultIsAHeadingAndALongOneIsBody() {
        val short = ThreadItem(1, 0, ThreadKind.RESULT, taskId = 1, text = "Opened Clock.")
        val sixty = short.copy(text = "x".repeat(60))
        val long = short.copy(text = "x".repeat(61))
        assertEquals(R.style.TextAppearance_App_Heading, ThreadAdapter.sentenceStyle(short))
        assertEquals(R.style.TextAppearance_App_Heading, ThreadAdapter.sentenceStyle(sixty))
        assertEquals(R.style.TextAppearance_App_Body, ThreadAdapter.sentenceStyle(long))
    }

    @Test fun anApprovedApprovalShowsTheResultInPlaceOfTheQuestionAndTheResultRowIsNotRepeated() {
        val result = "Sent Sam 9 photos on WhatsApp."
        val approval = ThreadItem(40, 0, ThreadKind.APPROVAL, ThreadState.APPROVED, taskId = 77, appPackage = FakeFeed.WHATSAPP,
            text = "9 photos to Sam. Send them?", actionWord = "Send", evidence = Evidence(count = 9), detail = result)
        val resultRow = ThreadItem(41, 0, ThreadKind.RESULT, taskId = 77, text = result)
        val other = ThreadItem(42, 0, ThreadKind.RESULT, taskId = 78, text = "Opened Clock.")
        val (adapter, holders) = bindAll(listOf(approval, resultRow, other))
        assertEquals(listOf(40L, 42L), adapter.currentList.map { it.id })
        val view = holders[0].itemView
        assertEquals(result, view.findViewById<TextView>(R.id.approval_question).text.toString())
        assertEquals(View.GONE, view.findViewById<View>(R.id.approval_buttons).visibility)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.approval_thumbs).visibility)
    }

    @Test fun anApprovedApprovalWithoutAResultKeepsItsQuestionAndTheResultRowStays() {
        val approval = ThreadItem(40, 0, ThreadKind.APPROVAL, ThreadState.APPROVED, taskId = 77, text = "Send to Sam?",
            actionWord = "Send", evidence = Evidence(count = 1))
        val resultRow = ThreadItem(41, 0, ThreadKind.RESULT, taskId = 77, text = "Sent.")
        val (adapter, holders) = bindAll(listOf(approval, resultRow))
        assertEquals(2, adapter.currentList.size)
        assertEquals("Send to Sam?", holders[0].itemView.findViewById<TextView>(R.id.approval_question).text.toString())
    }

    @Test fun aLongApprovedResultUsesBody() {
        val long = "Sent Sam the nine photos from yesterday's dinner at the harbour on WhatsApp."
        val approval = ThreadItem(40, 0, ThreadKind.APPROVAL, ThreadState.APPROVED, taskId = 77, text = "Send?",
            actionWord = "Send", detail = long)
        assertEquals(long, ThreadAdapter.approvedResult(approval))
        assertEquals(R.style.TextAppearance_App_Body, ThreadAdapter.resultStyle(long))
        assertNull(ThreadAdapter.approvedResult(approval.copy(state = ThreadState.OPEN)))
    }

    // ── Item 5: the builder's question card ─────────────────────────────

    @Test fun aBuilderQuestionPostsTheQuestionCardOnlyWhenTheHomePageIsNotInFront() {
        val manager = context.getSystemService(NotificationManager::class.java)
        assertFalse(BuilderQuestionCard.post(context, "Which language?", homeInFront = true))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertTrue(BuilderQuestionCard.post(context, "Which language?", homeInFront = false))
        val posted = shadowOf(manager).allNotifications.single()
        val extras = posted.extras
        assertEquals("${context.getString(R.string.app_name)} has a question", extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("Which language?", extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        val target = shadowOf(posted.contentIntent).savedIntent
        assertEquals(ControlPanelActivity::class.java.name, target.component?.className)
        BuilderQuestionCard.cancel(context)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    // ── Item 7: the sign-in return link ─────────────────────────────────

    private fun signedIn() = Intent(Intent.ACTION_VIEW, Uri.parse(PlanSignIn.RETURN_URI))
        .setClass(context, FirstRunActivity::class.java)

    @Test fun theSignInReturnGoesBackToTheFirstRunWhenItStartedThere() {
        StartPrefs(context).signInFromStart = true
        val a = Robolectric.buildActivity(FirstRunActivity::class.java, signedIn()).create().get()
        assertTrue(a.isFinishing)
        assertEquals(StartActivity::class.java.name, startedActivities().last().component?.className)
        assertFalse(StartPrefs(context).signInFromStart)
    }

    @Test fun theSignInReturnOpensSetUpOtherwise() {
        val a = Robolectric.buildActivity(FirstRunActivity::class.java, signedIn()).setup().get()
        assertFalse(a.isFinishing)
        assertTrue(startedActivities().none { it.component?.className == StartActivity::class.java.name })
    }
}
