package ai.opencyclon.llm

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Plain-text completion: same endpoint and key as the agent, no tool schema, no forced tool call. */
class CompleteTextTest {
    private lateinit var server: MockWebServer
    private val base get() = server.url("/").toString().trimEnd('/')

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun openAiCompatibleSendsNoToolsAndReturnsTheText() = runBlocking {
        server.enqueue(json("""{"choices":[{"message":{"role":"assistant","content":"[{\"key\":\"a\"}]"}}]}"""))
        val text = LLMClient("sk-test", "gpt-test", base).completeText("Classify these")
        assertEquals("""[{"key":"a"}]""", text)
        val request = server.takeRequest()
        assertEquals("/chat/completions", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val payload = JSONObject(request.body.readUtf8())
        assertFalse(payload.has("tools"))
        assertFalse(payload.has("tool_choice"))
        assertFalse(payload.getBoolean("stream"))
        assertEquals("Classify these", payload.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun anthropicJoinsTextBlocks() = runBlocking {
        server.enqueue(json("""{"content":[{"type":"text","text":"<html>"},{"type":"text","text":"</html>"}]}"""))
        val text = AnthropicClient("key-1", "claude-test", base).completeText("Write the app")
        assertEquals("<html></html>", text)
        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("Bearer key-1", request.getHeader("Authorization"))
        val payload = JSONObject(request.body.readUtf8())
        assertFalse(payload.has("tools"))
        assertFalse(payload.has("tool_choice"))
        assertTrue(payload.getInt("max_tokens") > 1024)
    }

    @Test fun ollamaReturnsTheMessageContent() = runBlocking {
        server.enqueue(json("""{"message":{"role":"assistant","content":"hello"},"done":true}"""))
        assertEquals("hello", OllamaClient("llama-test", base).completeText("hi"))
        val request = server.takeRequest()
        assertEquals("/api/chat", request.path)
        val payload = JSONObject(request.body.readUtf8())
        assertFalse(payload.has("tools"))
        assertFalse(payload.getBoolean("stream"))
    }

    @Test fun clientErrorIsNotRetriedAndNamesTheCode() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
        try {
            LLMClient("wrong", "gpt-test", base).completeText("x")
            fail("expected an error")
        } catch (e: LLMException) {
            assertTrue(e.message!!.contains("401"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun replyWithoutTextIsAnError() = runBlocking {
        server.enqueue(json("""{"choices":[{"message":{"role":"assistant","content":null}}]}"""))
        try {
            LLMClient("sk", "gpt-test", base).completeText("x")
            fail("expected an error")
        } catch (e: LLMException) {
            assertTrue(e.message!!.contains("no text"))
        }
    }
}
