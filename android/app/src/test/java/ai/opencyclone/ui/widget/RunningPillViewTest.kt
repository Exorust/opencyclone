package ai.opencyclone.ui.widget

import ai.opencyclone.R
import ai.opencyclone.feed.FakeFeed
import ai.opencyclone.feed.Phase
import ai.opencyclone.feed.RunningTask
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunningPillViewTest {
    private val context: Context
        get() = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_OpenCyclone)

    private val wa = FakeFeed.WHATSAPP
    private val photos = FakeFeed.PHOTOS

    private fun task(phase: Phase = Phase.RUNNING, packages: List<String> = listOf(photos, wa)) =
        RunningTask(7, "Send Sam the photos", "Picking the photos", packages, 0L, phase)

    private fun pill(running: List<RunningTask>, queued: Int) = RunningPillView(context).apply { bind(running, queued) }

    @Test fun hiddenWhenNothingRunsAndNothingWaits() {
        val p = pill(emptyList(), 0)
        assertEquals(View.GONE, p.visibility)
        p.bind(listOf(task()), 0)
        assertEquals(View.VISIBLE, p.visibility)
        p.bind(emptyList(), 0)
        assertEquals(View.GONE, p.visibility)
    }

    @Test fun runningShowsTheCurrentAppByPackageNameWhenNotInstalled() {
        assertEquals("1 running · $wa ›", pill(listOf(task()), 0).text())
    }

    @Test fun runningShowsTheInstalledAppLabel() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val info = ApplicationInfo().apply { packageName = wa; nonLocalizedLabel = "WhatsApp" }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = wa; applicationInfo = info })
        val p = pill(listOf(task()), 0)
        assertEquals("1 running · WhatsApp ›", p.text())
        assertEquals("1 running, WhatsApp. Show details", p.contentDescription)
    }

    @Test fun runningWithWaitingTasks() {
        assertEquals("1 running · 1 waiting ›", pill(listOf(task()), 1).text())
        assertEquals("1 running · 2 waiting ›", pill(listOf(task()), 2).text())
        assertEquals("1 running, 2 waiting. Show details", pill(listOf(task()), 2).contentDescription)
    }

    @Test fun eachPhaseHasItsWords() {
        assertEquals("Ready for you ›", pill(listOf(task(Phase.WAITING_APPROVAL)), 0).text())
        assertEquals("Has a question ›", pill(listOf(task(Phase.WAITING_ANSWER)), 0).text())
        assertEquals("1 paused ›", pill(listOf(task(Phase.PAUSED)), 0).text())
        assertEquals("Ready for you ›", pill(listOf(task(Phase.WAITING_APPROVAL)), 2).text())
        assertEquals("Ready for you. Show details", pill(listOf(task(Phase.WAITING_APPROVAL)), 0).contentDescription)
    }

    @Test fun onlyQueuedShowsWaitingCount() {
        assertEquals("1 waiting ›", pill(emptyList(), 1).text())
        val p = pill(emptyList(), 2)
        assertEquals("2 waiting ›", p.text())
        assertEquals(View.VISIBLE, p.visibility)
        assertTrue(p.iconViews().isEmpty())
    }

    @Test fun iconsAreCappedAtThreeAndOnlyTheCurrentOneHasTheArcWhileRunning() {
        val p = pill(listOf(task(packages = listOf("a.one", "b.two", "c.three", "d.four"))), 0)
        assertEquals(3, p.iconViews().size)
        assertFalse(p.hasArc(0))
        assertFalse(p.hasArc(1))
        assertTrue(p.hasArc(2))
        val size = context.resources.getDimensionPixelSize(R.dimen.icon_pill)
        val overlap = context.resources.getDimensionPixelSize(R.dimen.pill_icon_overlap)
        val lp = p.iconViews()[1].layoutParams as android.widget.LinearLayout.LayoutParams
        assertEquals(size, lp.width)
        assertEquals(-overlap, lp.marginStart)

        p.bind(listOf(task(Phase.PAUSED, listOf(wa))), 0)
        assertEquals(1, p.iconViews().size)
        assertFalse(p.hasArc(0))
    }

    @Test fun tapDimsThenCallsOnClick() {
        val p = pill(listOf(task()), 0)
        var clicks = 0
        p.onClick = { clicks++ }
        p.performClick()
        assertEquals(0.5f, p.alpha)
        assertEquals(0, clicks)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RunningPillView.PRESS_MS))
        assertEquals(1f, p.alpha)
        assertEquals(1, clicks)
    }

    @Test fun pillIsOneButtonOf52dp() {
        val p = pill(listOf(task()), 0)
        assertEquals(android.widget.Button::class.java.name, p.accessibilityClassName)
        assertTrue(p.isClickable)
        assertEquals(context.resources.getDimensionPixelSize(R.dimen.pill_height), p.minimumHeight)
    }
}
