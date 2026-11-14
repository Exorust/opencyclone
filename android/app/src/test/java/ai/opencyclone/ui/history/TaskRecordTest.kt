package ai.opencyclone.ui.history

import ai.opencyclone.R
import ai.opencyclone.feed.Evidence
import ai.opencyclone.feed.FakeFeed
import ai.opencyclone.feed.FeedLocator
import ai.opencyclone.feed.HistoryEntry
import ai.opencyclone.feed.HistoryStatus
import ai.opencyclone.feed.ThreadItem
import ai.opencyclone.feed.ThreadKind
import ai.opencyclone.feed.ThreadState
import ai.opencyclone.ui.ControlPanelActivity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.Calendar

/** The record of one task (F15.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskRecordTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var feed: FakeFeed

    @Before fun setUp() {
        feed = FakeFeed()
        FeedLocator.feed = feed
        FeedLocator.commands = feed
    }

    private fun at(minute: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 23, 14, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun open(taskId: Long): ActivityController<TaskRecordActivity> =
        Robolectric.buildActivity(TaskRecordActivity::class.java, TaskRecordActivity.intent(context, taskId)).setup().also { idle() }

    private fun allViews(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } else listOf(v)

    private fun shownTexts(v: View) = allViews(v).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }

    private fun record(approval: ThreadState): List<ThreadItem> = listOf(
        ThreadItem(-1, at(38), ThreadKind.OWNER, taskId = 7, text = "Open Gate Test, open the chat with Alex and tap Send"),
        ThreadItem(10, at(39), ThreadKind.SENTENCE, taskId = 7, text = "Found the chat with Alex."),
        ThreadItem(11, at(40), ThreadKind.APPROVAL, approval, taskId = 7, appPackage = "ai.opencyclone.testapp",
            text = "A message to Alex. Send it?", actionWord = "Send", evidence = Evidence(composeText = "See you at 7")),
        ThreadItem(12, at(41), ThreadKind.SENTENCE, taskId = 7, text = "Sent Alex the message."),
    ) + if (approval == ThreadState.APPROVED)
        listOf(ThreadItem(13, at(41), ThreadKind.RECEIPT, taskId = 7, appPackage = "ai.opencyclone.testapp", actionWord = "Sent", text = "to Alex"))
    else emptyList()

    @Test fun anApprovedTaskShowsItsRowsTheApprovedLineAndNoApproveButton() {
        feed.records.value = mapOf(7L to record(ThreadState.APPROVED))
        val a = open(7).get()
        val rows = a.rowsView()
        val texts = shownTexts(rows)
        assertTrue(texts.toString(), texts.contains("Open Gate Test, open the chat with Alex and tap Send"))
        assertTrue(texts.contains("Found the chat with Alex."))
        assertTrue(texts.contains("A message to Alex. Send it?"))
        assertTrue(texts.contains("Approved · 2:41 PM"))
        assertTrue(texts.contains("Sent Alex the message."))
        // No button that approves or sends.
        assertTrue(allViews(rows).filterIsInstance<Button>().none { it.isShown })
        val note = allViews(rows).single { it.tag == TaskRecordActivity.TAG_NOTE }
        assertEquals("Approved · 2:41 PM", (note as TextView).text.toString())
    }

    @Test fun aTaskThatWasNotApprovedSaysNotSent() {
        feed.records.value = mapOf(7L to record(ThreadState.REJECTED))
        val a = open(7).get()
        assertTrue(shownTexts(a.rowsView()).contains("Not sent · 2:41 PM"))
    }

    @Test fun theRecordUpdatesWhileTheTaskRuns() {
        feed.records.value = mapOf(7L to record(ThreadState.OPEN).take(3))
        val a = open(7).get()
        // An open approval of a running task keeps its buttons, wired to the real commands.
        val approve = allViews(a.rowsView()).filterIsInstance<Button>().first { it.id == R.id.approval_primary }
        assertTrue(approve.isShown)
        approve.performClick()
        idle()
        assertEquals(listOf(7L), feed.approved)
        feed.records.value = mapOf(7L to record(ThreadState.APPROVED))
        idle()
        assertTrue(shownTexts(a.rowsView()).contains("Approved · 2:41 PM"))
    }

    @Test fun askAgainSubmitsAndReturnsToTheHomePage() {
        feed.records.value = mapOf(7L to record(ThreadState.APPROVED))
        feed.historyEntries.value = listOf(HistoryEntry(7, at(38), "Open Gate Test, open the chat with Alex and tap Send",
            HistoryStatus.DONE, emptyList(), null))
        val a = open(7).get()
        val ask = a.findViewById<Button>(R.id.record_ask_again)
        assertEquals("Ask again", ask.text.toString())
        ask.performClick()
        assertEquals(listOf(7L), feed.askedAgain)
        assertEquals(listOf("Open Gate Test, open the chat with Alex and tap Send"), feed.submitted)
        val started = shadowOf(a).nextStartedActivity
        assertEquals(ControlPanelActivity::class.java.name, started.component?.className)
        assertTrue(a.isFinishing)
    }
}
