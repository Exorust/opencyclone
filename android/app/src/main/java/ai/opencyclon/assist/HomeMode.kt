package ai.opencyclon.assist

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log

/**
 * The optional home mode (plan decision 10, off by default): the home page doubles as the phone's home screen
 * through a disabled-by-default alias with a HOME intent filter, and the system's role picker makes it the
 * home app. It also holds the rule that keeps the engine from starting our own page on the hidden display.
 */
object HomeMode {
    const val ALIAS_CLASS = "ai.opencyclon.ui.HomeAlias"
    private const val TAG = "HomeMode"

    fun alias(context: Context) = ComponentName(context.packageName, ALIAS_CLASS)

    /** The alias is enabled only by [toggle]; the manifest default is disabled. */
    fun isAliasEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(alias(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setAliasEnabled(context: Context, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        context.packageManager.setComponentEnabledSetting(alias(context), state, PackageManager.DONT_KILL_APP)
    }

    fun isHomeRoleHeld(context: Context): Boolean = roleHeld(context, RoleManager.ROLE_HOME)

    fun isAssistantRoleHeld(context: Context): Boolean = roleHeld(context, RoleManager.ROLE_ASSISTANT)

    /** Home mode is on when the alias is enabled and the system made this app the home app. */
    fun isOn(context: Context): Boolean = isAliasEnabled(context) && isHomeRoleHeld(context)

    /** True when the page was started as the home screen (Home key, or the launch after choosing it). */
    fun isHomeIntent(intent: Intent?): Boolean = intent?.hasCategory(Intent.CATEGORY_HOME) == true

    /**
     * The switch of the "Home app" row. On: enable the alias. Off (home mode is on): disable it. Either way the
     * system "Default home app" page opens, where the owner confirms the choice. When the alias is enabled but
     * another app is still the home app, the alias stays enabled and the page opens again.
     */
    fun toggle(context: Context) {
        setAliasEnabled(context, !isOn(context))
        open(context, Intent(Settings.ACTION_HOME_SETTINGS))
    }

    /** The "Assistant" row: the system page where the digital assistant app is chosen. */
    fun openAssistantSettings(context: Context) {
        open(context, Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }

    /** Opens the first intent that resolves; the app details page is the last fallback. */
    private fun open(context: Context, vararg intents: Intent) {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        for (intent in intents.toList() + details) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private fun roleHeld(context: Context, role: String): Boolean = runCatching {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(role) == true
    }.getOrDefault(false)

    // ── The home app for the hidden display ─────────────────────────────

    data class HomeCandidate(val component: ComponentName, val system: Boolean)

    /**
     * The home activity to start on the hidden display: never one of [ownPackage] (the gate blocks every action
     * inside our own app, so a task could not leave it), a system app before others, otherwise the first in
     * the system's order. Null when no other home app exists.
     */
    fun pickHome(candidates: List<HomeCandidate>, ownPackage: String): ComponentName? {
        val others = candidates.filter { it.component.packageName != ownPackage && it.component.packageName != "android" }
        return (others.firstOrNull { it.system } ?: others.firstOrNull())?.component
    }

    /** All activities for `MAIN` + [category], as [pickHome] candidates. */
    fun homeCandidates(context: Context, category: String): List<HomeCandidate> = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        context.packageManager.queryIntentActivities(intent, 0).map {
            val info = it.activityInfo
            HomeCandidate(
                ComponentName(info.packageName, info.name),
                (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot list home activities", e)
        emptyList()
    }
}
