package ai.opencyclon.llm

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ChatGPT-plan client: Responses translation, SSE parsing, token refresh. MockWebServer only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatGptPlanClientTest {
    private lateinit var server: MockWebServer
    private lateinit var store: ChatGptTokenStore
    private val now = 1_800_000_000_000L
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val base get() = server.url("/backend-api/codex").toString()
    private val tokenUrl get() = server.url("/oauth/token").toString()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        store = ChatGptTokenStore(context).also { it.clear() }
    }

    @After fun tearDown() {
        server.shutdown()
        store.clear()
    }

    private fun client() = ChatGptPlanClient(store, "gpt-5.5", base, tokenUrl, clock = { now }, retryDelayMs = 1)

    private fun sse(vararg events: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "event: x\ndata: $it\n\n" })

    private val functionCallStream = arrayOf(
        """{"type":"response.created","response":{"id":"r1"}}""",
        """{"type":"response.function_call_arguments.delta","delta":"{\"thought\":\"t\","}""",
        """{"type":"response.function_call_arguments.delta","delta":"\"action_type\":\"tap\",\"x\":5,\"y\":7}"}""",
        """{"type":"response.function_call_arguments.done","arguments":"{\"thought\":\"t\",\"action_type\":\"tap\",\"x\":5,\"y\":7}"}""",
        """{"type":"response.completed","response":{"id":"r1"}}"""
    )

    private fun jwt(claims: JSONObject): String {
        fun b(s: String) = Base64.encodeToString(s.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return b("""{"alg":"none"}""") + "." + b(claims.toString()) + ".sig"
    }

    private fun validTokens() = store.save("access-1", "refresh-1", "acct-1", now + 60 * 60 * 1000)

    @Test fun translatesMessagesWithImageAndPriorToolCall() = runBlocking {
        validTokens()
        server.enqueue(sse(*functionCallStream))
        val messages = listOf<Map<String, Any>>(
            mapOf("role" to "system", "content" to "You drive a phone."),
            mapOf("role" to "user", "content" to listOf(
                mapOf("type" to "text", "text" to "Open settings"),
                mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/jpeg;base64,AAAA"))
            )),
            mapOf("role" to "assistant", "content" to "", "tool_calls" to listOf(
                mapOf("id" to "call_1", "type" to "function",
                    "function" to mapOf("name" to "phone_action", "arguments" to """{"action_type":"wait"}"""))
            )),
            mapOf("role" to "tool", "tool_call_id" to "call_1", "content" to "waited"),
            mapOf("role" to "assistant", "content" to """{"action_type":"tap"}""")
        )
        client().chatWithTools(messages)

        val request = server.takeRequest()
        assertEquals("/backend-api/codex/responses", request.path)
        assertEquals("Bearer access-1", request.getHeader("Authorization"))
        assertEquals("acct-1", request.getHeader("chatgpt-account-id"))
        assertEquals("codex_cli_rs", request.getHeader("originator"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertTrue(request.getHeader("session-id")!!.isNotEmpty())

        val body = JSONObject(request.body.readUtf8())
        assertEquals("gpt-5.5", body.getString("model"))
        assertEquals("", body.getString("instructions"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("required", body.getString("tool_choice"))
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("phone_action", tool.getString("name"))
        assertTrue(tool.getJSONObject("parameters").has("properties"))
        assertFalse(body.has("temperature"))

        val input = body.getJSONArray("input")
        assertEquals(5, input.length())
        val dev = input.getJSONObject(0)
        assertEquals("developer", dev.getString("role"))
        assertEquals("input_text", dev.getJSONArray("content").getJSONObject(0).getString("type"))
        val user = input.getJSONObject(1).getJSONArray("content")
        assertEquals("input_text", user.getJSONObject(0).getString("type"))
        assertEquals("Open settings", user.getJSONObject(0).getString("text"))
        assertEquals("input_image", user.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,AAAA", user.getJSONObject(1).getString("image_url"))
        val call = input.getJSONObject(2)
        assertEquals("function_call", call.getString("type"))
        assertEquals("call_1", call.getString("call_id"))
        assertEquals("phone_action", call.getString("name"))
        val output = input.getJSONObject(3)
        assertEquals("function_call_output", output.getString("type"))
        assertEquals("call_1", output.getString("call_id"))
        assertEquals("waited", output.getString("output"))
        val assistant = input.getJSONObject(4)
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("output_text", assistant.getJSONArray("content").getJSONObject(0).getString("type"))
    }

    @Test fun functionCallStreamReturnsTheArguments() = runBlocking {
        validTokens()
        server.enqueue(sse(*functionCallStream))
        val result = client().chatWithTools(listOf(mapOf("role" to "user", "content" to "hi")))
        assertEquals("tap", result["action_type"])
        assertEquals("t", result["thought"])
        assertEquals(5, (result["x"] as Number).toInt())
        assertEquals(7, (result["y"] as Number).toInt())
    }

    @Test fun outputItemDoneAloneIsEnough() = runBlocking {
        validTokens()
        server.enqueue(sse(
            """{"type":"response.output_item.done","item":{"type":"function_call","name":"phone_action","arguments":"{\"action_type\":\"finish\",\"thought\":\"ok\"}"}}""",
            """{"type":"response.completed"}"""
        ))
        assertEquals("finish", client().chatWithTools(listOf(mapOf("role" to "user", "content" to "x")))["action_type"])
    }

    @Test fun textCompletionJoinsDeltasAndSendsNoTools() = runBlocking {
        validTokens()
        server.enqueue(sse(
            """{"type":"response.output_text.delta","delta":"<html>"}""",
            """{"type":"response.output_text.delta","delta":"</html>"}""",
            """{"type":"response.completed"}"""
        ))
        assertEquals("<html></html>", client().completeText("Write the app"))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(body.has("tools"))
        assertFalse(body.has("tool_choice"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("Write the app", body.getJSONArray("input").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun refreshesWhenNearExpiryAndSavesRotatedToken() = runBlocking {
        store.save("old-access", "refresh-1", "acct-1", now + 60_000) // inside the 5-minute margin
        val idToken = jwt(JSONObject().put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", "acct-2")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            JSONObject().put("access_token", "new-access").put("refresh_token", "refresh-2")
                .put("id_token", idToken).put("expires_in", 3600).toString()))
        server.enqueue(sse(*functionCallStream))

        client().chatWithTools(listOf(mapOf("role" to "user", "content" to "x")))

        val refresh = server.takeRequest()
        assertEquals("/oauth/token", refresh.path)
        val payload = JSONObject(refresh.body.readUtf8())
        assertEquals("refresh_token", payload.getString("grant_type"))
        assertEquals(PlanSignIn.CHATGPT_CLIENT_ID, payload.getString("client_id"))
        assertEquals("refresh-1", payload.getString("refresh_token"))
        val call = server.takeRequest()
        assertEquals("Bearer new-access", call.getHeader("Authorization"))
        assertEquals("acct-2", call.getHeader("chatgpt-account-id"))
        assertEquals("new-access", store.accessToken)
        assertEquals("refresh-2", store.refreshToken)
        assertEquals(now + 3_600_000, store.expiresAtMs)
    }

    @Test fun refreshesOnceOn401ThenRetries() = runBlocking {
        validTokens()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"expired"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"access_token":"access-2"}"""))
        server.enqueue(sse(*functionCallStream))

        val result = client().chatWithTools(listOf(mapOf("role" to "user", "content" to "x")))

        assertEquals("tap", result["action_type"])
        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
        assertEquals("/oauth/token", server.takeRequest().path)
        assertEquals("Bearer access-2", server.takeRequest().getHeader("Authorization"))
        assertEquals("access-2", store.accessToken)
        assertEquals("refresh-1", store.refreshToken) // not rotated: the old one is kept
        assertEquals(3, server.requestCount)
    }

    @Test fun secondUnauthorizedIsAnError() = runBlocking {
        validTokens()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"access_token":"access-2"}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            client().chatWithTools(listOf(mapOf("role" to "user", "content" to "x")))
            throw AssertionError("expected an error")
        } catch (e: LLMException) {
            assertTrue(e.message!!.contains("401"))
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun notSignedInFailsWithoutARequest() = runBlocking {
        try {
            client().chatWithTools(listOf(mapOf("role" to "user", "content" to "x")))
            throw AssertionError("expected an error")
        } catch (e: LLMException) {
            assertTrue(e.message!!.contains("Sign in"))
        }
        assertEquals(0, server.requestCount)
    }
}
