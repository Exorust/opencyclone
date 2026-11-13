package ai.opencyclon.gate

import android.app.KeyguardManager
import android.content.Context

/** Approve, a spoken "Yes", and "Do this" all need an unlocked phone. */
object DeviceLock {
    fun isUnlocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false
}
