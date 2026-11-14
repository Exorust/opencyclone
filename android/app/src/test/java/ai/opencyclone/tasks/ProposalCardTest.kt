package ai.opencyclone.tasks

import ai.opencyclone.gate.DeviceLock
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProposalCardTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun doThisButtonRequiresUnlock() {
        ProposalCard(context).showDoThis(7, "Send yesterday's photos to Alex", "WhatsApp")
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        val doThis = notification.actions.single { it.title == ProposalCard.ACTION_DO_THIS }
        assertTrue(doThis.isAuthenticationRequired)
    }

    @Test fun lockedPhoneDoesNotAcceptAndUnlockedPhoneMay() {
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        keyguard.setIsDeviceLocked(true)
        assertFalse(DeviceLock.isUnlocked(context))
        // Returns before the runtime is touched: no task can start.
        ProposalReceiver().onReceive(context, Intent(ProposalReceiver.ACTION_ACCEPT).putExtra(ProposalReceiver.EXTRA_TASK_ID, 7L))
        keyguard.setIsDeviceLocked(false)
        assertTrue(DeviceLock.isUnlocked(context))
    }
}
