package ai.opencyclone.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * The phone's own speech recognizer ([SpeechRecognizer]). On-device recognition when the phone has it;
 * otherwise an explicit recognition service of another app. Never the default service: while this app holds
 * the assistant role the default is our own stub, which refuses every request. Every call runs on the main thread.
 */
class AndroidSpeechInputEngine(context: Context) : SpeechInputEngine {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false
    private var listener: SpeechInputEngine.Listener? = null
    private var turnOver = false
    private var destroyed = false

    override fun startListening(listener: SpeechInputEngine.Listener) = onMain {
        if (destroyed) return@onMain
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError(VoiceErrors.NO_PERMISSION)
            return@onMain
        }
        this.listener = listener
        turnOver = false
        val r = recognizer ?: create()?.also { recognizer = it }
        if (r == null) {
            listener.onUnavailable("no speech recognizer on this phone")
            return@onMain
        }
        r.setRecognitionListener(callbacks)
        try {
            r.startListening(recognizeIntent())
            Log.i(TAG, "Listening started (${if (onDevice) "on-device" else "service"})")
        } catch (t: Throwable) {
            Log.e(TAG, "startListening failed", t)
            listener.onUnavailable("startListening failed: ${t.message}")
        }
    }

    override fun stopListening() = onMain {
        turnOver = true
        recognizer?.cancel()
    }

    /** The platform stops listening and delivers what it heard as the final result. */
    override fun finishNow() = onMain {
        if (!turnOver) recognizer?.stopListening()
    }

    override fun destroy() = onMain {
        destroyed = true
        turnOver = true
        recognizer?.destroy()
        recognizer = null
        listener = null
    }

    private fun create(): SpeechRecognizer? = try {
        when (val choice = choose(appContext)) {
            Choice.OnDevice -> {
                onDevice = true
                Log.i(TAG, "Recognizer: on-device")
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            }
            is Choice.Service -> {
                onDevice = false
                Log.i(TAG, "Recognizer: service ${choice.component.flattenToShortString()}")
                SpeechRecognizer.createSpeechRecognizer(appContext, choice.component)
            }
            Choice.None -> {
                Log.w(TAG, "Recognizer: none available")
                null
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Recognizer creation failed", t)
        null
    }

    private val callbacks = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "Ready for speech")
            if (!turnOver) listener?.onReady()
        }

        override fun onBeginningOfSpeech() { Log.i(TAG, "Beginning of speech") }
        override fun onRmsChanged(rmsdB: Float) {
            if (!turnOver) listener?.onLevel(MicLevel.fromDecibels(rmsdB))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { Log.i(TAG, "Pause detected (end of speech)") }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (!turnOver && text.isNotEmpty()) listener?.onPartialResult(text)
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            Log.i(TAG, "Final result: ${text.length} characters")
            if (turnOver) return
            turnOver = true
            if (text.isNotEmpty()) listener?.onFinalResult(text) else listener?.onEnd()
        }

        override fun onError(error: Int) {
            val action = errorAction(error, onDevice)
            Log.w(TAG, "Recognizer error $error -> $action")
            if (turnOver) return
            turnOver = true
            val l = listener ?: return
            when (action) {
                ErrorAction.HEARD_NOTHING -> l.onEnd()
                ErrorAction.NO_PERMISSION -> l.onError(VoiceErrors.NO_PERMISSION)
                ErrorAction.MIC_BUSY -> l.onError(VoiceErrors.MIC_BUSY)
                ErrorAction.COULD_NOT_LISTEN -> l.onError(VoiceErrors.FAILED)
                ErrorAction.LANGUAGE_MISSING -> {
                    triggerLanguageDownload()
                    l.onUnavailable("language not available on-device (error $error)")
                }
                ErrorAction.UNAVAILABLE -> l.onUnavailable("recognizer error $error")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** Asks the on-device recognizer to fetch the phone's language for next time (API 33+), once per process. */
    private fun triggerLanguageDownload() {
        if (downloadTriggered || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        downloadTriggered = true
        try {
            recognizer?.triggerModelDownload(recognizeIntent())
            Log.i(TAG, "On-device language download requested")
        } catch (t: Throwable) {
            Log.w(TAG, "On-device language download could not start: ${t.message}")
        }
    }

    private fun recognizeIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    enum class ErrorAction { HEARD_NOTHING, NO_PERMISSION, MIC_BUSY, COULD_NOT_LISTEN, LANGUAGE_MISSING, UNAVAILABLE }

    sealed class Choice {
        object OnDevice : Choice()
        data class Service(val component: ComponentName) : Choice()
        object None : Choice()
    }

    /** One installed recognition service. */
    data class Candidate(val packageName: String, val className: String, val system: Boolean)

    companion object {
        const val TAG = "VoiceEngine"

        @Volatile private var downloadTriggered = false

        /** What a [SpeechRecognizer] error code means for the listening screen. */
        fun errorAction(code: Int, onDevice: Boolean): ErrorAction = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ErrorAction.HEARD_NOTHING
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> ErrorAction.NO_PERMISSION
            SpeechRecognizer.ERROR_AUDIO -> ErrorAction.MIC_BUSY
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> ErrorAction.COULD_NOT_LISTEN
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                if (onDevice) ErrorAction.LANGUAGE_MISSING else ErrorAction.UNAVAILABLE
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT, SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS ->
                ErrorAction.UNAVAILABLE
            else -> ErrorAction.COULD_NOT_LISTEN
        }

        /** A recognition service of another app, system apps first; null when there is none. */
        fun pickService(candidates: List<Candidate>, ownPackage: String): Candidate? =
            candidates.filter { it.packageName != ownPackage }
                .sortedByDescending { it.system }
                .firstOrNull()

        /** Installed recognition services (needs the `<queries>` entry for [RecognitionService.SERVICE_INTERFACE]). */
        fun services(context: Context): List<Candidate> =
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).mapNotNull { info ->
                val s = info.serviceInfo ?: return@mapNotNull null
                Candidate(s.packageName, s.name, (s.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
            }

        fun onDeviceAvailable(context: Context): Boolean =
            try { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) } catch (_: Throwable) { false }

        fun choose(context: Context): Choice {
            if (onDeviceAvailable(context)) return Choice.OnDevice
            return pickService(services(context), context.packageName)
                ?.let { Choice.Service(ComponentName(it.packageName, it.className)) } ?: Choice.None
        }

        /** Whether this engine can listen at all on this phone. */
        fun available(context: Context): Boolean = choose(context) != Choice.None

        private fun firstResult(bundle: Bundle?): String =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
    }
}
