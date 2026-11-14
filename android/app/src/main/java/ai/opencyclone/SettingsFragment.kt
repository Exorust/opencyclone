package ai.opencyclone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import ai.opencyclone.config.ConfigRepository
import ai.opencyclone.config.ProfileRepository
import ai.opencyclone.config.ProviderProfile
import ai.opencyclone.ui.ControlPanelActivity
import ai.opencyclone.ui.MemoryActivity
import ai.opencyclone.ui.theme.applySystemBarPadding

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var config: ConfigRepository
    private lateinit var profileRepo: ProfileRepository

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "opencyclone_config"
        preferenceManager.sharedPreferencesMode = android.content.Context.MODE_PRIVATE

        migrateIntKeys()
        setPreferencesFromResource(R.xml.settings_root, rootKey)
        applyRowLayouts(preferenceScreen)

        config = ConfigRepository(requireContext())
        profileRepo = ProfileRepository(config, requireContext())
        profileRepo.ensureMigrated()

        setupProfilePreference()
        setupProviderPreference()
        setupTextSummaries()
        setupApiKeyPreference()
        setupMaxStepsPreference()
        setupAutoSaveOnChange()
        setupMemoryPreference()
        setupBlacklistPreference()
        setupBackendPreferences()
        findPreference<Preference>("model_choice")?.setOnPreferenceClickListener {
            startActivity(ai.opencyclone.ui.screens.ModelSettingsActivity.intent(requireContext()))
            true
        }
        findPreference<Preference>("sign_in")?.setOnPreferenceClickListener {
            startActivity(android.content.Intent(requireContext(), ai.opencyclone.ui.FirstRunActivity::class.java))
            true
        }
        findPreference<Preference>("voice_settings")?.setOnPreferenceClickListener {
            startActivity(ai.opencyclone.ui.screens.VoiceSettingsActivity.intent(requireContext()))
            true
        }
        setupSuggestions()
        setupVersionInfo()
    }

    private fun setupSuggestions() {
        val pref = findPreference<androidx.preference.SwitchPreference>(ai.opencyclone.proactive.Suggestions.KEY) ?: return
        val suggestions = ai.opencyclone.proactive.Suggestions(requireContext())
        pref.isChecked = suggestions.enabled
        pref.setOnPreferenceChangeListener { _, value ->
            if (value == true) {
                AlertDialog.Builder(requireContext())
                    .setMessage(R.string.suggestions_confirmation)
                    .setPositiveButton(R.string.suggestions_turn_on) { _, _ ->
                        suggestions.enabled = true
                        pref.isChecked = true
                    }
                    .setNegativeButton(R.string.ui_not_now, null)
                    .show()
                false
            } else {
                suggestions.enabled = false
                true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Rows are separated by space, not lines; the last row scrolls above the navigation bar.
        setDivider(null)
        listView.clipToPadding = false
        listView.overScrollMode = View.OVER_SCROLL_NEVER
        applySystemBarPadding(view, top = false, bottom = true)
        view.post { androidx.core.view.ViewCompat.requestApplyInsets(view) }
    }

    /** Gives every row the surface_2 card layout and every group the plain title layout. */
    private fun applyRowLayouts(group: PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            pref.isIconSpaceReserved = false
            pref.isSingleLineTitle = false
            if (pref is PreferenceCategory) {
                pref.layoutResource = R.layout.settings_category
                applyRowLayouts(pref)
            } else {
                // A nested PreferenceScreen is a row here; its children are styled when it opens.
                pref.layoutResource = R.layout.settings_row
            }
        }
    }

    /** Migrate Int-stored SharedPreferences values to String so EditTextPreference doesn't crash. */
    private fun migrateIntKeys() {
        val prefs = preferenceManager.sharedPreferences ?: return
        val editor = prefs.edit()
        for (key in listOf("max_steps")) {
            try {
                val v = prefs.getInt(key, Int.MIN_VALUE)
                if (v != Int.MIN_VALUE) {
                    editor.remove(key).putString(key, v.toString())
                }
            } catch (_: ClassCastException) {
                // Already a String
            }
        }
        editor.apply()
    }

    // --- Provider auto-defaults ---

    private fun setupProviderPreference() {
        val providerPref = findPreference<ListPreference>("api_provider") ?: return
        providerPref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                val provider = newValue as String
                val modelPref = findPreference<EditTextPreference>("model")
                val urlPref = findPreference<EditTextPreference>("base_url")
                val currentModel = modelPref?.text ?: ""
                val currentUrl = urlPref?.text ?: ""
                if (ConfigRepository.isDefaultModelOrUrl(currentModel, currentUrl)) {
                    val (defModel, defUrl) = defaultsFor(provider)
                    modelPref?.text = defModel
                    urlPref?.text = defUrl
                }
                val active = config.activeProfileName
                if (active.isNotEmpty()) {
                    val curModel = modelPref?.text ?: ""
                    val curUrl = urlPref?.text ?: ""
                    profileRepo.saveProfile(ProviderProfile(active, provider, config.apiKey, curModel, curUrl))
                    refreshProfileSummary()
                }
                true
            }
    }

    private fun defaultsFor(provider: String): Pair<String, String> = when (provider) {
        ConfigRepository.PROVIDER_ANTHROPIC ->
            ConfigRepository.DEFAULT_ANTHROPIC_MODEL to ConfigRepository.DEFAULT_ANTHROPIC_BASE_URL
        ConfigRepository.PROVIDER_OLLAMA ->
            ConfigRepository.DEFAULT_OLLAMA_MODEL to ConfigRepository.DEFAULT_OLLAMA_BASE_URL
        ConfigRepository.PROVIDER_KIMI ->
            ConfigRepository.DEFAULT_KIMI_MODEL to ConfigRepository.DEFAULT_KIMI_BASE_URL
        else -> ConfigRepository.DEFAULT_MODEL to ConfigRepository.DEFAULT_BASE_URL
    }

    // --- Provider Profiles ---

    private fun setupProfilePreference() {
        // Outer PreferenceScreen on main page — just update summary
        findPreference<Preference>("subscreen_profile")?.let { refreshProfileSummary(it) }

        // Inner Preference inside sub-page — clicking shows profile dialog
        val pref = findPreference<Preference>("provider_profile") ?: return
        refreshProfileSummary(pref)
        pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            showProfileDialog()
            true
        }
    }

    private fun refreshProfileSummary(pref: Preference? = null) {
        val active = config.activeProfileName
        val summary = if (active.isNotEmpty()) {
            getString(R.string.pref_profile_summary_active, active)
        } else {
            getString(R.string.pref_profile_summary_none)
        }
        // Update whichever preference is visible in the current fragment
        (pref ?: findPreference<Preference>("provider_profile")
            ?: findPreference<Preference>("subscreen_profile"))?.let {
            it.summary = summary
        }
    }

    private fun showProfileDialog() {
        val profiles = profileRepo.listProfiles()
        val activeProfileName = config.activeProfileName

        val names = profiles.map { it.name }.toMutableList()
        val subtitles = profiles.map { "${it.provider} / ${it.model}" }.toMutableList()
        names.add(getString(R.string.profile_dialog_new))
        subtitles.add("")

        val items = names.mapIndexed { i, name ->
            if (i < profiles.size) {
                val label = if (name == activeProfileName) getString(R.string.pref_profile_in_use, name) else name
                "$label\n${subtitles[i]}"
            } else {
                name
            }
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_dialog_title)
            .setItems(items) { _, which ->
                if (which < profiles.size) {
                    val selected = profiles[which]
                    if (selected.name == activeProfileName) {
                        showProfileOptionsDialog(selected.name)
                    } else {
                        profileRepo.switchTo(selected.name)
                        refreshAllProviderFields()
                        refreshProfileSummary()
                        Toast.makeText(requireContext(),
                            getString(R.string.profile_switched, selected.name),
                            Toast.LENGTH_SHORT).show()
                    }
                } else {
                    showSaveProfileDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSaveProfileDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.profile_name_hint)
            setText(profileRepo.generateProfileName(config.apiProvider, config.model))
            selectAll()
            setPadding(60, 40, 60, 20)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.profile_save_as_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.profile_name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (profileRepo.getProfile(name) != null) {
                    Toast.makeText(requireContext(),
                        getString(R.string.profile_name_exists, name),
                        Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                profileRepo.saveCurrentAsProfile(name)
                refreshProfileSummary()
                Toast.makeText(requireContext(),
                    getString(R.string.profile_saved, name),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileOptionsDialog(profileName: String) {
        val options = arrayOf(getString(R.string.pref_profile_delete))
        AlertDialog.Builder(requireContext())
            .setTitle(profileName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        AlertDialog.Builder(requireContext())
                            .setMessage(getString(R.string.profile_delete_confirm, profileName))
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                profileRepo.deleteProfile(profileName)
                                refreshProfileSummary()
                                refreshAllProviderFields()
                                Toast.makeText(requireContext(),
                                    getString(R.string.profile_deleted, profileName),
                                    Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun refreshAllProviderFields() {
        findPreference<ListPreference>("api_provider")?.value = config.apiProvider
        findPreference<EditTextPreference>("model")?.text = config.model
        findPreference<EditTextPreference>("base_url")?.text = config.baseUrl
        findPreference<EditTextPreference>("api_key")?.text = config.apiKey
    }

    private fun setupAutoSaveOnChange() {
        for (key in listOf("api_key", "model", "base_url")) {
            val pref = findPreference<EditTextPreference>(key) ?: continue
            val existing = pref.onPreferenceChangeListener
            pref.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { p, newValue ->
                    val result = existing?.onPreferenceChange(p, newValue) ?: true
                    if (result && key == "model") config.modelChosenByHand = true
                    if (result) {
                        val active = config.activeProfileName
                        if (active.isNotEmpty()) {
                            val profile = ProviderProfile(
                                name = active,
                                provider = config.apiProvider,
                                apiKey = if (key == "api_key") newValue as String else config.apiKey,
                                model = if (key == "model") newValue as String else config.model,
                                baseUrl = if (key == "base_url") newValue as String else config.baseUrl
                            )
                            profileRepo.saveProfile(profile)
                        }
                    }
                    result
                }
        }
    }

    // --- Text summaries (SimpleSummaryProvider for plain, custom mask for secrets) ---

    private fun setupTextSummaries() {
        val simpleProvider = EditTextPreference.SimpleSummaryProvider.getInstance()

        // Plain text fields — show value as summary
        for (key in listOf("model", "base_url")) {
            findPreference<EditTextPreference>(key)?.summaryProvider = simpleProvider
        }

        // Sensitive fields — show masked value
        val maskProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            val v = pref.text
            if (v.isNullOrBlank()) getString(R.string.pref_not_set)
            else if (v.length <= 8) "••••"
            else "${v.take(4)}••••${v.takeLast(4)}"
        }
        for (key in listOf("api_key")) {
            findPreference<EditTextPreference>(key)?.summaryProvider = maskProvider
        }
    }

    // The model key lives in SecretStore (ConfigRepository): this row never writes it to the plain settings file.
    private fun setupApiKeyPreference() {
        val pref = findPreference<EditTextPreference>("api_key") ?: return
        pref.isPersistent = false
        pref.text = config.apiKey
        pref.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            config.apiKey = (newValue as? String).orEmpty()
            true
        }
    }

    // --- Max Steps: EditTextPreference writes String, ConfigRepository reads Int ---

    private fun setupMaxStepsPreference() {
        val pref = findPreference<EditTextPreference>("max_steps") ?: return
        pref.text = config.maxSteps.toString()
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            val v = it.text?.toIntOrNull() ?: ConfigRepository.DEFAULT_MAX_STEPS
            getString(R.string.pref_max_steps_summary, v)
        }

        pref.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _, newValue ->
                val input = (newValue as? String)?.trim()?.toIntOrNull()
                    ?: ConfigRepository.DEFAULT_MAX_STEPS
                val clamped = input.coerceIn(ConfigRepository.MIN_MAX_STEPS, ConfigRepository.MAX_MAX_STEPS)
                config.maxSteps = clamped
                pref.text = clamped.toString()
                false
            }
    }

    // --- Manage Memory ---

    private fun setupMemoryPreference() {
        findPreference<Preference>("manage_memory")?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                startActivity(Intent(requireContext(), MemoryActivity::class.java))
                true
            }
    }

    private fun setupBlacklistPreference() {
        val pref = findPreference<Preference>("manage_blacklist") ?: return
        updateBlacklistSummary(pref)
        pref.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            showBlacklistDialog()
            true
        }
    }

    private fun updateBlacklistSummary(pref: Preference) {
        val current = config.blacklistedPackages
        pref.summary = if (current.isEmpty()) {
            getString(R.string.pref_app_blacklist_none)
        } else {
            val pm = requireContext().packageManager
            current.take(3).mapNotNull { pkg ->
                try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                catch (_: Exception) { pkg }
            }.joinToString(", ") + if (current.size > 3) " and ${current.size - 3} more" else ""
        }
    }

    private fun showBlacklistDialog() {
        val pm = requireContext().packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchableApps = pm.queryIntentActivities(mainIntent, 0)
            .map { it.activityInfo }
            .filter { it.packageName != requireContext().packageName }
            .sortedBy { pm.getApplicationLabel(it.applicationInfo).toString().lowercase() }

        val labels = launchableApps.map { pm.getApplicationLabel(it.applicationInfo).toString() }
            .toTypedArray()
        val packages = launchableApps.map { it.packageName }
        val currentBlacklist = config.blacklistedPackages
        val checkedItems = packages.map { it in currentBlacklist }.toBooleanArray()

        android.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pref_app_blacklist))
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = packages.filterIndexed { i, _ -> checkedItems[i] }.toSet()
                config.blacklistedPackages = selected
                updateBlacklistSummary(findPreference("manage_blacklist")!!)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Backend status and revoke ---

    private fun refreshBackendStatus() {
        val statusPref = findPreference<Preference>("backend_status") ?: return
        val revokePref = findPreference<Preference>("backend_revoke") ?: return

        val backendName = App.agentService?.activeBackendName
        statusPref.summary = when (backendName) {
            "system"     -> getString(R.string.pref_backend_system)
            "shizuku"    -> getString(R.string.pref_backend_shizuku)
            "adb-direct" -> getString(R.string.pref_backend_adb)
            null, "none" -> getString(R.string.pref_backend_none)
            else         -> backendName
        }
        revokePref.isVisible = (backendName != null && backendName != "none")
    }

    private fun setupBackendPreferences() {
        val statusPref = findPreference<Preference>("backend_status") ?: return
        val revokePref = findPreference<Preference>("backend_revoke") ?: return

        refreshBackendStatus()

        statusPref.setOnPreferenceClickListener {
            val name = App.agentService?.activeBackendName
            if (name == "system") {
                Toast.makeText(requireContext(), R.string.pref_backend_system_fixed, Toast.LENGTH_SHORT).show()
                return@setOnPreferenceClickListener true
            }
            val intent = android.content.Intent(requireContext(),
                ai.opencyclone.backend.SetupActivity::class.java)
            if (name != null && name != "none" && name != "system") {
                intent.putExtra(
                    ai.opencyclone.backend.SetupActivity.EXTRA_FORCE_METHOD_CHOOSER,
                    true
                )
            }
            startActivity(intent)
            true
        }

        revokePref.setOnPreferenceClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_revoke_dialog_title)
                .setMessage(R.string.pref_revoke_dialog_message)
                .setPositiveButton(R.string.pref_revoke_title) { _, _ ->
                    App.agentService?.revokeBackend()
                    refreshBackendStatus()
                    Toast.makeText(requireContext(), R.string.pref_revoke_done, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    // --- Version info ---

    private fun setupVersionInfo() {
        val pref = findPreference<Preference>("version_info") ?: return
        try {
            val info = requireContext().packageManager.getPackageInfo(
                requireContext().packageName, 0
            )
            pref.summary = info.versionName ?: "1.0"
        } catch (_: Exception) {
            pref.summary = "1.0"
        }
    }

    override fun onResume() {
        super.onResume()
        findPreference<Preference>("model_choice")?.summary = config.model
        findPreference<androidx.preference.SwitchPreference>(ai.opencyclone.proactive.Suggestions.KEY)?.isChecked =
            ai.opencyclone.proactive.Suggestions(requireContext()).enabled
        refreshBackendStatus()
    }
}

/**
 * The "History" row in settings. It has no key (the settings keys are fixed); a tap opens the history page of the home page.
 */
class HistoryPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {
    override fun onClick() {
        context.startActivity(ControlPanelActivity.historyIntent(context))
    }
}
