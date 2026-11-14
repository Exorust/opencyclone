package ai.opencyclone.ui.home

import ai.opencyclone.AgentConnection
import ai.opencyclone.AgentService
import ai.opencyclone.backend.SetupActivity
import ai.opencyclone.backend.SetupStateDetector
import ai.opencyclone.backend.ShizukuConnector
import ai.opencyclone.backend.ShizukuStatus
import ai.opencyclone.config.ConfigRepository
import ai.opencyclone.llm.ModelClients
import ai.opencyclone.ui.AppEntries
import ai.opencyclone.ui.start.StartActivity
import ai.opencyclone.ui.start.StartPrefs
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The checks the home page runs at start. The service is started and bound only to read `activeBackendName`.
 *
 * While the linear start is not done: open [StartActivity] at once for this page on a new installation (no model)
 * or when the start was begun before. Only an owner who never began the start but has a model configured (one who
 * used the app before the start existed) waits up to 3 s for the backend detection; with the connection there,
 * the start is marked done instead. The start owns the pairing, the model, and the notification permission.
 *
 * Once the start is done, the recovery behaviour of the old control panel: after the check, the pairing screen
 * when no privileged connection exists, unless the owner left it without connecting in the last 12 hours or
 * disconnected in the settings; the model setup screen once when no model is configured; on resume, a retry of
 * the backend detection when Shizuku or wireless debugging is present (not after "Disconnect"). The status
 * sentence and the "Tap to reconnect" notification are the way back to the pairing screen.
 *
 * This is the only class of the home page that touches [AgentService].
 */
class StartChecks(private val activity: AppCompatActivity, savedFirstRunOpened: Boolean) {

    private val config = ConfigRepository(activity)
    private val prefs = StartPrefs(activity)
    private var service: AgentService? = null
    private var bound = false
    private var setupLaunched = false
    private var startOpened = false
    private var checkJob: Job? = null

    /** The model setup screen opens once per page, after pairing is done or skipped. */
    var firstRunOpened = savedFirstRunOpened
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? AgentService.AgentBinder)?.getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** Call from onCreate. */
    fun onCreate() {
        val intent = Intent(activity, AgentService::class.java)
        runCatching { activity.startForegroundService(intent) }
            .onFailure { Log.w(TAG, "Could not start the agent service", it) }
        if (!bound) bound = activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        checkBackend()
    }

    /** Call from onResume: the owner may have turned on wireless debugging or Shizuku meanwhile. */
    fun onResume() {
        if (!prefs.done) {
            // The start was cleared away by another screen (the "Set up" page's Done): show it again.
            if (StartActivity.consumeInterrupted()) {
                startOpened = false
                openStart()
            }
            return
        }
        val s = service ?: return
        val name = s.activeBackendName
        if (name != null && name != "none") return
        if (AgentConnection.ownerDisconnected(activity)) return
        val present = ShizukuConnector.status() != ShizukuStatus.UNAVAILABLE ||
            SetupStateDetector.isWirelessDebuggingEnabled(activity)
        if (!present) return
        activity.lifecycleScope.launch {
            if (s.retryBackendDetection()) {
                setupLaunched = false
            } else if (!setupLaunched && mayOpenPairing()) {
                launchSetup()
            }
        }
    }

    /** Call from onActivityResult; returns true when the result was the pairing screen's. */
    fun onActivityResult(requestCode: Int): Boolean {
        if (requestCode != REQUEST_SETUP) return false
        setupLaunched = false
        // Back, "Not now", or close without a connection: do not open the pairing screen by itself for a while.
        if (!connected()) {
            prefs().edit().putLong(KEY_PAIRING_LEFT_AT, clock()).apply()
        }
        openFirstRunIfNeeded()
        return true
    }

    /** Call from onDestroy. */
    fun onDestroy() {
        checkJob?.cancel()
        if (bound) {
            runCatching { activity.unbindService(connection) }
            bound = false
        }
        service = null
    }

    private fun connected(): Boolean {
        val name = backendNameOverride?.invoke() ?: service?.activeBackendName
        return name != null && name != "none"
    }

    private fun checkBackend() {
        checkJob?.cancel()
        if (!prefs.done && (prefs.begun || !ModelClients.isConfigured(config))) {
            // A new installation (no model) or a start already begun: no reason to wait for the backend.
            // The start skips the Connect step by itself once the connection turns out to exist.
            openStart()
            return
        }
        checkJob = activity.lifecycleScope.launch {
            // Give the backend detection time to finish, as the old screen did; stop early once it is there.
            var waited = 0L
            while (!connected() && waited < BACKEND_CHECK_DELAY_MS) {
                delay(BACKEND_POLL_MS)
                waited += BACKEND_POLL_MS
            }
            val isConnected = connected()
            if (!prefs.done) {
                if (!prefs.begun && isConnected && ModelClients.isConfigured(config)) {
                    prefs.done = true // a returning owner
                } else {
                    openStart()
                }
                return@launch
            }
            if (!isConnected) {
                if (mayOpenPairing() && !setupLaunched) launchSetup()
                else if (!setupLaunched) openFirstRunIfNeeded()
            } else {
                openFirstRunIfNeeded()
            }
        }
    }

    private fun prefs() = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** False while the owner disconnected in the settings or left the pairing screen in the last 12 hours. */
    private fun mayOpenPairing(): Boolean {
        return mayOpenPairing(prefs().getLong(KEY_PAIRING_LEFT_AT, 0L), AgentConnection.ownerDisconnected(activity), clock())
    }

    private fun openStart() {
        if (startOpened || activity.isFinishing) return
        startOpened = true
        activity.startActivity(StartActivity.intent(activity))
    }

    @Suppress("DEPRECATION")
    private fun launchSetup() {
        if (activity.isFinishing) return
        setupLaunched = true
        activity.startActivityForResult(Intent(activity, SetupActivity::class.java), REQUEST_SETUP)
    }

    private fun openFirstRunIfNeeded() {
        if (firstRunOpened || activity.isFinishing || !AppEntries.needsFirstRun(config)) return
        firstRunOpened = true
        AppEntries.openFirstRun(activity)
    }

    companion object {
        private const val TAG = "StartChecks"
        const val REQUEST_SETUP = 1001
        const val BACKEND_CHECK_DELAY_MS = 3000L
        const val BACKEND_POLL_MS = 200L
        private const val PREFS = "opencyclone_config"
        private const val KEY_PAIRING_LEFT_AT = "pairing_left_at"
        const val PAIRING_QUIET_MS = 12 * 60 * 60 * 1000L

        @VisibleForTesting internal var clock: () -> Long = System::currentTimeMillis

        /**
         * May the home page open the pairing screen by itself? Not while the owner disconnected in the settings
         * ([ownerDisconnected]), and not within [PAIRING_QUIET_MS] after he left it without connecting ([leftAt],
         * 0 when never).
         */
        fun mayOpenPairing(leftAt: Long, ownerDisconnected: Boolean, now: Long): Boolean =
            !ownerDisconnected && (leftAt <= 0L || now - leftAt >= PAIRING_QUIET_MS || now < leftAt)

        /** Tests set the backend name the check reads. */
        @VisibleForTesting internal var backendNameOverride: (() -> String?)? = null
    }
}
