package ai.opencyclon.config

import android.content.Context
import android.content.SharedPreferences

class ConfigRepository(
    private val context: Context,
    private val secrets: SecretStore = SecretStore(context),
) {

    /** For checks such as "is the ChatGPT sign-in saved", which read the token store. */
    val appContext: Context get() = context.applicationContext ?: context

    companion object {
        private const val PREFS_NAME = "opencyclon_config"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_API_PROVIDER = "api_provider"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_ACTIVE_PROFILE = "active_profile"
        private const val KEY_BLACKLISTED_PACKAGES = "blacklisted_packages"
        /** Set once the stored step limit has been checked against the old default of 100. */
        private const val KEY_MAX_STEPS_CHECKED = "max_steps_v30"
        /** Set once the stored ChatGPT model has been checked against [OLD_CHATGPT_MODEL]. */
        private const val KEY_CHATGPT_MODEL_CHECKED = "chatgpt_model_v56"
        private const val KEY_MODEL_BY_HAND = "model_by_hand"
        private const val KEY_MODEL_SWITCH_NOTICE = "model_switch_notice"
        /** Name of the model key in [SecretStore]. */
        const val SECRET_API_KEY = "api_key"

        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_ANTHROPIC = "anthropic"
        const val PROVIDER_OLLAMA = "ollama"
        const val PROVIDER_KIMI = "kimi"
        /** ChatGPT subscription through the Codex backend, signed in with OAuth (no key). */
        const val PROVIDER_CHATGPT = "chatgpt"
        const val DEFAULT_CHATGPT_MODEL = "gpt-5.6-terra"
        /** The ChatGPT default before 2026-11-12; ChatGPT accounts now refuse it. A stored copy is replaced once. */
        const val OLD_CHATGPT_MODEL = "gpt-5.5"
        /** Tried in this order, once each, when the ChatGPT default is refused (see ModelClients). */
        val CHATGPT_FALLBACK = listOf("gpt-5.6-terra", "gpt-5.6-sol", "gpt-6.1-terra")
        const val DEFAULT_CHATGPT_BASE_URL = "https://chatgpt.com/backend-api/codex"
        const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_OPENROUTER_MODEL = "google/gemini-3.8-flash"
        const val DEFAULT_MODEL = "gpt-5.5"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-7-20250415"
        const val DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com"
        const val DEFAULT_OLLAMA_MODEL = "gemma4:26b-a4b-it-q4_K_M"
        const val DEFAULT_OLLAMA_BASE_URL = "https://localhost:11434"
        const val DEFAULT_KIMI_MODEL = "kimi-k2.6"
        const val DEFAULT_KIMI_BASE_URL = "https://api.moonshot.cn/v1"
        const val DEFAULT_MAX_STEPS = 30
        private const val OLD_DEFAULT_MAX_STEPS = 100
        const val MIN_MAX_STEPS = 5
        const val MAX_MAX_STEPS = 100

        /** Returns true if model or url matches any known provider default.
         *  SettingsActivity uses this to decide whether to auto-fill defaults when
         *  the provider spinner changes. */
        fun isDefaultModelOrUrl(model: String, url: String): Boolean =
            model in listOf(DEFAULT_MODEL, DEFAULT_ANTHROPIC_MODEL, DEFAULT_OLLAMA_MODEL, DEFAULT_KIMI_MODEL) ||
            url in listOf(DEFAULT_BASE_URL, DEFAULT_ANTHROPIC_BASE_URL, DEFAULT_OLLAMA_BASE_URL, DEFAULT_KIMI_BASE_URL)
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // One-time move of the plain-text model key into the encrypted store.
        if (prefs.contains(KEY_API_KEY)) {
            val plain = prefs.getString(KEY_API_KEY, "") ?: ""
            if (plain.isEmpty() || secrets.put(SECRET_API_KEY, plain)) {
                prefs.edit().remove(KEY_API_KEY).commit()
            }
        }
    }

    /** Migrate a preference stored as Int to String (for EditTextPreference compat). */
    private fun migrateIntToString(key: String, default: Int): Int {
        val value = prefs.getInt(key, default)
        prefs.edit().remove(key).putString(key, value.toString()).apply()
        return value
    }

    var activeProfileName: String
        get() = prefs.getString(KEY_ACTIVE_PROFILE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE, value).apply()

    fun applyProfile(provider: String, apiKey: String, model: String, baseUrl: String, profileName: String) {
        secrets.put(SECRET_API_KEY, apiKey)
        val current = if (provider == PROVIDER_CHATGPT && model == OLD_CHATGPT_MODEL) DEFAULT_CHATGPT_MODEL else model
        prefs.edit()
            .putString(KEY_API_PROVIDER, provider)
            .putString(KEY_MODEL, current)
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_ACTIVE_PROFILE, profileName)
            .apply()
    }

    var apiProvider: String
        get() = prefs.getString(KEY_API_PROVIDER, PROVIDER_OPENAI) ?: PROVIDER_OPENAI
        set(value) = prefs.edit().putString(KEY_API_PROVIDER, value).apply()

    var apiKey: String
        get() = secrets.get(SECRET_API_KEY) ?: ""
        set(value) { secrets.put(SECRET_API_KEY, value) }

    /** The model name. For the ChatGPT sign-in, a stored [OLD_CHATGPT_MODEL] is read once as [DEFAULT_CHATGPT_MODEL]. */
    var model: String
        get() {
            val stored = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
            if (apiProvider != PROVIDER_CHATGPT || prefs.getBoolean(KEY_CHATGPT_MODEL_CHECKED, false)) return stored
            val value = if (stored == OLD_CHATGPT_MODEL) DEFAULT_CHATGPT_MODEL else stored
            prefs.edit().putString(KEY_MODEL, value).putBoolean(KEY_CHATGPT_MODEL_CHECKED, true).apply()
            return value
        }
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /** True when the owner chose the model by hand; the ChatGPT default may then not be replaced automatically. */
    var modelChosenByHand: Boolean
        get() = prefs.getBoolean(KEY_MODEL_BY_HAND, false)
        set(value) = prefs.edit().putBoolean(KEY_MODEL_BY_HAND, value).apply()

    /** The owner chose [name]: saves it, marks it as chosen by hand, and keeps the active profile in step. */
    fun chooseModel(name: String) {
        prefs.edit().putString(KEY_MODEL, name.trim()).putBoolean(KEY_MODEL_BY_HAND, true).apply()
        if (activeProfileName.isNotEmpty()) ProfileRepository(this, context, secrets).saveCurrentAsProfile(activeProfileName)
    }

    /**
     * "I switched to <name>, because this account cannot use <old>.", left by the automatic recovery for the
     * home page to say once. [takeModelSwitchNotice] reads and clears it.
     */
    var modelSwitchNotice: String?
        get() = prefs.getString(KEY_MODEL_SWITCH_NOTICE, null)
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_MODEL_SWITCH_NOTICE) else putString(KEY_MODEL_SWITCH_NOTICE, value) }.apply()

    fun takeModelSwitchNotice(): String? = modelSwitchNotice?.also { modelSwitchNotice = null }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    /** Steps one task may take, 5 to 100. A stored 100 from before the limit became 30 is read once as 30. */
    var maxSteps: Int
        get() {
            val stored = try {
                prefs.getString(KEY_MAX_STEPS, null)?.toIntOrNull()
                    ?: migrateIntToString(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
            } catch (_: ClassCastException) {
                migrateIntToString(KEY_MAX_STEPS, DEFAULT_MAX_STEPS)
            }
            if (!prefs.getBoolean(KEY_MAX_STEPS_CHECKED, false)) {
                val value = if (stored == OLD_DEFAULT_MAX_STEPS) DEFAULT_MAX_STEPS else stored
                prefs.edit().putString(KEY_MAX_STEPS, value.toString()).putBoolean(KEY_MAX_STEPS_CHECKED, true).apply()
                return value.coerceIn(MIN_MAX_STEPS, MAX_MAX_STEPS)
            }
            return stored.coerceIn(MIN_MAX_STEPS, MAX_MAX_STEPS)
        }
        set(value) = prefs.edit()
            .putString(KEY_MAX_STEPS, value.coerceIn(MIN_MAX_STEPS, MAX_MAX_STEPS).toString())
            .putBoolean(KEY_MAX_STEPS_CHECKED, true)
            .apply()

    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()

    var blacklistedPackages: Set<String>
        get() = prefs.getString(KEY_BLACKLISTED_PACKAGES, "")!!
            .split(",").filter { it.isNotBlank() }.toSet()
        set(value) = prefs.edit().putString(KEY_BLACKLISTED_PACKAGES, value.joinToString(",")).apply()
}
