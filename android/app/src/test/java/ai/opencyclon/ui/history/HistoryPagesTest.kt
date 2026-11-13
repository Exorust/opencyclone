package ai.opencyclon.ui.history

import ai.opencyclon.R
import ai.opencyclon.config.ConfigRepository
import ai.opencyclon.feed.FakeFeed
import ai.opencyclon.feed.FeedLocator
import ai.opencyclon.feed.HistoryEntry
import ai.opencyclon.feed.HistoryStatus
import ai.opencyclon.feed.LiveState
import ai.opencyclon.feed.Phase
import ai.opencyclon.feed.RunningTask
import ai.opencyclon.ui.ControlPanelActivity
import ai.opencyclon.ui.home.HomePager
import ai.opencyclon.ui.screens.MoreActivity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** The three pages (F15.1, F15.7, F16.1), the history page (F15.1, F15.3, F15.4, F15.6), and the menu word. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class HistoryPagesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var feed: FakeFeed
    private lateinit var config: ConfigRepository

    @Before fun setUp() {
        feed = FakeFeed()
        FeedLocator.feed = feed
        FeedLocator.commands = feed
        ControlPanelActivity.clock = { at(23, 14) }
        HistoryPageView.clock = { at(23, 14) }
        config = ConfigRepository(context)
        config.apiProvider = ConfigRepository.PROVIDER_OPENAI
        config.apiKey = "sk-test"
    }

    @After fun tearDown() {
        ControlPanelActivity.clock = System::currentTimeMillis
        HistoryPageView.clock = System::currentTimeMillis
        config.apiKey = ""
    }

    /** Friday, October 23, 2026 at [hour]:41 (the week starts on Sunday the 18th in the test locale). */
    private fun at(day: Int, hour: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, day, hour, 41, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun start(intent: Intent? = null): ActivityController<ControlPanelActivity> =
        (if (intent == null) Robolectric.buildActivity(ControlPanelActivity::class.java)
        else Robolectric.buildActivity(ControlPanelActivity::class.java, intent)).setup().also { idle() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    private fun allViews(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else listOf(v)

    private fun texts(v: View) = allViews(v).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }

    private fun startedActivities(): List<Intent> {
        val app = shadowOf(context as Application)
        return generateSequence { app.nextStartedActivity }.toList()
    }

    /** A drag across the pager by [dx], [dy] pixels from its middle. */
    private fun drag(pager: HomePager, dx: Float, dy: Float = 0f, startX: Float = pager.width / 2f) {
        val y = pager.height / 2f
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int, x: Float, yy: Float, dt: Long) = MotionEvent.obtain(t, t + dt, action, x, yy, 0)
        pager.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, startX, y, 0))
        for (i in 1..10) pager.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, startX + dx * i / 10, y + dy * i / 10, i * 16L))
        pager.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, startX + dx, y + dy, 200))
        settle()
    }

    private fun entry(id: Long, title: String, at: Long, status: HistoryStatus = HistoryStatus.DONE, pkgs: List<String> = emptyList()) =
        HistoryEntry(id, at, title, status, pkgs, null)

    private fun sample() {
        feed.historyEntries.value = listOf(
            entry(1, "Open Gate Test and send Alex the file", at(23, 10), pkgs = listOf("com.whatsapp")),
            entry(2, "Reply to Nina", at(23, 9), HistoryStatus.RUNNING),
            entry(3, "Book the table", at(22, 20), HistoryStatus.NOT_SENT),
            entry(4, "Return the lamp", at(20, 12), HistoryStatus.FAILED),
            entry(5, "Old one", at(10, 12), HistoryStatus.STOPPED),
        )
    }

    // ── Paging ──────────────────────────────────────────────────────────

    @Test fun opensOnHomeAndSwipesShowHistoryAndWidgets() {
        val a = start().get()
        val pager = a.pager()
        assertEquals(HomePager.HOME, pager.page)
        assertTrue(a.findViewById<View>(R.id.home_root).isShown)
        assertFalse(a.historyPage().isShown)

        drag(pager, pager.width * 0.6f)
        assertEquals(HomePager.HISTORY, pager.page)
        assertTrue(a.historyPage().isShown)
        assertFalse(a.findViewById<View>(R.id.home_root).isShown)
        assertEquals("History", a.findViewById<TextView>(R.id.hist_title).text.toString())

        drag(pager, -pager.width * 0.6f)
        assertEquals(HomePager.HOME, pager.page)
        drag(pager, -pager.width * 0.6f)
        assertEquals(HomePager.WIDGETS, pager.page)
    }

    @Test fun backOnHistoryOrWidgetsReturnsToHome() {
        val a = start().get()
        a.pager().show(HomePager.HISTORY, animate = false)
        assertTrue(a.isBackHandled())
        a.onBackPressedDispatcher.onBackPressed()
        settle()
        assertEquals(HomePager.HOME, a.pager().page)
        a.pager().show(HomePager.WIDGETS, animate = false)
        a.onBackPressedDispatcher.onBackPressed()
        settle()
        assertEquals(HomePager.HOME, a.pager().page)
        assertFalse(a.isBackHandled())
    }

    @Test fun theOpenPanelBlocksPaging() {
        feed.state.value = LiveState(running = listOf(RunningTask(9, "Send photos", "Picking", listOf("com.whatsapp"), 0, Phase.RUNNING)))
        val a = start().get()
        a.findViewById<View>(R.id.home_pill).performClick()
        settle()
        assertTrue(a.findViewById<ai.opencyclon.ui.widget.RunningPanelView>(R.id.home_panel).isOpen)
        drag(a.pager(), a.pager().width * 0.6f)
        assertEquals(HomePager.HOME, a.pager().page)
    }

    @Test fun theTypeInputBlocksPaging() {
        val a = start().get()
        a.findViewById<View>(R.id.footer_type).performClick()
        idle()
        assertTrue(a.isInputOpen())
        drag(a.pager(), a.pager().width * 0.6f)
        assertEquals(HomePager.HOME, a.pager().page)
        assertTrue(a.isInputOpen())
    }

    @Test fun theAppSearchBlocksPaging() {
        val a = start().get()
        a.appSearch().open()
        idle()
        drag(a.pager(), a.pager().width * 0.6f)
        assertEquals(HomePager.HOME, a.pager().page)
    }

    @Test fun aMostlyVerticalMoveNeverPages() {
        val a = start().get()
        drag(a.pager(), a.pager().width * 0.4f, a.pager().width * 0.3f)
        assertEquals(HomePager.HOME, a.pager().page)
    }

    @Test fun theIntentExtraOpensTheHistoryPageAndAPlainIntentReturnsToHome() {
        val c = start(ControlPanelActivity.historyIntent(context))
        assertEquals(HomePager.HISTORY, c.get().pager().page)
        c.newIntent(Intent(context, ControlPanelActivity::class.java))
        idle()
        assertEquals(HomePager.HOME, c.get().pager().page)
    }

    @Test fun theWidgetsSlotHoldsTheWidgetsPage() {
        val a = start().get()
        val slot = a.findViewById<ViewGroup>(R.id.page_widgets_slot)
        assertEquals(1, slot.childCount)
        assertTrue(slot.getChildAt(0) is ai.opencyclon.ui.widgets.WidgetsPageView)
    }

    // ── Menu ────────────────────────────────────────────────────────────

    @Test fun theMenuWordOnHomeOpensTheListOfScreens() {
        val a = start().get()
        val menu = a.findViewById<TextView>(R.id.home_menu)
        assertEquals("menu", menu.text.toString())
        assertEquals("Menu", menu.contentDescription)
        assertTrue(menu.isShown)
        assertTrue(menu.layoutParams.height >= (48 * a.resources.displayMetrics.density).toInt())
        startedActivities()
        menu.performClick()
        assertEquals(MoreActivity::class.java.name, startedActivities().single().component?.className)
    }

    @Test fun theMenuWordOnHistoryOpensTheListOfScreens() {
        val a = start().get()
        a.pager().show(HomePager.HISTORY, animate = false)
        val menu = a.findViewById<TextView>(R.id.hist_menu)
        assertEquals("menu", menu.text.toString())
        assertTrue(menu.isShown)
        startedActivities()
        menu.performClick()
        assertEquals(MoreActivity::class.java.name, startedActivities().single().component?.className)
    }

    @Test fun historyInTheListOfScreensOpensTheHistoryPage() {
        val more = Robolectric.buildActivity(MoreActivity::class.java).setup().get()
        val row = allViews(more.window.decorView).first { it.id == R.id.row_text }
        row.performClick()
        val started = shadowOf(more).nextStartedActivity
        assertEquals(ControlPanelActivity::class.java.name, started.component?.className)
        assertEquals(ControlPanelActivity.PAGE_HISTORY, started.getStringExtra(ControlPanelActivity.EXTRA_PAGE))
    }

    // ── History page ────────────────────────────────────────────────────

    @Test fun nothingYetOnANewInstallation() {
        val a = start().get()
        a.pager().show(HomePager.HISTORY, animate = false)
        idle()
        val page = a.historyPage()
        assertTrue(page.searchField().isShown)
        assertEquals("Nothing yet. Your tasks will be listed here.", page.emptyText().text.toString())
        assertTrue(page.emptyText().isShown)
        assertFalse(page.searchField().hasFocus())
    }

    @Test fun rowsAreGroupedByDayNewestFirstWithStatusWords() {
        sample()
        val a = start().get()
        a.pager().show(HomePager.HISTORY, animate = false)
        idle()
        val list = a.historyPage().listView()
        assertEquals(listOf(
            "Today",
            "Open Gate Test and send Alex the file", "10:41 AM · Done",
            "Reply to Nina", "9:41 AM · Running",
            "Yesterday",
            "Book the table", "8:41 PM · Not sent",
            "Earlier this week",
            "Return the lamp", "Tue 12:41 PM · Failed",
            "Older",
            "Old one", "Oct 10 · Stopped",
        ), texts(list))
        assertFalse(a.historyPage().emptyText().isShown)
    }

    @Test fun everyStatusHasItsWord() {
        assertEquals(listOf("Done", "Not sent", "Failed", "Stopped", "Running", "Waiting for you"),
            HistoryStatus.entries.map { context.getString(HistoryPageView.statusWord(it)) })
    }

    @Test fun dayGroupsAtTheEdges() {
        val now = at(23, 14)
        assertEquals(HistoryPageView.TODAY, HistoryPageView.groupOf(at(23, 0) - 41 * 60_000L, now))
        assertEquals(HistoryPageView.YESTERDAY, HistoryPageView.groupOf(at(23, 0) - 41 * 60_000L - 1, now))
        assertEquals(HistoryPageView.THIS_WEEK, HistoryPageView.groupOf(at(18, 1), now))
        assertEquals(HistoryPageView.OLDER, HistoryPageView.groupOf(at(17, 23), now))
    }

    @Test fun searchKeepsMatchesInBoldAndSaysWhenNothingMatches() {
        sample()
        feed.searchText[3] = "Booked for Alex"
        val a = start().get()
        a.pager().show(HomePager.HISTORY, animate = false)
        val page = a.historyPage()
        page.searchField().setText("alex")
        idle()
        val shown = texts(page.listView())
        assertEquals(listOf("Today", "Open Gate Test and send Alex the file", "10:41 AM · Done",
            "Yesterday", "Book the table", "8:41 PM · Not sent"), shown)
        val title = allViews(page.listView()).filterIsInstance<TextView>().first { it.text.toString().startsWith("Open Gate") }
        val spans = (title.text as Spanned).getSpans(0, title.text.length, TypefaceSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals("Alex", title.text.subSequence((title.text as Spanned).getSpanStart(spans[0]), (title.text as Spanned).getSpanEnd(spans[0])).toString())

        page.searchField().setText("zzqx")
        idle()
        assertEquals("No task matches.", page.emptyText().text.toString())
        assertTrue(page.emptyText().isShown)

        page.searchField().setText("")
        idle()
        assertEquals(5, texts(page.listView()).count { it.contains(" · ") })
    }

    private fun row(a: ControlPanelActivity, title: String): View =
        allViews(a.historyPage().listView()).first { it.id == R.id.hist_row && texts(it).contains(title) }

    private fun sheetOptions(a: ControlPanelActivity): List<TextView> =
        allViews(a.historyPage().lastDialog!!.window!!.decorView).filterIsInstance<TextView>().filter { it.isClickable }

    @Test fun pressAndHoldOffersAskAgainAndDeleteWhichAsksFirst() {
        sample()
        val a = start().get()
        a.pager().show(HomePager.HISTORY, animate = false)
        idle()
        row(a, "Book the table").performLongClick()
        idle()
        val options = sheetOptions(a)
        assertEquals(listOf("Ask again", "Delete"), options.map { it.text.toString() })
        options[1].performClick()
        idle()
        val confirm = a.historyPage().lastDialog as AlertDialog
        assertTrue(confirm.isShowing)
        assertTrue(feed.deleted.isEmpty())
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(listOf(3L), feed.deleted)
        assertTrue(texts(a.historyPage().listView()).none { it == "Book the table" })
    }

    @Test fun aRunningTaskOffersStopNotDelete() {
        sample()
        val a = start().get()
        a.pager().show(HomePager.HISTORY, animate = false)
        idle()
        row(a, "Reply to Nina").performLongClick()
        idle()
        val options = sheetOptions(a)
        assertEquals(listOf("Ask again", "Stop"), options.map { it.text.toString() })
        options[1].performClick()
        assertEquals(listOf(2L), feed.stopped)
    }

    @Test fun askAgainSubmitsTheWordsAndShowsHome() {
        sample()
        val a = start().get()
        a.pager().show(HomePager.HISTORY, animate = false)
        idle()
        row(a, "Book the table").performLongClick()
        idle()
        sheetOptions(a)[0].performClick()
        settle()
        assertEquals(listOf(3L), feed.askedAgain)
        assertEquals(listOf("Book the table"), feed.submitted)
        assertEquals(HomePager.HOME, a.pager().page)
    }

    @Test fun aTapOpensTheRecord() {
        sample()
        val a = start().get()
        a.pager().show(HomePager.HISTORY, animate = false)
        idle()
        startedActivities()
        row(a, "Book the table").performClick()
        val started = startedActivities().single()
        assertEquals(TaskRecordActivity::class.java.name, started.component?.className)
        assertEquals(3L, started.getLongExtra(TaskRecordActivity.EXTRA_TASK_ID, 0))
        assertNotNull(started)
    }
}
