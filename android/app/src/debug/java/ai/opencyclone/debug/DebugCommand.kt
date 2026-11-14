package ai.opencyclone.debug

import ai.opencyclone.feed.LiveState
import ai.opencyclone.feed.ThreadItem
import org.json.JSONArray
import org.json.JSONObject

/** One walk-harness command, parsed from the broadcast extras. */
sealed class DebugCommand {
    data class SetModel(val provider: String, val baseUrl: String, val model: String, val key: String) : DebugCommand()
    data class Start(val text: String) : DebugCommand()
    data class Answer(val text: String) : DebugCommand()
    object Stop : DebugCommand()
    object Approve : DebugCommand()
    object Reject : DebugCommand()
    data class Edit(val text: String) : DebugCommand()
    data class Notify(val packageName: String, val title: String, val text: String) : DebugCommand()
    object State : DebugCommand()
    /** The models of the saved connection, fetched again (the Model screen's list). */
    object ListModels : DebugCommand()
    /** Wake lock mode for the next task: "screen" (present behaviour) or "partial". */
    data class WakeLock(val mode: String) : DebugCommand()
    /** Synthesize [text] and run it through the real recognizer (no microphone). */
    data class VoiceSelftest(val text: String, val engine: String = "phone_model") : DebugCommand()
    /**
     * Sets the voice engine preference ([value]: android, phone_model, service) and/or the phone model in use
     * ([model], a catalogue id). For service, [address], [serviceModel], and [key] set the service settings.
     */
    data class VoiceEngine(
        val value: String?,
        val model: String? = null,
        val address: String? = null,
        val serviceModel: String? = null,
        val key: String? = null,
    ) : DebugCommand()
    /** Open the microphone as listening does for [ms] milliseconds and measure the level. */
    data class VoiceMictest(val ms: Long) : DebugCommand()
    data class Invalid(val reason: String) : DebugCommand()

    companion object {
        const val ACTION = "ai.opencyclone.debug.CMD"
        const val EXTRA_CMD = "cmd"
        val NAMES = listOf("set_model", "start", "answer", "stop", "approve", "reject", "edit", "notify", "state", "wake_lock", "voice_selftest", "voice_mictest", "voice_engine", "list_models")

        /** [extra] returns the string extra with that name, or null. Blank values count as missing. */
        fun parse(cmd: String?, extra: (String) -> String?): DebugCommand {
            fun value(name: String): String? = extra(name)?.takeIf { it.isNotBlank() }
            fun required(name: String, build: (String) -> DebugCommand): DebugCommand =
                value(name)?.let(build) ?: Invalid("$cmd needs extra '$name'")
            return when (cmd?.trim()) {
                null, "" -> Invalid("missing extra 'cmd'; one of ${NAMES.joinToString(", ")}")
                "set_model" -> {
                    val baseUrl = value("base_url") ?: return Invalid("set_model needs extra 'base_url'")
                    SetModel(
                        provider = value("provider") ?: "openai",
                        baseUrl = baseUrl.trimEnd('/'),
                        model = value("model") ?: "walk-model",
                        // The OpenAI-style client always sends a key; the mock ignores it.
                        key = value("key") ?: "walk"
                    )
                }
                "start" -> required("text") { Start(it) }
                "answer" -> required("text") { Answer(it) }
                "stop" -> Stop
                "approve" -> Approve
                "reject" -> Reject
                "edit" -> required("text") { Edit(it) }
                "notify" -> {
                    val pkg = value("package") ?: return Invalid("notify needs extra 'package'")
                    val text = value("text") ?: return Invalid("notify needs extra 'text'")
                    Notify(pkg, extra("title").orEmpty(), text)
                }
                "state" -> State
                "list_models" -> ListModels
                "wake_lock" -> when (val mode = value("mode")) {
                    "screen", "partial" -> WakeLock(mode)
                    else -> Invalid("wake_lock needs extra 'mode': screen or partial")
                }
                "voice_selftest" -> when (val engine = value("engine") ?: "phone_model") {
                    "android", "phone_model", "service" -> VoiceSelftest(value("text") ?: "open the clock app", engine)
                    else -> Invalid("voice_selftest extra 'engine' must be android, phone_model, or service")
                }
                "voice_engine" -> {
                    val v = value("value")
                    val model = value("voice_model")
                    when {
                        v == null && model == null -> Invalid("voice_engine needs extra 'value' (android, phone_model, service) or 'voice_model'")
                        v != null && v !in listOf("android", "phone_model", "service") ->
                            Invalid("voice_engine extra 'value' must be android, phone_model, or service")
                        else -> VoiceEngine(v, model, value("address")?.trimEnd('/'), value("service_model"), value("key"))
                    }
                }
                "voice_mictest" -> {
                    val ms = value("ms")?.toLongOrNull() ?: 2000L
                    if (ms !in 100L..20_000L) Invalid("voice_mictest extra 'ms' must be 100 to 20000")
                    else VoiceMictest(ms)
                }
                else -> Invalid("unknown cmd '$cmd'; one of ${NAMES.joinToString(", ")}")
            }
        }
    }
}

/** What `state` reports. Plain values so the JSON shape can be tested without a device. */
data class WalkState(
    val requestId: String?,
    val at: Long,
    val engine: Engine?,
    val tasks: List<Task>,
    val approval: Approval?,
    val window: Window?,
    val notifications: List<LogEntry>,
    val backend: String?,
    val accessibilityConnected: Boolean,
    val displayId: Int?,
    val displayTopPackage: String?,
    val serviceRunning: Boolean,
    /** The feed's live state and today's thread rows, as the home page sees them. */
    val live: LiveState? = null,
    val thread: List<ThreadItem> = emptyList(),
    val wakeLockMode: String? = null,
    /** The last task's steps (F18): action, addressed by number, element label, wait, feedback to the model. */
    val steps: List<ai.opencyclone.engine.StepLog.Fact> = ai.opencyclone.engine.StepLog.lastTask(),
) {
    /** [kind] is the AgentState class name: Idle, Running, Paused, Error, WaitingForUser, WaitingForHandoff. */
    data class Engine(val kind: String, val step: Int?, val detail: String?)
    data class Task(val id: Long, val origin: String, val goal: String, val state: String, val result: String?)
    /** [screenTitle] to [attachmentBounds]: the facts the gate read at hold time (lane N5). */
    data class Approval(val taskId: Long, val reason: String, val label: String?, val title: String,
                        val packageName: String?, val approveOffered: Boolean,
                        val screenTitle: String? = null, val attachmentCount: Int? = null,
                        val attachmentNoun: String? = null, val composeText: String? = null,
                        val attachmentBounds: List<IntArray> = emptyList())
    data class Window(val endsAt: Long, val active: Boolean, val handled: Int, val hidden: Int, val needsYou: Int)
    data class LogEntry(val packageName: String, val title: String, val result: String, val at: Long)

    fun toJson(): JSONObject = JSONObject().apply {
        put("request_id", requestId ?: JSONObject.NULL)
        put("at", at)
        put("service_running", serviceRunning)
        put("engine", engine?.let {
            JSONObject().put("state", it.kind).put("step", it.step ?: JSONObject.NULL)
                .put("detail", it.detail ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
        put("tasks", JSONArray().apply {
            tasks.forEach {
                put(JSONObject().put("id", it.id).put("origin", it.origin).put("goal", it.goal)
                    .put("state", it.state).put("result", it.result ?: JSONObject.NULL))
            }
        })
        put("approval", approval?.let {
            JSONObject().put("task_id", it.taskId).put("reason", it.reason)
                .put("label", it.label ?: JSONObject.NULL).put("title", it.title)
                .put("package", it.packageName ?: JSONObject.NULL).put("approve_offered", it.approveOffered)
                .put("facts", JSONObject().put("title", n(it.screenTitle)).put("attachment_count", n(it.attachmentCount))
                    .put("attachment_noun", n(it.attachmentNoun)).put("compose_text", n(it.composeText))
                    .put("attachment_bounds", JSONArray().apply { it.attachmentBounds.forEach { b -> put(JSONArray(b.toList())) } }))
        } ?: JSONObject.NULL)
        put("window", window?.let {
            JSONObject().put("ends_at", it.endsAt).put("active", it.active).put("handled", it.handled)
                .put("hidden", it.hidden).put("needs_you", it.needsYou)
        } ?: JSONObject.NULL)
        put("notifications", JSONArray().apply {
            notifications.forEach {
                put(JSONObject().put("package", it.packageName).put("title", it.title)
                    .put("result", it.result).put("at", it.at))
            }
        })
        put("backend", backend ?: JSONObject.NULL)
        put("accessibility_connected", accessibilityConnected)
        put("display_id", displayId ?: JSONObject.NULL)
        put("display_top_package", displayTopPackage ?: JSONObject.NULL)
        put("wake_lock_mode", wakeLockMode ?: JSONObject.NULL)
        put("live", live?.let(::liveJson) ?: JSONObject.NULL)
        put("thread", JSONArray().apply { thread.forEach { put(itemJson(it)) } })
        put("steps", JSONArray().apply {
            steps.forEach {
                put(JSONObject().put("step", it.step).put("action", it.action).put("by_number", it.byNumber)
                    .put("number", n(it.number)).put("label", n(it.label)).put("wait_ms", n(it.waitMs))
                    .put("feedback", n(it.feedback)).put("success", it.success).put("cycle", n(it.cycle)))
            }
        })
    }

    private fun n(value: Any?): Any = value ?: JSONObject.NULL

    private fun liveJson(l: LiveState) = JSONObject().apply {
        put("status", l.status)
        put("queued", l.queued)
        put("queued_goals", JSONArray(l.queuedGoals))
        put("status_emphasis", l.statusEmphasis?.let { JSONArray(listOf(it.first, it.last + 1)) } ?: JSONObject.NULL)
        put("running", JSONArray().apply {
            l.running.forEach {
                put(JSONObject().put("task_id", it.taskId).put("goal", it.goal).put("caption", it.caption)
                    .put("packages", JSONArray(it.packages)).put("started_at", it.startedAt).put("phase", it.phase.name))
            }
        })
        put("window", l.window?.let {
            JSONObject().put("ends_at", it.endsAt).put("handled", it.handled).put("hidden", it.hidden).put("needs_you", it.needsYou)
        } ?: JSONObject.NULL)
        put("approval", l.approval?.let {
            JSONObject().put("task_id", it.taskId).put("heading", it.heading).put("action_word", it.actionWord)
                .put("package", n(it.appPackage)).put("can_approve", it.canApprove)
                .put("count", n(it.evidence.count)).put("frame_path", n(it.evidence.framePath))
                .put("compose_text", n(it.evidence.composeText)).put("thumbs", JSONArray(it.evidence.thumbs))
        } ?: JSONObject.NULL)
        put("question", l.question?.let { JSONObject().put("task_id", n(it.taskId)).put("text", it.text) } ?: JSONObject.NULL)
        put("apps", JSONArray().apply {
            l.apps.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("accent", it.accent).put("updating", it.updating)) }
        })
    }

    private fun itemJson(i: ThreadItem) = JSONObject().apply {
        put("id", i.id); put("at", i.at); put("kind", i.kind.name); put("state", i.state.name)
        put("task_id", n(i.taskId)); put("package", n(i.appPackage)); put("title", n(i.title)); put("text", i.text)
        put("detail", n(i.detail)); put("action_word", n(i.actionWord)); put("can_approve", i.canApprove)
        put("open_app_id", n(i.openAppId))
        put("evidence", i.evidence?.let {
            JSONObject().put("count", n(it.count)).put("compose_text", n(it.composeText))
                .put("thumbs", JSONArray(it.thumbs)).put("frame_path", n(it.framePath))
        } ?: JSONObject.NULL)
    }
}
