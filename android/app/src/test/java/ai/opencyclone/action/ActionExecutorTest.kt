package ai.opencyclone.action

import ai.opencyclone.backend.PrivilegeBackend
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Supplementary tests for Action sealed class parsing and properties.
 * Covers cases not already present in ActionTest.kt — primarily Note and Remember
 * action types, plus additional edge-case coverage.
 *
 * ActionExecutor itself requires Android Context and cannot be unit-tested without
 * instrumentation, so this file focuses on the Action data model layer.
 */
class ActionExecutorTest {

    // ── Note action property tests ──────────────────────────────────────────

    @Test
    fun `Note action has correct type name and properties`() {
        val action = Action.Note(note = "User prefers dark mode", thought = "observed preference")
        assertEquals("note", action.typeName)
        assertEquals("User prefers dark mode", action.note)
        assertEquals("observed preference", action.thought)
    }

    @Test
    fun `Note default thought is empty string`() {
        val action = Action.Note(note = "some observation")
        assertEquals("", action.thought)
    }

    // ── Note fromMap tests ──────────────────────────────────────────────────

    @Test
    fun `fromMap parses note action with note field`() {
        val map = mapOf<String, Any?>(
            "action_type" to "note",
            "note" to "Screen is showing a login form",
            "thought" to "recording observation"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.Note)
        val note = action as Action.Note
        assertEquals("Screen is showing a login form", note.note)
        assertEquals("recording observation", note.thought)
    }

    @Test
    fun `fromMap note falls back to thought when note field missing`() {
        val map = mapOf<String, Any?>(
            "action_type" to "note",
            "thought" to "I notice the app crashed"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.Note)
        assertEquals("I notice the app crashed", (action as Action.Note).note)
    }

    @Test
    fun `fromMap note field takes priority over thought for note content`() {
        val map = mapOf<String, Any?>(
            "action_type" to "note",
            "note" to "Explicit note content",
            "thought" to "fallback thought"
        )
        val action = Action.fromMap(map) as Action.Note
        assertEquals("Explicit note content", action.note)
        assertEquals("fallback thought", action.thought)
    }

    // ── Remember action property tests ──────────────────────────────────────

    @Test
    fun `Remember action has correct type name and properties`() {
        val action = Action.Remember(
            key = "wifi_password",
            value = "secret123",
            category = "credential",
            thought = "saving wifi info"
        )
        assertEquals("remember", action.typeName)
        assertEquals("wifi_password", action.key)
        assertEquals("secret123", action.value)
        assertEquals("credential", action.category)
        assertEquals("saving wifi info", action.thought)
    }

    @Test
    fun `Remember default thought and category are empty strings`() {
        val action = Action.Remember(key = "city", value = "Tokyo")
        assertEquals("", action.thought)
        assertEquals("", action.category)
    }

    // ── Remember fromMap tests ──────────────────────────────────────────────

    @Test
    fun `fromMap parses remember action with all fields`() {
        val map = mapOf<String, Any?>(
            "action_type" to "remember",
            "memory_key" to "user_name",
            "memory_value" to "Alice",
            "memory_category" to "identity",
            "thought" to "user introduced herself"
        )
        val action = Action.fromMap(map)
        assertTrue(action is Action.Remember)
        val remember = action as Action.Remember
        assertEquals("user_name", remember.key)
        assertEquals("Alice", remember.value)
        assertEquals("identity", remember.category)
        assertEquals("user introduced herself", remember.thought)
    }

    @Test
    fun `fromMap remember defaults missing fields to empty strings`() {
        val map = mapOf<String, Any?>(
            "action_type" to "remember",
            "thought" to "saving"
        )
        val action = Action.fromMap(map) as Action.Remember
        assertEquals("", action.key)
        assertEquals("", action.value)
        assertEquals("", action.category)
    }

    @Test
    fun `fromMap remember with only key and value`() {
        val map = mapOf<String, Any?>(
            "action_type" to "remember",
            "memory_key" to "fav_color",
            "memory_value" to "blue"
        )
        val action = Action.fromMap(map) as Action.Remember
        assertEquals("fav_color", action.key)
        assertEquals("blue", action.value)
        assertEquals("", action.category)
        assertEquals("", action.thought)
    }

    // ── Trim whitespace in note action_type ─────────────────────────────────

    @Test
    fun `fromMap trims spaces in note action_type`() {
        val map = mapOf<String, Any?>(
            "action_type" to " note ",
            "note" to "observation",
            "thought" to "noting"
        )
        val action = Action.fromMap(map)
        assertTrue("Expected Note but got ${action::class.simpleName}", action is Action.Note)
        assertEquals("observation", (action as Action.Note).note)
    }

    @Test
    fun `fromMap trims spaces in handoff_user action_type`() {
        val map = mapOf<String, Any?>(
            "action_type" to "  handoff_user  ",
            "handoff_reason" to "password required",
            "thought" to "sensitive"
        )
        val action = Action.fromMap(map)
        assertTrue("Expected HandoffUser but got ${action::class.simpleName}", action is Action.HandoffUser)
        assertEquals("password required", (action as Action.HandoffUser).reason)
    }

    @Test
    fun `fromMap trims spaces in long_press action_type`() {
        val map = mapOf<String, Any?>(
            "action_type" to " long_press ",
            "x" to 50,
            "y" to 60,
            "thought" to "hold it"
        )
        val action = Action.fromMap(map)
        assertTrue("Expected LongPress but got ${action::class.simpleName}", action is Action.LongPress)
        assertEquals(50, (action as Action.LongPress).x)
        assertEquals(60, action.y)
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Test
    fun `fromMap fail defaults reason to unknown reason when reason missing`() {
        val map = mapOf<String, Any?>(
            "action_type" to "fail",
            "thought" to "something bad happened"
        )
        val action = Action.fromMap(map) as Action.Fail
        assertEquals("unknown reason", action.reason)
        assertEquals("something bad happened", action.thought)
    }

    @Test
    fun `fromMap open_app defaults app_name to empty string when missing`() {
        val map = mapOf<String, Any?>(
            "action_type" to "open_app",
            "thought" to "open something"
        )
        val action = Action.fromMap(map) as Action.OpenApp
        assertEquals("", action.appName)
    }

    @Test
    fun `fromMap swipe defaults direction to up when missing`() {
        val map = mapOf<String, Any?>(
            "action_type" to "swipe",
            "thought" to "scroll"
        )
        val action = Action.fromMap(map) as Action.Swipe
        assertEquals("up", action.direction)
    }

    @Test
    fun `fromMap key_event defaults key to back when missing`() {
        val map = mapOf<String, Any?>(
            "action_type" to "key_event",
            "thought" to "press key"
        )
        val action = Action.fromMap(map) as Action.KeyEvent
        assertEquals("back", action.key)
    }

    @Test
    fun `fromMap type_text defaults text to empty string when missing`() {
        val map = mapOf<String, Any?>(
            "action_type" to "type_text",
            "thought" to "typing"
        )
        val action = Action.fromMap(map) as Action.TypeText
        assertEquals("", action.text)
    }

    @Test
    fun `fromMap unknown action type preserves thought in Fail`() {
        val map = mapOf<String, Any?>(
            "action_type" to "teleport",
            "thought" to "I want to teleport"
        )
        val action = Action.fromMap(map) as Action.Fail
        assertTrue(action.reason.contains("Unknown action type"))
        assertTrue(action.reason.contains("teleport"))
        assertEquals("I want to teleport", action.thought)
    }

    @Test
    fun `fromMap with empty map returns Fail`() {
        val map = emptyMap<String, Any?>()
        val action = Action.fromMap(map)
        assertTrue(action is Action.Fail)
    }
}

/** ActionExecutor needs an Android context; Robolectric supplies one for the call_tool cases. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActionExecutorCallToolTest {
    // ── call_tool execution ─────────────────────────────────────────────

    private fun executor(caller: (suspend (String, String, String) -> Pair<Boolean, String>)?) = ActionExecutor(
        context = ApplicationProvider.getApplicationContext<Context>(),
        backend = mockk<PrivilegeBackend>(relaxed = true),
        toolCaller = caller
    )

    private val callTool = Action.CallTool("notes", "add_note", "{\"title\":\"pm-test\"}")

    @Test
    fun `call_tool without a caller fails with no integrations`() = runBlocking {
        val result = executor(null).execute(callTool, 1)
        assertFalse(result.success)
        assertEquals("No integrations are connected", result.detail)
        assertEquals("call_tool", result.actionType)
    }

    @Test
    fun `call_tool with a caller returns its result`() = runBlocking {
        var seen: Triple<String, String, String>? = null
        val result = executor { s, t, a -> seen = Triple(s, t, a); true to "note created" }.execute(callTool, 2)
        assertTrue(result.success)
        assertEquals("note created", result.detail)
        assertEquals(Triple("notes", "add_note", "{\"title\":\"pm-test\"}"), seen)
        val failed = executor { _, _, _ -> false to "denied" }.execute(callTool, 3)
        assertFalse(failed.success)
        assertEquals("denied", failed.detail)
    }

    @Test
    fun `call_tool detail is truncated to 4000 characters`() = runBlocking {
        val result = executor { _, _, _ -> true to "x".repeat(5000) }.execute(callTool, 1)
        assertEquals(4000, result.detail.length)
    }

    @Test
    fun `call_tool caller exception does not leak its message`() = runBlocking {
        val result = executor { _, _, _ -> throw IllegalStateException("secret-token-123") }.execute(callTool, 1)
        assertFalse(result.success)
        assertFalse(result.detail.contains("secret-token-123"))
    }
}
