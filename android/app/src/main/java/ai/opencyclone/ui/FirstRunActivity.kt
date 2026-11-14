package ai.opencyclone.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.opencyclone.AgentService
import ai.opencyclone.App
import ai.opencyclone.R
import ai.opencyclone.backend.SetupActivity
import ai.opencyclone.config.ConfigRepository
import ai.opencyclone.config.ProfileRepository
import ai.opencyclone.db.AppDatabase
import ai.opencyclone.llm.LLMClientInterface
import ai.opencyclone.llm.ModelClients
import ai.opencyclone.llm.PlanSignIn
import ai.opencyclone.proactive.AllowlistEntity
import ai.opencyclone.ui.screens.PlainScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * First-run screen (F12) in the plain look (UI.md 5.12): permission status rows, the two sign-in
 * pills and "Use a key" for a model address and key with a Test button, the allow-list of messaging
 * apps, the privacy statement, the one line about the long press on the greeting, and Done.
 */
class FirstRunActivity : AppCompatActivity() {

    companion object {
        const val TAG_DONE = "first_run_done"
        const val TAG_TEST_RESULT = "first_run_test_result"
        const val TAG_SIGN_IN_CHATGPT = "first_run_sign_in_chatgpt"
        const val TAG_SIGN_IN_OPENROUTER = "first_run_sign_in_openrouter"
        const val TAG_SIGN_IN_STATUS = "first_run_sign_in_status"
        const val TAG_MODEL_ROW = "first_run_model_row"
        const val TAG_USE_KEY = "first_run_use_key"
        const val TAG_TEST = "first_run_test"
        const val TAG_MORE_HINT = "first_run_more_hint"
        /** Opens the page with the address and key fields of "Use a key" already shown. */
        const val EXTRA_SHOW_KEY_FIELDS = "ai.opencyclone.extra.SHOW_KEY_FIELDS"
        private const val TEST_TIMEOUT_MS = 30_000L
        private const val PRIVILEGED_CHECKS = 20
        private const val PRIVILEGED_CHECK_INTERVAL_MS = 1_000L

        /** Known messaging apps, in addition to any app that declares the messaging category. */
        val KNOWN_MESSAGING_PACKAGES = listOf(
            "com.whatsapp",
            "org.thoughtcrime.securesms",
            "org.telegram.messenger",
            "com.google.android.apps.messaging"
        )

        @VisibleForTesting
        internal var databaseFor: (Context) -> AppDatabase = { AppDatabase.getInstance(it) }

        /** Done needs a saved key, unless the selected provider needs none (the local model provider). */
        fun keyIntent(context: Context): Intent =
            Intent(context, FirstRunActivity::class.java).putExtra(EXTRA_SHOW_KEY_FIELDS, true)

        fun isDoneEnabled(config: ConfigRepository): Boolean = ModelClients.isConfigured(config)

        /** Builds the same client the agent uses for the saved provider. */
        fun clientFor(config: ConfigRepository): LLMClientInterface = ModelClients.forConfig(config)

        /** One minimal request. Returns null when the model answered, otherwise the reason. */
        suspend fun testModel(client: LLMClientInterface, timeoutMs: Long = TEST_TIMEOUT_MS): String? = try {
            withTimeout(timeoutMs) {
                client.chatWithTools(listOf(mapOf("role" to "user", "content" to "Connection test. Reply with any action.")))
            }
            null
        } catch (_: TimeoutCancellationException) {
            "no answer within ${timeoutMs / 1000} seconds"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            client.shutdown()
        }

        /** Installed messaging apps as (package, label), sorted by label. */
        fun messagingApps(pm: PackageManager): List<Pair<String, String>> {
            val packages = LinkedHashSet<String>()
            KNOWN_MESSAGING_PACKAGES.forEach { pkg ->
                try { pm.getApplicationInfo(pkg, 0); packages.add(pkg) } catch (_: PackageManager.NameNotFoundException) {}
            }
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
            pm.queryIntentActivities(query, 0).forEach { packages.add(it.activityInfo.packageName) }
            return packages.mapNotNull { pkg ->
                try {
                    pkg to pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }.sortedBy { it.second.lowercase() }
        }
    }

    private lateinit var config: ConfigRepository
    private lateinit var screen: PlainScreen
    private lateinit var permissionList: LinearLayout
    private lateinit var manualFields: LinearLayout
    private lateinit var addressField: EditText
    private lateinit var keyField: EditText
    private lateinit var testResult: TextView
    private lateinit var signInStatus: TextView
    private lateinit var modelRow: View
    private lateinit var privacyText: TextView
    private lateinit var allowList: LinearLayout
    private lateinit var doneButton: Button
    private var privilegedWatch: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (returnToStart(intent)) return
        config = ConfigRepository(this)
        screen = PlainScreen.install(this, getString(R.string.h_first_run_title))

        // (1) Permissions, as status rows
        screen.section(getString(R.string.h_section_permissions))
        permissionList = column(screen.rows)

        // (2) Model: two sign-in pills, the status line, then "Use a key" that opens the manual fields
        screen.section(getString(R.string.h_section_model))
        val signIns = column(screen.rows)
        signIns.addView(palePill(getString(R.string.signin_chatgpt), TAG_SIGN_IN_CHATGPT) {
            startSignIn { PlanSignIn.startChatGpt(this@FirstRunActivity) }
        })
        signIns.addView(palePill(getString(R.string.signin_openrouter), TAG_SIGN_IN_OPENROUTER) {
            startSignIn { PlanSignIn.startOpenRouter(this@FirstRunActivity) }
        })
        signInStatus = screen.sentence("").apply { tag = TAG_SIGN_IN_STATUS }
        modelRow = screen.textRow(getString(R.string.model_row, config.model), chevron = true, onClick = {
            startActivity(ai.opencyclone.ui.screens.ModelSettingsActivity.intent(this))
        }).apply { tag = TAG_MODEL_ROW }
        lateinit var useKeyRow: View
        useKeyRow = screen.textRow(
            title = getString(R.string.screens_use_key),
            detail = getString(R.string.screens_use_key_detail),
            chevron = true,
            onClick = { setManualVisible(manualFields.visibility != View.VISIBLE, useKeyRow) },
        )
        useKeyRow.tag = TAG_USE_KEY
        manualFields = column(screen.rows).apply { visibility = View.GONE }
        addressField = screen.fieldRow(
            getString(R.string.h_model_address_hint),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            text = config.baseUrl,
            parent = manualFields,
        )
        keyField = screen.fieldRow(
            getString(R.string.h_model_key_hint),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            text = config.apiKey,
            parent = manualFields,
        )
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(palePill(getString(R.string.h_model_save), null) {
            saveModel()
            testResult.text = getString(R.string.h_model_saved)
        })
        buttons.addView(palePill(getString(R.string.h_model_test), TAG_TEST) { saveAndTest(it) }.also {
            (it.layoutParams as LinearLayout.LayoutParams).marginStart = resources.getDimensionPixelSize(R.dimen.gap_buttons)
        })
        manualFields.addView(buttons)
        testResult = screen.sentence("", parent = manualFields).apply { tag = TAG_TEST_RESULT }
        if (intent.getBooleanExtra(EXTRA_SHOW_KEY_FIELDS, false)) setManualVisible(true, useKeyRow)

        // (3) Allow-list of messaging apps
        screen.section(getString(R.string.h_section_allowlist))
        screen.sentence(getString(R.string.h_allowlist_explain))
        allowList = column(screen.rows)

        // (4) Privacy sentence, and the one line that tells the owner where the other screens are
        screen.section(getString(R.string.h_section_privacy))
        privacyText = screen.sentence("")
        screen.sentence(getString(R.string.screens_more_hint)).tag = TAG_MORE_HINT

        // (5) Done
        doneButton = screen.primary(getString(R.string.h_done)) {
            startActivity(Intent(this@FirstRunActivity, ControlPanelActivity::class.java))
            finish()
        }.apply { tag = TAG_DONE }

        updateSavedState()
        loadAllowList()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        returnToStart(intent)
    }

    /**
     * The sign-in return link always opens this page; when the sign-in began on the first-run screen, that screen
     * comes back instead. Returns true when this page finished for it.
     */
    private fun returnToStart(intent: Intent?): Boolean {
        if (intent?.data?.toString()?.startsWith(PlanSignIn.RETURN_URI) != true) return false
        val prefs = ai.opencyclone.ui.start.StartPrefs(this)
        if (!prefs.signInFromStart) return false
        prefs.signInFromStart = false
        startActivity(ai.opencyclone.ui.start.StartActivity.intent(this)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
        return true
    }

    private fun setManualVisible(visible: Boolean, row: View) {
        manualFields.visibility = if (visible) View.VISIBLE else View.GONE
        row.findViewById<View>(R.id.row_chevron).rotation = if (visible) 90f else 0f
    }

    private fun column(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        parent.addView(this)
    }

    /** A pale pill in the body of the screen (not the bottom bar). */
    private fun palePill(label: String, viewTag: String?, onClick: (Button) -> Unit): Button =
        Button(this, null, 0, R.style.Widget_App_Button_Pale).apply {
            text = label
            minHeight = resources.getDimensionPixelSize(R.dimen.button_height)
            minimumHeight = minHeight
            tag = viewTag
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.gap_chip) }
            setOnClickListener { onClick(this) }
        }

    override fun onResume() {
        super.onResume()
        PlanSignIn.onFinished = { _, error ->
            if (error != null) signInStatus.text = getString(R.string.signin_failed, error)
            refreshModelFields()
        }
        refreshModelFields()
        renderPermissions()
        watchPrivilegedConnection()
    }

    /**
     * The privileged-connection row reads the backend that AgentService detects. Start the
     * service if it is not running, then re-check for a short while until the row turns green,
     * so the row is not red only because the service had not started yet.
     */
    private fun watchPrivilegedConnection() {
        if (App.agentService == null) {
            try {
                startForegroundService(Intent(this, AgentService::class.java))
            } catch (e: Exception) {
                android.util.Log.w("FirstRunActivity", "Could not start AgentService: ${e.message}")
            }
        }
        privilegedWatch?.cancel()
        privilegedWatch = lifecycleScope.launch {
            repeat(PRIVILEGED_CHECKS) {
                if (PermissionStatus.readers(this@FirstRunActivity).privilegedConnected()) {
                    renderPermissions()
                    return@launch
                }
                delay(PRIVILEGED_CHECK_INTERVAL_MS)
            }
            renderPermissions()
        }
    }

    override fun onPause() {
        PlanSignIn.onFinished = null
        privilegedWatch?.cancel()
        super.onPause()
    }

    private fun renderPermissions() {
        permissionList.removeAllViews()
        for (row in PermissionStatus.rows(this)) {
            screen.statusRow(row.name, row.granted, detail = row.howToFix, onGrant = { grant(row) }, parent = permissionList)
                .apply { if (row.optional && row.granted && row.fix != null) setOnClickListener { grant(row) } }
        }
    }

    /**
     * "Grant" opens the system page where the permission is given; the small line under the row
     * still says how to fix it, as before.
     */
    private fun grant(row: PermissionRow) {
        row.fix?.let { it(this); return }
        val pkg = Uri.parse("package:$packageName")
        val intent = when (row.name) {
            getString(R.string.h_perm_privileged) -> Intent(this, SetupActivity::class.java)
            getString(R.string.h_perm_accessibility) -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            getString(R.string.h_perm_notification_listener) -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            getString(R.string.h_perm_post_notifications) ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            getString(R.string.h_perm_battery) -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
        }
    }

    /** The listener waits on a process-wide scope; AgentService (started in onResume) keeps the process in the foreground. */
    private fun startSignIn(start: () -> Unit) {
        ai.opencyclone.ui.start.StartPrefs(this).signInFromStart = false
        signInStatus.text = getString(R.string.signin_waiting)
        start()
    }

    /** After a sign-in the saved provider, address and key change: show them and "Connected". */
    private fun refreshModelFields() {
        val connected = when {
            PlanSignIn.isChatGptConnected(config) -> "ChatGPT (${config.model})"
            PlanSignIn.isOpenRouterConnected(config) -> "OpenRouter (${config.model})"
            else -> null
        }
        if (connected != null) {
            signInStatus.text = getString(R.string.signin_connected, connected)
            if (addressField.text.toString() != config.baseUrl) addressField.setText(config.baseUrl)
            if (keyField.text.toString() != config.apiKey) keyField.setText(config.apiKey)
        }
        // "Model: <name>" under the Connected line opens the Model screen.
        modelRow.findViewById<TextView>(R.id.row_title).text = getString(R.string.model_row, config.model)
        modelRow.visibility = if (isDoneEnabled(config)) View.VISIBLE else View.GONE
        updateSavedState()
    }

    private fun saveModel() {
        // A key typed while the ChatGPT sign-in is selected means "use this key instead".
        if (config.apiProvider == ConfigRepository.PROVIDER_CHATGPT && keyField.text.toString().isNotBlank()) {
            config.apiProvider = ConfigRepository.PROVIDER_OPENAI
        }
        config.baseUrl = addressField.text.toString().trim().trimEnd('/')
        config.apiKey = keyField.text.toString().trim()
        val profiles = ProfileRepository(config, this)
        if (config.activeProfileName.isEmpty()) {
            profiles.ensureMigrated()
        } else {
            profiles.saveCurrentAsProfile(config.activeProfileName)
        }
        updateSavedState()
    }

    private fun saveAndTest(button: Button) {
        saveModel()
        button.isEnabled = false
        testResult.text = getString(R.string.h_model_testing)
        val client = clientFor(config)
        lifecycleScope.launch {
            val reason = testModel(client)
            testResult.text = if (reason == null) {
                getString(R.string.h_model_reachable)
            } else {
                getString(R.string.h_model_unreachable, reason)
            }
            button.isEnabled = true
        }
    }

    private fun updateSavedState() {
        privacyText.text = getString(R.string.h_privacy_sent_to, config.baseUrl)
        doneButton.isEnabled = isDoneEnabled(config)
    }

    private fun loadAllowList() {
        val dao = databaseFor(this).allowlistDao()
        lifecycleScope.launch {
            val (apps, allowed) = withContext(Dispatchers.IO) {
                messagingApps(packageManager) to dao.all().map { it.packageName }.toSet()
            }
            allowList.removeAllViews()
            if (apps.isEmpty()) {
                screen.sentence(getString(R.string.h_allowlist_empty), parent = allowList)
            }
            for ((pkg, label) in apps) {
                screen.toggleRow(label, pkg in allowed, onChange = { checked ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (checked) dao.insert(AllowlistEntity(pkg)) else dao.remove(pkg)
                    }
                }, parent = allowList)
            }
        }
    }
}
