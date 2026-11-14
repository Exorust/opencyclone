package ai.opencyclone.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import ai.opencyclone.App
import ai.opencyclone.assist.HomeMode
import ai.opencyclone.R
import ai.opencyclone.backend.BackendDetector

/**
 * One row of the set-up screen. An [optional] row never blocks "Done". [fix], when present, is what "Grant"
 * does (and, for an optional row that is granted, what a tap on the row does: it switches the option off).
 */
data class PermissionRow(
    val name: String,
    val granted: Boolean,
    val howToFix: String,
    val optional: Boolean = false,
    val fix: ((Context) -> Unit)? = null
)

/** The readers PermissionStatus needs. Each one is a small lambda so tests can pass fixed values. */
class PermissionReaders(
    val privilegedConnected: () -> Boolean,
    val enabledAccessibilityServices: () -> String?,
    val enabledNotificationListeners: () -> String?,
    val postNotificationsGranted: () -> Boolean,
    val ignoringBatteryOptimizations: () -> Boolean,
    val assistantRoleHeld: () -> Boolean = { false },
    val homeModeOn: () -> Boolean = { false }
)

object PermissionStatus {
    const val ACCESSIBILITY_CLASS = "ai.opencyclone.accessibility.VdAccessibilityService"
    const val NOTIFICATION_LISTENER_CLASS = "ai.opencyclone.proactive.NotificationListener"

    /** Rows in a fixed order. [text] resolves a string resource id. */
    fun rows(readers: PermissionReaders, packageName: String, text: (Int) -> String): List<PermissionRow> = listOf(
        PermissionRow(text(R.string.h_perm_privileged), readers.privilegedConnected(), text(R.string.h_perm_privileged_fix)),
        PermissionRow(
            text(R.string.h_perm_accessibility),
            containsComponent(readers.enabledAccessibilityServices(), packageName, ACCESSIBILITY_CLASS),
            text(R.string.h_perm_accessibility_fix)
        ),
        PermissionRow(
            text(R.string.h_perm_notification_listener),
            containsComponent(readers.enabledNotificationListeners(), packageName, NOTIFICATION_LISTENER_CLASS),
            text(R.string.h_perm_notification_listener_fix)
        ),
        PermissionRow(
            text(R.string.h_perm_post_notifications),
            readers.postNotificationsGranted(),
            text(R.string.h_perm_post_notifications_fix)
        ),
        PermissionRow(text(R.string.h_perm_battery), readers.ignoringBatteryOptimizations(), text(R.string.h_perm_battery_fix)),
        // Optional: holding power opens the listening screen (F1.5, F1.12).
        PermissionRow(
            text(R.string.roles_perm_assistant), readers.assistantRoleHeld(), text(R.string.roles_perm_assistant_fix),
            optional = true, fix = HomeMode::openAssistantSettings
        ),
        // Optional: the page as the phone's home screen (plan decision 10, F14.7, F14.8).
        PermissionRow(
            text(R.string.roles_perm_home), readers.homeModeOn(), text(R.string.roles_perm_home_fix),
            optional = true, fix = HomeMode::toggle
        )
    )

    /**
     * True when a colon-separated component list (the format of `enabled_notification_listeners`
     * and `enabled_accessibility_services`) contains our component. Entries may use the short
     * form `pkg/.Cls`.
     */
    fun containsComponent(setting: String?, packageName: String, className: String): Boolean {
        if (setting.isNullOrBlank()) return false
        return setting.split(':').any { entry ->
            val slash = entry.indexOf('/')
            if (slash <= 0) return@any false
            val pkg = entry.substring(0, slash).trim()
            var cls = entry.substring(slash + 1).trim()
            if (cls.startsWith(".")) cls = pkg + cls
            pkg == packageName && cls == className
        }
    }

    fun readers(context: Context): PermissionReaders {
        val app = context.applicationContext
        return PermissionReaders(
            privilegedConnected = { App.agentService?.activeBackendName != null || BackendDetector.isSystemUid() },
            enabledAccessibilityServices = {
                Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            },
            enabledNotificationListeners = {
                Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners")
            },
            postNotificationsGranted = {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            },
            ignoringBatteryOptimizations = {
                app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true
            },
            assistantRoleHeld = { HomeMode.isAssistantRoleHeld(app) },
            homeModeOn = { HomeMode.isOn(app) }
        )
    }

    fun rows(context: Context): List<PermissionRow> =
        rows(readers(context), context.packageName) { context.getString(it) }
}
