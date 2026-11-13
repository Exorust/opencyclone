package ai.opencyclon.voice

import ai.opencyclon.config.SecretStore
import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** What the internet recognizer needs: the address (OpenAI-compatible, ending before `/audio`), model, key. */
data class VoiceServiceConfig(
    val address: String,
    val model: String,
    val key: String,
    val language: String? = null,
) {
    /** Address and key are set, and the address is allowed ([VoiceServiceStore.addressAllowed]). */
    fun usable(debug: Boolean): Boolean = key.isNotBlank() && VoiceServiceStore.addressAllowed(address, debug)
}

/** The service settings: address and model in the preferences file `voice`, the key in [SecretStore]. */
class VoiceServiceStore(context: Context, private val secrets: SecretStore = SecretStore(context)) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): VoiceServiceConfig = VoiceServiceConfig(
        address = prefs.getString(KEY_ADDRESS, null) ?: DEFAULT_ADDRESS,
        model = prefs.getString(KEY_MODEL, null) ?: DEFAULT_MODEL,
        key = secrets.get(SECRET_KEY).orEmpty(),
    )

    /** Saves address and model; [key] only when it is not null (a blank field keeps the stored key). */
    fun save(address: String, model: String, key: String?) {
        prefs.edit()
            .putString(KEY_ADDRESS, address.trim().trimEnd('/').ifEmpty { DEFAULT_ADDRESS })
            .putString(KEY_MODEL, model.trim().ifEmpty { DEFAULT_MODEL })
            .apply()
        if (key != null) secrets.put(SECRET_KEY, key.trim())
    }

    fun hasKey(): Boolean = !secrets.get(SECRET_KEY).isNullOrEmpty()

    companion object {
        private const val PREFS = "voice"
        const val KEY_ADDRESS = "voice_service_address"
        const val KEY_MODEL = "voice_service_model"
        const val SECRET_KEY = "voice_service_key"
        const val DEFAULT_ADDRESS = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-transcribe"

        /** True in a debuggable build, the one whose network config allows plain HTTP to localhost. */
        fun debugBuild(context: Context): Boolean =
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

        /**
         * Only `https://` addresses. A debug build also accepts `http://localhost` and `http://127.0.0.1`
         * (any port), which its network config lets through for the walk harness.
         */
        fun addressAllowed(address: String, debug: Boolean): Boolean {
            val url = address.trim().toHttpUrlOrNull() ?: return false
            if (url.scheme == "https") return true
            return debug && url.scheme == "http" && (url.host == "localhost" || url.host == "127.0.0.1")
        }
    }
}

/** One request to an OpenAI-compatible `POST <address>/audio/transcriptions`. */
class VoiceServiceClient(private val http: OkHttpClient = defaultHttp) {

    sealed class Result {
        data class Text(val text: String) : Result()
        object Refused : Result()
        object Unreachable : Result()
        data class Failed(val detail: String) : Result()
    }

    /** Sends [samples] (16-bit mono at [sampleRate]) as a WAV file. Blocking; call it off the main thread. */
    fun transcribe(config: VoiceServiceConfig, samples: ShortArray, sampleRate: Int = MicRecorder.SAMPLE_RATE): Result {
        val url = (config.address.trim().trimEnd('/') + "/audio/transcriptions").toHttpUrlOrNull()
            ?: return Result.Failed("bad address")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav(samples, sampleRate).toRequestBody(WAV))
            .addFormDataPart("model", config.model)
            .apply { config.language?.takeIf { it.isNotBlank() }?.let { addFormDataPart("language", it) } }
            .build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${config.key}")
            .post(body)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> parseText(response.body?.string().orEmpty())?.let { Result.Text(it) }
                        ?: Result.Failed(NO_TEXT)
                    401, 403 -> Result.Refused
                    else -> Result.Failed("HTTP ${response.code}")
                }
            }
        } catch (_: IOException) {
            Result.Unreachable
        }
    }

    /** The settings screen's "Test": one second of silence; any HTTP 200 counts as an answer. */
    fun test(config: VoiceServiceConfig): Result =
        when (val r = transcribe(config, ShortArray(MicRecorder.SAMPLE_RATE))) {
            is Result.Failed -> if (r.detail == NO_TEXT) Result.Text("") else r
            else -> r
        }

    companion object {
        private val WAV = "audio/wav".toMediaType()
        private const val NO_TEXT = "reply without text"

        private val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** A 44-byte RIFF header and the samples, little-endian 16-bit mono PCM. */
        fun wav(samples: ShortArray, sampleRate: Int): ByteArray {
            val dataBytes = samples.size * 2
            val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
            buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            buf.put("data".toByteArray()).putInt(dataBytes)
            samples.forEach { buf.putShort(it) }
            return buf.array()
        }

        /** The `text` of the JSON reply, trimmed; null when the reply has none. */
        fun parseText(json: String): String? = try {
            val obj = JSONObject(json)
            if (obj.has("text") && !obj.isNull("text")) obj.getString("text").trim() else null
        } catch (_: Exception) {
            null
        }
    }
}
