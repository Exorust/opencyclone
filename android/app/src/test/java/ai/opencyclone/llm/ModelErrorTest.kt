package ai.opencyclone.llm

import ai.opencyclone.config.ConfigRepository
import ai.opencyclone.feed.FeedText
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Typed errors from each client, their sentences, and the one automatic recovery of the ChatGPT default. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelErrorTest {
    private lateinit var server: MockWebServer
    private lateinit var store: ChatGptTokenStore
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = 1_800_000_000_000L

    /** The body the ChatGPT backend sent on the phone on 2026-11-12. */
    private val refusedBody = """{"detail":"The 'gpt-5.5' model is not supported when using Codex with a ChatGPT account.",""" +
        """"error":{"code":"model_not_found","message":"The 'gpt-5.5' model is not supported when using Codex with a ChatGPT account."}}"""

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        store = ChatGptTokenStore(context).also { it.clear() }
        context.getSharedPreferences("opencyclone_config", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        server.shutdown()
        store.clear()
    }

    private fun chatGpt(model: String = "gpt-5.5") = ChatGptPlanClient(
        store, model, server.url("/codex").toString(), server.url("/oauth/token").toString(), clock = { now }, retryDelayMs = 1)

    private fun validTokens() = store.save("access-1", "refresh-1", "acct-1", now + 60 * 60 * 1000)

    private fun error(block: suspend () -> Unit): LLMException = runBlocking {
        try {
            block(); fail("expected LLMException"); throw AssertionError()
        } catch (e: LLMException) {
            e
        }
    }

    private val msgs = listOf<Map<String, Any>>(mapOf("role" to "user", "content" to "hi"))

    @Test fun chatGpt400ModelNotFound() {
        validTokens()
        server.enqueue(MockResponse().setResponseCode(400).setBody(refusedBody))
        val e = error { chatGpt().chatWithTools(msgs) }
        assertEquals(ModelError(ModelErrorKind.MODEL_NOT_FOUND, "gpt-5.5", 400), e.error)
        assertTrue("old message kept", e.message!!.startsWith("ChatGPT error (400):"))
        assertEquals("This account cannot use the model gpt-5.5. Choose another in Settings, Model.",
            FeedText.failure("LLM error: ${e.message}"))
    }

    @Test fun chatGpt401AfterRefresh() {
        validTokens()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        val e = error { chatGpt().chatWithTools(msgs) }
        assertEquals(ModelErrorKind.AUTH, e.error?.kind)
        assertEquals(401, e.error?.httpCode)
        assertEquals(FeedText.MODEL_AUTH, FeedText.failure("LLM error: ${e.message}"))
    }

    @Test fun chatGpt429And500() {
        validTokens()
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"usage_limit_reached"}}""")) }
        assertEquals(ModelErrorKind.RATE_LIMIT, error { chatGpt().chatWithTools(msgs) }.error?.kind)
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500).setBody("oops")) }
        val e = error { chatGpt().chatWithTools(msgs) }
        assertEquals(ModelError(ModelErrorKind.SERVER, "gpt-5.5", 500), e.error)
        assertEquals(FeedText.MODEL_SERVER, FeedText.failure("LLM error: ${e.message}"))
    }

    @Test fun chatGptNetworkFailureIsUnreachable() {
        validTokens()
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        val e = error { chatGpt().chatWithTools(msgs) }
        assertEquals(ModelErrorKind.UNREACHABLE, e.error?.kind)
        assertEquals(FeedText.MODEL_UNREACHABLE, FeedText.failure("LLM error: ${e.message}"))
    }

    @Test fun notSignedInIsAuth() {
        assertEquals(ModelErrorKind.AUTH, error { chatGpt().chatWithTools(msgs) }.error?.kind)
    }

    @Test fun timeoutIsUnreachable() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        // Three attempts: the first two time out and are retried, so give the last ones something that also times out.
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        val e = error {
            TextCompletion.post(client, "test", "m-1") {
                Request.Builder().url(server.url("/x")).build()
            }
        }
        assertEquals(ModelError(ModelErrorKind.UNREACHABLE, "m-1", null), e.error)
        assertTrue(e.message!!.startsWith("Model unreachable"))
    }

    @Test fun openAiClient404And401() {
        val base = server.url("/v1").toString().trimEnd('/')
        server.enqueue(MockResponse().setResponseCode(404)
            .setBody("""{"error":{"message":"The model `x-1` does not exist","code":"model_not_found"}}"""))
        val e = error { LLMClient("k", "x-1", base).chatWithTools(msgs) }
        assertEquals(ModelError(ModelErrorKind.MODEL_NOT_FOUND, "x-1", 404), e.error)
        assertTrue(e.message!!.startsWith("LLM API error (404)"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"invalid_api_key"}}"""))
        assertEquals(ModelErrorKind.AUTH, error { LLMClient("k", "x-1", base).chatWithTools(msgs) }.error?.kind)
    }

    @Test fun anthropicAndOllama404() {
        val base = server.url("").toString().trimEnd('/')
        server.enqueue(MockResponse().setResponseCode(404)
            .setBody("""{"type":"error","error":{"type":"not_found_error","message":"model: claude-x"}}"""))
        assertEquals(ModelErrorKind.MODEL_NOT_FOUND, error { AnthropicClient("k", "claude-x", base).chatWithTools(msgs) }.error?.kind)
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"model 'gemma9' not found"}"""))
        assertEquals(ModelError(ModelErrorKind.MODEL_NOT_FOUND, "gemma9", 404),
            error { OllamaClient(model = "gemma9", baseUrl = base).chatWithTools(msgs) }.error)
    }

    @Test fun httpKinds() {
        assertEquals(ModelErrorKind.RATE_LIMIT, ModelError.fromHttp(429, "", "m").kind)
        assertEquals(ModelErrorKind.SERVER, ModelError.fromHttp(503, "", "m").kind)
        assertEquals(ModelErrorKind.AUTH, ModelError.fromHttp(403, "{}", "m").kind)
        assertEquals(ModelErrorKind.MODEL_NOT_FOUND, ModelError.fromHttp(403, """{"error":{"code":"model_not_found"}}""", "m").kind)
        assertEquals(ModelErrorKind.OTHER, ModelError.fromHttp(400, """{"error":{"code":"bad"}}""", "m").kind)
        assertEquals(ModelErrorKind.RATE_LIMIT, ModelError.fromHttp(400, """{"error":{"code":"insufficient_quota"}}""", "m").kind)
    }

    @Test fun tagRoundTrips() {
        val e = ModelError(ModelErrorKind.MODEL_NOT_FOUND, "gpt-6.1-terra", 400)
        assertEquals(e, ModelError.find("LLM API error: x {\"a\":1} ${e.tag()}"))
        assertEquals(ModelError(ModelErrorKind.UNREACHABLE), ModelError.find(ModelError(ModelErrorKind.UNREACHABLE).tag()))
        assertEquals("x", ModelError.strip("x ${e.tag()}"))
    }

    // ── Automatic recovery ────────────────────────────────────────────

    private class Fake(val model: String, val refused: Set<String>, val calls: MutableList<String>) : LLMClientInterface {
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
            calls += model
            if (model in refused) throw modelException("refused", ModelError(ModelErrorKind.MODEL_NOT_FOUND, model, 400))
            return mapOf("action_type" to "done")
        }
        override fun shutdown() {}
    }

    private fun chatGptConfig(model: String) = ConfigRepository(context).apply {
        apiProvider = ConfigRepository.PROVIDER_CHATGPT
        this.model = model
    }

    @Test fun refusedDefaultSwitchesToTheNextModelAndStoresIt() = runBlocking {
        val config = chatGptConfig("gpt-5.6-terra")
        val calls = mutableListOf<String>()
        val client = ChatGptFallback(config) { Fake(it, setOf("gpt-5.6-terra"), calls) }
        assertEquals("done", client.chatWithTools(msgs)["action_type"])
        assertEquals(listOf("gpt-5.6-terra", "gpt-5.6-sol"), calls)
        assertEquals("gpt-5.6-sol", config.model)
        assertFalse(config.modelChosenByHand)
        assertEquals("I switched to gpt-5.6-sol, because this account cannot use gpt-5.6-terra.", config.takeModelSwitchNotice())
        // The next step uses the stored model directly.
        client.chatWithTools(msgs)
        assertEquals("gpt-5.6-sol", calls.last())
    }

    @Test fun eachFallbackIsTriedOnce() = runBlocking {
        val config = chatGptConfig("gpt-5.6-terra")
        val calls = mutableListOf<String>()
        val all = ConfigRepository.CHATGPT_FALLBACK.toSet()
        val client = ChatGptFallback(config) { Fake(it, all, calls) }
        val e = try { client.chatWithTools(msgs); null } catch (e: LLMException) { e }
        assertEquals(ModelErrorKind.MODEL_NOT_FOUND, e?.error?.kind)
        assertEquals(listOf("gpt-5.6-terra", "gpt-5.6-sol", "gpt-6.1-terra"), calls)
        assertEquals("gpt-5.6-terra", config.model)
    }

    @Test fun handChosenModelIsNeverReplaced() = runBlocking {
        val config = chatGptConfig("gpt-6-luna")
        config.chooseModel("gpt-6-luna")
        val calls = mutableListOf<String>()
        val client = ChatGptFallback(config) { Fake(it, setOf("gpt-6-luna"), calls) }
        val e = try { client.chatWithTools(msgs); null } catch (e: LLMException) { e }
        assertEquals(ModelErrorKind.MODEL_NOT_FOUND, e?.error?.kind)
        assertEquals(listOf("gpt-6-luna"), calls)
        assertEquals("gpt-6-luna", config.model)
    }

    @Test fun recoveryWorksAgainstTheRealClient() = runBlocking {
        validTokens()
        val config = chatGptConfig("gpt-5.5")  // read once as gpt-5.6-terra
        server.enqueue(MockResponse().setResponseCode(400).setBody(refusedBody.replace("gpt-5.5", "gpt-5.6-terra")))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"type\":\"response.function_call_arguments.done\",\"arguments\":\"{\\\"action_type\\\":\\\"done\\\"}\"}\n\n" +
                "data: {\"type\":\"response.completed\"}\n\n"))
        val client = ChatGptFallback(config) { chatGpt(it) }
        assertEquals("done", client.chatWithTools(msgs)["action_type"])
        assertTrue(server.takeRequest().body.readUtf8().contains("\"model\":\"gpt-5.6-terra\""))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"model\":\"gpt-5.6-sol\""))
        assertEquals("gpt-5.6-sol", config.model)
    }
}
