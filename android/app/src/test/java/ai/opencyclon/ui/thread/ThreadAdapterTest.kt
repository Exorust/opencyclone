package ai.opencyclon.ui.thread

import ai.opencyclon.R
import ai.opencyclon.feed.Evidence
import ai.opencyclon.feed.FakeFeed
import ai.opencyclon.feed.ThreadItem
import ai.opencyclon.feed.ThreadKind
import ai.opencyclon.feed.ThreadState
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_APPROVAL
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_HIDDEN
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_NOTIFICATION_CARD
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_NOTIFICATION_QUIET
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_OWNER_LIST
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_OWNER_SINGLE
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_RECEIPT
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_RESULT_CARD
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_SENTENCE
import ai.opencyclon.ui.widget.ThumbnailGridView
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadAdapterTest {
    private val context: Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_OpenCyclon)
    private val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }

    private val now = 1_800_000_000_000L

    private fun approval(
        state: ThreadState = ThreadState.OPEN,
        canApprove: Boolean = true,
        evidence: Evidence? = Evidence(count = 9),
    ) = ThreadItem(40, now, ThreadKind.APPROVAL, state, taskId = 77, appPackage = FakeFeed.WHATSAPP,
        text = "9 photos to Sam. Send them?", actionWord = "Send", evidence = evidence, canApprove = canApprove)

    private fun adapterWith(items: List<ThreadItem>) = ThreadAdapter().apply {
        this.now = { this@ThreadAdapterTest.now }
        submitList(items) // the first list is applied at once
    }

    private fun bindAt(adapter: ThreadAdapter, position: Int): ThreadAdapter.Holder {
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
        adapter.onBindViewHolder(holder, position)
        return holder
    }

    private fun bindOne(item: ThreadItem, adapter: ThreadAdapter = adapterWith(listOf(item))) = bindAt(adapter, 0)

    private fun allSample(): List<ThreadItem> = FakeFeed.sample().items.value + listOf(
        ThreadItem(100, now, ThreadKind.QUESTION, ThreadState.OPEN, taskId = 5, text = "Which album should I use?"),
        ThreadItem(101, now, ThreadKind.NOTIFICATION, ThreadState.OPEN, appPackage = FakeFeed.WHATSAPP, title = "Sam",
            text = "Can you send me the photos from yesterday?"),
        ThreadItem(102, now, ThreadKind.SENTENCE, text = "I'm finding them.", detail = ThreadAdapter.DETAIL_HEADING),
        ThreadItem(103, now, ThreadKind.SENTENCE, text = "Done. Lingo has Portuguese now.", detail = ThreadAdapter.DETAIL_PHONE_LINE),
        ThreadItem(104, now, ThreadKind.RESULT, taskId = 11, text = "Sent Sam 9 photos on WhatsApp."),
        ThreadItem(105, now, ThreadKind.RECEIPT, title = "Priya", actionWord = "Added the trip planner", text = "checked"),
        approval().copy(id = 106, evidence = Evidence(count = 14, composeText = "Here you go", thumbs = listOf("/a", "/b"))),
        approval(ThreadState.REJECTED).copy(id = 107),
        ThreadItem(108, now, ThreadKind.SHARED_APP, ThreadState.OPEN, title = "Priya",
            text = "Priya made a trip planner. It keeps your bookings in one place.", detail = "Trips", openAppId = 3),
        ThreadItem(109, now, ThreadKind.CONFIRM, ThreadState.OPEN, text = "This widget will read: your task history. OK?",
            actionWord = "Build it"),
    )

    private fun allViews(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else listOf(v)

    // --- every kind binds -------------------------------------------------------------------------

    @Test fun everyKindBindsFromTheSample() {
        val items = allSample()
        assertEquals(ThreadKind.entries.toSet(), items.map { it.kind }.toSet())
        val adapter = adapterWith(items)
        items.indices.forEach { bindAt(adapter, it) }
    }

    // --- approval ---------------------------------------------------------------------------------

    @Test fun approvalWithoutTargetHasNoPrimaryButStillNotNow() {
        val v = bindOne(approval(canApprove = false)).itemView
        assertEquals(View.GONE, v.findViewById<Button>(R.id.approval_primary).visibility)
        val notNow = v.findViewById<Button>(R.id.approval_not_now)
        assertEquals(View.VISIBLE, notNow.visibility)
        assertEquals("Not now", notNow.text.toString())
    }

    @Test fun approvalPrimaryUsesTheActionWordAndTapsCallBackWithTheTask() {
        val adapter = adapterWith(listOf(approval()))
        val approved = mutableListOf<Long>()
        val rejected = mutableListOf<Long>()
        adapter.onApprove = { approved += it }
        adapter.onNotNow = { rejected += it }
        val v = bindAt(adapter, 0).itemView
        val primary = v.findViewById<Button>(R.id.approval_primary)
        assertEquals(View.VISIBLE, primary.visibility)
        assertEquals("Send", primary.text.toString())
        assertTrue(primary.contentDescription.toString().contains("to Sam"))
        primary.performClick()
        v.findViewById<Button>(R.id.approval_not_now).performClick()
        assertEquals(listOf(77L), approved)
        assertEquals(listOf(77L), rejected)
    }

    @Test fun approvedKeepsTheEvidenceWithoutButtons() {
        val v = bindOne(approval(ThreadState.APPROVED)).itemView
        assertEquals(View.GONE, v.findViewById<View>(R.id.approval_buttons).visibility)
        assertEquals(View.VISIBLE, v.findViewById<View>(R.id.approval_thumbs).visibility)
    }

    @Test fun rejectedOrExpiredApprovalCollapses() {
        assertEquals(TYPE_HIDDEN, ThreadAdapter.viewTypeOf(approval(ThreadState.REJECTED)))
        assertEquals(TYPE_HIDDEN, ThreadAdapter.viewTypeOf(approval(ThreadState.EXPIRED)))
        assertEquals(TYPE_APPROVAL, ThreadAdapter.viewTypeOf(approval(ThreadState.APPROVED)))
        assertEquals(0, bindOne(approval(ThreadState.REJECTED)).itemView.layoutParams.height)
    }

    @Test fun approvalShowsTextAndThumbnailsTogether() {
        val ev = Evidence(count = 14, composeText = "I'm in for 7:30", thumbs = listOf("/a", "/b", "/c"))
        val opened = mutableListOf<ThreadItem>()
        val adapter = adapterWith(listOf(approval(evidence = ev))).apply { onOpen = { opened += it } }
        val v = bindAt(adapter, 0).itemView
        val compose = v.findViewById<TextView>(R.id.approval_compose)
        assertEquals(View.VISIBLE, compose.visibility)
        assertEquals("I'm in for 7:30", compose.text.toString())
        val grid = v.findViewById<ThumbnailGridView>(R.id.approval_thumbs)
        assertEquals(View.VISIBLE, grid.visibility)
        assertEquals(10, grid.tileCount)
        assertEquals("+5", grid.overflowText)
        assertEquals(View.GONE, v.findViewById<View>(R.id.approval_frame).visibility)
        grid.getChildAt(0).performClick()
        assertEquals(1, opened.size)
    }

    @Test fun approvalWithOnlyAScreenPictureShowsIt() {
        val file = File.createTempFile("frame", ".png").apply { deleteOnExit() }
        Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            .compress(Bitmap.CompressFormat.PNG, 100, file.outputStream())
        val v = bindOne(approval(evidence = Evidence(framePath = file.path))).itemView
        assertEquals(View.VISIBLE, v.findViewById<ImageView>(R.id.approval_frame).visibility)
        assertEquals(View.GONE, v.findViewById<View>(R.id.approval_thumbs).visibility)
        assertEquals(View.GONE, v.findViewById<View>(R.id.approval_compose).visibility)
    }

    // --- owner ------------------------------------------------------------------------------------

    @Test fun ownerOneLineIsTheDarkBubbleAndSeveralLinesTheList() {
        val one = ThreadItem(1, now, ThreadKind.OWNER, text = "Add Portuguese to my language app.")
        val three = ThreadItem(2, now, ThreadKind.OWNER, text = "Follow Jordan back.\nReply to the club.\nStart the return.")
        assertEquals(TYPE_OWNER_SINGLE, ThreadAdapter.viewTypeOf(one))
        assertEquals(TYPE_OWNER_LIST, ThreadAdapter.viewTypeOf(three))

        val single = bindOne(one).itemView.findViewById<TextView>(R.id.owner_text)
        assertEquals("Add Portuguese to my language app.", single.text.toString())

        val list = bindOne(three).itemView
        val lines = list.findViewById<LinearLayout>(R.id.owner_lines)
        assertEquals(3, lines.childCount)
        assertEquals("Reply to the club.", (lines.getChildAt(1) as TextView).text.toString())
        assertEquals("You", list.findViewById<TextView>(R.id.owner_you).text.toString())
    }

    // --- notification -----------------------------------------------------------------------------

    @Test fun openNotificationIsTheCardAndDoneIsQuiet() {
        val base = ThreadItem(1, now, ThreadKind.NOTIFICATION, appPackage = FakeFeed.WHATSAPP, title = "Sam", text = "Photos?")
        val open = base.copy(state = ThreadState.OPEN)
        assertEquals(TYPE_NOTIFICATION_CARD, ThreadAdapter.viewTypeOf(open))
        assertEquals(TYPE_NOTIFICATION_QUIET, ThreadAdapter.viewTypeOf(base))

        val card = bindOne(open).itemView
        assertTrue(card.findViewById<View>(R.id.notification_card).elevation > 0f)
        assertEquals("Sam · now", card.findViewById<TextView>(R.id.notification_header).text.toString())

        val quiet = bindOne(base.copy(at = now - 30 * 60_000L)).itemView
        assertEquals(0f, quiet.findViewById<View>(R.id.notification_card).elevation)
        val header = quiet.findViewById<TextView>(R.id.notification_header).text.toString()
        assertTrue(header.startsWith("Sam · ") && !header.endsWith("now"))
        assertEquals("Photos?", quiet.findViewById<TextView>(R.id.notification_text).text.toString())
    }

    // --- other kinds ------------------------------------------------------------------------------

    @Test fun sentenceMarkersPickTheStyle() {
        val body = ThreadItem(1, now, ThreadKind.SENTENCE, text = "x")
        assertEquals(R.style.TextAppearance_App_Body, ThreadAdapter.sentenceStyle(body))
        assertEquals(R.style.TextAppearance_App_Heading, ThreadAdapter.sentenceStyle(body.copy(detail = "heading")))
        assertEquals(R.style.TextAppearance_App_PhoneLine, ThreadAdapter.sentenceStyle(body.copy(detail = "phone-line")))
        assertEquals(R.style.TextAppearance_App_Heading, ThreadAdapter.sentenceStyle(body.copy(kind = ThreadKind.QUESTION)))
        assertEquals(TYPE_SENTENCE, ThreadAdapter.viewTypeOf(body))
    }

    @Test fun resultWithDetailAndAppIsATappableCard() {
        val item = ThreadItem(1, now, ThreadKind.RESULT, text = "Lingo is updated", detail = "Portuguese · tap to open", openAppId = 1)
        assertEquals(TYPE_RESULT_CARD, ThreadAdapter.viewTypeOf(item))
        val opened = mutableListOf<ThreadItem>()
        val adapter = adapterWith(listOf(item)).apply { onOpen = { opened += it } }
        val v = bindAt(adapter, 0).itemView
        assertEquals("Lingo is updated", v.findViewById<TextView>(R.id.result_title).text.toString())
        v.findViewById<View>(R.id.result_card).performClick()
        assertEquals(listOf(item), opened)
    }

    @Test fun receiptAboutAPersonHasAnAvatarAndATappableOneOpens() {
        val item = ThreadItem(1, now, ThreadKind.RECEIPT, title = "Priya", actionWord = "Added Trips", text = "checked", openAppId = 3)
        assertEquals(TYPE_RECEIPT, ThreadAdapter.viewTypeOf(item))
        val opened = mutableListOf<ThreadItem>()
        val adapter = adapterWith(listOf(item)).apply { onOpen = { opened += it } }
        val v = bindAt(adapter, 0).itemView
        val icon = v.findViewById<ImageView>(R.id.receipt_icon)
        assertEquals(View.VISIBLE, icon.visibility)
        assertEquals("Priya", icon.contentDescription)
        assertEquals("Added Trips · checked", v.findViewById<TextView>(R.id.receipt_text).text.toString())
        assertTrue(v.minimumHeight >= ThreadAdapter.touchMin(context))
        v.performClick()
        assertEquals(1, opened.size)
    }

    @Test fun progressPillReadsTextDotDetail() {
        val sample = FakeFeed.sample().items.value.first { it.kind == ThreadKind.PROGRESS }
        val v = bindOne(sample).itemView
        assertEquals("Updating Lingo · ~1 min ›", v.findViewById<TextView>(R.id.progress_text).text.toString())
    }

    @Test fun sharedAppCardOpensAndDismisses() {
        val item = allSample().first { it.id == 108L }
        val opened = mutableListOf<ThreadItem>()
        val dismissed = mutableListOf<ThreadItem>()
        val adapter = adapterWith(listOf(item)).apply { onOpen = { opened += it }; onDismiss = { dismissed += it } }
        val v = bindAt(adapter, 0).itemView
        assertEquals("Trips", v.findViewById<TextView>(R.id.shared_app_name).text.toString())
        assertEquals("Checked · sent by Priya", v.findViewById<TextView>(R.id.shared_checked).text.toString())
        v.findViewById<Button>(R.id.shared_open).performClick()
        v.findViewById<Button>(R.id.shared_not_now).performClick()
        assertEquals(listOf(item), opened)
        assertEquals(listOf(item), dismissed)
    }

    // --- DiffUtil ---------------------------------------------------------------------------------

    @Test fun changedStateOnTheSameIdRebindsThatRowOnly() {
        val items = FakeFeed.sample().items.value
        val adapter = adapterWith(items)
        val events = mutableListOf<String>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { events += "all" }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { events += "changed $positionStart $itemCount" }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { events += "changed $positionStart $itemCount" }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { events += "inserted" }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { events += "removed" }
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { events += "moved" }
        })
        val index = items.indexOfFirst { it.kind == ThreadKind.SHARED_APP }
        val changed = items.toMutableList().apply { this[index] = this[index].copy(state = ThreadState.DONE) }
        val done = CountDownLatch(1)
        adapter.submitList(changed) { done.countDown() }
        val deadline = System.currentTimeMillis() + 5_000
        while (done.count > 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals(0L, done.count)
        assertEquals(listOf("changed $index 1"), events)
    }

    @Test fun firstListShowsAtOnceAndANewSentenceRevealsWordByWord() {
        val first = ThreadItem(1, now, ThreadKind.SENTENCE, text = "Found 12 in your chats.")
        val adapter = adapterWith(listOf(first))
        val restored = bindAt(adapter, 0)
        assertFalse((restored.itemView as ai.opencyclon.ui.widget.WordRevealTextView).isRevealing)
        assertEquals(1f, restored.itemView.alpha)

        val added = ThreadItem(2, now, ThreadKind.SENTENCE, text = "I'm sending them now.")
        val done = CountDownLatch(1)
        adapter.submitList(listOf(first, added)) { done.countDown() }
        val deadline = System.currentTimeMillis() + 5_000
        while (done.count > 0 && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        val fresh = bindAt(adapter, 1)
        assertTrue((fresh.itemView as ai.opencyclon.ui.widget.WordRevealTextView).isRevealing)
        // bound again (scroll, rebind): the text is there at once
        val again = bindAt(adapter, 1)
        assertFalse((again.itemView as ai.opencyclon.ui.widget.WordRevealTextView).isRevealing)
        assertEquals(1f, again.itemView.alpha)
    }

    // --- rules of UI.md section 2 -----------------------------------------------------------------

    @Test fun noItemLayoutHasATextBox() {
        val layouts = R.layout::class.java.fields.filter { it.name.startsWith("item_thread_") }
        assertTrue(layouts.size >= 9)
        layouts.forEach { f ->
            val root = LayoutInflater.from(context).inflate(f.getInt(null), parent, false)
            allViews(root).forEach { assertFalse("${f.name} has a text box", it is EditText) }
        }
    }

    @Test fun sampleTextHasNoExclamationMarkOrEmoji() {
        val items = allSample()
        val adapter = adapterWith(items)
        val texts = items.indices.flatMap { pos ->
            allViews(bindAt(adapter, pos).itemView).filterIsInstance<TextView>().map { it.text.toString() }
        }
        assertTrue(texts.isNotEmpty())
        texts.forEach { t ->
            assertFalse("exclamation mark in \"$t\"", t.contains('!'))
            assertFalse("emoji in \"$t\"", t.codePoints().anyMatch { it >= 0x1F000 || it in 0x2600..0x27BF })
        }
    }

    // --- spacing ----------------------------------------------------------------------------------

    @Test fun spacingFollowsTheRules() {
        fun gap(prev: Int, type: Int, prevTap: Boolean = false, tap: Boolean = false) =
            ThreadSpacing.gapBetween(prev, prevTap, type, tap)
        assertEquals(R.dimen.gap_receipt, gap(TYPE_RECEIPT, TYPE_RECEIPT))
        assertEquals(R.dimen.gap_heading, gap(TYPE_SENTENCE, TYPE_RECEIPT))
        assertEquals(R.dimen.gap_quiet_cards, gap(TYPE_NOTIFICATION_QUIET, TYPE_NOTIFICATION_QUIET))
        assertEquals(R.dimen.gap_block, gap(TYPE_NOTIFICATION_QUIET, TYPE_OWNER_LIST))
        assertEquals(R.dimen.gap_block, gap(TYPE_OWNER_SINGLE, TYPE_SENTENCE))
    }

    @Test fun decorationAddsMarginsAndSkipsCollapsedRows() {
        val items = listOf(
            ThreadItem(1, now, ThreadKind.SENTENCE, text = "Done."),
            approval(ThreadState.REJECTED).copy(id = 2),
            ThreadItem(3, now, ThreadKind.RECEIPT, appPackage = FakeFeed.WHATSAPP, actionWord = "Sent", text = "9 photos"),
        )
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = adapterWith(items)
            addItemDecoration(ThreadSpacing(context))
            measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1080, 2000)
        }
        val res = context.resources
        val receipt = recycler.findViewHolderForAdapterPosition(2)!!.itemView
        val first = recycler.findViewHolderForAdapterPosition(0)!!.itemView
        assertEquals(res.getDimensionPixelSize(R.dimen.margin_page), receipt.left)
        assertEquals(res.getDimensionPixelSize(R.dimen.gap_heading), receipt.top - first.bottom)
        assertNotNull(recycler.findViewHolderForAdapterPosition(1))
    }
}
