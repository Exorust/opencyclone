package ai.opencyclon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import ai.opencyclon.action.ActionExecutor
import ai.opencyclon.accessibility.VdAccessibilityService
import ai.opencyclon.backend.BackendDetector
import ai.opencyclon.backend.ConnectionState
import ai.opencyclon.backend.DetectionResult
import ai.opencyclon.backend.PrivilegeBackend
import ai.opencyclon.backend.RemoteBackend
import ai.opencyclon.backend.SetupStateDetector
import ai.opencyclon.backend.SystemBackend
import ai.opencyclon.capture.ScreenCapture
import ai.opencyclon.config.ConfigRepository
import ai.opencyclon.db.ChatHistoryRepository
import ai.opencyclon.db.GlobalMemoryRepository
import ai.opencyclon.display.AgentAppCloser
import ai.opencyclon.display.TaskDisplayGuard
import ai.opencyclon.display.TaskSnapshot
import ai.opencyclon.display.VirtualDisplayManager
import ai.opencyclon.engine.AgentEngine
import ai.opencyclon.engine.AgentState
import ai.opencyclon.engine.ConnectionLoss
import ai.opencyclon.engine.HandoffUiState
import ai.opencyclon.engine.HandoffVisualDetector
import ai.opencyclon.engine.StepResult
import ai.opencyclon.engine.TaskEnd
import ai.opencyclon.feed.FeedText
import ai.opencyclon.gate.ApprovalCard
import ai.opencyclon.input.CoordinateMapper
import ai.opencyclon.tasks.AgentTaskRunner
import ai.opencyclon.tasks.ProactiveRuntime
import ai.opencyclon.llm.LLMClientInterface
import ai.opencyclon.ui.ControlPanelActivity
import ai.opencyclon.ui.MessageType
import ai.opencyclon.ui.start.StartPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Foreground service that hosts the AgentEngine.
 * Manages display state transitions (CHAT/VIEW/TAKEOVER) via task reparenting.
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "opencyclon_agent"
        private const val CHANNEL_ASK = "opencyclon_ask_user"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_ASK_ID = 1002
        private const val NOTIFICATION_COMPLETED_ID = 1003
        private const val CHANNEL_CONNECTION = "connection"
        private const val NOTIFICATION_CONNECTION_ID = 1004
        private const val HANDOFF_TIMEOUT_MS = 30_000L
        private const val HANDOFF_COUNTDOWN_SECONDS = 10
        private const val HANDOFF_SAMPLE_INTERVAL_MS = 1_000L
        const val EXTRA_SHOW_HANDOFF = "show_handoff"

        // Wake lock while a task runs. SCREEN keeps the present behaviour: the main screen turns on and stays
        // bright for up to 2 hours. PARTIAL keeps only the CPU awake, to test on the phone whether the hidden
        // display still renders behind a dark screen. Switch with the debug receiver (cmd wake_lock, extra mode)
        // or by writing the preference below; it applies to the next task.
        const val WAKE_LOCK_PREFS = "agent_wake_lock"
        const val KEY_WAKE_LOCK_MODE = "mode"
        const val WAKE_LOCK_SCREEN = "screen"
        const val WAKE_LOCK_PARTIAL = "partial"
        const val DEFAULT_WAKE_LOCK_MODE = WAKE_LOCK_SCREEN
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        fun wakeLockMode(context: Context): String =
            context.getSharedPreferences(WAKE_LOCK_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_WAKE_LOCK_MODE, DEFAULT_WAKE_LOCK_MODE) ?: DEFAULT_WAKE_LOCK_MODE

        fun setWakeLockMode(context: Context, mode: String) {
            require(mode == WAKE_LOCK_SCREEN || mode == WAKE_LOCK_PARTIAL) { "mode must be screen or partial" }
            context.getSharedPreferences(WAKE_LOCK_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WAKE_LOCK_MODE, mode).apply()
        }

        const val WORKING_PREFIX = "Working: "
        const val MAX_GOAL = 80
        const val NOT_CONNECTED_NOTICE = "Not connected \u00B7 Tap to reconnect"

        /** The foreground notification while a task runs: "Working: <goal>" on one line. */
        fun workingText(goal: String): String {
            val line = goal.replace(Regex("\\s+"), " ").trim()
            return WORKING_PREFIX + if (line.length <= MAX_GOAL) line else line.take(MAX_GOAL - 1).trimEnd() + "\u2026"
        }

        /** The foreground notification while no task runs. */
        fun readyText(appName: String): String = "$appName is ready"

        /** The text of the completion notification for [end], or null when the owner ended the task himself. */
        fun completionText(end: TaskEnd): String? = when (end) {
            is TaskEnd.Done -> FeedText.firstSentence(end.message)
            is TaskEnd.Failed -> FeedText.failure(end.message)
            is TaskEnd.Cancelled, TaskEnd.Rejected -> null
        }
    }

    // ── Display state management ────────────────────────────────────────

    enum class DisplayState { CHAT, VIEW, TAKEOVER }

    var displayState = DisplayState.CHAT
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: AgentEngine? = null

    /**
     * Multi-subscriber flow that emits whenever the active AgentEngine changes
     * (or is cleared). Replaces the previous single-listener `onEngineCreated`
     * var, which silently overwrote prior listeners when several screens registered against it.
     */
    private val _engineFlow = MutableStateFlow<AgentEngine?>(null)
    val engineFlow: StateFlow<AgentEngine?> = _engineFlow.asStateFlow()
    private val _handoffUiState = MutableStateFlow<HandoffUiState>(HandoffUiState.Idle)
    val handoffUiState: StateFlow<HandoffUiState> = _handoffUiState.asStateFlow()
    private var virtualDisplayManager: VirtualDisplayManager? = null
    private var taskDisplayGuard: TaskDisplayGuard? = null
    private var taskRescueInProgress = false
    private var agentWakeLock: PowerManager.WakeLock? = null
    private lateinit var config: ConfigRepository
    private lateinit var historyRepo: ChatHistoryRepository
    private lateinit var memoryRepo: GlobalMemoryRepository
    var currentConversationId: Long? = null
        private set
    var currentInstruction: String = ""
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backendDeferred = CompletableDeferred<PrivilegeBackend>()
    private var backend: PrivilegeBackend? = null

    /** True once the first backend detection finished, so a null backend means "not set up" and not "still looking". */
    val backendDetected: Boolean get() = backendDeferred.isCompleted
    private var engineEventsJob: Job? = null
    private var engineStateTapJob: Job? = null

    /** The name of the currently active backend (e.g. "system", "shizuku", "adb-direct"), or null. */
    val activeBackendName: String?
        get() = backend?.capabilities?.name

    /**
     * Disconnect the privilege backend ("Disconnect" in the settings). Nothing reconnects by itself until the owner
     * connects again from the pairing screen (AgentConnection.ownerDisconnected).
     */
    fun revokeBackend() {
        AgentConnection.setOwnerDisconnected(this, true)
        disconnectObserverJob?.cancel()
        backend?.destroy()
        backend = null
        ScreenCapture.backend = SystemBackend()
        onConnection(false)
        engine?.takeIf { it.isRunning }?.fail(FeedText.CONNECTION_LOST)
        Log.i(TAG, "Backend revoked by user")
    }

    /** Update the backend after pairing/reconnection (called by AdbPairingService). */
    fun updateBackend(newBackend: PrivilegeBackend) {
        val previousBackend = backend
        backend = newBackend
        ScreenCapture.backend = newBackend
        observeConnectorState(newBackend)
        if (previousBackend !== newBackend) {
            previousBackend?.destroy()
        }
        // Only the pairing screen calls this: the owner connected.
        AgentConnection.setOwnerDisconnected(this, false)
        onConnection(true)
        Log.i(TAG, "Backend updated: ${newBackend.capabilities.name}")
    }
    private var historyObserverJob: Job? = null
    private var lastHistoryStatus: String? = null
    private var handoffObserverJob: Job? = null
    private var handoffMonitorJob: Job? = null
    private var activeHandoffStep: Int? = null
    private var activeHandoffReason: String = ""
    private var disconnectTimestamp: Long? = null
    private var disconnectObserverJob: Job? = null
    private var startJob: Job? = null
    private var endObserverJob: Job? = null
    /** True while a privileged backend is set; a task waits for it before it starts. */
    private val backendPresent = MutableStateFlow(false)
    private val connectionLoss = ConnectionLoss(scope) { failForLostConnection() }

    inner class AgentBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    private val binder = AgentBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        App.agentService = this
        config = ConfigRepository(this)
        // Auto-apply active profile to ensure config values match saved profile
        val activeProfile = config.activeProfileName
        if (activeProfile.isNotEmpty()) {
            val profileRepo = ai.opencyclon.config.ProfileRepository(config, this)
            profileRepo.switchTo(activeProfile)
        }
        historyRepo = ChatHistoryRepository(this)
        memoryRepo = GlobalMemoryRepository(this)
        createNotificationChannel()
        ensureAccessibilityServiceEnabled()
        // Coordinator, gate parts, integrations (restored here at process start), app builder.
        ProactiveRuntime.init(this)
        // Mark stale "running" conversations as stopped — agent can't survive process restart
        scope.launch {
            historyRepo.markStaleRunningAsStopped()
        }
        // Detect privilege backend (async for Shizuku, instant for system app)
        scope.launch {
            if (AgentConnection.ownerDisconnected(this@AgentService)) {
                // The owner disconnected in the settings: no automatic connection until he connects again.
                backendDeferred.complete(SystemBackend())
                onConnection(false)
                Log.i(TAG, "Backend detection skipped: disconnected by the owner")
                return@launch
            }
            when (val result = BackendDetector.detect(this@AgentService)) {
                is DetectionResult.Ready -> {
                    backend = result.backend
                    ScreenCapture.backend = result.backend
                    backendDeferred.complete(result.backend)
                    observeConnectorState(result.backend)
                    onConnection(true)
                    Log.i(TAG, "Backend ready: ${result.backend.capabilities.name}")
                }
                is DetectionResult.SetupRequired -> {
                    // Don't fall back to SystemBackend on standard flavor — it won't work
                    backendDeferred.complete(SystemBackend()) // unblock startAgent (will fail gracefully)
                    onConnection(false)
                    Log.w(TAG, "Backend setup required — user needs to complete setup wizard")
                }
                is DetectionResult.NoneAvailable -> {
                    backendDeferred.complete(SystemBackend()) // unblock startAgent
                    onConnection(false)
                    Log.w(TAG, "No backend available — wireless debugging not enabled?")
                }
            }
        }
        Log.i(TAG, "AgentService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(foregroundText(), working = isTaskRunning()))

        if (intent?.action == AgentTaskRunner.ACTION_RUN_TASK) {
            val goal = intent.getStringExtra(AgentTaskRunner.EXTRA_GOAL) ?: return START_STICKY
            runAgentNow(intent.getLongExtra(AgentTaskRunner.EXTRA_TASK_ID, -1L), goal)
            return START_STICKY
        }

        // Support starting agent via intent extra (e.g. from adb)
        val instruction = intent?.getStringExtra("instruction")
        if (!instruction.isNullOrBlank()) {
            Log.i(TAG, "Starting agent from intent: $instruction")
            startAgent(instruction)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        App.agentService = null
        taskDisplayGuard?.stop()
        taskDisplayGuard = null
        handoffObserverJob?.cancel()
        handoffMonitorJob?.cancel()
        disconnectObserverJob?.cancel()
        startJob?.cancel()
        endObserverJob?.cancel()
        connectionLoss.restored()
        engineEventsJob?.cancel()
        engineStateTapJob?.cancel()
        releaseAgentWakeLock()
        engine?.destroy()
        engine = null
        _engineFlow.value = null
        virtualDisplayManager?.destroy()
        virtualDisplayManager = null
        backend?.destroy()
        backend = null
        Log.i(TAG, "AgentService destroyed")
        super.onDestroy()
    }

    private fun observeConnectorState(b: PrivilegeBackend) {
        disconnectObserverJob?.cancel()
        if (b !is RemoteBackend) return

        disconnectObserverJob = scope.launch {
            b.connector.state.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected -> {
                        if (isTaskRunning()) {
                            Log.w(TAG, "Backend disconnected during active task, pausing")
                            if (engine?.state?.value is AgentState.Running) engine?.pause()
                            disconnectTimestamp = System.currentTimeMillis()
                            // Ends the task after 60 s without a connection, or when the attempts below fail.
                            connectionLoss.dropped()
                        } else {
                            Log.w(TAG, "Backend disconnected while idle")
                        }

                        // Shizuku is independent of wireless debugging. Only the direct
                        // ADB connector should use adb_wifi_enabled as a retry condition.
                        val wirelessOn = SetupStateDetector.isWirelessDebuggingEnabled(this@AgentService)
                        val canRetry = (b.connector.name == "shizuku" || wirelessOn) &&
                            !AgentConnection.ownerDisconnected(this@AgentService)
                        if (canRetry) {
                            Log.i(TAG, "Attempting ${b.connector.name} backend reconnect...")
                            var reconnected = false
                            for (attempt in 1..5) {
                                delay(3000L * attempt)
                                if (retryBackendDetection()) {
                                    Log.i(TAG, "Auto-reconnect succeeded on attempt $attempt")
                                    reconnected = true
                                    break
                                }
                            }
                            if (!reconnected) {
                                Log.w(TAG, "Auto-reconnect failed after 5 attempts")
                                clearBackend()
                                connectionLoss.reconnectFailed()
                            }
                        } else {
                            Log.i(TAG, "Wireless debugging is off for ADB backend, clearing backend")
                            clearBackend()
                            connectionLoss.reconnectFailed()
                        }
                    }
                    is ConnectionState.Connected -> {
                        connectionLoss.restored()
                        onConnection(true)
                        val wasDisconnected = disconnectTimestamp != null
                        val isEnginePaused = engine?.state?.value is AgentState.Paused
                        if (wasDisconnected && isEnginePaused) {
                            val elapsed = System.currentTimeMillis() - disconnectTimestamp!!
                            disconnectTimestamp = null

                            if (elapsed > 10_000) {
                                Log.i(TAG, "Long disconnect (${elapsed}ms), resuming with fresh state")
                            }
                            engine?.resume()
                            refreshNotification()
                            Log.i(TAG, "Backend reconnected after ${elapsed}ms, resumed agent")
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /** The connection is gone: no backend until a reconnect or the pairing screen sets one. */
    private fun clearBackend() {
        backend = null
        ScreenCapture.backend = SystemBackend()
        onConnection(false)
    }

    /** True while an engine run has not ended. */
    private fun isTaskRunning(): Boolean = engine?.let { it.isRunning && it.end.value == null } == true

    /**
     * The connection did not come back in time (ConnectionLoss): the running task ends as failed through the normal
     * end path, so the coordinator frees the slot and the thread gets the sentence. Queued tasks wait in
     * [runAgentNow] until a backend is set again.
     */
    private fun failForLostConnection() {
        Log.w(TAG, "Connection did not come back, ending the task")
        disconnectTimestamp = null
        if (backend is RemoteBackend) clearBackend() else onConnection(false)
        val e = engine
        if (e != null && e.isRunning) e.fail(FeedText.CONNECTION_LOST)
        postReconnectNotification()
    }

    /**
     * Every change of the connection comes here: the home page's "Not connected" sentence (only after the start is
     * done), the "Tap to reconnect" notification, and the start of a task that waits for a backend.
     */
    private fun onConnection(connected: Boolean) {
        backendPresent.value = connected
        val startDone = StartPrefs(this).done
        AgentConnection.setNotConnected(!connected && startDone)
        if (connected) {
            cancelReconnectNotification()
        } else if (startDone && !AgentConnection.ownerDisconnected(this)) {
            postReconnectNotification()
        }
    }

    private fun postReconnectNotification() {
        val intent = Intent(this, ai.opencyclon.backend.SetupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, 4, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif = Notification.Builder(this, CHANNEL_CONNECTION)
            .setContentTitle(NOT_CONNECTED_NOTICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_CONNECTION_ID, notif)
    }

    private fun cancelReconnectNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_CONNECTION_ID)
    }

    private fun ensureAccessibilityServiceEnabled() {
        try {
            val componentName = VdAccessibilityService.componentName(packageName)
            val existing = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (componentName in existing) return

            val updated = if (existing.isBlank()) componentName else "$existing:$componentName"
            android.provider.Settings.Secure.putString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated
            )
            android.provider.Settings.Secure.putInt(
                contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1
            )
            Log.i(TAG, "Auto-enabled VdAccessibilityService")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to auto-enable accessibility service (requires system app)", e)
        }
    }

    // ── Agent lifecycle ─────────────────────────────────────────────────

    /**
     * Every caller's entry point: the request router decides whether this is a built-in
     * command or a task for the coordinator, which calls [runAgentNow] when the slot is free.
     */
    fun startAgent(instruction: String) {
        val runtime = ProactiveRuntime.init(this)
        scope.launch { runtime.router.route(instruction) }
    }

    /**
     * Create and start the agent with the given instruction.
     */
    fun runAgentNow(taskId: Long, instruction: String) {
        // If in VIEW/TAKEOVER, return to CHAT first
        if (displayState != DisplayState.CHAT) {
            returnToChat()
        }

        lastHistoryStatus = null
        cancelCompletionNotification()
        // Create or re-create engine with current config. The end observer goes first, so the old engine's
        // end does not touch the new task.
        startJob?.cancel()
        endObserverJob?.cancel()
        connectionLoss.restored()
        engine?.destroy()
        handoffObserverJob?.cancel()
        handoffMonitorJob?.cancel()
        taskDisplayGuard?.stop()
        taskDisplayGuard = null
        taskRescueInProgress = false
        activeHandoffStep = null
        _handoffUiState.value = HandoffUiState.Idle
        releaseAgentWakeLock()

        Log.i(TAG, "startAgent config: provider=${config.apiProvider} model=${config.model} baseUrl=${config.baseUrl}")
        val runtime = ProactiveRuntime.init(this)
        val llmClient: LLMClientInterface = runtime.modelClient()

        // Await backend detection, then create VDM + engine in coroutine
        startJob = scope.launch {
            backendDeferred.await()
            if (backend == null) {
                // No connection: the task keeps its place and starts when a backend is set again
                // (reconnect or the pairing screen). The home page says "Not connected".
                // ponytail: the row reads "running" while it waits; the coordinator has no state for waiting
                // on the connection. Stop in the running panel ends it.
                Log.i(TAG, "Task $taskId waits for the connection")
                backendPresent.first { it }
            }
            val b = backend ?: backendDeferred.await()

            // Always use VirtualDisplay — reuse existing or create new
            var vdm = virtualDisplayManager
            var displayId: Int
            var displaySize: Point?

            if (vdm != null && vdm.isCreated) {
                displayId = vdm.displayId
                displaySize = Point(vdm.width, vdm.height)
                Log.i(TAG, "Reusing existing virtual display $displayId")
            } else {
                vdm?.destroy()
                vdm = VirtualDisplayManager(this@AgentService, b)
                // Use physical screen resolution so VD matches the real display
                val dm = resources.displayMetrics
                val id = vdm.create(width = dm.widthPixels, height = dm.heightPixels, dpi = dm.densityDpi)
                if (id != -1) {
                    displayId = id
                    displaySize = Point(vdm.width, vdm.height)
                    virtualDisplayManager = vdm
                    Log.i(TAG, "Virtual display created: $id (${vdm.width}x${vdm.height})")
                } else {
                    Log.e(TAG, "Failed to create virtual display")
                    vdm.destroy()
                    virtualDisplayManager = null
                    refreshNotification()
                    ProactiveRuntime.reportFinished(taskId, "failed", "Failed to create virtual display")
                    return@launch
                }
            }

            acquireAgentWakeLock()

            val actionExecutor = ActionExecutor(
                this@AgentService,
                displayId,
                displaySize,
                onOpenAppSuccess = { launchedPkg ->
                    taskDisplayGuard?.trackLaunch(launchedPkg)
                    mainHandler.postDelayed({ refreshControlledTasksFromVd("open_app_success") }, 250)
                },
                backend = b,
                blacklistedPackages = runtime.blockedPackages(),
                toolCaller = runtime::callTool
            )

            val gateDisplayId = displayId
            val mapper = CoordinateMapper(this@AgentService, displaySize)
            val gate = runtime.approvalGate(gateDisplayId) { x, y -> mapper.normalizedToPixel(x, y).let { it.x to it.y } }
            val gateVdm: VirtualDisplayManager = vdm
            val newEngine = AgentEngine(
                llmClient,
                actionExecutor,
                config.maxSteps,
                vdm,
                config.debugMode,
                memoryRepo,
                elementsProvider = { displayId, w, h ->
                    VdAccessibilityService.captureElements(displayId, w, h)
                },
                blacklistedPackages = runtime.blockedPackages(),
                approvalGate = gate,
                appLabel = { pkg -> ApprovalCard.appLabel(this@AgentService, pkg) },
                onHold = { held ->
                    ProactiveRuntime.handleHold(taskId, held, captureFrame = {
                        withContext(Dispatchers.IO) {
                            if (gateVdm.hasLocalDisplay) gateVdm.captureViaImageReader(timeoutMs = 600L)
                            else ScreenCapture.captureBitmap(gateDisplayId)
                        }
                    }, displaySize = displaySize)
                },
                extraSystemPrompt = runtime.executorPromptExtra()
            )
            engine = newEngine
            currentInstruction = instruction
            _engineFlow.value = newEngine
            observeConnectorState(b)
            ProactiveRuntime.watchTask(taskId, newEngine.end)
            // Subscribed before start, so the first events are not missed. Tagged with the task id here.
            engineEventsJob?.cancel()
            engineStateTapJob?.cancel()
            engineEventsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                newEngine.events.collect { ProactiveRuntime.onEngineEvent(taskId, it) }
            }
            engineStateTapJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                newEngine.state.collect { ProactiveRuntime.onEngineState(taskId, it) }
            }
            newEngine.start(instruction)

            refreshNotification()
            Log.i(TAG, "Agent started with instruction: $instruction")

            // Persist conversation to history DB
            val convId = historyRepo.startConversation(instruction)
            currentConversationId = convId
            historyRepo.addMessage(convId, MessageType.USER_INPUT, instruction)

            observeHistoryWrites()
            observeEnd(newEngine)
            observeAskUserNotifications()
            observeHandoffRequests()
            startTaskDisplayGuard(vdm)
        }
    }

    private var askObserverJob: Job? = null

    /**
     * Watch for WaitingForUser state and post a high-priority heads-up notification
     * so the user sees the prompt even when OpenCyclon is in the background.
     */
    private fun observeAskUserNotifications() {
        askObserverJob?.cancel()
        val flow = engine?.state ?: return
        askObserverJob = scope.launch {
            var wasAsking = false
            flow.collect { state ->
                val asking = state is AgentState.WaitingForUser
                if (asking && !wasAsking) {
                    val q = (state as AgentState.WaitingForUser).question
                    postAskNotification(q)
                } else if (!asking && wasAsking) {
                    cancelAskNotification()
                }
                wasAsking = asking
            }
        }
    }

    private fun postAskNotification(question: String) {
        // The home page shows the question.
        val pi = PendingIntent.getActivity(
            this, 1, homeIntent(openPanel = false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ASK)
            .setContentTitle(getString(R.string.notif_needs_answer))
            .setContentText(question)
            .setStyle(Notification.BigTextStyle().bigText(question))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ASK_ID, notif)
    }

    private fun cancelAskNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ASK_ID)
    }

    /** Posted when the owner is in another app: the result sentence, and a tap opens the home page. */
    private fun postCompletionNotification(sentence: String) {
        val pi = PendingIntent.getActivity(
            this, 2, homeIntent(openPanel = false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ASK)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(sentence)
            .setStyle(Notification.BigTextStyle().bigText(sentence))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_COMPLETED_ID, notif)
    }

    private fun cancelCompletionNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_COMPLETED_ID)
    }

    private fun observeHandoffRequests() {
        handoffObserverJob?.cancel()
        val flow = engine?.state ?: return
        handoffObserverJob = scope.launch {
            flow.collect { state ->
                if (state is AgentState.WaitingForHandoff) {
                    if (activeHandoffStep != state.step) {
                        activeHandoffStep = state.step
                        activeHandoffReason = state.reason
                        startUserHandoff(state.reason)
                    }
                } else if (activeHandoffStep != null && state !is AgentState.Paused) {
                    activeHandoffStep = null
                    activeHandoffReason = ""
                    handoffMonitorJob?.cancel()
                    _handoffUiState.value = HandoffUiState.Idle
                }
            }
        }
    }

    /**
     * The model asked the owner to do a step himself. No screen is forced over the owner's app: the question
     * notification says why, and the owner takes over from the live view (running panel), whose "Return control"
     * hands the task back.
     * ponytail: the visual monitor that returned control by itself starts only from the live view's buttons now.
     */
    private fun startUserHandoff(reason: String) {
        Log.i(TAG, "Starting user handoff: $reason")
        _handoffUiState.value = HandoffUiState.Active(reason)
        postAskNotification(reason)
        refreshNotification()
    }

    private fun startHandoffVisualMonitor(reason: String) {
        handoffMonitorJob?.cancel()
        val vdm = virtualDisplayManager ?: return
        val detector = HandoffVisualDetector()
        handoffMonitorJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var baseline: HandoffVisualDetector.Fingerprint? = null
            var stableTransitionCount = 0

            while (true) {
                val elapsed = System.currentTimeMillis() - startedAt
                _handoffUiState.value = HandoffUiState.Active(reason, elapsed)

                val fingerprint = withContext(Dispatchers.IO) {
                    val bitmap = vdm.captureViaImageReader(timeoutMs = 250)
                    try {
                        if (bitmap != null) detector.fingerprint(bitmap) else null
                    } finally {
                        bitmap?.recycle()
                    }
                }

                if (fingerprint != null) {
                    val base = baseline
                    if (base == null || base.width != fingerprint.width || base.height != fingerprint.height) {
                        baseline = fingerprint
                        stableTransitionCount = 0
                    } else if (detector.isHighConfidenceTransition(base, fingerprint)) {
                        stableTransitionCount++
                        if (stableTransitionCount >= 2) {
                            Log.i(TAG, "Handoff visual monitor detected high-confidence transition")
                            _handoffUiState.value = HandoffUiState.PendingReturn(
                                reason = getString(R.string.handoff_page_changed),
                                countdownSeconds = 0,
                                source = "visual_change_stable"
                            )
                            delay(900L)
                            completeUserHandoff("visual_change_stable")
                            return@launch
                        }
                    } else {
                        stableTransitionCount = 0
                    }
                }

                if (elapsed >= HANDOFF_TIMEOUT_MS) {
                    showPendingHandoffReturn(reason, "timeout")
                    return@launch
                }

                delay(HANDOFF_SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun showPendingHandoffReturn(reason: String, source: String) {
        handoffMonitorJob?.cancel()
        handoffMonitorJob = scope.launch {
            for (remaining in HANDOFF_COUNTDOWN_SECONDS downTo 1) {
                _handoffUiState.value = HandoffUiState.PendingReturn(reason, remaining, source)
                delay(1_000L)
            }
            completeUserHandoff("${source}_countdown_elapsed")
        }
    }

    fun completeUserHandoff(source: String = "manual_return_control") {
        Log.i(TAG, "Completing user handoff: $source")
        handoffMonitorJob?.cancel()
        handoffMonitorJob = null
        activeHandoffStep = null
        _handoffUiState.value = HandoffUiState.Idle
        if (displayState == DisplayState.TAKEOVER) {
            displayState = DisplayState.VIEW
        }
        engine?.completeUserHandoff(source)
        cancelAskNotification()
        refreshNotification()
    }

    fun cancelPendingHandoffReturn() {
        val reason = activeHandoffReason.ifBlank { getString(R.string.handoff_reason_default) }
        Log.i(TAG, "Cancelling pending handoff return")
        startHandoffVisualMonitor(reason)
    }

    /**
     * Observe step results and state changes to persist chat history.
     */
    private fun observeHistoryWrites() {
        historyObserverJob?.cancel()
        val eng = engine ?: return

        // Observe step results
        historyObserverJob = scope.launch {
            eng.stepResults.collect { result ->
                if (result.success && !result.completed && shouldRefreshControlledTasks(result)) {
                    refreshControlledTasksFromVd("step_${result.step}_${result.actionType}")
                }

                val convId = currentConversationId ?: return@collect
                if (config.debugMode) {
                    val prefix = if (result.success) "" else "[FAIL] "
                    val msg = "[Step ${result.step}] ${prefix}${result.actionType}: ${result.detail}"
                    historyRepo.addMessage(convId, MessageType.AGENT_STATUS, msg)
                    if (!result.debugInfo.isNullOrBlank()) {
                        historyRepo.addMessage(convId, MessageType.AGENT_DEBUG, "[LLM]\n${result.debugInfo}")
                    }
                } else if (!result.completed) {
                    val userMsg = result.thought.ifBlank { result.detail }
                    if (userMsg != lastHistoryStatus) {
                        if (lastHistoryStatus == null) {
                            historyRepo.addMessage(convId, MessageType.AGENT_STATUS, userMsg)
                        } else {
                            historyRepo.updateLastAgentStatus(convId, userMsg)
                        }
                        lastHistoryStatus = userMsg
                    }
                }

                if (result.completed) {
                    val summary = when (result.actionType) {
                        "finish" -> "✅ ${getString(R.string.task_complete, result.thought)}"
                        "fail" -> "❌ ${getString(R.string.task_failed, result.detail.removePrefix("Task failed: "))}"
                        "error" -> "❌ ${getString(R.string.task_error, result.detail)}"
                        else -> "✅ ${getString(R.string.task_complete_step, result.step)}"
                    }
                    if (!config.debugMode && lastHistoryStatus != null) {
                        historyRepo.updateLastAgentStatus(convId, summary)
                        historyRepo.updateLastAgentStatusToResult(convId)
                    } else {
                        historyRepo.addMessage(convId, MessageType.AGENT_RESULT, summary)
                    }
                    lastHistoryStatus = null
                    val status = when (result.actionType) {
                        "finish" -> "completed"
                        "fail", "error" -> "failed"
                        else -> "completed"
                    }
                    historyRepo.updateStatus(convId, status)
                }
            }
        }

        // Observe ask_user state
        scope.launch {
            eng.state.collect { state ->
                val convId = currentConversationId ?: return@collect
                if (state is AgentState.WaitingForUser) {
                    if (!config.debugMode) {
                        lastHistoryStatus = null
                    }
                    historyRepo.addMessage(convId, MessageType.AGENT_QUESTION, state.question)
                } else if (state is AgentState.WaitingForHandoff) {
                    if (!config.debugMode) {
                        lastHistoryStatus = null
                    }
                    historyRepo.addMessage(
                        convId,
                        MessageType.AGENT_STATUS,
                        getString(R.string.handoff_sensitive_input, state.reason)
                    )
                }
            }
        }
    }

    /**
     * Watch for the end of [eng]'s run. Keep VD alive for user inspection.
     */
    private fun observeEnd(eng: AgentEngine) {
        endObserverJob?.cancel()
        endObserverJob = scope.launch {
            val end = eng.end.filterNotNull().first()
            if (engine !== eng) return@launch
            Log.i(TAG, "Task ended ($end), keeping virtual display for user inspection")
            taskDisplayGuard?.stop()
            taskDisplayGuard = null
            releaseAgentWakeLock()
            cancelAskNotification()
            refreshNotification()
            val isBackground = !androidx.lifecycle.ProcessLifecycleOwner.get()
                .lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            if (isBackground) completionText(end)?.let { postCompletionNotification(it) }
        }
    }

    suspend fun retryBackendDetection(): Boolean {
        val result = BackendDetector.detect(this@AgentService)
        if (result is DetectionResult.Ready) {
            backend = result.backend
            ScreenCapture.backend = result.backend
            observeConnectorState(result.backend)
            // New PrivilegedService won't have the old VD's ImageReader — destroy stale VDM
            virtualDisplayManager?.destroy()
            virtualDisplayManager = null
            // Automatic callers check AgentConnection.ownerDisconnected first; a success here is a connection.
            AgentConnection.setOwnerDisconnected(this, false)
            connectionLoss.restored()
            onConnection(true)
            Log.i(TAG, "Backend re-detected: ${result.backend.capabilities.name}")
            return true
        }
        Log.w(TAG, "Backend re-detection failed: $result")
        return false
    }

    fun stopAgent() {
        startJob?.cancel()
        connectionLoss.restored()
        taskDisplayGuard?.stop()
        taskDisplayGuard = null
        taskRescueInProgress = false
        handoffMonitorJob?.cancel()
        handoffMonitorJob = null
        handoffObserverJob?.cancel()
        activeHandoffStep = null
        activeHandoffReason = ""
        _handoffUiState.value = HandoffUiState.Idle
        releaseAgentWakeLock()

        // Resume agent if paused (for clean stop)
        if (displayState == DisplayState.TAKEOVER) {
            engine?.resume()
        }

        engine?.stop()
        historyObserverJob?.cancel()
        askObserverJob?.cancel()
        cancelAskNotification()

        currentConversationId?.let { convId ->
            scope.launch { historyRepo.updateStatus(convId, "stopped") }
            currentConversationId = null
        }
        currentInstruction = ""

        // Close the agent's apps before the VD goes away: releasing the VD would move them
        // onto the main screen (Reject, Stop, expiry). Only a task that could not be removed
        // falls back to the upstream move below.
        val vdm = virtualDisplayManager
        var rescueTaskId: Int? = null
        if (vdm != null && vdm.isCreated) {
            rescueTaskId = AgentAppCloser.closeAll({ vdm.getTopTaskIdOnDisplay(vdm.displayId) }, vdm::removeTask)
            if (rescueTaskId != null) Log.w(TAG, "Could not close task $rescueTaskId on the hidden display")
        }

        // Destroy VD first so it stops rendering
        virtualDisplayManager?.destroy()
        virtualDisplayManager = null

        // Move task to Display 0 on next frame — deferred so the foreground
        // activity is fully composited and the controlled app won't flash.
        // Bring OpenCyclon UI to front BEFORE the move to prevent z-order flicker.
        if (rescueTaskId != null) {
            Handler(Looper.getMainLooper()).post {
                bringOpenCyclonUiToFront()
                vdm?.moveTaskToDisplay(rescueTaskId, 0)
                Log.i(TAG, "Deferred move of task $rescueTaskId to Display 0")
            }
        }

        displayState = DisplayState.CHAT
        refreshNotification()
        Log.i(TAG, "Agent stopped, hidden display released")
    }

    @Suppress("DEPRECATION")
    private fun acquireAgentWakeLock() {
        val current = agentWakeLock
        if (current?.isHeld == true) return

        try {
            val powerManager = getSystemService(PowerManager::class.java)
            val mode = wakeLockMode(this)
            val flags = if (mode == WAKE_LOCK_PARTIAL) PowerManager.PARTIAL_WAKE_LOCK
                else PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
            agentWakeLock = powerManager.newWakeLock(flags, "$packageName:Agent").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            Log.i(TAG, "Agent wake lock acquired ($mode)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire agent wake lock: ${e.message}")
            agentWakeLock = null
        }
    }

    private fun releaseAgentWakeLock() {
        val wakeLock = agentWakeLock ?: return
        try {
            if (wakeLock.isHeld) wakeLock.release()
            Log.i(TAG, "Agent wake lock released")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release agent wake lock: ${e.message}")
        } finally {
            agentWakeLock = null
        }
    }

    fun submitUserResponse(answer: String) {
        engine?.submitUserResponse(answer)
        refreshNotification()
        currentConversationId?.let { convId ->
            scope.launch { historyRepo.addMessage(convId, MessageType.USER_ANSWER, answer) }
        }
    }

    fun submitUserSupplement(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        Log.i(TAG, "submitUserSupplement(${trimmed.length} chars)")
        engine?.submitUserSupplement(trimmed)
        refreshNotification()
        currentConversationId?.let { convId ->
            scope.launch { historyRepo.addMessage(convId, MessageType.USER_SUPPLEMENT, trimmed) }
        }
    }

    // ── Display state transitions (SurfaceView-based) ────────────────────

    /**
     * Enter VIEW mode: launch ViewActivity with SurfaceView rendering VD content.
     * Agent keeps running — user can watch the agent work in real-time.
     * App stays on VD (no task reparenting).
     */
    fun enterViewMode(showHandoff: Boolean = false): Boolean {
        val vdm = virtualDisplayManager
        if (vdm == null || !vdm.isCreated) {
            mainHandler.post {
                Toast.makeText(this, "No virtual display", Toast.LENGTH_SHORT).show()
            }
            return false
        }

        // Prevent infinite recursion: if VD shows our own app, move it off the VD.
        // Without this, SurfaceView → VD → OpenCyclon UI → SurfaceView → ... (套娃)
        // Use backend (shell uid) for task queries — main process can't access hidden APIs.
        // getTopTaskIdOnDisplay returns -1 if only OpenCyclon tasks are on VD.
        // In that case we just proceed — the VD will show whatever was last rendered.

        // Launch ViewActivity — SurfaceView will connect to VD via setSurface()
        val intent = Intent(this, ai.opencyclon.ui.ViewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (showHandoff) {
                putExtra(EXTRA_SHOW_HANDOFF, true)
                putExtra(ai.opencyclon.ui.ViewActivity.EXTRA_SHOW_CONTROLS, true)
            }
        }
        startActivity(intent)

        displayState = DisplayState.VIEW
        refreshNotification()
        Log.i(TAG, "Entered VIEW mode (agent keeps running)")
        return true
    }

    /**
     * Enter TAKEOVER mode: pause agent, let user interact via touch forwarding.
     * Called by ViewActivity when user taps "Takeover".
     */
    fun enterTakeoverMode() {
        if (displayState != DisplayState.VIEW) return
        if (engine?.state?.value !is AgentState.WaitingForHandoff) {
            engine?.pause()
        }
        displayState = DisplayState.TAKEOVER
        refreshNotification()
        Log.i(TAG, "Entered TAKEOVER mode (agent paused)")
    }

    /**
     * Exit TAKEOVER mode back to VIEW mode: resume agent.
     * Called by ViewActivity when user taps "Return Control".
     */
    fun exitTakeoverMode() {
        if (displayState != DisplayState.TAKEOVER) return
        if (engine?.state?.value !is AgentState.WaitingForHandoff) {
            engine?.resume()
        }
        displayState = DisplayState.VIEW
        refreshNotification()
        Log.i(TAG, "Exited TAKEOVER → VIEW mode (agent resumed)")
    }

    /**
     * Return to CHAT mode: close ViewActivity, bring ControlPanelActivity to front.
     * Agent keeps running on VD.
     */
    fun returnToChat() {
        // Resume agent if it was paused for TAKEOVER
        if (displayState == DisplayState.TAKEOVER) {
            engine?.resume()
        }

        // Bring ControlPanelActivity to front
        val intent = Intent(this, ai.opencyclon.ui.ControlPanelActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)

        displayState = DisplayState.CHAT
        refreshNotification()
        Log.i(TAG, "Returned to CHAT mode")
    }

    // ── VD task escape guard ─────────────────────────────────────────────

    private fun startTaskDisplayGuard(vdm: VirtualDisplayManager) {
        taskDisplayGuard?.stop()
        taskDisplayGuard = TaskDisplayGuard(
            context = this,
            vdm = vdm,
            scope = scope,
            onEscape = { task -> handleControlledTaskEscape(task) }
        ).also { it.start() }
    }

    private fun refreshControlledTasksFromVd(reason: String) {
        val vdm = virtualDisplayManager ?: return
        if (!vdm.isCreated) return
        // Use backend (shell uid) for task queries — main process can't access hidden APIs on real devices
        val b = backend ?: return
        val taskId = b.getTopTaskIdOnDisplay(vdm.displayId, packageName)
        if (taskId <= 0) {
            Log.i(TAG, "No controlled VD task found while refreshing ($reason)")
            return
        }
        taskDisplayGuard?.addControlledTaskId(taskId)
        Log.d(TAG, "Refreshed controlled VD task ($reason): #$taskId")
    }

    private fun shouldRefreshControlledTasks(result: StepResult): Boolean {
        if (result.actionType == "open_app") return true
        return taskDisplayGuard?.controlledTaskIdsSnapshot()?.isNotEmpty() == true
    }

    private fun handleControlledTaskEscape(task: TaskSnapshot) {
        if (taskRescueInProgress) return
        val vdm = virtualDisplayManager ?: return
        if (!vdm.isCreated) return
        if (task.containsPackage(packageName)) return

        taskRescueInProgress = true
        try {
            // TaskDisplayGuard already did moveTaskToDisplay; just bring our UI back to front
            Log.i(TAG, "Escape callback for task ${task.taskId}, bringing UI to front on Display 0")
            bringOpenCyclonUiToFront()
        } finally {
            taskRescueInProgress = false
        }
    }

    private fun bringOpenCyclonUiToFront() {
        val cls = when (displayState) {
            DisplayState.VIEW, DisplayState.TAKEOVER -> ai.opencyclon.ui.ViewActivity::class.java
            DisplayState.CHAT -> ai.opencyclon.ui.ControlPanelActivity::class.java
        }
        Log.i(TAG, "bringOpenCyclonUiToFront: ${cls.simpleName} on Display 0")
        val intent = Intent(this, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (cls == ai.opencyclon.ui.ViewActivity::class.java) {
                putExtra(ai.opencyclon.ui.ViewActivity.EXTRA_SHOW_CONTROLS, true)
            }
        }
        val options = android.app.ActivityOptions.makeBasic()
        try {
            options.setLaunchDisplayId(0)
        } catch (_: Exception) {}
        startActivity(intent, options.toBundle())
    }

    // ── Public accessors ────────────────────────────────────────────────

    val stateFlow: StateFlow<AgentState>?
        get() = engine?.state

    val stepResultFlow: SharedFlow<StepResult>?
        get() = engine?.stepResults

    fun getVirtualDisplayManager(): VirtualDisplayManager? = virtualDisplayManager

    /**
     * A small picture of the hidden display for the home page's preview, [widthPx] wide, or null. Only the
     * hidden display is read; there is no fallback to the main screen.
     */
    suspend fun previewFrame(widthPx: Int, quality: Int): android.graphics.Bitmap? {
        val vdm = virtualDisplayManager?.takeIf { it.isCreated && it.displayId > 0 } ?: return null
        val width = widthPx.coerceIn(64, 1080)
        return withContext(Dispatchers.IO) {
            try {
                if (vdm.hasLocalDisplay) {
                    vdm.captureViaImageReader(timeoutMs = 300L)?.let { full ->
                        if (full.width <= width) full
                        else android.graphics.Bitmap.createScaledBitmap(full, width, full.height * width / full.width, true)
                            .also { if (it !== full) full.recycle() }
                    }
                } else {
                    val b = backend ?: return@withContext null
                    b.captureScreen(vdm.displayId, width, quality)?.let {
                        android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Preview capture failed: ${e.message}")
                null
            }
        }
    }

    // ── Notification ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ASK,
                getString(R.string.notif_channel_ask),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_channel_ask_desc)
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, "Connection", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /** The home page; with [openPanel] the running panel opens over it. */
    private fun homeIntent(openPanel: Boolean): Intent = ControlPanelActivity.homeIntent(this).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (openPanel) putExtra(ControlPanelActivity.EXTRA_OPEN_PANEL, true)
    }

    private fun foregroundText(): String =
        if (isTaskRunning()) workingText(currentInstruction) else readyText(getString(R.string.app_name))

    private fun buildNotification(text: String, working: Boolean): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, if (working) 3 else 0,
            homeIntent(openPanel = working),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    /** "Working: <goal>" while a task runs, "<app name> is ready" otherwise. */
    private fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(foregroundText(), working = isTaskRunning()))
    }
}
