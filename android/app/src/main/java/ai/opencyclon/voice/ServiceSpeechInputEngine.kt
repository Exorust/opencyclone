package ai.opencyclon.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The recognizer on the internet (F17.3): records the turn, ends it by loudness ([EnergyPause]) or
 * [finishNow], and sends one request through [client]. No words while listening.
 *
 * Failures: with no network at the start the engine is unavailable ([VoiceErrors.SERVICE_UNREACHABLE]) and
 * [FallbackSpeechInputEngine] gives the turn to the phone. When the request cannot reach the address after the
 * owner spoke, [localTranscriber] (the phone model, when installed) turns the same recording into words with
 * the notice [VoiceErrors.SERVICE_FELL_BACK]; without it the turn ends with the error and nothing is sent.
 * A refused key ends the turn with [VoiceErrors.SERVICE_REFUSED] and never falls back.
 */
class ServiceSpeechInputEngine(
    context: Context,
    private val config: VoiceServiceConfig,
    private val client: VoiceServiceClient = VoiceServiceClient(),
    private val localTranscriber: ((ShortArray) -> String)? = null,
    private val networkAvailable: (Context) -> Boolean = ::hasNetwork,
) : SpeechInputEngine {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var job: Job? = null
    @Volatile private var recording = false
    @Volatile private var finishRequested = false
    @Volatile private var destroyed = false
    @Volatile private var recorder: AudioRecord? = null

    init {
        instances.incrementAndGet()
    }

    override val showsPartials: Boolean get() = false

    override fun startListening(listener: SpeechInputEngine.Listener) {
        if (destroyed || job?.isActive == true) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError(VoiceErrors.NO_PERMISSION)
            return
        }
        if (!networkAvailable(appContext)) {
            Log.w(TAG, "No network; the voice service cannot be reached")
            listener.onUnavailable("${VoiceErrors.SERVICE_UNREACHABLE}: no network")
            return
        }
        recording = true
        finishRequested = false
        job = scope.launch { run(listener) }
    }

    override fun stopListening() {
        recording = false
        MicRecorder.stop(recorder)
        job?.cancel()
        job = null
    }

    override fun finishNow() {
        if (job?.isActive == true) finishRequested = true
    }

    override fun destroy() {
        destroyed = true
        stopListening()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun run(listener: SpeechInputEngine.Listener) {
        val samples = try {
            record(listener) ?: return
        } catch (t: Throwable) {
            Log.e(TAG, "Recording failed", t)
            if (recording) post { listener.onError(SherpaOnnxSpeechInputEngine.errorMessage(t)) }
            return
        } finally {
            MicRecorder.release(recorder)
            recorder = null
        }
        if (samples.isEmpty()) {
            post { listener.onEnd() }
            return
        }
        Log.i(TAG, "Sending ${samples.size} samples")
        val result = client.transcribe(config, samples)
        if (!recording) return // stopped or closed while the request was out: nothing is shown
        Log.i(TAG, "Service result: ${result.javaClass.simpleName}")
        deliver(result, samples, listener)
    }

    /** Turns the service's [result] for the recorded [samples] into what the screen hears. */
    internal fun deliver(result: VoiceServiceClient.Result, samples: ShortArray, listener: SpeechInputEngine.Listener) {
        when (result) {
            is VoiceServiceClient.Result.Text -> finish(result.text, listener)
            VoiceServiceClient.Result.Refused -> post { listener.onError(VoiceErrors.SERVICE_REFUSED) }
            is VoiceServiceClient.Result.Failed -> post { listener.onError(VoiceErrors.SERVICE_FAILED) }
            VoiceServiceClient.Result.Unreachable -> {
                val local = localTranscriber
                if (local == null) {
                    post { listener.onError(VoiceErrors.SERVICE_UNREACHABLE) }
                } else {
                    val text = runCatching { local(samples) }.getOrNull()
                    if (text == null) {
                        post { listener.onError(VoiceErrors.SERVICE_UNREACHABLE) }
                    } else {
                        post { listener.onNotice(VoiceErrors.SERVICE_FELL_BACK) }
                        finish(text, listener)
                    }
                }
            }
        }
    }

    private fun finish(text: String, listener: SpeechInputEngine.Listener) {
        recording = false
        val words = text.trim()
        post { if (words.isEmpty()) listener.onEnd() else listener.onFinalResult(words) }
    }

    /** The turn's samples; empty when nothing was said; null when listening was stopped. */
    @SuppressLint("MissingPermission")
    private fun record(listener: SpeechInputEngine.Listener): ShortArray? {
        val r = MicRecorder.create().also { recorder = it }
        if (!recording) return null
        MicRecorder.start(r)
        post { listener.onReady() }
        val pause = EnergyPause()
        val block = ShortArray(MicRecorder.READ_SIZE)
        val turn = ShortArrayBuilder()
        while (recording) {
            if (finishRequested) break
            val read = MicRecorder.read(r, block)
            if (read == 0) continue
            turn.add(block, read)
            val level = MicLevel.rms(MicRecorder.toFloats(block, read), read)
            post { listener.onLevel(level) }
            if (pause.block(level, read * 1000L / MicRecorder.SAMPLE_RATE)) break
        }
        if (!recording) return null
        // Nothing above the speech level: nothing is sent.
        return if (pause.heardSpeech) turn.build() else ShortArray(0)
    }

    private fun post(block: () -> Unit) {
        main.post { if (!destroyed) block() }
    }

    /** A growing sample buffer; a turn is at most 20 s, 640 KB. */
    private class ShortArrayBuilder {
        private var data = ShortArray(MicRecorder.SAMPLE_RATE * 4)
        private var size = 0
        fun add(src: ShortArray, count: Int) {
            if (size + count > data.size) data = data.copyOf(maxOf(data.size * 2, size + count))
            System.arraycopy(src, 0, data, size, count)
            size += count
        }
        fun build(): ShortArray = data.copyOf(size)
    }

    companion object {
        private const val TAG = "ServiceSpeechInput"

        /** Every construction counts; a test proves the local choices never build this engine. */
        @JvmStatic internal val instances = AtomicInteger()

        fun hasNetwork(context: Context): Boolean =
            context.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
    }
}
