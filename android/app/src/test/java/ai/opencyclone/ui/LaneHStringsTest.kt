package ai.opencyclone.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every string lane H added (names starting with "h_") exists in every strings.xml file. */
class LaneHStringsTest {
    private val expected = listOf(
        "h_first_run_title", "h_section_permissions", "h_section_model", "h_section_privacy", "h_section_allowlist",
        "h_perm_privileged", "h_perm_privileged_fix", "h_perm_accessibility", "h_perm_accessibility_fix",
        "h_perm_notification_listener", "h_perm_notification_listener_fix",
        "h_perm_post_notifications", "h_perm_post_notifications_fix", "h_perm_battery", "h_perm_battery_fix",
        "h_model_address_hint", "h_model_key_hint", "h_model_save", "h_model_test", "h_model_testing", "h_model_saved",
        "h_model_reachable", "h_model_unreachable", "h_privacy_sent_to", "h_allowlist_explain", "h_allowlist_empty",
        "h_done", "h_privacy_title", "h_privacy_model_address", "h_privacy_data_heading",
        "h_privacy_data_notifications", "h_privacy_data_screens", "h_privacy_blocked_heading",
        "h_privacy_blocked_fixed", "h_privacy_blocked_owner", "h_privacy_blocked_owner_none", "h_privacy_dropped",
        "h_privacy_open_rules", "h_rules_empty", "h_quick_ask_tile_label"
    )

    private fun resDir(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"), File("android/app/src/main/res"))
        return candidates.first { it.isDirectory }
    }

    private fun names(file: File): Set<String> =
        Regex("<string name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    @Test fun everyAddedStringExistsInEveryStringsFile() {
        val files = resDir().listFiles()!!.filter { it.name.startsWith("values") }
            .map { File(it, "strings.xml") }.filter { it.isFile }
        // English only (decision 12): the default strings file is the only one.
        assertTrue("expected the default strings file", files.isNotEmpty())
        for (file in files) {
            val present = names(file)
            val missing = expected.filter { it !in present }
            assertTrue("${file.path} is missing $missing", missing.isEmpty())
        }
        // Nothing added under the h_ prefix that this list does not know about.
        val added = names(File(resDir(), "values/strings.xml")).filter { it.startsWith("h_") }.toSet()
        assertTrue("unlisted: ${added - expected.toSet()}", added == expected.toSet())
    }
}
