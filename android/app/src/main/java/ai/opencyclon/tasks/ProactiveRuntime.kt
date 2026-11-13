package ai.opencyclon.tasks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.opencyclon.R
import ai.opencyclon.accessibility.VdAccessibilityService
import ai.opencyclon.config.ConfigRepository
import ai.opencyclon.config.ModelPhrases
import ai.opencyclon.llm.ModelLists
import ai.opencyclon.db.AppDatabase
import ai.opencyclon.App
import ai.opencyclon.AgentService
import ai.opencyclon.SettingsActivity
import ai.opencyclon.engine.AgentState
import ai.opencyclon.engine.EngineEvent
import ai.opencyclon.engine.LlmPrompts
import ai.opencyclon.engine.TaskEnd
import ai.opencyclon.feed.EvidenceStore
import ai.opencyclon.feed.FeedText
import ai.opencyclon.feed.HomeCommandsImpl
import ai.opencyclon.feed.LiveStateSource
import ai.opencyclon.feed.PreviewSource
import ai.opencyclon.feed.QuestionView
import ai.opencyclon.feed.TaskEventSink
import ai.opencyclon.feed.ThreadRecorder
import ai.opencyclon.feed.ThreadState
import ai.opencyclon.ui.AppEntries
import ai.opencyclon.ui.ControlPanelActivity
import ai.opencyclon.ui.FirstRunActivity
import ai.opencyclon.ui.PrivacyActivity
import ai.opencyclon.ui.RulesActivity
import ai.opencyclon.gate.ApprovalCard
import ai.opencyclon.gate.ApprovalGate
import ai.opencyclon.gate.ApprovalSession
import ai.opencyclon.gate.BlockedApps
import ai.opencyclon.gate.DeviceLock
import ai.opencyclon.gate.HeldAction
import ai.opencyclon.gate.HeldActionJson
import ai.opencyclon.gate.HoldResult
import ai.opencyclon.integrations.IntegrationsActivity
import ai.opencyclon.integrations.ToolRegistry
import ai.opencyclon.llm.LLMClientInterface
import ai.opencyclon.llm.ModelClients
import ai.opencyclon.miniapp.BuildResult
import ai.opencyclon.miniapp.Builder
import ai.opencyclon.miniapp.MiniAppRequests
import ai.opencyclon.miniapp.MiniAppStore
import ai.opencyclon.miniapp.Tester
import ai.opencyclon.proactive.NotificationListener
import ai.opencyclon.proactive.Pipeline
import ai.opencyclon.proactive.Rules
import ai.opencyclon.proactive.Suggestions
import ai.opencyclon.proactive.Summary
import ai.opencyclon.proactive.Triage
import ai.opencyclon.feed.ThreadKind
import ai.opencyclon.miniapp.Bridge
import ai.opencyclon.ui.widgets.WidgetDraftActivity
import ai.opencyclon.widgets.WidgetBridge
import ai.opencyclon.widgets.WidgetFlow
import ai.opencyclon.widgets.WidgetSources
import ai.opencyclon.widgets.WidgetStore
import ai.opencyclon.widgets.WidgetTester
import ai.opencyclon.widgets.WidgetThread
import ai.opencyclon.widgets.Widgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * The single TaskCoordinator, Rules, Triage, Summary, Pipeline, ApprovalSession, RequestRouter,
 * ToolRegistry and app Builder for the process, and the decisions AgentService used to make:
 * which model client, which gate, which tools, and how a task ends.
 */
object ProactiveRuntime {
    private const val TAG = "ProactiveRuntime"
    private const val TICK_MS = 60_000L
    private const val WINDOW_CHANNEL = "notification_window"
    private const val WINDOW_CHANNEL_NAME = "Notification window"
    private const val WINDOW_NOTIFICATION_ID = 50_001
    private const val WINDOW_SUMMARY_ID = 50_002
    private const val WINDOW_ENDED = "Notification window ended"
    private const val WINDOW_TAP_CODE = 50_003
    private const val EXPIRED_FALLBACK_TITLE = "Approval needed"
    private const val PREFS = "proactive_runtime"
    private const val ASK_TIMEOUT_MS = 10 * 60_000L
    const val NO_ANSWER = "No answer to the question"

    private const val NEEDS_SETUP = "I am not connected to the phone's helper yet. Finish the setup first."
    private const val CANNOT_START = "I could not start. Open the app once and try again."
    private const val BUILDING_NEW = "Building your app"
    private const val COULD_NOT_BUILD = "I could not build the app."
    /** Bridge storage id of the widgets' web fetcher; it stores nothing. */
    private const val WEB_BRIDGE_ID = -1L
    private val OPEN_STATES = setOf("proposed", "queued", "running", "paused", "awaiting_approval")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ready = false
    /** True once [init] built the runtime. */
    val isReady: Boolean get() = ready
    private lateinit var app: Context
    private lateinit var db: AppDatabase

    lateinit var session: ApprovalSession; private set
    lateinit var coordinator: TaskCoordinator; private set
    lateinit var rules: Rules; private set
    lateinit var triage: Triage; private set
    lateinit var summary: Summary; private set
    lateinit var pipeline: Pipeline; private set
    lateinit var router: RequestRouter; private set
    lateinit var registry: ToolRegistry; private set
    lateinit var builder: Builder; private set
    private lateinit var miniApps: MiniAppStore
    lateinit var widgets: WidgetFlow; private set
    /** The "Do this?", "Set up" and "Allow" rows (lane X2). */
    lateinit var confirms: HomeConfirms; private set

    /** The one writer of today's thread, the live state, and the engine-event handler (feed contract). */
    lateinit var recorder: ThreadRecorder; private set
    lateinit var liveState: LiveStateSource; private set
    lateinit var previews: PreviewSource; private set
    lateinit var commands: HomeCommandsImpl; private set
    private lateinit var sink: TaskEventSink
    private lateinit var evidence: EvidenceStore

    // Thread and live-state writes run one at a time, in the order they were asked for.
    private val recordQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** Approval holds that the expiry tick ended; their rows show EXPIRED, not REJECTED. */
    private val expiredHolds = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Long, Boolean>())

    /** The app the builder is changing (set when the builder reads its current file), or null for a new app. */
    private val changingApp = AtomicReference<Long?>(null)
    private data class BuildRow(val rowId: Long, val appId: Long?, val name: String?)
    private var buildRow: BuildRow? = null // touched only inside the record queue

    /** The builder's open question; the owner's next chat message answers it. */
    private val pendingAnswer = AtomicReference<CompletableDeferred<String>?>(null)

    /** True while the app builder waits for an answer. */
    fun builderQuestionWaiting(): Boolean = ready && pendingAnswer.get() != null

    private var tickerJob: Job? = null
    private var windowJob: Job? = null

    fun init(context: Context): ProactiveRuntime {
        if (ready) return this
        synchronized(this) {
            if (!ready) {
                build(context.applicationContext)
                ready = true
            }
        }
        return this
    }

    private fun build(context: Context) {
        app = context
        db = AppDatabase.getInstance(context)
        val now: () -> Long = System::currentTimeMillis
        val runner = AgentTaskRunner(context) { taskId, reason -> reportFinished(taskId, "failed", reason) }
        session = ApprovalSession()
        evidence = EvidenceStore(context.filesDir)
        recorder = ThreadRecorder(db.threadItemDao(), now, onPruned = evidence::deleteBefore)
        liveState = LiveStateSource(scope, db.taskDao().observeOccupied(), db.taskDao().observeQueuedCount(),
            db.windowDao().observeLatest(), db.miniAppDao().observeAll(), { db.taskDao().all() }, now)
        sink = TaskEventSink(recorder, liveState, appLabel = { pkg -> pkg?.let { ApprovalCard.appLabel(app, it) } },
            modelSwitchNotice = { ConfigRepository(app).takeModelSwitchNotice() })
        previews = PreviewSource({ taskId, width -> previewFrame(taskId, width) })
        scope.launch { for (job in recordQueue) runRecord(job) }
        // Rows left open by an earlier process: no engine or builder survived it. Queued first, so it runs first.
        record { recorder.expireOpenAfterRestart() }
        coordinator = TaskCoordinator(db.taskDao(), db.windowDao(), db.allowlistDao(), object : TaskRunner {
            override fun start(taskId: Long, goal: String) {
                runner.start(taskId, goal)
                ensureTicker()
            }
            override fun stop(taskId: Long) = runner.stop(taskId)
        }, proposalPresenter(ProposalCard(context) { ensureTicker() }), now, recoverOnFirstUse = true, onFinished = ::onTaskFinished)
        // This is process start and every engine starts through this coordinator, so no engine is alive:
        // close rows a stopped process left in the task slot, and their stale approval cards.
        // Their open approval and question rows close through onTaskFinished, with the restart sentence.
        scope.launch { coordinator.recoverAfterProcessStart().forEach { ApprovalCard.cancel(app, it) } }
        // The window's ongoing notification and its end-of-window summary lived only in the old process.
        scope.launch {
            val window = db.windowDao().latest()
            if (window != null && window.endsAt > now()) showWindow(window.endsAt)
        }
        rules = Rules(db.ruleDao(), ::cancelNotification)
        summary = Summary(db.notificationLogDao())
        triage = Triage(::triageModel, coordinator, db.notificationLogDao(), db.windowDao(), ::cancelNotification, now,
            onTriaged = ::onTriaged)
        pipeline = Pipeline(scope, ::blockedPackages, rules, triage, db.notificationLogDao(), db.windowDao(), now,
            recordQueued = { item -> recordNow { recorder.notification(item.packageName, item.title, item.text) } ?: 0L },
            allowed = { at -> Suggestions(app, db.windowDao()).allowed(at) },
            discardQueued = { item -> record { if (item.threadId > 0) recorder.dropNotification(item.threadId) } })
        registry = ToolRegistry.getInstance(context)
        miniApps = MiniAppStore(context, onCurrentHtmlRead = { changingApp.set(it) })
        val tester = Tester(context)
        builder = Builder(model = ::completeText, ask = ::askOwner, test = tester::test, store = miniApps,
            progress = ::showBuildProgress)
        buildWidgets(context)
        // Widget sentences come before built-app sentences ("make a widget" is not "make an app").
        router = RequestRouter(listOf(::handleWidget, ::handleMiniApp), coordinator, rules, summary, ::installedApps, ::say, now,
            onWindowStarted = ::showWindow, onWindowStopped = ::hideWindow,
            inactiveWindowSentence = context.getString(ai.opencyclon.R.string.x3_notifications_inactive),
            lastSummary = LastSummaryStore.Prefs(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
            onConnectService = ::openIntegrations,
            onRequest = { text -> record { recorder.owner(text) } },
            // The router's sentence "Rule saved: <rule>." is the only row for a saved rule.
            onOpenScreen = ::openScreen,
            onSuggestions = ::changeSuggestions,
            onModelPhrase = { phrase ->
                val config = ConfigRepository(app)
                ModelPhrases.handle(config, phrase, ModelLists(app).cachedFor(config)?.map { it.id }.orEmpty())
            })
        confirms = HomeConfirms(recorder, coordinator, isUnlocked = { DeviceLock.isUnlocked(app) }, say = ::say,
            openSetUp = { main.post { startActivitySafely(Intent(app, FirstRunActivity::class.java)) } },
            openMicrophoneSettings = {
                main.post {
                    startActivitySafely(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", app.packageName, null)))
                }
            },
            widgets = { itemId, yes -> widgets.onConfirm(itemId, yes) })
        commands = HomeCommandsImpl(
            builderQuestionWaiting = ::builderQuestionWaiting,
            answerBuilderQuestion = ::answerPendingQuestion,
            engineQuestionWaiting = { App.agentService?.stateFlow?.value is AgentState.WaitingForUser },
            answerEngine = { text -> main.post { App.agentService?.submitUserResponse(text) } },
            session = { session },
            approveByYes = { text -> handleSpokenYes(app, text) },
            isYes = HomeCommandsImpl::isPlainYes,
            newRequest = ::startRequest,
            recordOwner = { text -> record { recorder.owner(text) } },
            say = ::say,
            isUnlocked = { DeviceLock.isUnlocked(app) },
            cancelTask = { taskId -> scope.launch { coordinator.onTaskFinished(taskId, "cancelled", null) } },
            openLiveView = ::openLive,
            onConfirm = { itemId, yes -> record {
                if (!Suggestions(app).confirm(recorder, itemId, yes)) confirms.confirm(itemId, yes)
            } },
            onNote = ::say,
            onMicrophoneOff = { record { confirms.microphoneOff() } },
        )
        NotificationListener.pipeline = pipeline
        // Integrations come back at process start, before the executor prompt lists their tools.
        scope.launch {
            try {
                registry.restore()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Integration restore failed: ${error.message}")
            }
        }
    }

    fun blockedPackages(): Set<String> = BlockedApps.all(ConfigRepository(app).blacklistedPackages, app.packageName)

    /** If the builder asked a question, [text] is the answer. Returns true when it was used as one. */
    fun answerPendingQuestion(text: String): Boolean {
        if (!ready) return false
        val waiting = pendingAnswer.getAndSet(null) ?: return false
        return waiting.complete(text.trim())
    }

    // ── Built apps ──────────────────────────────────────────────────────

    private suspend fun handleMiniApp(sentence: String): Boolean {
        changingApp.set(null)
        return MiniAppRequests.tryHandle(sentence, builder, miniApps, { goal -> coordinator.submitUserRequest(goal) }, ::say,
            ::onBuildResult)
    }

    private fun onBuildResult(result: BuildResult) {
        val changed = changingApp.getAndSet(null)
        if (result !is BuildResult.Ready) {
            // The failure sentence came through showBuildProgress; only the tile's updating mark is left.
            if (changed != null) record { liveState.setUpdating(changed, false) }
            return
        }
        val text = result.name + if (result.version == 1) Builder.READY else Builder.UPDATED
        record {
            val row = buildRow
            buildRow = null
            row?.appId?.let { liveState.setUpdating(it, false) }
            if (row != null) recorder.progressToResultCard(row.rowId, result.name, text, FeedText.OPEN_APP, result.appId)
            else recorder.result(null, text).also { id ->
                recorder.update(id) { it.copy(title = result.name, detail = FeedText.OPEN_APP, openAppId = result.appId) }
            }
        }
    }

    // Building and testing are one progress row, updated in place; "<name> is ready" or "<name> is updated"
    // comes from onBuildResult, which turns the row into the result card.
    private fun showBuildProgress(text: String) {
        if (text.endsWith(Builder.READY) || text.endsWith(Builder.UPDATED)) return
        val appId = changingApp.get()
        record {
            when {
                text == Builder.BUILDING || text == Builder.TESTING -> {
                    if (buildRow == null) {
                        val name = appId?.let { db.miniAppDao().get(it)?.name }
                        val label = if (name != null) "Updating $name" else BUILDING_NEW
                        appId?.let { liveState.setUpdating(it, true) }
                        buildRow = BuildRow(recorder.progress(label, FeedText.BUILD_ETA, name, appId), appId, name)
                    }
                }
                text.startsWith(Builder.COULD_NOT_BUILD) || text.startsWith(Builder.COULD_NOT_UPDATE) -> {
                    val row = buildRow
                    buildRow = null
                    val sentence = row?.name?.let { "I could not update $it." } ?: COULD_NOT_BUILD
                    row?.appId?.let { liveState.setUpdating(it, false) }
                    if (row != null) recorder.progressToSentence(row.rowId, sentence) else recorder.sentence(sentence)
                }
                else -> recorder.sentence(text)
            }
        }
    }

    private suspend fun askOwner(question: String): String {
        val answer = CompletableDeferred<String>()
        pendingAnswer.getAndSet(answer)?.cancel()
        BuilderQuestionCard.post(app, FeedText.asSentence(question))
        val row = recordNow {
            liveState.setQuestion(QuestionView(null, FeedText.asSentence(question)))
            recorder.question(question, null)
        }
        var answered = false
        try {
            val text = withTimeoutOrNull(ASK_TIMEOUT_MS) { answer.await() } ?: throw IllegalStateException(NO_ANSWER)
            answered = true
            return text
        } finally {
            pendingAnswer.compareAndSet(answer, null)
            BuilderQuestionCard.cancel(app)
            record {
                liveState.clearQuestion(null)
                row?.let { recorder.resolve(it, if (answered) ThreadState.ANSWERED else ThreadState.FAILED) }
            }
        }
    }

    // ── Widgets (F16) ────────────────────────────────────────────────────

    private fun buildWidgets(context: Context) {
        val store = WidgetStore(context, db)
        val web = Bridge(context, WEB_BRIDGE_ID, deliver = {}, allowNotify = false)
        val sources = WidgetSources(
            todayRows = { recorder.today().first() },
            running = { liveState.state.value.running.firstOrNull() },
            tasks = { db.taskDao().all() },
            apps = { db.miniAppDao().all() },
            appData = { id ->
                context.getSharedPreferences("${Bridge.PREFS_PREFIX}$id", Context.MODE_PRIVATE).all
                    .mapValues { it.value?.toString() }
            },
            tools = { registry.tools() },
            isReadOnly = { server, tool -> registry.isReadOnly(server, tool) },
            callTool = { server, tool, args -> registry.call(server, tool, args) },
            fetch = { url -> withContext(Dispatchers.IO) { web.getText(url) } },
            appLabel = { pkg -> ApprovalCard.appLabel(app, pkg) },
        )
        val tester = WidgetTester(context)
        val thread = object : WidgetThread {
            override suspend fun confirm(text: String, actionWord: String): Long {
                BuilderQuestionCard.post(app, text)
                return recordNow { recorder.confirm(text, actionWord) } ?: 0L
            }
            override fun resolveConfirm(id: Long, yes: Boolean) {
                BuilderQuestionCard.cancel(app)
                record { recorder.resolve(id, if (yes) ThreadState.ANSWERED else ThreadState.REJECTED) }
            }
            override suspend fun progress(text: String): Long = recordNow { recorder.progress(text, null, null, null) } ?: 0L
            override fun progressToSentence(id: Long, text: String) = record { recorder.progressToSentence(id, text) }
            override fun progressToDraftCard(id: Long, name: String) = record {
                recorder.update(id) {
                    it.copy(kind = ThreadKind.RESULT, state = ThreadState.DONE, title = name, text = WidgetFlow.DRAFT_READY,
                        detail = WidgetFlow.TAP_TO_SEE, actionWord = WidgetFlow.DRAFT_CARD, openAppId = null)
                }
            }
            override fun say(text: String) = this@ProactiveRuntime.say(text)
        }
        widgets = WidgetFlow(::completeText, tester::test, store, sources, thread, scope,
            openDraftPage = { main.post { startActivitySafely(Intent(app, WidgetDraftActivity::class.java)) } },
            log = WidgetBridge.logger(context), ownerText = { context.getString(it) })
        Widgets.install(store, sources, widgets)
        // No draft record survived an earlier process.
        scope.launch { store.deleteAllDrafts() }
    }

    private suspend fun handleWidget(sentence: String): Boolean = widgets.handle(sentence)

    // ── Own screens (plan decision 9) ───────────────────────────────────

    private fun openScreen(screen: OwnScreen) {
        val intent = when (screen) {
            OwnScreen.SETTINGS -> Intent(app, SettingsActivity::class.java)
            OwnScreen.HISTORY -> ControlPanelActivity.historyIntent(app)
            OwnScreen.RULES -> Intent(app, RulesActivity::class.java)
            OwnScreen.PRIVACY -> Intent(app, PrivacyActivity::class.java)
            OwnScreen.INTEGRATIONS -> Intent(app, IntegrationsActivity::class.java)
            OwnScreen.SET_UP -> Intent(app, FirstRunActivity::class.java)
        }
        main.post {
            runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w(TAG, "Cannot open ${screen.title}", it) }
        }
    }

    // ── Integrations ────────────────────────────────────────────────────

    private fun openIntegrations(name: String, address: String) {
        main.post {
            app.startActivity(Intent(app, IntegrationsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(IntegrationsActivity.EXTRA_NAME, name)
                .putExtra(IntegrationsActivity.EXTRA_ADDRESS, address))
        }
    }

    /** The executor's tool caller: tool text on success, "Tool error <code>" on failure. */
    suspend fun callTool(server: String, tool: String, argumentsJson: String): Pair<Boolean, String> =
        ToolRegistry.outcome(registry.call(server, tool, argumentsJson))

    /** The connected-tools section of the executor prompt; empty when no tool is connected. */
    fun executorPromptExtra(): String = LlmPrompts.toolsSection(registry.tools())

    /** The gate for one task on the hidden display. Tool calls are held unless the server marks the tool read-only. */
    fun approvalGate(displayId: Int, toPixels: (x: Int, y: Int) -> Pair<Int, Int>): ApprovalGate = ApprovalGate(
        reader = VdAccessibilityService.screenReader,
        blockedPackages = ::blockedPackages,
        displayId = { displayId },
        isReadOnlyTool = registry::isReadOnly,
        toPixels = toPixels
    )

    private fun cancelNotification(key: String) {
        NotificationListener.instance?.cancelNotification(key)
    }

    /** Launcher label to package name. */
    private fun installedApps(): Map<String, String> {
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).associate {
            it.loadLabel(pm).toString() to it.activityInfo.packageName
        }
    }

    /** A sentence of the phone: stored in the thread (nothing is lost when no screen is open). */
    private fun say(text: String) {
        record { recorder.sentence(text) }
    }

    /** The owner's words typed on a screen that is not the home page (the old edit screen, the live view). */
    fun recordOwnerWords(text: String) {
        if (!ready || text.isBlank()) return
        record { recorder.owner(text) }
    }

    // ── Proposals ───────────────────────────────────────────────────────

    /** The notification's "Do this" (or a tap on its body): the same call as the home page's "Do it". */
    suspend fun acceptProposal(taskId: Long): Boolean = confirms.accept(taskId)

    /** The notification's "Dismiss": the same as the home page's "Not now". */
    suspend fun declineProposal(taskId: Long) = confirms.decline(taskId)

    /** The card for the notification, and a CONFIRM row under the notification card on the home page. */
    private fun proposalPresenter(card: ProposalCard) = object : ProposalPresenter {
        // Called inside the coordinator lock: the row is only queued.
        override fun showDoThis(taskId: Long, goal: String, sourceLabel: String) {
            card.showDoThis(taskId, goal, sourceLabel)
            record { confirms.proposalShown(taskId, goal) }
        }
        override fun dismiss(taskId: Long) = card.dismiss(taskId)
    }

    // ── Thread and live state ───────────────────────────────────────────

    /** Queues one write of the thread or live state; writes run one at a time in this order. */
    private fun record(block: suspend () -> Unit) {
        recordQueue.trySend(block)
    }

    /** Queues one write and waits for its result (null when it failed). */
    private suspend fun <T> recordNow(block: suspend () -> T): T? {
        val done = CompletableDeferred<T?>()
        record {
            try {
                done.complete(block())
            } finally {
                done.complete(null)
            }
        }
        return done.await()
    }

    private suspend fun runRecord(job: suspend () -> Unit) {
        try {
            job()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Thread write failed: ${error.message}")
        }
    }

    /** An engine event of task [taskId], from the service that runs the engine. */
    fun onEngineEvent(taskId: Long, event: EngineEvent) {
        if (!ready) return
        record { sink.onEvent(taskId, event) }
    }

    fun onEngineState(taskId: Long, state: AgentState) {
        if (!ready) return
        record { liveState.onEngineState(taskId, state) }
    }

    // Called inside the coordinator lock: queue only.
    private fun onTaskFinished(before: TaskEntity, state: String, result: String?) {
        val expired = state == "cancelled" && result == null && before.state == "awaiting_approval" &&
            before.updatedAt <= System.currentTimeMillis() - TaskCoordinator.EXPIRY_MS
        if (expired) expiredHolds += before.id
        record { sink.onTaskEnded(before.id, before.state, state, result, expired) }
    }

    private fun onTriaged(item: ai.opencyclon.proactive.Incoming, result: String, taskId: Long?, sentence: String?) {
        record {
            if (item.threadId > 0) {
                if (result == "ignore" || result == "hide") {
                    recorder.dropNotification(item.threadId)
                    ai.opencyclon.proactive.NotificationIntents.remove(item.threadId)
                }
                else recorder.notificationTriaged(item.threadId, taskId)
            }
            if (result == "proposed" && taskId != null) sink.sayWhenStarted(taskId, sentence ?: FeedText.triageFallback(item.title))
        }
    }

    private fun changeSuggestions(enable: Boolean) {
        if (!enable) Suggestions(app).enabled = false
        record { Suggestions(app).request(app, recorder, enable) }
    }

    /**
     * A new request from the home page: the checks the old chat screen made before it started a task, then the
     * request router (AgentService.startAgent does the same). [ownerRecorded] is true when the row exists already.
     */
    private fun startRequest(text: String, ownerRecorded: Boolean) {
        // These local controls must work even before model or helper setup.
        if (Suggestions.phrase(RequestRouter.clean(text)) != null) {
            scope.launch { router.route(text, recordOwner = !ownerRecorded) }
            return
        }
        // ponytail: the debug walk's unconfigured provider has no credential source; release ignores it.
        val debuggable = (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if ((debuggable && ConfigRepository(app).apiProvider == "walk-unconfigured") ||
            AppEntries.needsFirstRun(ConfigRepository(app))) {
            // F1.4: a sentence and a "Set up" row; no task, no screen opens by itself.
            if (!ownerRecorded) record { recorder.owner(text) }
            record { confirms.noModel() }
            return
        }
        val service = App.agentService
        if (service == null) {
            // The coordinator starts the service for a task; starting it now also starts backend detection.
            try {
                app.startForegroundService(Intent(app, AgentService::class.java))
            } catch (error: Exception) {
                Log.w(TAG, "Cannot start AgentService: ${error.message}")
            }
        } else if (service.backendDetected && service.activeBackendName.let { it == null || it == "none" }) {
            if (!ownerRecorded) record { recorder.owner(text) }
            say(NEEDS_SETUP)
            main.post { startActivitySafely(Intent(app, ai.opencyclon.backend.SetupActivity::class.java)) }
            return
        }
        // ponytail: the old chat also asked for the overlay permission here; the home page does not need the overlay.
        scope.launch { router.route(text, recordOwner = !ownerRecorded) }
    }

    private fun startActivitySafely(intent: Intent) {
        try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) {
            Log.w(TAG, "Cannot open ${intent.component?.shortClassName}: ${error.message}")
            say(CANNOT_START)
        }
    }

    private fun openLive(context: Context, taskId: Long) {
        val service = App.agentService
        if (service != null && service.getVirtualDisplayManager()?.isCreated == true) {
            service.enterViewMode()
            return
        }
        val intent = Intent(context, ai.opencyclon.ui.ViewActivity::class.java)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (error: Exception) {
            Log.w(TAG, "Cannot open the live view for task $taskId: ${error.message}")
        }
    }

    /** A preview of task [taskId]'s hidden display, only while it is the running task; never the main screen. */
    private suspend fun previewFrame(taskId: Long, widthPx: Int): Bitmap? {
        if (liveState.state.value.running.firstOrNull()?.taskId != taskId) return null
        return App.agentService?.previewFrame(widthPx, PreviewSource.QUALITY)
    }

    // ── Model calls ─────────────────────────────────────────────────────

    /** The client for the saved provider; the executor, triage and the builder use the same endpoint and key. */
    fun modelClient(): LLMClientInterface = ModelClients.forConfig(ConfigRepository(app))

    private suspend fun completeText(prompt: String): String {
        val client = modelClient()
        try {
            return client.completeText(prompt)
        } finally {
            client.shutdown()
        }
    }

    private suspend fun triageModel(prompt: String): String = Triage.jsonArrayIn(completeText("${Triage.FRAME}\n\n$prompt"))

    // ── Held actions ────────────────────────────────────────────────────

    /** The engine's onHold: record, show the card, wait for the owner. */
    suspend fun handleHold(taskId: Long, held: HeldAction, captureFrame: suspend () -> Bitmap?, displaySize: Point?): HoldResult {
        coordinator.onAwaitingApproval(taskId, HeldActionJson.toJson(held), HeldActionJson.factsToJson(held.facts))
        ensureTicker()
        val frame = try {
            captureFrame()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Frame capture for approval card failed: ${error.message}")
            null
        }
        val framePath = frame?.let {
            withContext(Dispatchers.IO) { evidence.saveHold(taskId, it, held, displaySize) }
        }
        val result = try {
            ApprovalCard.post(app, taskId, held, frame, displaySize)
            record { sink.onHold(taskId, held, framePath) }
            session.awaitResult(taskId, held)
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Approval already pending, rejecting task $taskId: ${error.message}")
            HoldResult.Reject
        } finally {
            ApprovalCard.cancel(app, taskId)
        }
        val expired = expiredHolds.remove(taskId)
        record { sink.onHoldResolved(taskId, result, expired) }
        // Approved or edited: back to running inside the coordinator lock, so it is not expired later.
        if (result != HoldResult.Reject) coordinator.onApprovalResolved(taskId)
        return result
    }

    /** Spoken "yes" while a card is showing and the phone is unlocked runs the Approve path. */
    fun handleSpokenYes(context: Context, text: String): Boolean {
        if (!ready || !HomeCommandsImpl.isPlainYes(text)) return false
        val pending = session.pending() ?: return false
        if (pending.held.facts.title == null) return false
        if (!DeviceLock.isUnlocked(context)) return false
        return session.approve(pending.taskId)
    }

    // ── Task end ────────────────────────────────────────────────────────

    /**
     * Reports the engine's end for this task to the coordinator, once. The end is a value the engine
     * keeps, so it is reported even when no Running state was ever observed.
     */
    fun watchTask(taskId: Long, end: StateFlow<TaskEnd?>) {
        scope.launch {
            val (state, result) = TaskEnd.toTaskState(end.filterNotNull().first())
            coordinator.onTaskFinished(taskId, state, result)
        }
    }

    fun reportFinished(taskId: Long, state: String, result: String?) {
        scope.launch { coordinator.onTaskFinished(taskId, state, result) }
    }

    // ── Expiry ──────────────────────────────────────────────────────────

    @Synchronized
    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                try {
                    tick()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Expiry tick failed: ${error.message}")
                }
                if (db.taskDao().all().none { it.state in OPEN_STATES }) break
            }
        }
    }

    private suspend fun tick() {
        val cutoff = System.currentTimeMillis() - TaskCoordinator.EXPIRY_MS
        val expiring = db.taskDao().all().filter { it.state == "awaiting_approval" && it.updatedAt <= cutoff }.map { it.id }
        val pending = session.pending()
        coordinator.expireOld()
        for (id in expiring) {
            if (db.taskDao().get(id)?.state != "cancelled") continue
            expiredHolds += id
            val title = pending?.takeIf { it.taskId == id }?.held
                ?.let { ApprovalCard.title(it, ApprovalCard.appLabel(app, it.facts.topPackage)) }
                ?: EXPIRED_FALLBACK_TITLE
            session.expire(id)
            ApprovalCard.cancel(app, id)
            ApprovalCard.postExpired(app, id, title)
        }
    }

    // ── Notification window ─────────────────────────────────────────────

    private fun showWindow(endsAt: Long) {
        val manager = app.getSystemService(NotificationManager::class.java)
        ensureWindowChannel(manager)
        manager.notify(WINDOW_NOTIFICATION_ID, Notification.Builder(app, WINDOW_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Handling notifications until ${RequestRouter.formatTime(endsAt)}")
            .setContentIntent(homeTap())
            .setOngoing(true)
            .build())
        synchronized(this) {
            windowJob?.cancel()
            windowJob = scope.launch {
                delay((endsAt - System.currentTimeMillis()).coerceAtLeast(0))
                manager.cancel(WINDOW_NOTIFICATION_ID)
                val window = db.windowDao().latest() ?: return@launch
                val text = RequestRouter.windowSummaryText(WindowSummary(window.handled, window.hidden, window.needsYou))
                manager.notify(WINDOW_SUMMARY_ID, Notification.Builder(app, WINDOW_CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(WINDOW_ENDED)
                    .setContentText(text.replace('\n', ' '))
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setContentIntent(homeTap())
                    .setAutoCancel(true)
                    .build())
                // One multi-line sentence: the sentence, then "Handled 2.", "Hidden 1.", "Needs you 0.".
                say("$WINDOW_ENDED.\n$text")
            }
        }
    }

    private fun hideWindow() {
        synchronized(this) {
            windowJob?.cancel()
            windowJob = null
        }
        app.getSystemService(NotificationManager::class.java).cancel(WINDOW_NOTIFICATION_ID)
    }

    /** A tap on a notification-window notification opens the home page. */
    private fun homeTap(): android.app.PendingIntent = android.app.PendingIntent.getActivity(app, WINDOW_TAP_CODE,
        ControlPanelActivity.homeIntent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

    private fun ensureWindowChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(WINDOW_CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(WINDOW_CHANNEL, WINDOW_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW))
    }
}
