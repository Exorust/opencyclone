package ai.opencyclon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every component from the wave-2 manifest fragments is declared once, and setup.sh grants existing components. */
class ManifestEntriesTest {
    private fun file(vararg candidates: String) = candidates.map(::File).first { it.exists() }
    private val manifest by lazy { file("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").readText() }

    @Test fun mergedComponentsAreDeclaredOnce() {
        for (name in listOf(".gate.ApprovalReceiver", ".tasks.ProposalReceiver", ".gate.EditActivity",
                ".miniapp.MiniAppActivity", ".integrations.IntegrationsActivity", ".ui.FirstRunActivity",
                ".ui.PrivacyActivity", ".ui.RulesActivity", ".ui.QuickAskTileService")) {
            assertEquals(name, 1, Regex("android:name=\"${Regex.escape(name)}\"").findAll(manifest).count())
        }
        for (permission in listOf("WRITE_SECURE_SETTINGS", "POST_NOTIFICATIONS", "com.android.launcher.permission.INSTALL_SHORTCUT")) {
            assertEquals(permission, 1, Regex("uses-permission[^>]*${Regex.escape(permission)}\"").findAll(manifest).count())
        }
        assertTrue(manifest.contains("android:resource=\"@xml/shortcuts\""))
        assertFalse("manifest-fragments must be merged and removed", File("../manifest-fragments").exists())
    }

    /** Every component another app can reach is listed here on purpose; anything new must be added deliberately. */
    @Test fun onlyReviewedComponentsAreExported() {
        val exported = Regex("<(activity|service|receiver|provider)\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).findAll(manifest)
            .map { it.value }
            .filter { it.contains("android:exported=\"true\"") }
            .map { Regex("android:name=\"([^\"]+)\"").find(it)!!.groupValues[1] }
            .toSet()
        assertEquals(setOf(
            ".ui.ControlPanelActivity",                 // launcher entry
            ".accessibility.VdAccessibilityService",    // bound by the system, BIND_ACCESSIBILITY_SERVICE
            "rikka.shizuku.ShizukuProvider",            // Shizuku hands over its binder, INTERACT_ACROSS_USERS_FULL
            ".backend.BinderExchangeProvider",          // the shell-uid helper returns its binder; uid and token checked
            ".schedule.BootReceiver",                   // BOOT_COMPLETED, only the system can send it
            ".proactive.NotificationListener",          // bound by the system, BIND_NOTIFICATION_LISTENER_SERVICE
            ".ui.FirstRunActivity",                     // browser return opencyclon://signed-in, state-checked
            ".ui.QuickAskTileService",                  // bound by the system, BIND_QUICK_SETTINGS_TILE
            ".miniapp.ReceivedAppActivity",             // "Open with"/share of a received app file; checked, stores only on Open
            ".ui.HomeAlias",                            // home mode; disabled by default, the owner switches it on in Set up
            ".assist.AssistService",                    // bound by the system, BIND_VOICE_INTERACTION
            ".assist.AssistSessionService",             // bound by the system, BIND_VOICE_INTERACTION
            ".assist.StubRecognitionService",           // required by the assistant role; answers every caller with an error, never records
        ), exported)
        assertFalse("the config deep link is removed", manifest.contains("android:host=\"config\""))
        for (permission in listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
                "SCHEDULE_EXACT_ALARM", "INJECT_EVENTS", "READ_FRAME_BUFFER", "CAPTURE_SECURE_VIDEO_OUTPUT", "MANAGE_ACTIVITY_TASKS")) {
            assertFalse(permission, manifest.contains("android.permission.$permission\""))
        }
    }

    @Test fun setupGrantsMatchDeclaredServices() {
        val setup = file("../../setup.sh", "../setup.sh").readText()
        val components = Regex("\\\$PACKAGE_ID/(ai\\.opencyclon\\.[A-Za-z.]+)").findAll(setup).map { it.groupValues[1] }.toList()
        assertTrue(components.isNotEmpty())
        for (cls in components) {
            assertTrue("$cls is granted by setup.sh but not declared", manifest.contains("android:name=\"${cls.removePrefix("ai.opencyclon")}\""))
        }
    }

    @Test fun homeAliasIsDisabledByDefaultAndAssistServicesNeedTheSystemPermission() {
        val alias = Regex("<activity-alias\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.value }
            .single { it.contains("android:name=\".ui.HomeAlias\"") }
        assertTrue(alias.contains("android:enabled=\"false\""))
        assertTrue(alias.contains("android:targetActivity=\".ui.ControlPanelActivity\""))
        for (name in listOf(".assist.AssistService", ".assist.AssistSessionService")) {
            val tag = Regex("<service\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.value }
                .single { it.contains("android:name=\"$name\"") }
            assertTrue(name, tag.contains("android:permission=\"android.permission.BIND_VOICE_INTERACTION\""))
        }
        assertFalse("our home must not be a secondary home", manifest.contains("SECONDARY_HOME"))
    }
}
