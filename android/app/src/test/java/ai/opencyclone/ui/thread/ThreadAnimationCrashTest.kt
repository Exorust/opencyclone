package ai.opencyclone.ui.thread

import ai.opencyclone.R
import ai.opencyclone.feed.Evidence
import ai.opencyclone.feed.FakeFeed
import ai.opencyclone.feed.ThreadItem
import ai.opencyclone.feed.ThreadKind
import ai.opencyclone.feed.ThreadState
import android.app.Activity
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The crash seen on the phone: "Tmp detached view should be removed from RecyclerView before it can be recycled",
 * when an approval far down a long thread went OPEN to APPROVED while its row animated. The thread runs here in a real
 * RecyclerView with the adapter's item animator and animations on; any such exception fails the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h800dp")
class ThreadAnimationCrashTest {
    private val approvalAt = 132

    private fun rows(state: ThreadState, extra: Int = 0): List<ThreadItem> = (0 until 150 + extra).map { i ->
        when (i) {
            approvalAt -> ThreadItem(1_000, 0, ThreadKind.APPROVAL, state, taskId = 77, appPackage = FakeFeed.WHATSAPP,
                text = "9 photos to Sam. Send them?", actionWord = "Send", evidence = Evidence(count = 9),
                detail = if (state == ThreadState.APPROVED) "Sent Sam 9 photos on WhatsApp." else null)
            else -> ThreadItem(i.toLong(), 0, if (i % 3 == 0) ThreadKind.OWNER else ThreadKind.SENTENCE,
                text = "Row $i, a sentence long enough to wrap onto a second line on a phone.")
        }
    }

    private class Host(val activity: Activity, val list: RecyclerView, val adapter: ThreadAdapter)

    private fun host(): Host {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val themed = android.view.ContextThemeWrapper(activity, R.style.Theme_OpenCyclone)
        val adapter = ThreadAdapter()
        val list = RecyclerView(themed).apply {
            layoutManager = LinearLayoutManager(themed).apply { stackFromEnd = true }
            this.adapter = adapter
            clipChildren = false
        }
        activity.setContentView(FrameLayout(activity).apply {
            addView(list, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        return Host(activity, list, adapter)
    }

    private fun frames(ms: Long) {
        // Small steps, so animations are cut by the next update in the middle.
        repeat((ms / 16).toInt().coerceAtLeast(1)) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    private fun drive(target: ThreadState, onScreen: Boolean) {
        val h = host()
        h.adapter.submitList(rows(ThreadState.OPEN))
        frames(100)
        h.list.scrollToPosition(if (onScreen) approvalAt else 0)
        frames(100)
        // A new row arrives (an add animation), then the approval changes while that runs.
        h.adapter.submitList(rows(ThreadState.OPEN, extra = 1))
        frames(32)
        h.adapter.submitList(rows(target, extra = 1))
        frames(48)
        // Scroll across the approval while its buttons fade out, then back.
        h.list.scrollToPosition(if (onScreen) 0 else approvalAt)
        frames(32)
        h.list.scrollToPosition(approvalAt)
        h.adapter.submitList(rows(target, extra = 2))
        frames(600)
        assertTrue(h.adapter.itemCount >= 151)
        assertEquals(target, h.adapter.currentList.first { it.id == 1_000L }.state)
    }

    @Test fun approvedOnScreen() = drive(ThreadState.APPROVED, onScreen = true)
    @Test fun approvedOffScreen() = drive(ThreadState.APPROVED, onScreen = false)
    @Test fun rejectedOnScreen() = drive(ThreadState.REJECTED, onScreen = true)
    @Test fun rejectedOffScreen() = drive(ThreadState.REJECTED, onScreen = false)
}
