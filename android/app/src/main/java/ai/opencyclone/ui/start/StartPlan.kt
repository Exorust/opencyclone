package ai.opencyclone.ui.start

import ai.opencyclone.App
import ai.opencyclone.backend.BackendDetector
import ai.opencyclone.config.ConfigRepository
import ai.opencyclone.llm.ModelClients
import ai.opencyclone.ui.PermissionStatus
import ai.opencyclone.ui.voice.VoiceChoices
import ai.opencyclone.voice.SherpaModels
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager

/** The steps of the linear start, in order. */
enum class StartStep { WELCOME, CONNECT, MODEL, PERMISSIONS, VOICE, MAKE_HOME, TRY_IT }

/** The three switches of the permissions step, in the order they are asked. */
enum class Grant { ACCESSIBILITY, NOTIFICATION_ACCESS, POST_NOTIFICATIONS }

/** What the start reads from the phone. Tests pass fixed values. */
interface StartConditions {
    fun connected(): Boolean
    fun modelConfigured(): Boolean
    fun granted(grant: Grant): Boolean
    fun micGranted(): Boolean
    fun voiceModelPresent(): Boolean
    fun metered(): Boolean

    /** Android offers a usable recognizer: the voice step then needs only the microphone, no download. */
    fun androidRecognizer(): Boolean = false

    /** This app is the phone's home app already: the optional "Make this your home screen" step is left out. */
    fun homeRoleHeld(): Boolean = false
}

/** Which steps the owner sees. Satisfied steps are skipped; "Try it" is always shown. */
object StartPlan {

    /** [welcomeSeen] is true once "Start" was tapped, or when a model is already configured (a returning owner). */
    fun satisfied(step: StartStep, c: StartConditions, welcomeSeen: Boolean): Boolean = when (step) {
        StartStep.WELCOME -> welcomeSeen
        StartStep.CONNECT -> c.connected()
        StartStep.MODEL -> c.modelConfigured()
        StartStep.PERMISSIONS -> nextGrant(c) == null
        StartStep.VOICE -> voiceReady(c)
        StartStep.MAKE_HOME -> c.homeRoleHeld()
        StartStep.TRY_IT -> false
    }

    fun steps(c: StartConditions, welcomeSeen: Boolean): List<StartStep> {
        val seen = welcomeSeen || c.modelConfigured()
        return StartStep.entries.filter { !satisfied(it, c, seen) }
    }

    /** The first switch that is still off, or null when all three are on. */
    fun nextGrant(c: StartConditions): Grant? = Grant.entries.firstOrNull { !c.granted(it) }

    fun voiceReady(c: StartConditions): Boolean = (c.androidRecognizer() || c.voiceModelPresent()) && c.micGranted()

    /** Where a left start resumes: the saved step, or the first later step that is still in [plan]. */
    fun resumeIndex(plan: List<StartStep>, saved: StartStep?): Int {
        if (saved == null) return 0
        val exact = plan.indexOf(saved)
        if (exact >= 0) return exact
        return plan.indexOfFirst { it.ordinal > saved.ordinal }.takeIf { it >= 0 } ?: 0
    }

    /** Whole percent of a download; 0 while the size is unknown. */
    fun percent(read: Long, total: Long): Int =
        if (total <= 0) 0 else (read * 100 / total).toInt().coerceIn(0, 100)
}

/** "Start done", the current step, and "welcome seen", in one preferences file. */
class StartPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var done: Boolean
        get() = prefs.getBoolean(KEY_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_DONE, value).apply()

    var step: StartStep?
        get() = prefs.getString(KEY_STEP, null)?.let { name -> StartStep.entries.firstOrNull { it.name == name } }
        set(value) = prefs.edit().putString(KEY_STEP, value?.name).apply()

    var welcomeSeen: Boolean
        get() = prefs.getBoolean(KEY_WELCOME, false)
        set(value) = prefs.edit().putBoolean(KEY_WELCOME, value).apply()

    /** True while a sign-in started on the start screen waits for the browser: its return link comes back here. */
    var signInFromStart: Boolean
        get() = prefs.getBoolean(KEY_SIGN_IN_FROM_START, false)
        set(value) = prefs.edit().putBoolean(KEY_SIGN_IN_FROM_START, value).apply()

    /** True once the start screen was shown at least once. */
    val begun: Boolean get() = step != null

    companion object {
        const val FILE = "start_flow"
        private const val KEY_DONE = "done"
        private const val KEY_STEP = "step"
        private const val KEY_WELCOME = "welcome_seen"
        private const val KEY_SIGN_IN_FROM_START = "sign_in_from_start"
    }
}

/** The real readers. The connection is the backend the agent service detected, as [ai.opencyclone.ui.home.StartChecks] reads it. */
class DeviceStartConditions(context: Context) : StartConditions {
    private val app = context.applicationContext
    private val readers = PermissionStatus.readers(app)

    override fun connected(): Boolean {
        val name = App.agentService?.activeBackendName
        return (name != null && name != "none") || BackendDetector.isSystemUid()
    }

    override fun modelConfigured(): Boolean = ModelClients.isConfigured(ConfigRepository(app))

    override fun granted(grant: Grant): Boolean = when (grant) {
        Grant.ACCESSIBILITY -> PermissionStatus.containsComponent(
            readers.enabledAccessibilityServices(), app.packageName, PermissionStatus.ACCESSIBILITY_CLASS
        )
        Grant.NOTIFICATION_ACCESS -> PermissionStatus.containsComponent(
            readers.enabledNotificationListeners(), app.packageName, PermissionStatus.NOTIFICATION_LISTENER_CLASS
        )
        Grant.POST_NOTIFICATIONS -> readers.postNotificationsGranted()
    }

    override fun micGranted(): Boolean =
        app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun voiceModelPresent(): Boolean = SherpaModels.selected(app).installed(app.filesDir)

    override fun androidRecognizer(): Boolean = VoiceChoices.androidRecognizer(app).available

    override fun homeRoleHeld(): Boolean = ai.opencyclone.assist.HomeMode.isHomeRoleHeld(app)

    override fun metered(): Boolean =
        app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
}
