package ai.opencyclone.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
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

/** The optional caption is in every client's tool schema and comes back in the parsed tool call. MockWebServer only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptionClientsTest {
    private lateinit var server: MockWebServer
    private val args = """{"thought":"t","caption":"Picking the photos","action_type":"tap","x":5,"y":7}"""
    private val messages = listOf<Map<String, Any>>(
        mapOf("role" to "system", "content" to "sys"),
        mapOf("role" to "user", "content" to "pick photos"),
    )

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun assertCaptionOptional(parameters: JSONObject) {
        val caption = parameters.getJSONObject("properties").getJSONObject("caption")
        assertEquals("string", caption.getString("type"))
        assertTrue(caption.getString("description").contains("3 to 6 words"))
        val required = parameters.getJSONArray("required")
        assertFalse((0 until required.length()).any { required.getString(it) == "caption" })
    }

    @Test fun openAiStyle() = runBlocking {
        val event = JSONObject().put("type", "response.function_call_arguments.done").put("arguments", args)
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "text/event-stream")
            .setBody("data: $event\n\ndata: [DONE]\n\n"))
        val client = LLMClient("k", "m", "http://localhost:${server.port}")
        val result = client.chatWithTools(messages)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertCaptionOptional(body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters"))
        assertEquals("Picking the photos", result["caption"])
        assertEquals("tap", result["action_type"])
        client.shutdown()
    }

    @Test fun anthropic() = runBlocking {
        fun data(o: JSONObject) = "event: x\ndata: $o\n\n"
        val stream = data(JSONObject().put("type", "content_block_start").put("content_block", JSONObject().put("type", "tool_use").put("name", "phone_action"))) +
            data(JSONObject().put("type", "content_block_delta").put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", args))) +
            data(JSONObject().put("type", "content_block_stop")) +
            data(JSONObject().put("type", "message_delta").put("delta", JSONObject().put("stop_reason", "tool_use"))) +
            data(JSONObject().put("type", "message_stop"))
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "text/event-stream").setBody(stream))
        val client = AnthropicClient("k", "m", "http://localhost:${server.port}")
        val result = client.chatWithTools(messages)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertCaptionOptional(body.getJSONArray("tools").getJSONObject(0).getJSONObject("input_schema"))
        assertEquals("Picking the photos", result["caption"])
        client.shutdown()
    }

    @Test fun ollama() = runBlocking {
        val response = JSONObject().put("message", JSONObject().put("role", "assistant").put("content", "")
            .put("tool_calls", JSONArray().put(JSONObject().put("function", JSONObject().put("name", "phone_action").put("arguments", JSONObject(args))))))
            .put("done", true)
        server.enqueue(MockResponse().setResponseCode(200).setBody(response.toString()))
        val client = OllamaClient("m", "http://localhost:${server.port}")
        val result = client.chatWithTools(messages)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertCaptionOptional(body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getJSONObject("parameters"))
        assertEquals("Picking the photos", result["caption"])
        client.shutdown()
    }

    @Test fun chatGptPlan() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val store = ChatGptTokenStore(context).also { it.save("access-1", "refresh-1", "acct-1", Long.MAX_VALUE / 2) }
        val events = listOf(
            """{"type":"response.created","response":{"id":"r1"}}""",
            JSONObject().put("type", "response.function_call_arguments.done").put("arguments", args).toString(),
            """{"type":"response.completed","response":{"id":"r1"}}""",
        )
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString("") { "event: x\ndata: $it\n\n" }))
        val client = ChatGptPlanClient(store, "gpt-5.5", server.url("/codex").toString(), server.url("/oauth/token").toString(),
            clock = { 1_000L }, retryDelayMs = 1)
        val result = client.chatWithTools(messages)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertCaptionOptional(body.getJSONArray("tools").getJSONObject(0).getJSONObject("parameters"))
        assertEquals("Picking the photos", result["caption"])
        store.clear()
    }
}
