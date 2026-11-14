package ai.opencyclone.ui.start

import ai.opencyclone.R
import ai.opencyclone.assist.HomeMode
import ai.opencyclone.backend.SetupActivity
import ai.opencyclone.llm.PlanSignIn
import ai.opencyclone.ui.ControlPanelActivity
import ai.opencyclone.ui.FirstRunActivity
import ai.opencyclone.ui.PermissionStatus
import ai.opencyclone.ui.theme.Motion
import ai.opencyclone.ui.theme.Wash
import ai.opencyclone.ui.theme.applySystemBarPadding
import ai.opencyclone.ui.theme.drawBehindSystemBars
import ai.opencyclone.ui.theme.setWash
import ai.opencyclone.ui.voice.ListeningActivity
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity

/**
 * The linear start: one full-screen step at a time (Welcome, Connect, Model, Permissions, Voice, Try it).
 * Satisfied steps are left out of the plan; the plan is fixed when the screen opens so that "Step 2 of 5" stays
 * true, and a step that became satisfied meanwhile is skipped when going forward. The current step is saved, so a
 * left start resumes there. Finishing "Try it" marks the start done; the "Set up" page stays for repairs.
 */
class StartActivity : AppCompatActivity() {

    /** The voice step's states. */
    sealed class Voice {
        data object Idle : Voice()
        data object MicOff : Voice()
        data object Metered : Voice()
        data class Downloading(val percent: Int) : Voice()
        data object Ready : Voice()
        data object Failed : Voice()
    }

    private enum class SignIn { NONE, WAITING, FAILED }

    private lateinit var conditions: StartConditions
    private lateinit var prefs: StartPrefs
    private lateinit var content: View
    private lateinit var counter: TextView
    private lateinit var title: TextView
    private lateinit var text: TextView
    private lateinit var detailName: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var signInChatGpt: Button
    private lateinit var signInOpenRouter: Button
    private lateinit var useKey: TextView
    private lateinit var hint: TextView
    private lateinit var primary: Button
    private lateinit var pale: Button

    @VisibleForTesting internal lateinit var plan: List<StartStep>
        private set
    @VisibleForTesting internal var index = 0
        private set
    @VisibleForTesting internal var voice: Voice = Voice.Idle
        private set

    private var connectFailed = false
    private var signIn = SignIn.NONE
    private var openedKeyPage = false
    private var openedGrant = false
    private var openedHome = false
    private var askedPostNotifications = false
    private var downloading = false
    private var ownerLeft = false

    val step: StartStep get() = plan[index]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawBehindSystemBars()
        setContentView(R.layout.start_activity)
        prefs = StartPrefs(this)
        conditions = conditionsFor(this)
        if (prefs.done) {
            ownerLeft = true
            finish()
            return
        }

        val root = findViewById<View>(R.id.start_root)
        root.setWash(Wash.Warm)
        applySystemBarPadding(root)
        content = findViewById(R.id.start_content)
        counter = findViewById(R.id.start_counter)
        title = findViewById(R.id.start_title)
        text = findViewById(R.id.start_text)
        detailName = findViewById(R.id.start_detail_name)
        status = findViewById(R.id.start_status)
        progress = findViewById(R.id.start_progress)
        signInChatGpt = findViewById(R.id.start_signin_chatgpt)
        signInOpenRouter = findViewById(R.id.start_signin_openrouter)
        useKey = findViewById(R.id.start_use_key)
        hint = findViewById(R.id.start_hint)
        primary = findViewById(R.id.start_primary)
        pale = findViewById(R.id.start_pale)

        signInChatGpt.setOnClickListener { startSignIn { PlanSignIn.startChatGpt(this) } }
        signInOpenRouter.setOnClickListener { startSignIn { PlanSignIn.startOpenRouter(this) } }
        useKey.setOnClickListener {
            openedKeyPage = true
            startActivity(FirstRunActivity.keyIntent(this))
        }
        onBackPressedDispatcher.addCallback(this) { back() }

        val savedPlan = savedInstanceState?.getStringArray(STATE_PLAN)?.map { StartStep.valueOf(it) }
        plan = savedPlan ?: StartPlan.steps(conditions, prefs.welcomeSeen)
        index = savedInstanceState?.getInt(STATE_INDEX)?.coerceIn(0, plan.lastIndex)
            ?: StartPlan.resumeIndex(plan, prefs.step)
        prefs.step = step
        voice = initialVoice()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::plan.isInitialized) return
        outState.putStringArray(STATE_PLAN, plan.map { it.name }.toTypedArray())
        outState.putInt(STATE_INDEX, index)
    }

    override fun onResume() {
        super.onResume()
        if (ownerLeft) return
        PlanSignIn.onFinished = { _, error ->
            signIn = if (error == null) SignIn.NONE else SignIn.FAILED
            render()
        }
        when (step) {
            StartStep.MODEL -> if (openedKeyPage && conditions.modelConfigured()) {
                openedKeyPage = false
                next()
                return
            }
            StartStep.PERMISSIONS -> if (openedGrant && StartPlan.nextGrant(conditions) == null) {
                openedGrant = false
                next()
                return
            }
            StartStep.VOICE -> if (voice == Voice.MicOff && conditions.micGranted()) voice = Voice.Idle
            StartStep.MAKE_HOME -> if (openedHome && conditions.homeRoleHeld()) {
                openedHome = false
                next()
                return
            }
            else -> Unit
        }
        render()
    }

    override fun onPause() {
        PlanSignIn.onFinished = null
        super.onPause()
    }

    override fun onDestroy() {
        // Another screen (the "Set up" page's Done) can bring the home page to front and clear this one away.
        if (isFinishing && !ownerLeft) interrupted = true
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONNECT || step != StartStep.CONNECT) return
        if (resultCode == SetupActivity.RESULT_BACKEND_READY || conditions.connected()) {
            connectFailed = false
            next()
        } else {
            connectFailed = true
            render()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_MIC -> if (conditions.micGranted()) afterMic() else {
                voice = Voice.MicOff
                render()
            }
            REQUEST_POST_NOTIFICATIONS ->
                if (step == StartStep.PERMISSIONS && StartPlan.nextGrant(conditions) == null) next() else render()
        }
    }

    // ── Moving between steps ────────────────────────────────────────────

    /** Forward to the next step of the plan, skipping steps that became satisfied meanwhile. */
    @VisibleForTesting internal fun next() {
        var i = index + 1
        while (i < plan.lastIndex && StartPlan.satisfied(plan[i], conditions, prefs.welcomeSeen)) i++
        go(i.coerceAtMost(plan.lastIndex))
    }

    /** Back to the previous step; on the first step, leave the app. */
    private fun back() {
        if (index == 0) {
            ownerLeft = true
            finishAffinity()
        } else {
            go(index - 1)
        }
    }

    private fun go(i: Int) {
        index = i
        prefs.step = step
        connectFailed = false
        signIn = SignIn.NONE
        openedKeyPage = false
        openedGrant = false
        openedHome = false
        if (!downloading) voice = initialVoice()
        render()
        content.alpha = 0f
        content.animate().cancel()
        content.animate().alpha(1f).setDuration(Motion.ENTER_MS).start()
    }

    private fun initialVoice(): Voice = if (StartPlan.voiceReady(conditions)) Voice.Ready else Voice.Idle

    private fun finishStart(then: Intent) {
        prefs.done = true
        ownerLeft = true
        startActivity(then)
        finish()
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private fun render() {
        counter.text = getString(R.string.start_counter, index + 1, plan.size)
        listOf<View>(detailName, status, progress, signInChatGpt, signInOpenRouter, useKey, hint, primary, pale, modelRow)
            .forEach { it.visibility = View.GONE }
        primary.isEnabled = true
        when (step) {
            StartStep.WELCOME -> {
                title.text = getString(R.string.app_name)
                text.setText(R.string.start_welcome_text)
                primary(R.string.start_start) {
                    prefs.welcomeSeen = true
                    next()
                }
            }
            StartStep.CONNECT -> {
                title.setText(R.string.start_connect_title)
                text.setText(R.string.start_connect_text)
                if (connectFailed) status(getString(R.string.start_not_connected))
                @Suppress("DEPRECATION")
                primary(R.string.start_connect) {
                    startActivityForResult(Intent(this, SetupActivity::class.java), REQUEST_CONNECT)
                }
            }
            StartStep.MODEL -> renderModel()
            StartStep.PERMISSIONS -> renderPermissions()
            StartStep.VOICE -> renderVoice()
            StartStep.MAKE_HOME -> renderMakeHome()
            StartStep.TRY_IT -> renderTryIt()
        }
        // A single button fills the row; two share it, the pale one at the left.
        (pale.layoutParams as android.widget.LinearLayout.LayoutParams).marginEnd =
            if (primary.visibility == View.VISIBLE) resources.getDimensionPixelSize(R.dimen.gap_buttons) else 0
        pale.requestLayout()
    }

    /** Optional (never blocks finishing): "Make it home" opens Android's home-app page; "Not now" goes on. */
    private fun renderMakeHome() {
        title.setText(R.string.x2_home_title)
        text.setText(R.string.x2_home_text)
        primary(R.string.x2_home_make) {
            openedHome = true
            HomeMode.setAliasEnabled(this, true)
            open(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        pale(R.string.ui_not_now) { next() }
    }

    private fun renderTryIt() {
        title.setText(R.string.start_try_title)
        text.setText(R.string.start_try_text)
        hint.visibility = View.VISIBLE
        primary(R.string.start_talk) { finishStart(ListeningActivity.listenIntent(this)) }
        primary.isEnabled = StartPlan.voiceReady(conditions)
        pale(R.string.start_type) {
            finishStart(
                Intent(this, ControlPanelActivity::class.java)
                    .putExtra(EXTRA_OPEN_TYPE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    /** "Model: <name>" under the Connected sentence; opens the Model screen. Built here so the layout stays shared. */
    @VisibleForTesting internal val modelRow: TextView by lazy {
        TextView(this).apply {
            tag = TAG_MODEL_ROW
            setTextAppearance(R.style.TextAppearance_App_Body)
            setTextColor(getColor(R.color.ink))
            typeface = android.graphics.Typeface.create(typeface, 500, false)
            gravity = android.view.Gravity.CENTER_VERTICAL
            minHeight = resources.getDimensionPixelSize(R.dimen.button_height)
            val outValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            visibility = View.GONE
            setOnClickListener { startActivity(ai.opencyclone.ui.screens.ModelSettingsActivity.intent(this@StartActivity)) }
            val column = content as android.widget.LinearLayout
            column.addView(this, column.indexOfChild(this@StartActivity.text) + 1, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.gap_chip) })
        }
    }

    private fun renderModel() {
        title.setText(R.string.start_model_title)
        val configured = conditions.modelConfigured()
        text.setText(if (configured) R.string.start_model_connected else R.string.start_model_text)
        if (configured) {
            modelRow.text = getString(R.string.model_row, ai.opencyclone.config.ConfigRepository(this).model)
            modelRow.visibility = View.VISIBLE
        }
        signInChatGpt.visibility = View.VISIBLE
        signInOpenRouter.visibility = View.VISIBLE
        useKey.visibility = View.VISIBLE
        when (signIn) {
            SignIn.WAITING -> if (!configured) status(getString(R.string.start_signin_waiting))
            SignIn.FAILED -> status(getString(R.string.start_signin_failed))
            SignIn.NONE -> Unit
        }
        // A primary that cannot be pressed yet stays visible and disabled (UI.md 11).
        primary(R.string.start_continue) { next() }
        primary.isEnabled = configured
    }

    private fun renderPermissions() {
        title.setText(R.string.start_perm_title)
        text.setText(R.string.start_perm_text)
        val grant = StartPlan.nextGrant(conditions)
        if (grant == null) {
            status(getString(R.string.start_perm_done))
            primary(R.string.start_continue) { next() }
            return
        }
        val (name, why) = when (grant) {
            Grant.ACCESSIBILITY -> R.string.h_perm_accessibility to R.string.start_perm_accessibility
            Grant.NOTIFICATION_ACCESS -> R.string.h_perm_notification_listener to R.string.start_perm_notification_access
            Grant.POST_NOTIFICATIONS -> R.string.h_perm_post_notifications to R.string.start_perm_post_notifications
        }
        detailName.text = getString(name)
        detailName.visibility = View.VISIBLE
        status(getString(why))
        primary(R.string.start_open_settings) { openGrant(grant) }
    }

    private fun renderVoice() {
        title.setText(R.string.start_voice_title)
        // With Android's recognizer (the default choice) the step only asks for the microphone; no download.
        val android = conditions.androidRecognizer()
        text.setText(if (android) R.string.start_voice_text_android else R.string.start_voice_text)
        when (val v = voice) {
            Voice.Idle -> primary(if (android) R.string.start_voice_allow else R.string.start_voice_get) {
                if (conditions.micGranted()) afterMic()
                else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            }
            Voice.MicOff -> {
                status(getString(R.string.start_voice_mic_off))
                primary(R.string.start_open_settings) {
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
            }
            Voice.Metered -> {
                status(getString(R.string.start_voice_metered))
                primary(R.string.start_voice_download_anyway) { download() }
            }
            is Voice.Downloading -> {
                status(getString(R.string.start_voice_progress, v.percent))
                progress.progress = v.percent
                progress.visibility = View.VISIBLE
            }
            Voice.Ready -> {
                status(getString(R.string.start_voice_ready))
                primary(R.string.start_continue) { next() }
            }
            Voice.Failed -> {
                status(getString(R.string.start_voice_failed))
                primary(R.string.start_voice_try_again) { download() }
            }
        }
        if (voice != Voice.Ready) pale(R.string.ui_not_now) { next() }
    }

    private fun status(value: String) {
        status.text = value
        status.visibility = View.VISIBLE
    }

    private fun primary(@StringRes label: Int, onClick: () -> Unit) {
        primary.setText(label)
        primary.visibility = View.VISIBLE
        primary.setOnClickListener { onClick() }
    }

    private fun pale(@StringRes label: Int, onClick: () -> Unit) {
        pale.setText(label)
        pale.visibility = View.VISIBLE
        pale.setOnClickListener { onClick() }
    }

    // ── Actions ─────────────────────────────────────────────────────────

    private fun startSignIn(start: () -> Unit) {
        // The browser's return link (opencyclone://signed-in) opens the "Set up" page; it comes back here instead.
        prefs.signInFromStart = true
        signIn = SignIn.WAITING
        render()
        start()
    }

    private fun openGrant(grant: Grant) {
        openedGrant = true
        when (grant) {
            Grant.ACCESSIBILITY -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Grant.NOTIFICATION_ACCESS -> open(notificationAccessIntent())
            Grant.POST_NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedPostNotifications) {
                    // The system dialog first; when it is refused, the next tap opens the app's notification page.
                    askedPostNotifications = true
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
                } else {
                    open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
        }
    }

    private fun notificationAccessIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(packageName, PermissionStatus.NOTIFICATION_LISTENER_CLASS).flattenToString()
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun afterMic() {
        voice = when {
            conditions.androidRecognizer() || conditions.voiceModelPresent() -> Voice.Ready
            conditions.metered() -> Voice.Metered
            else -> return download()
        }
        render()
    }

    private fun download() {
        downloading = true
        voice = Voice.Downloading(0)
        render()
        voiceDownload.start(
            this,
            { percent ->
                if (isDestroyed || !downloading) return@start
                voice = Voice.Downloading(percent)
                if (step == StartStep.VOICE) render()
            },
            { error ->
                downloading = false
                if (isDestroyed) return@start
                voice = if (error == null) Voice.Ready else Voice.Failed
                if (step == StartStep.VOICE) render()
            },
        )
    }

    companion object {
        /** Asks the home page to open its type input. */
        const val TAG_MODEL_ROW = "start_model_row"
        const val EXTRA_OPEN_TYPE = "ai.opencyclone.extra.OPEN_TYPE_INPUT"
        const val REQUEST_CONNECT = 3101
        const val REQUEST_MIC = 3102
        const val REQUEST_POST_NOTIFICATIONS = 3103
        private const val STATE_PLAN = "start_plan"
        private const val STATE_INDEX = "start_index"

        @VisibleForTesting internal var conditionsFor: (Context) -> StartConditions = { DeviceStartConditions(it) }
        @VisibleForTesting internal var voiceDownload: VoiceDownload = ModelVoiceDownload

        @Volatile private var interrupted = false

        /** True once after the screen was closed by another screen rather than by the owner. */
        fun consumeInterrupted(): Boolean {
            val was = interrupted
            interrupted = false
            return was
        }

        fun intent(context: Context): Intent = Intent(context, StartActivity::class.java)
    }
}
