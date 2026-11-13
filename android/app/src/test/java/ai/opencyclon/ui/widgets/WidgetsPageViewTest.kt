package ai.opencyclon.ui.widgets

import ai.opencyclon.R
import ai.opencyclon.feed.FakeFeed
import ai.opencyclon.feed.FeedLocator
import ai.opencyclon.feed.ThreadItem
import ai.opencyclon.feed.ThreadKind
import ai.opencyclon.feed.ThreadState
import ai.opencyclon.ui.PrivacyActivity
import ai.opencyclon.ui.thread.ThreadAdapter
import ai.opencyclon.widgets.WidgetFixture
import ai.opencyclon.widgets.WidgetFlow
import ai.opencyclon.widgets.WidgetSize
import ai.opencyclon.widgets.Widgets
import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetsPageViewTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var f: WidgetFixture

    @Before fun setUp() {
        f = WidgetFixture(scope)
        Widgets.install(f.store, f.sources, f.flow)
        FeedLocator.feed = FakeFeed()
        FeedLocator.commands = FeedLocator.feed as FakeFeed
    }

    @After fun tearDown() {
        Widgets.reset()
        scope.cancel()
        f.close()
    }

    private fun waitFor(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!check()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) error("timed out")
            Thread.sleep(10)
        }
    }

    private fun page(): Pair<Activity, WidgetsPageView> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_OpenCyclon)
        val page = WidgetsPageView(activity)
        activity.setContentView(page, ViewGroup.LayoutParams(1080, 2200))
        shadowOf(Looper.getMainLooper()).idle()
        return activity to page
    }

    private fun layout(page: WidgetsPageView) {
        page.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY))
        page.layout(0, 0, 1080, 2200)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun draftAndSavedPageInflateTheSameClippedCardAndShadow() {
        val id = runBlocking { f.saved("Tasks", WidgetSize.TALL) }
        val (activity, page) = page()
        page.setActive(false)
        waitFor { page.grid().adapter?.itemCount == 1 }
        layout(page)
        val saved = page.grid().getChildAt(0).findViewById<WidgetCardView>(R.id.widget_card)
        runBlocking { f.flow.openSaved(id) }
        val controller = Robolectric.buildActivity(WidgetDraftActivity::class.java).setup()
        val draft = controller.get()
        try {
            waitFor { draft.findViewById<ViewGroup>(R.id.draft_frame).childCount == 1 }
            val preview = draft.findViewById<WidgetCardView>(R.id.widget_card)
            for (card in listOf(saved, preview)) {
                assertTrue(card.clipToOutline)
                assertTrue(card.findViewById<View>(R.id.widget_web) != null)
                assertTrue(card.findViewById<View>(R.id.widget_stopped) != null)
                val background = card.background as android.graphics.drawable.GradientDrawable
                assertEquals(card.resources.getDimension(R.dimen.radius_card), background.cornerRadius, 0f)
                assertEquals(card.context.getColor(R.color.surface), background.color!!.defaultColor)
            }
            assertEquals(saved.elevation, preview.elevation, 0f)
            assertEquals(saved.outlineAmbientShadowColor, preview.outlineAmbientShadowColor)
            assertEquals(saved.outlineSpotShadowColor, preview.outlineSpotShadowColor)
        } finally {
            controller.pause().stop().destroy()
            activity.finish()
        }
    }

    @Test fun hostResizesTallContentOnLoadAndRefreshAndStopsAfterDestroy() {
        val context = ContextThemeWrapper(f.context, R.style.Theme_OpenCyclon)
        val card = android.view.LayoutInflater.from(context).inflate(R.layout.item_widget_card,
            android.widget.FrameLayout(context), false) as WidgetCardView
        val web = card.findViewById<android.widget.FrameLayout>(R.id.widget_web)
        var contentHeight = 210
        lateinit var view: android.webkit.WebView
        val factory = WidgetHost.webViewFactory
        WidgetHost.webViewFactory = { ctx ->
            object : android.webkit.WebView(ctx) {
                override fun evaluateJavascript(script: String, resultCallback: android.webkit.ValueCallback<String>?) {
                    resultCallback?.onReceiveValue(if (script.startsWith("Math.ceil")) contentHeight.toString() else "null")
                }
            }.also { view = it }
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(card)
        val host = WidgetHost(web, "test", "<html></html>", emptyList(), scope, {}, {}, WidgetSize.TALL)
        try {
            fun heightAfterRefresh(cssHeight: Int, expected: Int) {
                contentHeight = cssHeight
                host.refresh()
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
                assertEquals((expected * context.resources.displayMetrics.density).toInt(), card.layoutParams.height)
            }
            view.webViewClient.onPageFinished(view, "https://widget-test.invalid/")
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            assertEquals((210 * context.resources.displayMetrics.density).toInt(), card.layoutParams.height)
            heightAfterRefresh(60, 120)
            heightAfterRefresh(500, 320)
            heightAfterRefresh(180, 180)
            contentHeight = 240
            host.refresh()
            host.destroy()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            assertEquals((180 * context.resources.displayMetrics.density).toInt(), card.layoutParams.height)
        } finally {
            host.destroy()
            WidgetHost.webViewFactory = factory
            activity.finish()
        }
    }

    @Test fun emptyStateSaysHowToAsk() {
        val (_, page) = page()
        val empty = page.findViewById<TextView>(R.id.widgets_empty)
        assertEquals(View.VISIBLE, empty.visibility)
        assertEquals("Nothing here yet. Ask me for a widget.", empty.text.toString())
        assertEquals("Make a widget that shows my streak and my next task.",
            page.findViewById<TextView>(R.id.widgets_example).text.toString())
        assertEquals("Widgets", page.findViewById<TextView>(R.id.widgets_title).text.toString())
    }

    @Test fun gridSpansZeroWebViewsWhenInactiveStoppedAndReorder() {
        val (a, b) = runBlocking { f.saved("Streak", WidgetSize.SMALL) to f.saved("Tasks", WidgetSize.WIDE) }
        val (_, page) = page()
        page.setActive(false)
        waitFor { page.grid().adapter?.itemCount == 2 }
        assertEquals(1, page.spanOf(0))
        assertEquals(2, page.spanOf(1))

        val baseline = WidgetHost.live
        layout(page)
        assertEquals(0, page.liveHosts())
        assertEquals(baseline, WidgetHost.live)

        val createdBefore = WidgetHost.created
        page.setActive(true)
        layout(page)
        waitFor { page.liveHosts() == 2 }
        assertEquals(2, WidgetHost.created - createdBefore)
        assertEquals(baseline + 2, WidgetHost.live)

        // One widget stops: its card says so, the other still runs.
        page.markStopped(runBlocking { f.store.get(a)!! })
        layout(page)
        assertEquals(1, page.liveHosts())
        val stoppedCard = page.grid().getChildAt(0)
        val stopped = stoppedCard.findViewById<TextView>(R.id.widget_stopped)
        assertEquals(View.VISIBLE, stopped.visibility)
        assertEquals("This widget stopped. Tap to rebuild.", stopped.text.toString())
        stopped.performClick()
        assertEquals(listOf("Rebuild the Streak widget"), (FeedLocator.commands as FakeFeed).submitted)

        page.setActive(false)
        assertEquals(0, page.liveHosts())
        assertEquals(baseline, WidgetHost.live)

        page.move(0, 1)
        page.saveOrder()
        waitFor { runBlocking { f.store.list().map { it.id } } == listOf(b, a) }
    }

    @Test fun removeDeletesTheCard() {
        val a = runBlocking { f.saved("Streak") }
        val (_, page) = page()
        waitFor { page.grid().adapter?.itemCount == 1 }
        page.remove(a)
        waitFor { page.grid().adapter?.itemCount == 0 }
        assertEquals(View.VISIBLE, page.findViewById<View>(R.id.widgets_empty).visibility)
    }

    @Test fun privacyListsEachWidgetWithItsSources() {
        runBlocking { f.saved("Lingo streak") }
        val activity = Robolectric.buildActivity(PrivacyActivity::class.java).setup().get()
        val text = { activity.window.decorView.allTexts() }
        waitFor { "Lingo streak" in text() }
        assertTrue(text().contains("Reads your app Lingo, your task history"))
        val row = activity.findViewById<ViewGroup>(R.id.screen_rows).let { rows ->
            (0 until rows.childCount).map { rows.getChildAt(it) }
                .first { it.findViewById<TextView?>(R.id.row_title)?.text?.toString() == "Lingo streak" }
        }
        row.performClick()
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(WidgetHost.PAGE_WIDGETS, next.getStringExtra(WidgetHost.EXTRA_OPEN_PAGE))
    }

    private fun View.allTexts(): List<String> = when (this) {
        is TextView -> listOf(text.toString())
        is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).allTexts() }
        else -> emptyList()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThreadAdapterConfirmTest {
    private val context: Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_OpenCyclon)
    private val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
    private val answers = mutableListOf<Pair<Long, Boolean>>()

    private fun bind(item: ThreadItem): View {
        val adapter = ThreadAdapter().apply {
            onConfirm = { it, yes -> answers += it.id to yes }
            submitList(listOf(item))
        }
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
        adapter.onBindViewHolder(holder, 0)
        return holder.itemView
    }

    private val question = "This widget will read: your app Lingo, your task history. OK?"

    @Test fun anOpenConfirmHasBuildItAndNotNow() {
        val item = ThreadItem(5, 0, ThreadKind.CONFIRM, ThreadState.OPEN, text = question, actionWord = "Build it")
        assertEquals(ThreadAdapter.TYPE_CONFIRM, ThreadAdapter.viewTypeOf(item))
        val view = bind(item)
        assertEquals(question, view.findViewById<TextView>(R.id.approval_question).text.toString())
        val primary = view.findViewById<Button>(R.id.approval_primary)
        assertEquals("Build it", primary.text.toString())
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.approval_buttons).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.approval_thumbs).visibility)
        assertEquals(View.GONE, view.findViewById<View>(R.id.approval_compose).visibility)
        primary.performClick()
        view.findViewById<Button>(R.id.approval_not_now).performClick()
        assertEquals(listOf(5L to true, 5L to false), answers)
    }

    @Test fun anAnsweredConfirmHasNoButtons() {
        val view = bind(ThreadItem(6, 0, ThreadKind.CONFIRM, ThreadState.ANSWERED, text = question, actionWord = "Build it"))
        assertEquals(View.GONE, view.findViewById<View>(R.id.approval_buttons).visibility)
    }

    @Test fun aDraftCardTapConfirms() {
        val item = ThreadItem(7, 0, ThreadKind.RESULT, title = "Lingo streak", text = WidgetFlow.DRAFT_READY,
            detail = WidgetFlow.TAP_TO_SEE, actionWord = WidgetFlow.DRAFT_CARD)
        assertEquals(ThreadAdapter.TYPE_RESULT_CARD, ThreadAdapter.viewTypeOf(item))
        bind(item).findViewById<View>(R.id.result_card).performClick()
        assertEquals(listOf(7L to true), answers)
    }
}
