package ai.opencyclone.debug

import ai.opencyclone.App
import ai.opencyclone.accessibility.VdAccessibilityService
import ai.opencyclone.config.ConfigRepository
import ai.opencyclone.db.AppDatabase
import ai.opencyclone.engine.AgentState
import ai.opencyclone.gate.ApprovalCard
import ai.opencyclone.gate.ApprovalReceiver
import ai.opencyclone.gate.ApprovalSession
import ai.opencyclone.gate.DeviceLock
import ai.opencyclone.proactive.Incoming
import ai.opencyclone.proactive.NotificationListener
import ai.opencyclone.tasks.ProactiveRuntime
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Debug builds only. Lets the walk harness drive the app from `adb shell am broadcast`:
 *
 *   adb shell am broadcast -n ai.opencyclone.standard/ai.opencyclone.debug.DebugCommandReceiver \
 *       -a ai.opencyclone.debug.CMD --es cmd state
 *
 * The manifest entry requires the sender to hold DUMP, which the adb shell user has and ordinary apps do not.
 * Every command logs one line with tag WALK and returns that line as the broadcast result data.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "WALK"
        const val EXTRA_REQUEST_ID = "id"
        private const val BUDGET_MS = 8_000L
        /** The voice self-test loads a 60 MB model and runs the platform voice; walk.py sends it as a background broadcast (60 s limit). */
        private const val VOICE_BUDGET_MS = 50_000L

        /** Tests swap this for an immediate dispatcher. */
        internal var dispatcher: CoroutineDispatcher = Dispatchers.Default
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext ?: context
        val pending = try { goAsync() } catch (_: Exception) { null }
        // Version 2 first (lane X2): start, approve, reject, confirm, page, widget_seed, app_seed use the home page's entry.
        val v2 = try {
            DebugCommandV2.parse(intent.getStringExtra(DebugCommand.EXTRA_CMD)) { intent.getStringExtra(it) }
        } catch (error: Exception) {
            DebugCommandV2.Invalid("cannot read extras: ${error.message}")
        }
        val command: DebugCommand? = if (v2 != null) null else try {
            DebugCommand.parse(intent.getStringExtra(DebugCommand.EXTRA_CMD)) { intent.getStringExtra(it) }
        } catch (error: Exception) {
            DebugCommand.Invalid("cannot read extras: ${error.message}")
        }
        val requestId = try { intent.getStringExtra(EXTRA_REQUEST_ID) } catch (_: Exception) { null }
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            val outcome = try {
                val budget = if (command is DebugCommand.VoiceSelftest || command is DebugCommand.VoiceMictest) VOICE_BUDGET_MS else BUDGET_MS
                withTimeout(budget) {
                    if (command == null) DebugCommandsV2(app).run(v2!!) else DebugCommands(app).run(command, requestId)
                }
            } catch (error: Throwable) {
                DebugCommands.Outcome(false, "error: ${error.javaClass.simpleName}: ${error.message}")
            }
            val name = intent.getStringExtra(DebugCommand.EXTRA_CMD) ?: "?"
            try {
                Log.i(TAG, "$name ${if (outcome.ok) "ok" else "FAIL"}: ${outcome.line.take(300)}")
                pending?.setResult(if (outcome.ok) 0 else 1, outcome.data ?: outcome.line, null)
            } catch (_: Throwable) {
            } finally {
                try { pending?.finish() } catch (_: Throwable) {}
            }
        }
    }
}

/**
 * Runs one command against the same entry points the screens and notification buttons use.
 * [session] and [deliverToApprovalReceiver] are parameters only so unit tests can avoid building the whole runtime.
 */
class DebugCommands(
    private val context: Context,
    private val session: () -> ApprovalSession = { ProactiveRuntime.init(context).session },
    private val deliverToApprovalReceiver: (Intent) -> Unit = { ApprovalReceiver().onReceive(context, it) }
) {
    /** [line] is the log line; [data] (the state JSON) replaces it as the broadcast result when set. */
    data class Outcome(val ok: Boolean, val line: String, val data: String? = null)

    private fun ok(line: String) = Outcome(true, line)
    private fun fail(line: String) = Outcome(false, line)

    suspend fun run(command: DebugCommand, requestId: String?): Outcome = when (command) {
        is DebugCommand.Invalid -> fail(command.reason)
        is DebugCommand.SetModel -> setModel(command)
        is DebugCommand.Start -> start(command.text)
        is DebugCommand.Answer -> answer(command.text)
        DebugCommand.Stop -> stop()
        DebugCommand.Approve -> approve()
        DebugCommand.Reject -> reject()
        is DebugCommand.Edit -> edit(command.text)
        is DebugCommand.Notify -> notify(command)
        DebugCommand.State -> state(requestId)
        DebugCommand.ListModels -> ai.opencyclone.llm.ModelLists(context).load(ConfigRepository(context), refresh = true)
            ?.let { list -> ok("models: " + list.joinToString(", ") { it.id }) } ?: fail("could not get the list of models")
        is DebugCommand.VoiceSelftest -> voice("voice_selftest") {
            when (command.engine) {
                ai.opencyclone.ui.voice.VoiceFactory.ANDROID -> VoiceDebug(context).selftestAndroid()
                ai.opencyclone.ui.voice.VoiceFactory.SERVICE -> VoiceDebug(context).selftestService(command.text)
                else -> VoiceDebug(context).selftest(command.text)
            }
        }
        is DebugCommand.VoiceEngine -> VoiceDebug(context).setEngine(command)?.let { fail(it) }
            ?: ok(VoiceDebug(context).engineLine())
        is DebugCommand.VoiceMictest -> voice("voice_mictest") { VoiceDebug(context).mictest(command.ms) }
        is DebugCommand.WakeLock -> {
            ai.opencyclone.AgentService.setWakeLockMode(context, command.mode)
            ok("wake lock mode ${command.mode}; applies to the next task")
        }
    }

    // The settings screen and the first-run screen write these same four preferences.
    private fun setModel(c: DebugCommand.SetModel): Outcome {
        val config = ConfigRepository(context)
        config.apiProvider = c.provider
        // The walk's missing-model case cannot use a real account, and must not clear its secrets.
        if (c.provider != "walk-unconfigured") config.apiKey = c.key
        config.baseUrl = c.baseUrl
        config.chooseModel(c.model)
        return ok("provider=${c.provider} base_url=${c.baseUrl} model=${c.model}")
    }

    // The chat's send button: AgentService.startAgent, which goes through the request router and the coordinator.
    private suspend fun start(text: String): Outcome {
        val service = App.agentService ?: return fail("agent service is not running; open the app first")
        withContext(Dispatchers.Main) { service.startAgent(text) }
        return ok("started: $text")
    }

    // The chat's send button while something waits: the builder's question first, then the agent's ask_user.
    private suspend fun answer(text: String): Outcome = withContext(Dispatchers.Main) {
        if (ProactiveRuntime.answerPendingQuestion(text)) return@withContext ok("answered the builder's question")
        val service = App.agentService ?: return@withContext fail("agent service is not running")
        val state = service.stateFlow?.value
        if (state !is AgentState.WaitingForUser) return@withContext fail("nothing waits for an answer (engine ${engineOf(state)?.kind})")
        service.submitUserResponse(text)
        ok("answered: $text")
    }

    private suspend fun stop(): Outcome = withContext(Dispatchers.Main) {
        val service = App.agentService ?: return@withContext fail("agent service is not running")
        service.stopAgent()
        ok("stopped")
    }

    private fun approve(): Outcome {
        val session = session()
        val pending = session.pending() ?: return fail("no approval is waiting")
        // The card shows Approve only when the target title is readable (ApprovalCard.post).
        if (pending.held.facts.title == null) return fail("Approve is not offered for task ${pending.taskId} (title unreadable)")
        // The button requires unlock; ApprovalReceiver checks again below.
        if (!DeviceLock.isUnlocked(context)) return fail("the phone is locked")
        deliverToApprovalReceiver(Intent(context, ApprovalReceiver::class.java)
            .setAction(ApprovalReceiver.ACTION_APPROVE).putExtra(ApprovalReceiver.EXTRA_TASK_ID, pending.taskId))
        return if (session.pending()?.taskId == pending.taskId) fail("approve not accepted for task ${pending.taskId}")
        else ok("approved task ${pending.taskId}")
    }

    private fun reject(): Outcome {
        val session = session()
        val pending = session.pending() ?: return fail("no approval is waiting")
        deliverToApprovalReceiver(Intent(context, ApprovalReceiver::class.java)
            .setAction(ApprovalReceiver.ACTION_REJECT).putExtra(ApprovalReceiver.EXTRA_TASK_ID, pending.taskId))
        return if (session.pending()?.taskId == pending.taskId) fail("reject not accepted for task ${pending.taskId}")
        else ok("rejected task ${pending.taskId}")
    }

    // EditActivity's submit: the card's Edit button requires unlock.
    private fun edit(text: String): Outcome {
        val session = session()
        val pending = session.pending() ?: return fail("no approval is waiting")
        if (!DeviceLock.isUnlocked(context)) return fail("the phone is locked")
        val accepted = session.edit(pending.taskId, text)
        ApprovalCard.cancel(context, pending.taskId)
        return if (accepted) ok("edited task ${pending.taskId}: $text") else fail("edit not accepted for task ${pending.taskId}")
    }

    // The same call NotificationListener.onNotificationPosted makes.
    private fun notify(c: DebugCommand.Notify): Outcome {
        ProactiveRuntime.init(context)
        val pipeline = NotificationListener.pipeline ?: return fail("notification pipeline is not ready")
        val now = System.currentTimeMillis()
        val key = "walk|${c.packageName}|$now"
        pipeline.post(Incoming(c.packageName, key, c.packageName + ":" + c.title, c.title, c.text, now))
        return ok("posted key=$key; triage runs about 30 seconds after the first queued notification")
    }

    /** Runs a voice test; the JSON goes to the broadcast result, the WALK log, and files/walk/<name>.json. */
    private suspend fun voice(name: String, test: suspend () -> org.json.JSONObject): Outcome {
        val json = withContext(Dispatchers.IO) { test() }
        val text = json.toString()
        try {
            File(File(context.filesDir, "walk").apply { mkdirs() }, "$name.json").writeText(text)
        } catch (error: Exception) {
            Log.w(DebugCommandReceiver.TAG, "cannot write $name.json: ${error.message}")
        }
        val pieces = text.chunked(3000)
        pieces.forEachIndexed { i, piece -> Log.i(DebugCommandReceiver.TAG, "$name ${i + 1}/${pieces.size} $piece") }
        return Outcome(json.optBoolean("ok", json.optBoolean("started", false)), "$name done", data = text)
    }

    private suspend fun state(requestId: String?): Outcome {
        val json = snapshot(requestId).toJson().toString()
        val written = listOfNotNull(context.getExternalFilesDir(null), context.filesDir).mapNotNull { dir ->
            try {
                val folder = File(dir, "walk").apply { mkdirs() }
                val tmp = File(folder, "state.json.tmp")
                tmp.writeText(json)
                val target = File(folder, "state.json")
                if (!tmp.renameTo(target)) { target.writeText(json); tmp.delete() }
                target.absolutePath
            } catch (error: Exception) {
                Log.w(DebugCommandReceiver.TAG, "cannot write state in $dir: ${error.message}")
                null
            }
        }
        // Logcat cuts long lines; the JSON goes out in numbered pieces.
        val pieces = json.chunked(3000)
        pieces.forEachIndexed { i, piece -> Log.i(DebugCommandReceiver.TAG, "state ${i + 1}/${pieces.size} $piece") }
        return Outcome(true, "state written to ${written.joinToString(", ")}", data = json)
    }

    suspend fun snapshot(requestId: String?): WalkState {
        val session = session()
        val db = AppDatabase.getInstance(context)
        val now = System.currentTimeMillis()
        val service = App.agentService
        val tasks = db.taskDao().all().takeLast(20).map {
            WalkState.Task(it.id, it.origin, it.goal, it.state, it.result)
        }
        val approval = session.pending()?.let { pending ->
            val held = pending.held
            WalkState.Approval(pending.taskId, held.reason.name, held.label,
                ApprovalCard.title(held, ApprovalCard.appLabel(context, held.facts.topPackage)),
                held.facts.topPackage, held.facts.title != null,
                held.facts.title, held.facts.attachmentCount, held.facts.attachmentNoun, held.facts.composeText,
                held.facts.attachmentBounds.map { intArrayOf(it.left, it.top, it.right, it.bottom) })
        }
        val window = db.windowDao().latest()?.let {
            WalkState.Window(it.endsAt, it.endsAt > now, it.handled, it.hidden, it.needsYou)
        }
        val log = db.notificationLogDao().since(now - 60 * 60_000L).takeLast(20).map {
            WalkState.LogEntry(it.packageName, it.title, it.result, it.at)
        }
        val vdm = service?.getVirtualDisplayManager()?.takeIf { it.isCreated }
        val displayId = vdm?.displayId
        return WalkState(
            requestId = requestId,
            at = now,
            engine = engineOf(service?.stateFlow?.value),
            tasks = tasks,
            approval = approval,
            window = window,
            notifications = log,
            backend = service?.activeBackendName,
            accessibilityConnected = VdAccessibilityService.instance != null,
            displayId = displayId,
            displayTopPackage = displayId?.let { VdAccessibilityService.getTopPackageOnDisplay(it) },
            serviceRunning = service != null,
            // Only when the runtime exists already; reading it must not build it.
            live = if (ProactiveRuntime.isReady) ProactiveRuntime.liveState.state.value else null,
            thread = if (ProactiveRuntime.isReady) withTimeoutOrNull(2_000) { ProactiveRuntime.recorder.today().first() }.orEmpty() else emptyList(),
            wakeLockMode = ai.opencyclone.AgentService.wakeLockMode(context)
        )
    }

    private fun engineOf(state: AgentState?): WalkState.Engine? = when (state) {
        null -> null
        is AgentState.Idle -> WalkState.Engine("Idle", null, state.resultMessage)
        is AgentState.Running -> WalkState.Engine("Running", state.step, state.thought)
        AgentState.Paused -> WalkState.Engine("Paused", null, null)
        is AgentState.Error -> WalkState.Engine("Error", null, state.message)
        is AgentState.WaitingForUser -> WalkState.Engine("WaitingForUser", state.step, state.question)
        is AgentState.WaitingForHandoff -> WalkState.Engine("WaitingForHandoff", state.step, state.reason)
        is AgentState.WaitingForApproval -> WalkState.Engine("WaitingForApproval", state.step, null)
    }
}
