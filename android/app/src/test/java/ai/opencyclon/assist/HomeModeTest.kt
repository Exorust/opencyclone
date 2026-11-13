package ai.opencyclon.assist

import android.app.Application
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowRoleManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeModeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val own = "ai.opencyclon.standard"
    private val ours = HomeMode.HomeCandidate(ComponentName(own, "ai.opencyclon.ui.HomeAlias"), false)
    private val stock = HomeMode.HomeCandidate(ComponentName("com.google.android.apps.nexuslauncher", "Launcher"), true)
    private val thirdParty = HomeMode.HomeCandidate(ComponentName("com.teslacoilsw.launcher", "Nova"), false)
    private val fallback = HomeMode.HomeCandidate(ComponentName("android", "com.android.internal.app.ResolverActivity"), true)

    @After fun tearDown() = ShadowRoleManager.reset()

    @Test fun hiddenDisplayHomeSkipsOurPackage() {
        assertEquals(stock.component, HomeMode.pickHome(listOf(ours, stock), own))
        assertEquals(stock.component, HomeMode.pickHome(listOf(ours, thirdParty, stock), own))
        assertEquals(thirdParty.component, HomeMode.pickHome(listOf(ours, thirdParty), own))
        assertNull(HomeMode.pickHome(listOf(ours), own))
        assertNull(HomeMode.pickHome(listOf(ours, fallback), own))
        assertNull(HomeMode.pickHome(emptyList(), own))
    }

    @Test fun homeIntentIsDetectedByCategory() {
        assertTrue(HomeMode.isHomeIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))
        assertFalse(HomeMode.isHomeIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertFalse(HomeMode.isHomeIntent(null))
    }

    @Test fun aliasIsOffByDefaultAndTheSwitchEnablesItAndOpensHomeSettings() {
        assertFalse(HomeMode.isAliasEnabled(context))
        assertFalse(HomeMode.isOn(context))
        HomeMode.toggle(context)
        assertTrue(HomeMode.isAliasEnabled(context))
        assertEquals(Settings.ACTION_HOME_SETTINGS, shadowOf(context as Application).nextStartedActivity.action)
        // The alias is enabled but another app is still the home app: home mode is not on, and the switch keeps the alias.
        assertFalse(HomeMode.isOn(context))
        HomeMode.toggle(context)
        assertTrue(HomeMode.isAliasEnabled(context))
    }

    @Test fun switchingOffDisablesTheAlias() {
        HomeMode.setAliasEnabled(context, true)
        holdRole(RoleManager.ROLE_HOME)
        assertTrue(HomeMode.isOn(context))
        HomeMode.toggle(context)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            context.packageManager.getComponentEnabledSetting(HomeMode.alias(context)))
        assertFalse(HomeMode.isOn(context))
        assertEquals(Settings.ACTION_HOME_SETTINGS, shadowOf(context as Application).nextStartedActivity.action)
    }

    @Test fun assistantSettingsOpenTheVoiceInputPage() {
        HomeMode.openAssistantSettings(context)
        assertEquals(Settings.ACTION_VOICE_INPUT_SETTINGS, shadowOf(context as Application).nextStartedActivity.action)
    }

    @Test fun rolesAreReadFromTheRoleManager() {
        assertFalse(HomeMode.isAssistantRoleHeld(context))
        holdRole(RoleManager.ROLE_ASSISTANT)
        assertTrue(HomeMode.isAssistantRoleHeld(context))
    }

    private fun holdRole(role: String) {
        val shadow = shadowOf(context.getSystemService(RoleManager::class.java))
        shadow.addAvailableRole(role)
        shadow.addHeldRole(role)
    }

    @Test fun roleRowTexts() {
        assertEquals("Assistant", context.getString(ai.opencyclon.R.string.roles_perm_assistant))
        assertEquals("Choose this app as the digital assistant app. Other apps that use Android's voice typing will need another recognizer.", context.getString(ai.opencyclon.R.string.roles_perm_assistant_fix))
        assertEquals("Home app", context.getString(ai.opencyclon.R.string.roles_perm_home))
    }
}
