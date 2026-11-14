package ai.opencyclone.ui.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Speech output through the platform [TextToSpeech], an offline voice preferred. Every callback of
 * [Listener] arrives on the main thread. Open so that tests can replace it without a speech engine.
 */
open class SpeechOut(context: Context) {

    interface Listener {
        /** The engine started to speak. */
        fun onStart()
        /** The engine is about to say the characters from [charStart]. Not every engine calls this. */
        fun onRange(charStart: Int)
        /** The speech ended or was stopped. */
        fun onDone()
        /** No speech engine or no English voice: nothing will be spoken. */
        fun onUnavailable()
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: Pair<String, Listener>? = null
    private var listener: Listener? = null

    /** The owner's speech rate from the system settings, 1.0 when unset. */
    open val rate: Float
        get() = Settings.Secure.getInt(appContext.contentResolver, "tts_default_rate", 100) / 100f

    open fun speak(text: String, listener: Listener) {
        this.listener = listener
        when {
            failed -> listener.onUnavailable()
            ready -> speakNow(text)
            else -> {
                pending = text to listener
                if (tts == null) tts = TextToSpeech(appContext) { status -> main.post { onInit(status) } }
            }
        }
    }

    /** Stops the speech; the listener gets [Listener.onDone]. */
    open fun stop() {
        pending = null
        runCatching { tts?.stop() }
    }

    open fun shutdown() {
        pending = null
        listener = null
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun onInit(status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null || !chooseVoice(engine)) {
            failed = true
            pending?.second?.onUnavailable()
            pending = null
            return
        }
        ready = true
        engine.setOnUtteranceProgressListener(progress)
        pending?.let { (text, _) -> speakNow(text) }
        pending = null
    }

    private fun speakNow(text: String) {
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (result != TextToSpeech.SUCCESS) listener?.onUnavailable()
    }

    /** English only (UI.md section 9, item 10). An offline voice when one is installed. */
    private fun chooseVoice(engine: TextToSpeech): Boolean {
        val lang = engine.setLanguage(Locale.US)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) return false
        val current = runCatching { engine.voice }.getOrNull()
        if (current != null && !current.isNetworkConnectionRequired) return true
        val offline = runCatching { engine.voices }.getOrNull().orEmpty().let(::pickOfflineVoice)
        if (offline != null) runCatching { engine.voice = offline }
        return true
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { main.post { listener?.onStart() } }
        override fun onDone(utteranceId: String?) { main.post { listener?.onDone() } }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { listener?.onDone() } }
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            main.post { listener?.onRange(start) }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Log.w(TAG, "Speech output failed")
            main.post { listener?.onDone() }
        }
    }

    companion object {
        private const val TAG = "SpeechOut"
        private const val UTTERANCE_ID = "voice_question"

        /** The best installed English voice that needs no network, or null. */
        fun pickOfflineVoice(voices: Collection<Voice>): Voice? =
            voices.filter {
                !it.isNetworkConnectionRequired &&
                    it.locale.language == Locale.ENGLISH.language &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }.maxWithOrNull(compareBy<Voice>({ it.locale.country == "US" }, { it.quality }, { -it.latency }))
    }
}
