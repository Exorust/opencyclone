package ai.opencyclone.debug

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCommandTest {
    private fun parse(cmd: String?, vararg extras: Pair<String, String>): DebugCommand {
        val map = extras.toMap()
        return DebugCommand.parse(cmd) { map[it] }
    }

    @Test fun setModelFillsDefaultsAndTrimsTheSlash() {
        assertEquals(DebugCommand.SetModel("openai", "http://localhost:8765/v1", "walk-model", "walk"),
            parse("set_model", "base_url" to "http://localhost:8765/v1/"))
        assertEquals(DebugCommand.SetModel("ollama", "http://127.0.0.1:1/v1", "m", "k"),
            parse("set_model", "provider" to "ollama", "base_url" to "http://127.0.0.1:1/v1", "model" to "m", "key" to "k"))
        assertTrue(parse("set_model") is DebugCommand.Invalid)
    }

    @Test fun textCommandsNeedText() {
        assertEquals(DebugCommand.Start("Open Gate Test"), parse("start", "text" to "Open Gate Test"))
        assertEquals(DebugCommand.Answer("Alex"), parse("answer", "text" to "Alex"))
        assertEquals(DebugCommand.Edit("Send to Sam"), parse("edit", "text" to "Send to Sam"))
        for (cmd in listOf("start", "answer", "edit")) {
            assertTrue(cmd, parse(cmd) is DebugCommand.Invalid)
            assertTrue(cmd, parse(cmd, "text" to "  ") is DebugCommand.Invalid)
        }
    }

    @Test fun plainCommands() {
        assertEquals(DebugCommand.Stop, parse("stop"))
        assertEquals(DebugCommand.Approve, parse("approve"))
        assertEquals(DebugCommand.Reject, parse(" reject "))
        assertEquals(DebugCommand.State, parse("state"))
    }

    @Test fun voiceCommands() {
        assertEquals(DebugCommand.VoiceSelftest("open the clock app", "phone_model"), parse("voice_selftest"))
        assertEquals(DebugCommand.VoiceSelftest("call Sam", "android"),
            parse("voice_selftest", "text" to "call Sam", "engine" to "android"))
        assertTrue(parse("voice_selftest", "engine" to "cloud") is DebugCommand.Invalid)
        assertEquals(DebugCommand.VoiceMictest(2000), parse("voice_mictest"))
        assertEquals(DebugCommand.VoiceMictest(500), parse("voice_mictest", "ms" to "500"))
        assertTrue(parse("voice_mictest", "ms" to "60000") is DebugCommand.Invalid)
        assertEquals(DebugCommand.VoiceEngine("phone_model"), parse("voice_engine", "value" to "phone_model"))
        assertTrue(parse("voice_engine") is DebugCommand.Invalid)
        assertTrue(parse("voice_engine", "value" to "x") is DebugCommand.Invalid)
        assertEquals(DebugCommand.VoiceSelftest("open the clock app", "service"), parse("voice_selftest", "engine" to "service"))
        assertEquals(DebugCommand.VoiceEngine("service", address = "http://localhost:8765/v1", serviceModel = "whisper-1", key = "walk"),
            parse("voice_engine", "value" to "service", "address" to "http://localhost:8765/v1/", "service_model" to "whisper-1", "key" to "walk"))
        assertEquals(DebugCommand.VoiceEngine(null, model = "en_20m_int8"), parse("voice_engine", "voice_model" to "en_20m_int8"))
        assertEquals(DebugCommand.VoiceEngine("android"), parse("voice_engine", "value" to "android"))
    }

    @Test fun notifyNeedsPackageAndText() {
        assertEquals(DebugCommand.Notify("ai.opencyclone.testapp", "Alex", "Dinner at 7?"),
            parse("notify", "package" to "ai.opencyclone.testapp", "title" to "Alex", "text" to "Dinner at 7?"))
        assertEquals(DebugCommand.Notify("p", "", "t"), parse("notify", "package" to "p", "text" to "t"))
        assertTrue(parse("notify", "text" to "t") is DebugCommand.Invalid)
        assertTrue(parse("notify", "package" to "p") is DebugCommand.Invalid)
    }

    @Test fun unknownOrMissingCommandIsInvalidAndNamesTheChoices() {
        val missing = parse(null) as DebugCommand.Invalid
        assertTrue(missing.reason.contains("set_model"))
        val unknown = parse("launch_rockets") as DebugCommand.Invalid
        assertTrue(unknown.reason.contains("launch_rockets"))
    }

    @Test fun stateJsonHasEveryField() {
        val state = WalkState(
            requestId = "r1", at = 5L,
            engine = WalkState.Engine("WaitingForUser", 3, "Which Alex?"),
            tasks = listOf(WalkState.Task(7, "user", "Send hi", "awaiting_approval", null)),
            approval = WalkState.Approval(7, "COMMIT_WORD", "Send", "Send in Gate Test to Alex", "ai.opencyclone.testapp", true),
            window = WalkState.Window(100L, true, 1, 2, 3),
            notifications = listOf(WalkState.LogEntry("p", "Alex", "proposed", 4L)),
            backend = "shizuku", accessibilityConnected = true, displayId = 12,
            displayTopPackage = "ai.opencyclone.testapp", serviceRunning = true
        )
        val json = JSONObject(state.toJson().toString())
        assertEquals(setOf("request_id", "at", "service_running", "engine", "tasks", "approval", "window",
            "notifications", "backend", "accessibility_connected", "display_id", "display_top_package",
            "wake_lock_mode", "live", "thread", "steps"), json.keySet())
        assertEquals("WaitingForUser", json.getJSONObject("engine").getString("state"))
        assertEquals(3, json.getJSONObject("engine").getInt("step"))
        val task = json.getJSONArray("tasks").getJSONObject(0)
        assertEquals(setOf("id", "origin", "goal", "state", "result"), task.keySet())
        assertTrue(task.isNull("result"))
        val approval = json.getJSONObject("approval")
        assertEquals("Send", approval.getString("label"))
        assertEquals("Send in Gate Test to Alex", approval.getString("title"))
        assertEquals("ai.opencyclone.testapp", approval.getString("package"))
        assertTrue(approval.getBoolean("approve_offered"))
        assertEquals(12, json.getInt("display_id"))
    }

    @Test fun emptyStateUsesNullsNotMissingKeys() {
        val json = WalkState(null, 1L, null, emptyList(), null, null, emptyList(), null, false, null, null, false).toJson()
        for (key in listOf("request_id", "engine", "approval", "window", "backend", "display_id", "display_top_package")) {
            assertTrue(key, json.has(key) && json.isNull(key))
        }
        assertFalse(json.getBoolean("accessibility_connected"))
        assertEquals(0, json.getJSONArray("tasks").length())
    }
}
