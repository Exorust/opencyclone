package ai.opencyclone.accessibility

import ai.opencyclone.accessibility.VdAccessibilityService.Companion.GateNode
import ai.opencyclone.accessibility.VdAccessibilityService.Companion.WindowRef
import ai.opencyclone.accessibility.VdAccessibilityService.Companion.factsFor
import ai.opencyclone.gate.ElementInfo
import ai.opencyclone.gate.ScreenFacts
import android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
import android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
import org.junit.Assert.assertEquals
import org.junit.Test

/** The chat screen of the test app on the hidden display, with the launcher's taskbar above it (Pixel 7, Android 17). */
class ScreenFactsTest {
    private val launcher = "com.example.launcher"
    private val testApp = "ai.opencyclone.testapp"
    private val home = setOf(launcher)

    // Front to back: index 0 is the taskbar, index 1 the app.
    private val taskbar = WindowRef(TYPE_APPLICATION, 10, launcher, 0, 2260, 1080, 2400)
    private val app = WindowRef(TYPE_APPLICATION, 1, testApp, 0, 0, 1080, 2400)

    private fun node(window: Int, pkg: String, text: String?, desc: String?, l: Int, t: Int, r: Int, b: Int,
                     heading: Boolean = false, toolbar: Boolean = false, depth: Int = 2) =
        GateNode(ElementInfo(text, desc, null, "TextView", l, t, r, b, false), pkg, window, depth,
            false, heading, toolbar, true)

    private val taskbarNodes = listOf(
        node(0, launcher, null, null, 0, 2260, 1080, 2400, depth = 0),
        node(0, launcher, null, "Apps list", 80, 2265, 205, 2390),
        node(0, launcher, null, "Taskbar Divider", 205, 2265, 330, 2390),
        node(0, launcher, "Phone", null, 330, 2265, 455, 2390),
        node(0, launcher, null, "Back", 475, 2270, 595, 2385),
        node(0, launcher, null, "Home", 655, 2270, 775, 2385),
        node(0, launcher, null, "Recents", 835, 2270, 955, 2385))
    private val appNodes = listOf(
        node(1, testApp, null, null, 0, 0, 1080, 2400, depth = 0),
        node(1, testApp, "Alex", null, 40, 120, 1040, 240, heading = true),
        node(1, testApp, "Sent: 0", null, 40, 300, 1040, 380),
        node(1, testApp, "SEND", null, 760, 2000, 1040, 2140))
    private val nodes = taskbarNodes + appNodes
    private val windows = listOf(taskbar, app)

    @Test fun `display facts come from the app, not the taskbar`() =
        assertEquals(ScreenFacts(testApp, "Alex", null), factsFor(nodes, windows, home, null))

    @Test fun `a tap on the app's send button is attributed to the app`() =
        assertEquals(ScreenFacts(testApp, "Alex", null), factsFor(nodes, windows, home, 900 to 2070))

    @Test fun `a tap on the taskbar's home button is attributed to the launcher`() =
        assertEquals(ScreenFacts(launcher, null, null), factsFor(nodes, windows, home, 700 to 2300))

    @Test fun `a tap on the keyboard is attributed to the app under it`() {
        val keyboard = WindowRef(TYPE_INPUT_METHOD, 5, "com.google.android.inputmethod.latin", 0, 1400, 1080, 2260)
        val keyNode = node(1, "com.google.android.inputmethod.latin", null, "Enter", 900, 2100, 1060, 2250)
        val shifted = appNodes.map { it.copy(window = 2) }
        assertEquals(ScreenFacts(testApp, "Alex", null),
            factsFor(taskbarNodes + keyNode + shifted, listOf(taskbar, keyboard, app), home, 950 to 2150))
    }

    @Test fun `the home screen gives the launcher and never a taskbar text as the title`() {
        val workspace = WindowRef(TYPE_APPLICATION, 1, launcher, 0, 0, 1080, 2400)
        val workspaceNodes = listOf(node(1, launcher, null, null, 0, 0, 1080, 2400, depth = 0))
        assertEquals(ScreenFacts(launcher, null, null),
            factsFor(taskbarNodes + workspaceNodes, listOf(taskbar, workspace), home, null))
        assertEquals(ScreenFacts(launcher, null, null), factsFor(taskbarNodes, listOf(taskbar), home, null))
    }

    @Test fun `a toolbar title is still read from the app window`() {
        val toolbarApp = appNodes.map { if (it.info.text == "Alex") it.copy(heading = false, inToolbar = true) else it }
        assertEquals(ScreenFacts(testApp, "Alex", null), factsFor(taskbarNodes + toolbarApp, windows, home, null))
    }
}
