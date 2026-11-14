package ai.opencyclone.ui

import ai.opencyclone.R
import org.junit.Assert.*
import org.junit.Test

class PermissionStatusTest {
    private val pkg = "ai.opencyclone.standard"
    private val ourListener = "$pkg/ai.opencyclone.proactive.NotificationListener"
    private val ourAccessibility = "$pkg/ai.opencyclone.accessibility.VdAccessibilityService"

    private fun readers(
        privileged: Boolean = false,
        accessibility: String? = null,
        listeners: String? = null,
        post: Boolean = false,
        battery: Boolean = false,
        assistant: Boolean = false,
        home: Boolean = false
    ) = PermissionReaders({ privileged }, { accessibility }, { listeners }, { post }, { battery }, { assistant }, { home })

    private fun rows(r: PermissionReaders) = PermissionStatus.rows(r, pkg) { "s$it" }

    private fun row(rows: List<PermissionRow>, res: Int) = rows.single { it.name == "s$res" }

    @Test fun allRowsInOrderNotGrantedWithFixText() {
        val result = rows(readers())
        assertEquals(
            listOf(R.string.h_perm_privileged, R.string.h_perm_accessibility, R.string.h_perm_notification_listener,
                R.string.h_perm_post_notifications, R.string.h_perm_battery,
                R.string.roles_perm_assistant, R.string.roles_perm_home).map { "s$it" },
            result.map { it.name }
        )
        assertTrue(result.none { it.granted })
        // The floating control is gone, so "Display over other apps" is no longer a row (the list above has no such row).
        assertEquals("s${R.string.h_perm_battery_fix}", row(result, R.string.h_perm_battery).howToFix)
        assertTrue(result.all { it.howToFix.isNotBlank() })
    }

    @Test fun allRowsGranted() {
        val result = rows(readers(true, ourAccessibility, ourListener, true, true, true, true))
        assertTrue(result.all { it.granted })
        // The required rows can all be granted while the optional role rows are not.
        val required = rows(readers(true, ourAccessibility, ourListener, true, true))
        assertTrue(required.filter { !it.optional }.all { it.granted })
        assertTrue(required.filter { it.optional }.none { it.granted })
    }

    @Test fun eachRowFollowsItsOwnReader() {
        assertTrue(row(rows(readers(privileged = true)), R.string.h_perm_privileged).granted)
        assertTrue(row(rows(readers(accessibility = ourAccessibility)), R.string.h_perm_accessibility).granted)
        assertTrue(row(rows(readers(listeners = ourListener)), R.string.h_perm_notification_listener).granted)
        assertTrue(row(rows(readers(post = true)), R.string.h_perm_post_notifications).granted)
        assertTrue(row(rows(readers(battery = true)), R.string.h_perm_battery).granted)
        // One granted reader does not grant the others.
        assertEquals(1, rows(readers(battery = true)).count { it.granted })
    }

    @Test fun notificationListenerOfAnotherAppIsNotOurs() {
        val other = "com.other.app/com.other.app.Listener:com.another/ai.opencyclone.proactive.NotificationListener"
        assertFalse(row(rows(readers(listeners = other)), R.string.h_perm_notification_listener).granted)
        // Same class name under the other flavor's package is not ours either.
        assertFalse(row(rows(readers(listeners = "ai.opencyclone/ai.opencyclone.proactive.NotificationListener")),
            R.string.h_perm_notification_listener).granted)
        assertTrue(row(rows(readers(listeners = "$other:$ourListener")), R.string.h_perm_notification_listener).granted)
        assertFalse(row(rows(readers(listeners = "")), R.string.h_perm_notification_listener).granted)
    }

    @Test fun shortComponentFormIsRecognised() {
        assertTrue(PermissionStatus.containsComponent("ai.opencyclone/.proactive.NotificationListener",
            "ai.opencyclone", PermissionStatus.NOTIFICATION_LISTENER_CLASS))
        assertFalse(PermissionStatus.containsComponent("garbage", pkg, PermissionStatus.NOTIFICATION_LISTENER_CLASS))
        assertFalse(PermissionStatus.containsComponent(null, pkg, PermissionStatus.NOTIFICATION_LISTENER_CLASS))
    }

    @Test fun roleRowsAreOptionalAndFollowTheirReaders() {
        val result = rows(readers())
        val assistant = row(result, R.string.roles_perm_assistant)
        val home = row(result, R.string.roles_perm_home)
        assertTrue(assistant.optional && home.optional)
        assertEquals(listOf(assistant, home), result.filter { it.optional })
        assertEquals("s${R.string.roles_perm_assistant_fix}", assistant.howToFix)
        assertNotNull(assistant.fix)
        assertNotNull(home.fix)
        assertTrue(row(rows(readers(assistant = true)), R.string.roles_perm_assistant).granted)
        assertTrue(row(rows(readers(home = true)), R.string.roles_perm_home).granted)
        assertEquals(1, rows(readers(home = true)).count { it.granted })
        // Required rows have no switch of their own; "Grant" opens their settings page in the set-up screen.
        assertTrue(result.filter { !it.optional }.all { it.fix == null })
    }
}
