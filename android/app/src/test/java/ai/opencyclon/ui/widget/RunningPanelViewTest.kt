package ai.opencyclon.ui.widget

import ai.opencyclon.R
import ai.opencyclon.feed.FakeFeed
import ai.opencyclon.feed.Phase
import ai.opencyclon.feed.RunningTask
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunningPanelViewTest {
    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @After fun tearDown() {
        scope.cancel()
        setDurationScale(1f)
    }

    private fun setDurationScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    /** A panel attached to a window. */
    private fun attached(): RunningPanelView {
        val a = activity()
        val root = FrameLayout(a)
        a.setContentView(root)
        val panel = RunningPanelView(android.view.ContextThemeWrapper(a, R.style.Theme_OpenCyclon))
        root.addView(panel)
        return panel
    }

    private fun task(id: Long = 7, startedAt: Long = 1_000_000L, caption: String = "Picking the photos") =
        RunningTask(id, "Send Sam the photos", caption, listOf(FakeFeed.PHOTOS, FakeFeed.WHATSAPP), startedAt, Phase.RUNNING)

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun startsHiddenAndBindsWithoutATask() {
        val panel = attached()
        assertEquals(View.GONE, panel.visibility)
        panel.bind(null, listOf("Book a table for Friday"))
        assertFalse(panel.hasTask())
        assertEquals(listOf("Waiting · Book a table for Friday"), panel.waitingLines())
    }

    @Test fun bindsATaskAndOneLinePerWaitingTask() {
        val panel = attached()
        panel.clock = { 1_034_000L }
        panel.bind(task(), listOf("Book a table", "Reply to Nina"))
        assertTrue(panel.hasTask())
        assertEquals(FakeFeed.WHATSAPP, panel.appText())
        assertEquals("Picking the photos", panel.captionText())
        assertEquals("Live 0:34", panel.liveText())
        assertEquals(listOf("Waiting · Book a table", "Waiting · Reply to Nina"), panel.waitingLines())
        assertTrue(panel.stopButton().minHeight >= (48 * panel.resources.displayMetrics.density).toInt())
    }

    @Test fun liveTimeFormats() {
        assertEquals("0:00", RunningPanelView.liveTime(-5))
        assertEquals("0:34", RunningPanelView.liveTime(34_900))
        assertEquals("12:05", RunningPanelView.liveTime(725_000))
        assertEquals("1:02:09", RunningPanelView.liveTime(3_729_000))
    }

    @Test fun timerCountsEverySecondWhileOpen() {
        val panel = attached()
        var now = 1_034_000L
        panel.clock = { now }
        panel.bind(task(), emptyList())
        panel.open()
        assertEquals("Live 0:34", panel.liveText())
        now += 1_000
        idle(1_000)
        assertEquals("Live 0:35", panel.liveText())
    }

    @Test fun stopAndPreviewTapsReportTheTaskId() {
        val panel = attached()
        val stopped = mutableListOf<Long>()
        val opened = mutableListOf<Long>()
        panel.onStop = { stopped += it }
        panel.onOpenLive = { opened += it }
        panel.bind(task(id = 42), emptyList())
        panel.stopButton().performClick()
        panel.previewView().performClick()
        assertEquals(listOf(42L), stopped)
        assertEquals(listOf(42L), opened)
    }

    @Test fun previewsAreCollectedOnlyBetweenOpenAndClose() {
        val feed = FakeFeed()
        val panel = attached()
        panel.bind(task(), emptyList())
        panel.collectPreviews(scope, feed)
        assertEquals(0, feed.previewFlow.subscriptionCount.value)

        panel.open()
        assertEquals(1, feed.previewFlow.subscriptionCount.value)
        val picture = Bitmap.createBitmap(9, 19, Bitmap.Config.ARGB_8888)
        feed.previewFlow.tryEmit(picture)
        assertSame(picture, panel.previewView().bitmap)

        panel.close()
        assertEquals(0, feed.previewFlow.subscriptionCount.value)
        idle(200)
        assertEquals(View.GONE, panel.visibility)

        panel.open()
        assertEquals(1, feed.previewFlow.subscriptionCount.value)
        (panel.parent as FrameLayout).removeView(panel)
        assertEquals(0, feed.previewFlow.subscriptionCount.value)
    }

    @Test fun noCollectionWithoutATaskAndANewTaskClearsThePicture() {
        val feed = FakeFeed()
        val panel = attached()
        panel.collectPreviews(scope, feed)
        panel.open()
        assertEquals(0, feed.previewFlow.subscriptionCount.value)
        panel.bind(task(id = 1), emptyList())
        assertEquals(1, feed.previewFlow.subscriptionCount.value)
        panel.setPreview(Bitmap.createBitmap(4, 8, Bitmap.Config.ARGB_8888))
        panel.bind(task(id = 2), emptyList())
        assertNull(panel.previewView().bitmap)
        assertEquals(1, feed.previewFlow.subscriptionCount.value)
        panel.bind(null, emptyList())
        assertEquals(0, feed.previewFlow.subscriptionCount.value)
    }

    @Test fun withAnimationsOffEverythingShowsAtOnce() {
        setDurationScale(0f)
        val panel = attached()
        panel.bind(task(), emptyList())
        panel.open()
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(1f, panel.findViewById<View>(R.id.running_panel_header).alpha)
        assertEquals("Picking the photos", panel.captionText())
        assertNull(panel.findViewById<View>(R.id.running_panel_body).clipBounds)
    }

    @Test fun withAnimationsOnTheCaptionAppearsAfterTheBodyUnrolls() {
        val panel = attached()
        panel.bind(task(), emptyList())
        panel.open()
        assertEquals("", panel.captionText())
        idle(RunningPanelView.UNROLL_MS + 50)
        assertEquals("Picking the photos", panel.captionText())
        assertNull(panel.findViewById<View>(R.id.running_panel_body).clipBounds)
    }

    @Test fun timerIsNotAnnounced() {
        val panel = attached()
        val badge = panel.findViewById<View>(R.id.running_panel_live)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, badge.importantForAccessibility)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, badge.accessibilityLiveRegion)
    }

    @Test fun runningStringsHaveNoEmojiOrExclamationMarks() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]")
        val names = R.string::class.java.fields.filter { it.name.startsWith("running_") }
        assertTrue(names.size >= 10)
        names.forEach { f ->
            val t = context.getString(f.getInt(null))
            assertFalse("emoji in ${f.name}: $t", emoji.containsMatchIn(t))
            assertFalse("exclamation in ${f.name}: $t", t.contains('!'))
        }
    }
}
