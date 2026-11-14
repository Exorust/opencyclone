package ai.opencyclone.ui.voice

import ai.opencyclone.voice.AndroidSpeechInputEngine
import ai.opencyclone.voice.FallbackSpeechInputEngine
import ai.opencyclone.voice.ServiceSpeechInputEngine
import ai.opencyclone.voice.SherpaModels
import ai.opencyclone.voice.SherpaOnnxSpeechInputEngine
import ai.opencyclone.voice.SpeechInputEngine
import ai.opencyclone.voice.VoiceServiceStore
import android.content.Context

/** Creates the recognizer and the speech output for [ListeningActivity]. Tests replace the two functions. */
object VoiceFactory {
    /** Preference `voice_engine`: Android's recognizer (default), the model on this phone, or a service. */
    const val ANDROID = "android"
    const val PHONE_MODEL = "phone_model"
    const val SERVICE = "service"
    val CHOICES = listOf(ANDROID, PHONE_MODEL, SERVICE)
    private const val PREFS = "voice"
    private const val KEY_ENGINE = "voice_engine"

    private val defaultEngine: (Context, (Long, Long) -> Unit) -> SpeechInputEngine = { context, progress ->
        val choice = choice(context)
        chain(
            choice,
            android = { AndroidSpeechInputEngine(context) },
            // After the service, the phone model is used only when it is already installed: no download.
            phoneModel = { SherpaOnnxSpeechInputEngine(context, downloadProgressListener = progress, allowDownload = choice != SERVICE) },
            service = { service(context) },
        )
    }
    private val defaultSpeechOut: (Context) -> SpeechOut? = { SpeechOut(it) }

    /** The recognizer; the second argument receives model download progress (bytes read, total bytes). */
    @Volatile var engine: (Context, (Long, Long) -> Unit) -> SpeechInputEngine = defaultEngine

    /** The speech output, or null when there is none. */
    @Volatile var speechOut: (Context) -> SpeechOut? = defaultSpeechOut

    fun reset() {
        engine = defaultEngine
        speechOut = defaultSpeechOut
    }

    fun choice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENGINE, ANDROID)
            ?.takeIf { it in CHOICES } ?: ANDROID

    /** Returns false for a value other than [ANDROID], [PHONE_MODEL], or [SERVICE]. */
    fun setChoice(context: Context, value: String): Boolean {
        if (value !in CHOICES) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ENGINE, value).apply()
        return true
    }

    /**
     * The engines a choice may use, in order. The service is never a fallback: it appears only when it is
     * the choice, so with [ANDROID] or [PHONE_MODEL] no audio can leave the phone.
     */
    fun order(choice: String): List<String> = when (choice) {
        PHONE_MODEL -> listOf(PHONE_MODEL, ANDROID)
        SERVICE -> listOf(SERVICE, PHONE_MODEL, ANDROID)
        else -> listOf(ANDROID, PHONE_MODEL)
    }

    /** The chosen engine first, the others as fallbacks for a turn the first cannot take ([order]). */
    fun chain(
        choice: String,
        android: () -> SpeechInputEngine,
        phoneModel: () -> SpeechInputEngine,
        service: () -> SpeechInputEngine = { error("the service is not configured") },
    ): FallbackSpeechInputEngine {
        val names = order(choice)
        val makers = names.map { name ->
            when (name) {
                ANDROID -> android
                PHONE_MODEL -> phoneModel
                else -> service
            }
        }
        return FallbackSpeechInputEngine(names, makers)
    }

    /**
     * The internet recognizer with its stored settings. When a model is installed on this phone, it turns
     * the recording into words if the service cannot be reached after the owner spoke.
     */
    private fun service(context: Context): SpeechInputEngine {
        val config = VoiceServiceStore(context).load()
        val model = SherpaModels.selected(context).takeIf { it.installed(context.filesDir) }
            ?: SherpaModels.ALL.firstOrNull { it.installed(context.filesDir) }
        val local: ((ShortArray) -> String)? = model?.let { m ->
            { samples ->
                val engine = SherpaOnnxSpeechInputEngine(context, model = m, allowDownload = false)
                try { engine.transcribe(samples) } finally { engine.destroy() }
            }
        }
        if (!config.usable(VoiceServiceStore.debugBuild(context))) {
            // Not set up (or an address that is not allowed): the turn goes to the phone, nothing is sent.
            return object : SpeechInputEngine {
                override fun startListening(listener: SpeechInputEngine.Listener) =
                    listener.onUnavailable("${ai.opencyclone.voice.VoiceErrors.SERVICE_UNREACHABLE}: not set up")
                override fun stopListening() = Unit
                override fun destroy() = Unit
            }
        }
        return ServiceSpeechInputEngine(context, config, localTranscriber = local)
    }
}
