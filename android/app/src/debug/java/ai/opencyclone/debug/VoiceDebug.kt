package ai.opencyclone.debug

import ai.opencyclone.ui.voice.VoiceFactory
import ai.opencyclone.voice.AndroidSpeechInputEngine
import ai.opencyclone.voice.MicLevel
import ai.opencyclone.voice.SpeechInputEngine
import ai.opencyclone.ui.voice.VoiceChoices
import ai.opencyclone.voice.MicRecorder
import ai.opencyclone.voice.SherpaModels
import ai.opencyclone.voice.VoiceServiceClient
import ai.opencyclone.voice.VoiceServiceStore
import ai.opencyclone.voice.SherpaOnnxSpeechInputEngine
import ai.opencyclone.voice.SherpaOnnxSpeechInputEngine.Companion.AUDIO_READ_SIZE
import ai.opencyclone.voice.SherpaOnnxSpeechInputEngine.Companion.SAMPLE_RATE
import ai.opencyclone.voice.SherpaOnnxSpeechInputEngine.Companion.feed
import ai.opencyclone.voice.SherpaOnnxSpeechInputEngine.Companion.flush
import android.app.ActivityManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds only. `voice_selftest` proves the real recognizer on the phone without a microphone:
 * the platform voice says [text] into a file, and those samples go through the same recognizer, the same
 * feed step, and the same flush that listening uses. `voice_mictest` opens the microphone exactly as
 * listening does and measures what arrives.
 */
class VoiceDebug(private val context: Context) {

    suspend fun selftest(text: String): JSONObject {
        val model = SherpaModels.selected(context)
        val out = JSONObject().put("cmd", "voice_selftest").put("engine", VoiceFactory.PHONE_MODEL)
            .put("recognizer", VoiceChoices.name(context, VoiceFactory.PHONE_MODEL))
            .put("model", model.id).put("model_name", model.name).put("text", text)
        val t0 = System.currentTimeMillis()
        fun ms() = System.currentTimeMillis() - t0

        // 1. Packaged libraries.
        try {
            SherpaOnnxSpeechInputEngine.loadNativeLibraries()
            out.put("libraries_loaded", true)
        } catch (t: Throwable) {
            out.put("libraries_loaded", false).put("libraries_error", describe(t))
        }
        out.put("libraries_ms", ms())

        // 2. Model files.
        val paths = model.paths(context.filesDir)
        out.put("model_files", JSONObject().apply {
            model.requiredFiles.forEach { name ->
                val f = File(paths.modelDir, name)
                put(name, if (f.exists()) f.length() else JSONObject.NULL)
            }
        })
        out.put("model_files_present", model.installed(context.filesDir))
        out.put("model_dir_other_files", JSONArray(paths.modelDir.list()?.filter { it !in model.requiredFiles }.orEmpty()))
        if (!out.getBoolean("libraries_loaded")) return out.put("ok", false)

        // 3. The recognizer, built by the engine with the engine's config.
        val engine = SherpaOnnxSpeechInputEngine(context, model = model)
        try {
            val started = System.currentTimeMillis()
            val recognizer = try {
                engine.recognizer(allowDownload = false).also {
                    out.put("recognizer_created", true).put("recognizer_ms", System.currentTimeMillis() - started)
                }
            } catch (t: Throwable) {
                out.put("recognizer_created", false).put("recognizer_error", describe(t))
                return out.put("ok", false)
            }

            // 4. Synthesized speech, 16 kHz.
            val synthStarted = System.currentTimeMillis()
            val audio = try {
                synthesize(text, out)
            } catch (t: Throwable) {
                out.put("tts_error", describe(t))
                null
            }
            out.put("tts_ms", System.currentTimeMillis() - synthStarted)

            // 5. Leading silence alone: does the pause rule end a turn before any word?
            out.put("silence_only", run(recognizer, FloatArray(SAMPLE_RATE * 10), stopAtEndpoint = false))

            if (audio == null) return out.put("ok", false)
            out.put("audio_ms", audio.size * 1000L / SAMPLE_RATE)
            // Half a second of silence before the words and two seconds after, as when someone speaks.
            val padded = FloatArray(SAMPLE_RATE / 2) + audio + FloatArray(SAMPLE_RATE * 2)
            val decodeStarted = System.currentTimeMillis()
            val result = run(recognizer, padded, stopAtEndpoint = false)
            out.put("speech", result.put("decode_ms", System.currentTimeMillis() - decodeStarted))
            out.put("final_text", result.optString("final_text"))
            out.put("text_shown_and_sent", SherpaOnnxSpeechInputEngine.normalize(result.optString("final_text")))
            out.put("ok", result.optString("final_text").isNotBlank())
        } finally {
            engine.destroy()
            out.put("total_ms", ms())
        }
        return out
    }

    /**
     * The phone's own recognizer cannot take a file, so this reports what it would use and listens for
     * [listenMs] through the real [AndroidSpeechInputEngine], then asks it to finish.
     */
    suspend fun selftestAndroid(listenMs: Long = 3000): JSONObject {
        val out = JSONObject().put("cmd", "voice_selftest").put("engine", VoiceFactory.ANDROID)
            .put("recognizer", VoiceChoices.name(context, VoiceFactory.ANDROID))
        out.put("on_device_available", AndroidSpeechInputEngine.onDeviceAvailable(context))
        val services = AndroidSpeechInputEngine.services(context)
        out.put("services", JSONArray().apply {
            services.forEach { put(JSONObject().put("package", it.packageName).put("class", it.className).put("system", it.system)) }
        })
        out.put("would_use", when (val c = AndroidSpeechInputEngine.choose(context)) {
            AndroidSpeechInputEngine.Choice.OnDevice -> "on-device"
            is AndroidSpeechInputEngine.Choice.Service -> c.component.flattenToShortString()
            AndroidSpeechInputEngine.Choice.None -> "none"
        })
        out.put("process_importance", ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance)
        val events = JSONArray()
        val t0 = System.currentTimeMillis()
        var ready = false
        var error: String? = null
        var unavailable: String? = null
        var final: String? = null
        var maxLevel = 0f
        val ended = CompletableDeferred<Unit>()
        fun event(name: String, detail: Any? = null) {
            events.put(JSONObject().put("at_ms", System.currentTimeMillis() - t0).put("event", name).put("detail", detail ?: JSONObject.NULL))
        }
        val engine = withContext(Dispatchers.Main) { AndroidSpeechInputEngine(context) }
        try {
            withContext(Dispatchers.Main) {
                engine.startListening(object : SpeechInputEngine.Listener {
                    override fun onReady() { ready = true; event("ready_for_speech") }
                    override fun onPartialResult(text: String) = event("partial", text)
                    override fun onFinalResult(text: String) { final = text; event("final", text); ended.complete(Unit) }
                    override fun onError(message: String) { error = message; event("error", message); ended.complete(Unit) }
                    override fun onEnd() { final = ""; event("end_nothing_heard"); ended.complete(Unit) }
                    override fun onLevel(level: Float) { maxLevel = maxOf(maxLevel, level) }
                    override fun onUnavailable(reason: String) { unavailable = reason; event("unavailable", reason); ended.complete(Unit) }
                })
            }
            withTimeoutOrNull(listenMs) { ended.await() }
            if (!ended.isCompleted) {
                withContext(Dispatchers.Main) { engine.finishNow() }
                withTimeoutOrNull(4000) { ended.await() }
            }
        } finally {
            withContext(Dispatchers.Main + NonCancellable) { engine.destroy() }
        }
        return out.put("ready_for_speech", ready)
            .put("error", error ?: JSONObject.NULL)
            .put("unavailable", unavailable ?: JSONObject.NULL)
            .put("final_text", final ?: JSONObject.NULL)
            .put("max_level", maxLevel.toDouble())
            .put("events", events)
            .put("recorder_errors_logged_as", "tag VoiceEngine, 'Recognizer error <code> -> <action>'")
            .put("ok", ready && error == null && unavailable == null)
    }

    /**
     * The internet service: the platform voice says [text], and the samples go to the configured address in the
     * same request listening sends. Reports the address, the reply kind, and the text.
     */
    suspend fun selftestService(text: String): JSONObject {
        val config = VoiceServiceStore(context).load()
        val out = JSONObject().put("cmd", "voice_selftest").put("engine", VoiceFactory.SERVICE)
            .put("recognizer", VoiceChoices.name(context, VoiceFactory.SERVICE))
            .put("address", config.address).put("model", config.model).put("key_set", config.key.isNotBlank())
            .put("text", text)
        if (!config.usable(VoiceServiceStore.debugBuild(context))) {
            return out.put("ok", false).put("error", "the service is not set up or the address is not allowed")
        }
        val audio = try {
            synthesize(text, out)
        } catch (t: Throwable) {
            return out.put("ok", false).put("tts_error", describe(t))
        }
        val samples = ShortArray(audio.size) { (audio[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        out.put("audio_ms", samples.size * 1000L / SAMPLE_RATE)
        val started = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) { VoiceServiceClient().transcribe(config, samples) }
        out.put("request_ms", System.currentTimeMillis() - started)
        out.put("result", result.javaClass.simpleName)
        return when (result) {
            is VoiceServiceClient.Result.Text -> out.put("final_text", result.text).put("ok", result.text.isNotBlank())
            is VoiceServiceClient.Result.Failed -> out.put("detail", result.detail).put("ok", false)
            else -> out.put("ok", false)
        }
    }

    /** Applies `voice_engine`; returns an error line, or null. */
    fun setEngine(command: DebugCommand.VoiceEngine): String? {
        command.model?.let { if (!SherpaModels.select(context, it)) return "unknown voice_model '$it'; one of ${SherpaModels.ALL.joinToString { m -> m.id }}" }
        if (command.address != null || command.serviceModel != null || command.key != null) {
            val store = VoiceServiceStore(context)
            val now = store.load()
            store.save(command.address ?: now.address, command.serviceModel ?: now.model, command.key)
        }
        command.value?.let { VoiceFactory.setChoice(context, it) }
        return null
    }

    fun engineLine(): String {
        val config = VoiceServiceStore(context).load()
        return "voice engine ${VoiceFactory.choice(context)}, voice model ${SherpaModels.selected(context).id}, " +
            "service ${config.address} (${config.model}, key ${if (config.key.isBlank()) "not set" else "set"}); " +
            "applies to the next listening screen"
    }

    /** Feeds [samples] in the engine's block size and reports partials, the pause, and the final text. */
    private fun run(recognizer: com.k2fsa.sherpa.onnx.OnlineRecognizer, samples: FloatArray, stopAtEndpoint: Boolean): JSONObject {
        val stream = recognizer.createStream("")
        val partials = JSONArray()
        var last = ""
        var endpointAtMs: Long? = null
        var endpointText = ""
        try {
            var offset = 0
            while (offset < samples.size) {
                val n = minOf(AUDIO_READ_SIZE, samples.size - offset)
                val text = feed(recognizer, stream, samples.copyOfRange(offset, offset + n))
                offset += n
                val atMs = offset * 1000L / SAMPLE_RATE
                if (text.isNotEmpty() && text != last) {
                    last = text
                    partials.put(JSONObject().put("at_ms", atMs).put("text", text))
                }
                if (endpointAtMs == null && recognizer.isEndpoint(stream)) {
                    endpointAtMs = atMs
                    endpointText = text
                    if (stopAtEndpoint) break
                }
            }
            val final = flush(recognizer, stream)
            return JSONObject()
                .put("partials", partials)
                .put("endpoint_at_ms", endpointAtMs ?: JSONObject.NULL)
                .put("text_at_endpoint", endpointText)
                .put("final_text", final)
        } finally {
            stream.release()
        }
    }

    private suspend fun synthesize(text: String, out: JSONObject): FloatArray {
        val ready = CompletableDeferred<Int>()
        val tts = TextToSpeech(context.applicationContext) { ready.complete(it) }
        try {
            val status = withTimeout(TTS_TIMEOUT_MS) { ready.await() }
            if (status != TextToSpeech.SUCCESS) throw IllegalStateException("TextToSpeech init status $status")
            out.put("tts_engine", tts.defaultEngine)
            val offline = runCatching {
                tts.voices.orEmpty().filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                    .minByOrNull { if (it.locale.country == "US") 0 else 1 }
            }.getOrNull()
            if (offline != null) tts.voice = offline
            out.put("tts_voice", tts.voice?.name ?: JSONObject.NULL)
            out.put("tts_voice_offline", offline != null)

            val file = File(context.cacheDir, "voice_selftest.wav").apply { delete() }
            val done = CompletableDeferred<String?>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { done.complete(null) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { done.complete("error") }
                override fun onError(utteranceId: String?, errorCode: Int) { done.complete("error $errorCode") }
            })
            val queued = tts.synthesizeToFile(text, Bundle(), file, "selftest")
            if (queued != TextToSpeech.SUCCESS) throw IllegalStateException("synthesizeToFile returned $queued")
            withTimeout(TTS_TIMEOUT_MS) { done.await() }?.let { throw IllegalStateException("synthesis failed: $it") }

            val wav = Wav.parse(file.readBytes())
            out.put("tts_wav", JSONObject().put("bytes", file.length()).put("rate", wav.rate)
                .put("channels", wav.channels).put("bits", wav.bits))
            return Wav.resample(wav.mono, wav.rate, SAMPLE_RATE)
        } finally {
            tts.shutdown()
        }
    }

    /** Opens the microphone as listening does and reads for [ms] milliseconds. */
    fun mictest(ms: Long): JSONObject {
        val out = JSONObject().put("cmd", "voice_mictest").put("ms", ms)
        out.put("process_importance", ActivityManager.RunningAppProcessInfo().also {
            ActivityManager.getMyMemoryState(it)
        }.importance)
        val recorder = try {
            MicRecorder.create()
        } catch (t: Throwable) {
            return out.put("started", false).put("error", describe(t))
        }
        try {
            recorder.startRecording()
            val recording = recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
            out.put("started", recording).put("sample_rate", recorder.sampleRate)
                .put("audio_source", recorder.audioSource).put("state", recorder.state)
            if (!recording) return out.put("error", "recordingState ${recorder.recordingState}")

            val buffer = ShortArray(AUDIO_READ_SIZE)
            val levels = mutableListOf<Float>()
            var total = 0L
            var nonZero = 0L
            var errors = 0
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    errors++
                    if (errors > 20) break
                    continue
                }
                total += read
                for (i in 0 until read) if (buffer[i].toInt() != 0) nonZero++
                levels += MicLevel.rms(SherpaOnnxSpeechInputEngine.toFloats(buffer, read), read)
            }
            val silenced = clientSilenced()
            out.put("samples_read", total).put("read_errors", errors)
                .put("rms_min", (levels.minOrNull() ?: 0f).toDouble())
                .put("rms_mean", if (levels.isEmpty()) 0.0 else levels.average())
                .put("rms_max", (levels.maxOrNull() ?: 0f).toDouble())
                .put("all_zero", nonZero == 0L)
                .put("client_silenced", silenced ?: JSONObject.NULL)
            if (nonZero == 0L) {
                out.put("note", if (silenced == true) "Android silenced this recording (app in the background or the microphone held by another app); this test cannot judge the microphone."
                else "Only zeros arrived; a recording from the background gets silence, so this test cannot judge the microphone. See the debug level line of ListeningActivity.")
            }
        } catch (t: Throwable) {
            out.put("error", describe(t))
        } finally {
            MicRecorder.release(recorder)
        }
        return out
    }

    /** Whether Android silences this app's recording (API 29+); null when it cannot be read. */
    private fun clientSilenced(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val configs = context.getSystemService(AudioManager::class.java)?.activeRecordingConfigurations ?: return null
        if (configs.isEmpty()) return null
        return configs.any { it.isClientSilenced }
    }

    private fun describe(t: Throwable): String =
        generateSequence(t) { it.cause }.take(3).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }

    /** 16-bit PCM WAV reading and resampling. */
    internal object Wav {
        class Audio(val rate: Int, val channels: Int, val bits: Int, val mono: FloatArray)

        fun parse(bytes: ByteArray): Audio {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
            var pos = 12
            var rate = 0
            var channels = 0
            var bits = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = buf.getInt(pos + 4)
                val body = pos + 8
                when (id) {
                    "fmt " -> {
                        channels = buf.getShort(body + 2).toInt()
                        rate = buf.getInt(body + 4)
                        bits = buf.getShort(body + 14).toInt()
                    }
                    "data" -> {
                        require(bits == 16 && channels > 0) { "unsupported WAV: $bits bits, $channels channels" }
                        // A streaming writer may leave the data size at 0 or too large; read to the end then.
                        val len = if (size <= 0 || body + size > bytes.size) bytes.size - body else size
                        val frames = len / (2 * channels)
                        val mono = FloatArray(frames) { f ->
                            var sum = 0f
                            for (c in 0 until channels) sum += buf.getShort(body + (f * channels + c) * 2) / 32768f
                            sum / channels
                        }
                        return Audio(rate, channels, bits, mono)
                    }
                }
                pos = body + size + (size and 1)
            }
            throw IllegalArgumentException("WAV has no data chunk")
        }

        /** Linear interpolation from [from] Hz to [to] Hz. */
        fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
            if (from == to || samples.isEmpty()) return samples
            val n = (samples.size.toLong() * to / from).toInt()
            val step = from.toDouble() / to
            return FloatArray(n) { i ->
                val x = i * step
                val a = x.toInt().coerceAtMost(samples.size - 1)
                val b = (a + 1).coerceAtMost(samples.size - 1)
                val frac = (x - a).toFloat()
                samples[a] * (1 - frac) + samples[b] * frac
            }
        }
    }

    companion object {
        private const val TTS_TIMEOUT_MS = 15_000L
    }
}
