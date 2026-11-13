package ai.opencyclon.accessibility

import ai.opencyclon.accessibility.VdAccessibilityService.Companion.topAppWindow
import ai.opencyclon.accessibility.VdAccessibilityService.Companion.TopWindow
import ai.opencyclon.accessibility.VdAccessibilityService.Companion.WindowRef
import android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
import android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
import android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
import android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopAppWindowTest {
    private val chat = WindowRef(TYPE_APPLICATION, 1, "com.whatsapp")
    private val keyboard = WindowRef(TYPE_INPUT_METHOD, 5, "com.google.android.inputmethod.latin")

    @Test fun `the keyboard above the app is skipped`() {
        assertEquals(TopWindow(0, "com.whatsapp"), topAppWindow(listOf(chat, keyboard)))
        assertEquals(TopWindow(1, "com.whatsapp"), topAppWindow(listOf(keyboard, chat)))
    }

    @Test fun `an accessibility overlay above the app is skipped`() {
        val overlay = WindowRef(TYPE_ACCESSIBILITY_OVERLAY, 9, "ai.opencyclon.standard")
        assertEquals(TopWindow(1, "com.whatsapp"), topAppWindow(listOf(overlay, chat, keyboard)))
    }

    @Test fun `the highest layer wins regardless of list order`() {
        val dialog = WindowRef(TYPE_APPLICATION, 3, "com.whatsapp.dialog")
        assertEquals(TopWindow(1, "com.whatsapp.dialog"), topAppWindow(listOf(chat, dialog, keyboard)))
    }

    @Test fun `system ui is skipped`() {
        val bar = WindowRef(TYPE_SYSTEM, 8, "com.android.systemui")
        assertEquals(TopWindow(1, "com.whatsapp"), topAppWindow(listOf(bar, chat)))
    }

    @Test fun `a blocked app under the keyboard is reported`() {
        val bank = WindowRef(TYPE_APPLICATION, 2, "com.chase.sig.android")
        assertEquals("com.chase.sig.android", topAppWindow(listOf(keyboard, bank))?.packageName)
    }

    @Test fun `only a keyboard means no app window`() {
        assertNull(topAppWindow(listOf(keyboard)))
        assertNull(topAppWindow(emptyList()))
    }

    // Rows a-f: a hidden display of 1080x2400 with the launcher's taskbar as a strip along the bottom.
    private val launcher = "com.example.launcher"
    private val home = setOf(launcher)
    private val app = WindowRef(TYPE_APPLICATION, 1, "ai.opencyclon.testapp", 0, 0, 1080, 2400)
    private val taskbar = WindowRef(TYPE_APPLICATION, 10, launcher, 0, 2260, 1080, 2400)
    private val sizedKeyboard = WindowRef(TYPE_INPUT_METHOD, 5, "com.google.android.inputmethod.latin", 0, 1400, 1080, 2260)

    @Test fun `a - the launcher taskbar above an app is decoration`() {
        assertEquals(TopWindow(1, "ai.opencyclon.testapp"), topAppWindow(listOf(taskbar, app), home))
    }

    @Test fun `b - app under a keyboard and a taskbar`() {
        assertEquals(TopWindow(2, "ai.opencyclon.testapp"), topAppWindow(listOf(taskbar, sizedKeyboard, app), home))
    }

    @Test fun `c - only the launcher gives the launcher`() {
        val workspace = WindowRef(TYPE_APPLICATION, 1, launcher, 0, 0, 1080, 2400)
        assertEquals(TopWindow(1, launcher), topAppWindow(listOf(taskbar, workspace), home))
        // The taskbar alone (the workspace exposes no window) is still the launcher.
        assertEquals(TopWindow(0, launcher), topAppWindow(listOf(taskbar), home))
    }

    @Test fun `d - a blocked app under the taskbar is reported`() {
        val settings = WindowRef(TYPE_APPLICATION, 1, "com.android.settings", 0, 0, 1080, 2400)
        assertEquals("com.android.settings", topAppWindow(listOf(taskbar, settings), home)?.packageName)
    }

    @Test fun `e - an app's own dialog above its main window`() {
        val dialog = WindowRef(TYPE_APPLICATION, 3, "ai.opencyclon.testapp", 140, 900, 940, 1500)
        assertEquals(TopWindow(1, "ai.opencyclon.testapp"), topAppWindow(listOf(taskbar, dialog, app), home))
    }

    @Test fun `f - a small permission dialog of another package wins`() {
        val permission = WindowRef(TYPE_APPLICATION, 3, "com.google.android.permissioncontroller", 60, 1500, 1020, 2200)
        assertEquals(TopWindow(1, "com.google.android.permissioncontroller"),
            topAppWindow(listOf(taskbar, permission, app), home))
    }

    @Test fun `an unknown launcher's system-type taskbar is decoration without home packages`() {
        val strip = WindowRef(TYPE_SYSTEM, 10, "com.other.launcher", 0, 2260, 1080, 2400)
        assertEquals(TopWindow(1, "ai.opencyclon.testapp"), topAppWindow(listOf(strip, app)))
    }

    @Test fun `a full-screen home window is not decoration`() {
        val workspace = WindowRef(TYPE_APPLICATION, 4, launcher, 0, 0, 1080, 2400)
        assertEquals(TopWindow(0, launcher), topAppWindow(listOf(workspace, app), home))
    }
}
