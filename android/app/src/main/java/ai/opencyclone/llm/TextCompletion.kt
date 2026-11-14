package ai.opencyclone.llm

import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** The request loop shared by the clients' plain-text completion: a few retries on network errors, 429 and 5xx. */
internal object TextCompletion {
    const val READ_TIMEOUT_MS = 300_000L
    private const val MAX_ATTEMPTS = 3

    suspend fun post(client: OkHttpClient, tag: String, model: String?, request: () -> Request): String {
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                client.newCall(request()).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (response.code in 200..299) return body
                    if (response.code in 400..499 && response.code != 429 || attempt == MAX_ATTEMPTS - 1) {
                        throw modelException("Model error (${response.code}): ${body.take(300)}", ModelError.fromHttp(response.code, body, model))
                    }
                    Log.w(tag, "Text completion HTTP ${response.code}, retrying")
                }
            } catch (e: IOException) {
                if (attempt == MAX_ATTEMPTS - 1) throw modelException("Model unreachable: ${e.message}", ModelError.unreachable(model), e)
                Log.w(tag, "Text completion attempt ${attempt + 1} failed: ${e.message}")
            }
            delay((1L shl attempt) * 1000)
        }
        throw LLMException("Model unreachable")
    }
}
