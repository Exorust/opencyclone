package ai.opencyclone.proactive

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Lane X2: a tap on a notification card opens it in its app; ongoing and system notifications get no row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationIntentsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val sent = mutableListOf<PendingIntent>()
    private var options: Bundle? = null
    private val original = NotificationIntents.send

    @Before fun setUp() {
        NotificationIntents.clear()
        NotificationIntents.send = { _, intent, o -> sent += intent; options = o }
    }

    @After fun tearDown() {
        NotificationIntents.send = original
        NotificationIntents.clear()
    }

    private fun intent(n: Int) = PendingIntent.getActivity(context, n, Intent("open.$n"), PendingIntent.FLAG_IMMUTABLE)

    private fun started(): List<Intent> = generateSequence { shadowOf(context as Application).nextStartedActivity }.toList()

    @Test fun aStoredIntentIsSentOnceWithTheBackgroundStartAllowed() {
        val stored = intent(1)
        NotificationIntents.put(7, "k|1", stored)
        assertTrue(NotificationIntents.open(context, 7, "com.google.android.gm"))
        assertEquals(listOf(stored), sent)
        assertTrue(options != null) // API 34+: MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        assertTrue(started().isEmpty())
    }

    @Test fun withoutAStoredIntentTheAppsLauncherScreenOpens() {
        assertTrue(NotificationIntents.open(context, 8, context.packageName))
        assertTrue(sent.isEmpty())
        val launch = started().single()
        assertEquals(context.packageName, launch.component?.packageName ?: launch.`package`)
        assertTrue(launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun neitherAnIntentNorAnInstalledAppIsAFailure() {
        assertFalse(NotificationIntents.open(context, 9, "com.example.not.installed"))
        assertFalse(NotificationIntents.open(context, 9, null))
    }

    @Test fun aCancelledIntentFallsBackToTheLauncher() {
        NotificationIntents.put(10, "k|10", intent(10))
        NotificationIntents.send = { _, _, _ -> throw PendingIntent.CanceledException() }
        assertTrue(NotificationIntents.open(context, 10, context.packageName))
        assertNull(NotificationIntents.get(10))
    }

    @Test fun removedNotificationsAreDroppedAndTheMapStaysUnderItsCap() {
        NotificationIntents.put(1, "k|a", intent(1))
        NotificationIntents.put(2, "k|b", intent(2))
        NotificationIntents.removeKey("k|a")
        assertNull(NotificationIntents.get(1))
        NotificationIntents.remove(2)
        assertEquals(0, NotificationIntents.size())
        for (i in 1..250) NotificationIntents.put(i.toLong(), "k|$i", intent(i))
        assertEquals(NotificationIntents.MAX, NotificationIntents.size())
        assertNull(NotificationIntents.get(1))     // the oldest went first
        assertTrue(NotificationIntents.get(250) != null)
    }

    @Test fun ongoingAndSystemNotificationsAreFilteredOut() {
        assertTrue(NotificationGate.isSystemOrOngoing(Notification.FLAG_ONGOING_EVENT, null, hasLauncher = true))
        assertTrue(NotificationGate.isSystemOrOngoing(Notification.FLAG_FOREGROUND_SERVICE, null, hasLauncher = true))
        assertTrue(NotificationGate.isSystemOrOngoing(0, Notification.CATEGORY_SERVICE, hasLauncher = true))
        assertTrue(NotificationGate.isSystemOrOngoing(0, Notification.CATEGORY_SYSTEM, hasLauncher = true))
        assertTrue(NotificationGate.isSystemOrOngoing(0, null, hasLauncher = false)) // "Running checks on apps"
        assertFalse(NotificationGate.isSystemOrOngoing(0, Notification.CATEGORY_MESSAGE, hasLauncher = true))
        assertFalse(NotificationGate.isSystemOrOngoing(Notification.FLAG_AUTO_CANCEL, Notification.CATEGORY_EMAIL, hasLauncher = true))
    }
}
