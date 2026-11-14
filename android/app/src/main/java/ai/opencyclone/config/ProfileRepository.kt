package ai.opencyclone.config

import android.content.Context
import androidx.annotation.MainThread

/** Saved provider profiles. Each profile's key lives in [SecretStore]; the profile list holds no key. */
class ProfileRepository(
    private val config: ConfigRepository,
    context: Context,
    private val secrets: SecretStore = SecretStore(context),
) {

    companion object {
        private const val PREFS_NAME = "opencyclone_profiles"
        private const val KEY_PROFILES = "profiles_json"
        fun secretName(profileName: String) = "profile_api_key:$profileName"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    constructor(context: Context) : this(ConfigRepository(context), context)

    fun listProfiles(): List<ProviderProfile> {
        val json = prefs.getString(KEY_PROFILES, "") ?: ""
        val stored = ProviderProfile.fromJsonArray(json)
        // One-time move of keys saved in plain text inside the list.
        if (stored.any { it.apiKey.isNotEmpty() }) persist(stored)
        return stored.map { it.copy(apiKey = secrets.get(secretName(it.name)) ?: "") }
    }

    fun getProfile(name: String): ProviderProfile? =
        listProfiles().find { it.name == name }

    fun saveCurrentAsProfile(name: String) {
        val profile = ProviderProfile(
            name = name,
            provider = config.apiProvider,
            apiKey = config.apiKey,
            model = config.model,
            baseUrl = config.baseUrl
        )
        saveProfile(profile)
        config.activeProfileName = name
    }

    fun saveProfile(profile: ProviderProfile) {
        val profiles = listProfiles().toMutableList()
        val idx = profiles.indexOfFirst { it.name == profile.name }
        if (idx >= 0) profiles[idx] = profile else profiles.add(profile)
        persist(profiles)
    }

    @MainThread
    fun switchTo(name: String): Boolean {
        val target = getProfile(name) ?: return false
        val currentName = config.activeProfileName
        if (currentName.isNotEmpty()) {
            saveCurrentAsProfile(currentName)
        }
        config.applyProfile(target.provider, target.apiKey, target.model, target.baseUrl, name)
        return true
    }

    fun deleteProfile(name: String) {
        val profiles = listProfiles().toMutableList()
        profiles.removeAll { it.name == name }
        secrets.remove(secretName(name))
        persist(profiles)
        if (config.activeProfileName == name) {
            if (profiles.isNotEmpty()) {
                switchTo(profiles.first().name)
            } else {
                config.activeProfileName = ""
                ensureMigrated()
            }
        }
    }

    fun generateProfileName(provider: String, model: String): String {
        val base = "${provider}/${model}".take(30)
        val existing = listProfiles().map { it.name }.toSet()
        if (base !in existing) return base
        var i = 2
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }

    fun ensureMigrated() {
        if (config.activeProfileName.isEmpty()) {
            val name = generateProfileName(config.apiProvider, config.model)
            saveCurrentAsProfile(name)
        }
        addDefaultProfiles()
    }

    private fun addDefaultProfiles() {
        if (getProfile("ollama") != null) return
        saveProfile(ProviderProfile(
            name = "ollama",
            provider = ConfigRepository.PROVIDER_OLLAMA,
            apiKey = "",
            model = ConfigRepository.DEFAULT_OLLAMA_MODEL,
            baseUrl = ConfigRepository.DEFAULT_OLLAMA_BASE_URL
        ))
    }

    private fun persist(profiles: List<ProviderProfile>) {
        profiles.forEach { secrets.put(secretName(it.name), it.apiKey) }
        prefs.edit().putString(KEY_PROFILES, ProviderProfile.toJsonArray(profiles.map { it.copy(apiKey = "") })).commit()
    }
}
