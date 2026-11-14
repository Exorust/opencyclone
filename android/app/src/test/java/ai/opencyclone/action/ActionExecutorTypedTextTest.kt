package ai.opencyclone.action

import ai.opencyclone.accessibility.VdAccessibilityService
import ai.opencyclone.backend.PrivilegeBackend
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.util.SparseArray
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActionExecutorTypedTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val backend = mockk<PrivilegeBackend>(relaxed = true)
    private val service = spyk(VdAccessibilityService())
    private val root = mockk<AccessibilityNodeInfo>(relaxed = true)
    private val window = mockk<AccessibilityWindowInfo>(relaxed = true)
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val executor = ActionExecutor(context, displayId = 9, backend = backend)

    @Before fun connect() {
        every { backend.injectInputEvent(any(), any(), any()) } returns true
        every { service.serviceInfo } returns AccessibilityServiceInfo()
        every { service.setServiceInfo(any()) } returns Unit
        every { service.packageManager } returns context.packageManager
        every { service.packageName } returns context.packageName
        every { window.getRoot(0) } returns root
        every { window.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { root.packageName } returns "chat"
        every { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns null
        every { service.windowsOnAllDisplays } returns SparseArray<List<AccessibilityWindowInfo>>().apply {
            put(9, listOf(window))
        }
        VdAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply {
            isAccessible = true
        }.invoke(service)
        clipboard.setPrimaryClip(ClipData.newPlainText("existing", "Keep this"))
    }

    @After fun disconnect() {
        service.onDestroy()
    }

    private fun focus(editable: Boolean, text: String): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.isEditable } returns editable
        every { node.text } returns text
        every { node.inputType } returns InputType.TYPE_CLASS_TEXT
        every { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns node
        return node
    }

    private fun assertRefused() = runBlocking {
        // Cover both the ASCII key path and the non-ASCII clipboard path.
        listOf("Running late", "Running laté", "draft\n").forEach { text ->
            val result = executor.execute(Action.TypeText(text), 1)
            assertFalse(result.success)
            assertEquals("type_text", result.actionType)
            assertEquals("No text field is selected. Tap a text field first.", result.detail)
        }
        verify(exactly = 0) { backend.injectInputEvent(any(), any(), any()) }
        verify(exactly = 0) { backend.injectKeyEvent(any(), any(), any(), any(), any(), any()) }
        assertEquals("Keep this", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun `no input focus sends no keys or paste`() {
        assertRefused()
    }

    @Test fun `focused Send button sends no keys or paste`() {
        focus(editable = false, text = "Send")
        assertRefused()
        verify { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
    }

    @Test fun `disconnected accessibility sends no keys or paste`() {
        service.onDestroy()
        assertRefused()
    }

    @Test fun `unreadable display sends no keys or paste`() {
        every { service.windowsOnAllDisplays } throws IllegalStateException("Unavailable")
        assertRefused()
    }

    @Test fun `editable focus on another display cannot authorize typing`() {
        focus(editable = true, text = "Running late")
        every { service.windowsOnAllDisplays } returns SparseArray<List<AccessibilityWindowInfo>>().apply {
            put(0, listOf(window))
        }
        assertRefused()
    }

    @Test fun `editable input focus permits typing on the task display`() = runBlocking {
        focus(editable = true, text = "Running late")
        assertTrue(executor.execute(Action.TypeText("Running late"), 1).success)
        verify(atLeast = 1) { backend.injectInputEvent(any(), 9, any()) }
        verify(exactly = 0) { backend.injectInputEvent(any(), 0, any()) }
    }

    @Test fun `editable input focus permits paste on the task display`() = runBlocking {
        focus(editable = true, text = "Running laté")
        assertTrue(executor.execute(Action.TypeText("Running laté"), 1).success)
        verify(exactly = 2) { backend.injectInputEvent(any(), 9, any()) }
        assertEquals("Running laté", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }
}
