package ai.opencyclone.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

/**
 * The recognizer inside the app. [model] is the catalogue entry in use; with [allowDownload] false a missing
 * model makes the engine unavailable instead of starting a download (the fallback after the internet service).
 */
class SherpaOnnxSpeechInputEngine(
    context: Context,
    private val numThreads: Int = DEFAULT_NUM_THREADS,
    private val downloadProgressListener: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    val model: SherpaModel = SherpaModels.selected(context),
    private val allowDownload: Boolean = true,
) : SpeechInputEngine {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var captureJob: Job? = null

    @Volatile
    private var shouldRecord = false

    @Volatile
    private var destroyed = false

    /** Set by [finishNow]: the sample loop ends the turn with the current hypothesis. */
    @Volatile
    private var finishRequested = false

    private var audioRecord: AudioRecord? = null
    private var recognizer: OnlineRecognizer? = null

    override fun startListening(listener: SpeechInputEngine.Listener) {
        if (destroyed) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError(VoiceErrors.NO_PERMISSION)
            return
        }

        synchronized(lock) {
            if (captureJob?.isActive == true) return
            shouldRecord = true
            finishRequested = false
            captureJob = scope.launch {
                runRecognition(listener)
            }
        }
    }

    override fun stopListening() {
        shouldRecord = false
        synchronized(lock) {
            MicRecorder.stop(audioRecord)
            captureJob?.cancel()
            captureJob = null
        }
    }

    override fun finishNow() {
        if (captureJob?.isActive == true) finishRequested = true
    }

    override fun destroy() {
        destroyed = true
        stopListening()
        scope.cancel()
        synchronized(lock) {
            recognizer?.release()
            recognizer = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun runRecognition(listener: SpeechInputEngine.Listener) {
        var stream: OnlineStream? = null
        var reason = "stopped"
        try {
            // With the model on the phone, the microphone opens first: the owner talks as soon as the screen
            // says "Listening", and the first second (creating the recognizer) waits in the recorder's buffer.
            // Without the model, the microphone opens only after the download.
            loadNativeLibraries()
            val recorder: AudioRecord
            val recognizer: OnlineRecognizer
            if (recognizerReady() || model.installed(appContext.filesDir)) {
                recorder = openMicrophone(listener) ?: return
                recognizer = recognizer(allowDownload)
            } else {
                recognizer = recognizer(allowDownload)
                if (!shouldRecord) return
                recorder = openMicrophone(listener) ?: return
            }
            if (!shouldRecord) return
            stream = recognizer.createStream("")

            val shortBuffer = ShortArray(AUDIO_READ_SIZE)
            val turn = TurnLog()

            while (shouldRecord) {
                if (finishRequested) {
                    // Manual end ("Stop", a volume key): flush what was heard and end with it.
                    reason = "finish requested"
                    endTurn(normalize(flush(recognizer, stream)), listener, reason)
                    break
                }
                val read = MicRecorder.read(recorder, shortBuffer)
                if (read == 0) continue

                val samples = toFloats(shortBuffer, read)
                val level = MicLevel.rms(samples, read)
                post { listener.onLevel(level) }

                val partial = normalize(feed(recognizer, stream, samples))
                val step = turn.block(level, partial, recognizer.isEndpoint(stream))
                if (step.partialChanged) post { listener.onPartialResult(partial) }
                if (step.endpoint) {
                    reason = if (partial.isEmpty()) "pause, nothing recognized" else "pause"
                    endTurn(partial, listener, reason)
                    break
                }
            }
        } catch (t: Throwable) {
            reason = "error"
            Log.e(TAG, "Speech recognition failed (shouldRecord=$shouldRecord)", t)
            if (shouldRecord) {
                if (t.isUnavailable()) post { listener.onUnavailable(t.message.orEmpty()) }
                else post { listener.onError(errorMessage(t)) }
            }
        } finally {
            Log.i(TAG, "Listening ended: $reason")
            shouldRecord = false
            stream?.release()
            synchronized(lock) {
                MicRecorder.release(audioRecord)
                audioRecord = null
                captureJob = null
            }
        }
    }

    /** Creates and starts the recorder; null when listening was stopped meanwhile. */
    private fun openMicrophone(listener: SpeechInputEngine.Listener): AudioRecord? {
        if (!shouldRecord) return null
        val recorder = MicRecorder.create()
        synchronized(lock) {
            audioRecord = recorder
        }
        if (!shouldRecord) return null
        MicRecorder.start(recorder)
        Log.i(TAG, "AudioRecord started: rate=${recorder.sampleRate} source=${recorder.audioSource}")
        post { listener.onReady() }
        return recorder
    }

    /**
     * Turns recorded 16 kHz samples into text without the microphone: the fallback when the internet service
     * cannot be reached after the owner spoke. Never downloads. Blocking; call it off the main thread.
     */
    fun transcribe(samples: ShortArray): String {
        val recognizer = recognizer(allowDownload = false)
        val stream = recognizer.createStream("")
        try {
            var offset = 0
            while (offset < samples.size) {
                val n = minOf(AUDIO_READ_SIZE, samples.size - offset)
                feed(recognizer, stream, toFloats(samples.copyOfRange(offset, offset + n), n))
                offset += n
            }
            return normalize(flush(recognizer, stream))
        } finally {
            stream.release()
        }
    }

    private fun recognizerReady(): Boolean = synchronized(lock) { recognizer != null }

    private fun endTurn(finalText: String, listener: SpeechInputEngine.Listener, reason: String) {
        Log.i(TAG, "Final result: ${finalText.length} characters ($reason)")
        shouldRecord = false
        finishRequested = false
        if (finalText.isNotEmpty()) {
            post { listener.onFinalResult(finalText) }
        } else {
            post { listener.onEnd() }
        }
    }

    /**
     * The recognizer, created once: packaged libraries, model files (downloaded when [allowDownload] and
     * missing), then the native recognizer. Listening and the debug self-test both come through here.
     */
    internal fun recognizer(allowDownload: Boolean = true): OnlineRecognizer {
        synchronized(lock) {
            recognizer?.let { return it }
        }

        // Packaged libraries first: a phone without them fails here, before a 64 MB download.
        loadNativeLibraries()
        val paths = ensureModelFiles(allowDownload)
        val config = recognizerConfig(paths, numThreads)
        val started = System.currentTimeMillis()
        val created = try {
            OnlineRecognizer(config = config)
        } catch (t: Throwable) {
            Log.e(TAG, "Recognizer creation failed", t)
            throw t
        }
        Log.i(TAG, "Recognizer created in ${System.currentTimeMillis() - started} ms")

        synchronized(lock) {
            recognizer?.let {
                created.release()
                return it
            }
            if (destroyed) {
                created.release()
                throw IOException("Speech recognizer has been destroyed")
            }
            recognizer = created
            return created
        }
    }

    private fun ensureModelFiles(allowDownload: Boolean): SherpaOnnxModelFiles.Paths {
        val paths = model.paths(appContext.filesDir)
        // Native libraries an older version downloaded next to the model are never loaded; remove them.
        paths.modelDir.listFiles { f -> f.name.endsWith(".so") }?.forEach { it.delete() }
        val present = model.installed(appContext.filesDir)
        Log.i(TAG, "Model check: ${if (present) "all files present" else "files missing"} in ${paths.modelDir}")
        if (present) return paths
        // The message carries VoiceErrors.UNAVAILABLE, so a turn without a download falls back to the next engine.
        if (!allowDownload) throw IOException("${VoiceErrors.UNAVAILABLE}: speech model files are missing")

        AsrModelDownloader.download(
            model = model,
            baseDir = appContext.filesDir,
            listener = downloadProgressListener?.let { callback ->
                AsrModelDownloader.ProgressListener { bytesRead, totalBytes ->
                    mainHandler.post { callback(bytesRead, totalBytes) }
                }
            }
        )

        return paths
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun Throwable.isUnavailable(): Boolean =
        generateSequence(this) { it.cause }.any { it is UnsatisfiedLinkError } ||
            message.orEmpty().contains(VoiceErrors.UNAVAILABLE, ignoreCase = true)

    internal companion object {
        internal const val TAG = "SherpaSpeechInput"
        const val SAMPLE_RATE = MicRecorder.SAMPLE_RATE
        private const val FEATURE_DIM = 80
        private const val DEFAULT_NUM_THREADS = 1
        internal const val AUDIO_READ_SIZE = MicRecorder.READ_SIZE
        const val MODEL_TYPE = "zipformer"

        @Volatile
        private var nativeLibrariesLoaded = false

        /** Loads the recognizer libraries packaged in the app. Remote native code is never loaded. */
        internal fun loadNativeLibraries() {
            if (nativeLibrariesLoaded) return
            synchronized(this) {
                if (nativeLibrariesLoaded) return
                try {
                    SherpaOnnxModelFiles.nativeLibs.forEach { System.loadLibrary(it) }
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Native library load failed", e)
                    throw IOException(VoiceErrors.UNAVAILABLE, e)
                }
                Log.i(TAG, "Native libraries loaded: ${SherpaOnnxModelFiles.nativeLibs}")
                nativeLibrariesLoaded = true
            }
        }

        /** One of [VoiceErrors] for a failure while listening. */
        internal fun errorMessage(t: Throwable): String {
            val message = t.message.orEmpty()
            return when {
                t is SecurityException || message.contains("permission", ignoreCase = true) -> VoiceErrors.NO_PERMISSION
                // AudioRecord refuses to start or initialise when another app holds the microphone.
                message.contains("did not start") || message.contains("initialization failed") -> VoiceErrors.MIC_BUSY
                else -> VoiceErrors.FAILED
            }
        }

        /**
         * The model writes upper case ("OPEN THE CLOCK APP"); the screen and the agent get a sentence:
         * lower case, the first letter and the word "I" in capitals.
         */
        internal fun normalize(text: String): String {
            if (text.isEmpty() || text.any { it.isLowerCase() }) return text
            val lower = text.lowercase(Locale.ROOT).split(' ').joinToString(" ") { if (it == "i") "I" else it }
            return lower.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }

        /**
         * Rule 1 ends a turn in which nothing was recognized: 8 s, so the owner has time to begin (the library
         * default, 2.4 s, closed the screen with "I did not hear anything." before many people start talking).
         * Rule 2 ends a turn 1.2 s after the last word. Rule 3 caps a turn at 20 s.
         */
        internal fun endpointConfig() = EndpointConfig(
            rule1 = EndpointRule(false, NOTHING_HEARD_SECONDS, 0f),
            rule2 = EndpointRule(true, PAUSE_AFTER_WORDS_SECONDS, 0f),
            rule3 = EndpointRule(false, 0f, MAX_TURN_SECONDS)
        )
        internal const val NOTHING_HEARD_SECONDS = 8f
        internal const val PAUSE_AFTER_WORDS_SECONDS = 1.2f
        internal const val MAX_TURN_SECONDS = 20f

        internal fun toFloats(buffer: ShortArray, count: Int): FloatArray = MicRecorder.toFloats(buffer, count)

        /** Accepts one block of 16 kHz samples, decodes every ready frame, and returns the hypothesis so far. */
        internal fun feed(recognizer: OnlineRecognizer, stream: OnlineStream, samples: FloatArray): String {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        }

        /** Marks the input finished, decodes what is left, and returns the final hypothesis. */
        internal fun flush(recognizer: OnlineRecognizer, stream: OnlineStream): String {
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        }

        fun recognizerConfig(
            paths: SherpaOnnxModelFiles.Paths,
            numThreads: Int
        ): OnlineRecognizerConfig =
            OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = paths.encoder,
                        decoder = paths.decoder,
                        joiner = paths.joiner
                    ),
                    tokens = paths.tokens,
                    numThreads = numThreads.coerceIn(1, 2),
                    provider = "cpu",
                    modelType = MODEL_TYPE
                ),
                endpointConfig = endpointConfig(),
                enableEndpoint = true,
                decodingMethod = "greedy_search"
            )
    }
}
