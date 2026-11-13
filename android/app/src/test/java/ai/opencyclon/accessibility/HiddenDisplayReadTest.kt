package ai.opencyclon.accessibility

import ai.opencyclon.accessibility.VdAccessibilityService.Companion.WindowRef
import ai.opencyclon.accessibility.VdAccessibilityService.Companion.fieldWindowOrder
import ai.opencyclon.accessibility.VdAccessibilityService.Companion.isDecoration
import ai.opencyclon.accessibility.VdAccessibilityService.Companion.isIndeterminateProgress
import android.view.accessibility.AccessibilityNodeInfo.RangeInfo
import android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
import android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
import android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the reader of the hidden display sees on Android 16 and later (F18.2, F18.3). */
class HiddenDisplayReadTest {
    private val app = WindowRef(TYPE_APPLICATION, 1, "ai.opencyclon.testapp", 0, 0, 1080, 2400)
    // On the phone the launcher taskbar is a system-type strip above the app, with its own input focus.
    private val taskbar = WindowRef(TYPE_SYSTEM, 4, "com.google.android.apps.nexuslauncher", 0, 2260, 1080, 2400)
    private val keyboard = WindowRef(TYPE_INPUT_METHOD, 5, "com.google.android.inputmethod.latin", 0, 1500, 1080, 2400)
    private val home = setOf("com.google.android.apps.nexuslauncher")

    @Test fun `an indeterminate ProgressBar is a spinner with or without a range`() {
        // From API 36 an indeterminate ProgressBar reports RangeInfo.INDETERMINATE instead of no range.
        assertEquals(3, RangeInfo.RANGE_TYPE_INDETERMINATE)
        assertTrue(isIndeterminateProgress("android.widget.ProgressBar", RangeInfo.RANGE_TYPE_INDETERMINATE))
        assertTrue(isIndeterminateProgress("android.widget.ProgressBar", null))
        assertFalse(isIndeterminateProgress("android.widget.ProgressBar", RangeInfo.RANGE_TYPE_INT))
        assertFalse(isIndeterminateProgress("android.widget.ProgressBar", RangeInfo.RANGE_TYPE_PERCENT))
        assertFalse(isIndeterminateProgress("android.widget.SeekBar", RangeInfo.RANGE_TYPE_INDETERMINATE))
    }

    @Test fun `the focused field is looked for in the app, not in the taskbar or the keyboard`() {
        assertEquals(listOf(1), fieldWindowOrder(listOf(taskbar, app, keyboard), home))
        assertEquals(listOf(0), fieldWindowOrder(listOf(app, taskbar), home))
    }

    @Test fun `a small home window is decoration, a full-size one is not`() {
        val area = 1080L * 2400
        assertTrue(isDecoration(taskbar, area, home))
        assertFalse(isDecoration(app, area, home))
        assertFalse(isDecoration(taskbar.copy(packageName = "com.whatsapp", type = TYPE_APPLICATION), area, home))
        assertFalse(isDecoration(taskbar.copy(top = 0, bottom = 2400), area, home))
    }
}
