package ai.opencyclone.ui.home

import android.content.ComponentName
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppSearchTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun app(label: String, pkg: String = "pkg.${label.lowercase().replace(' ', '.')}") =
        LaunchableApp(label, pkg, ComponentName(pkg, "$pkg.Main"), Process.myUserHandle())

    private val clock = app("Clock")
    private val calc = app("Calculator")
    private val camera = app("Camera")
    private val alarmClock = app("Alarm Clock")
    private val apps = listOf(alarmClock, camera, clock, calc)

    @After fun tearDown() {
        AppSearch.loader = AppSearch::installedApps
        AppSearch.starter = AppSearch::startApp
    }

    @Test fun emptyQueryListsEveryAppByName() {
        assertEquals(listOf("Alarm Clock", "Calculator", "Camera", "Clock"), AppSearch.filter(apps, "").map { it.label })
    }

    @Test fun filterIsCaseInsensitiveWithPrefixMatchesFirst() {
        assertEquals(listOf("Clock", "Alarm Clock"), AppSearch.filter(apps, "clo").map { it.label })
        assertEquals(listOf("Calculator", "Camera"), AppSearch.filter(apps, "CA").map { it.label })
        assertEquals(listOf("Alarm Clock"), AppSearch.filter(apps, "alarm").map { it.label })
        assertTrue(AppSearch.filter(apps, "zzz").isEmpty())
    }

    @Test fun openNameStartsOnlyOnExactSingleMatch() {
        assertEquals(clock, AppSearch.directTarget("open Clock", apps))
        assertEquals(clock, AppSearch.directTarget("  Open clock ", apps))
        assertEquals(alarmClock, AppSearch.directTarget("open alarm clock", apps))
        assertNull(AppSearch.directTarget("open clo", apps))
        assertNull(AppSearch.directTarget("open the clock app", apps))
        assertNull(AppSearch.directTarget("Clock", apps))
        assertNull(AppSearch.directTarget("please open Clock", apps))
        // Two apps with the same label (for example in a work profile): no direct start.
        assertNull(AppSearch.directTarget("open Clock", apps + app("Clock", "other.clock")))
    }

    @Test fun ourOwnScreenNamesAreLeftToTheRouter() {
        assertNull(AppSearch.directTarget("open Settings", apps + app("Settings", "com.android.settings")))
    }

    @Test fun tryOpenStartsTheAppAndReportsIt() {
        val started = mutableListOf<LaunchableApp>()
        AppSearch.loader = { apps }
        AppSearch.starter = { _, a -> started += a }
        assertTrue(AppSearch.tryOpen(context, "open Clock"))
        assertEquals(listOf(clock), started)
        assertFalse(AppSearch.tryOpen(context, "open Gate Test"))
        assertFalse(AppSearch.tryOpen(context, "Send hello to Alex"))
        assertEquals(1, started.size)
    }

    @Test fun installedAppsLeaveOutThisApp() {
        assertTrue(AppSearch.installedApps(context).none { it.packageName == context.packageName })
    }
}
