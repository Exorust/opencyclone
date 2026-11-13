package ai.opencyclon.gate

import ai.opencyclon.HomeForeground
import ai.opencyclon.action.Action
import ai.opencyclon.ui.ControlPanelActivity
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Item 3 of lane X1: the approval notification's buttons, its tap target, and its channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApprovalNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @After fun reset() {
        HomeForeground.resumed = false
    }

    private fun held(label: String?, title: String?) =
        HeldAction(Action.Tap(10, 20), HoldReason.COMMIT_WORD, label, null, 10, 20, ScreenFacts("com.whatsapp", title, null))

    private fun post(held: HeldAction) = ApprovalCard.post(context, 7, held, null, null).let {
        shadowOf(manager).getNotification(ApprovalCard.notificationId(7))
    }

    @Test fun buttonsNameTheActionAndTheBodyOpensHome() {
        val n = post(held("SEND", "Alex"))
        assertEquals(listOf("Send to Alex", "Change", "Not now"), n.actions.map { it.title.toString() })
        // The walk harness reads this title; it is unchanged.
        assertEquals("Send in com.whatsapp to Alex", n.extras.getString("android.title"))
        assertEquals(ControlPanelActivity::class.java.name,
            shadowOf(n.contentIntent).savedIntent.component?.className)
        assertEquals(ApprovalCard.CHANNEL_ID, n.channelId)
    }

    @Test fun approveWithoutAReadableLabel() {
        val n = post(held(null, "Alex"))
        assertEquals("Approve", n.actions.first().title.toString())
    }

    @Test fun silentWhileTheHomePageIsResumed() {
        HomeForeground.resumed = true
        val n = post(held("Send", "Alex"))
        assertEquals(ApprovalCard.QUIET_CHANNEL_ID, n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(ApprovalCard.QUIET_CHANNEL_ID).importance)
        // Still on the lock screen, title only.
        assertEquals(ApprovalCard.PUBLIC_TEXT, n.publicVersion.extras.getString("android.title"))
    }
}
