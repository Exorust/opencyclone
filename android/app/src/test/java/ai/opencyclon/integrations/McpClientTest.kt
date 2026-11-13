package ai.opencyclon.integrations

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class McpClientTest {
    private val mcp = McpTestServer(token = "tok-1")

    @After fun tearDown() = mcp.shutdown()

    private fun listedTools(client: McpClient): List<McpTool> = runBlocking {
        assertTrue(client.initialize() is McpResult.Ok)
        val listed = client.listTools()
        assertTrue(listed.toString(), listed is McpResult.Ok)
        McpClient.parseTools((listed as McpResult.Ok).text)
    }

    @Test fun `initialize and tools list with plain json`() {
        val tools = listedTools(McpClient(mcp.url, "tok-1"))
        assertEquals(listOf("list_notes", "add_note"), tools.map { it.name })
        assertEquals("Tool add_note", tools[1].description)
        assertEquals("object", JSONObject(tools[1].inputSchemaJson).getString("type"))

        val init = mcp.server.takeRequest()
        val initBody = JSONObject(init.body.readUtf8())
        assertEquals("2.0", initBody.getString("jsonrpc"))
        assertEquals("initialize", initBody.getString("method"))
        assertTrue(initBody.has("id"))
        assertEquals("POST", init.method)
        assertEquals("application/json, text/event-stream", init.getHeader("Accept"))
        assertEquals("Bearer tok-1", init.getHeader("Authorization"))
        val notification = JSONObject(mcp.server.takeRequest().body.readUtf8())
        assertEquals("notifications/initialized", notification.getString("method"))
        assertFalse(notification.has("id"))
        assertEquals("tools/list", JSONObject(mcp.server.takeRequest().body.readUtf8()).getString("method"))
    }

    @Test fun `initialize and tools list with an event stream reply`() {
        mcp.eventStream = true
        val tools = listedTools(McpClient(mcp.url, "tok-1"))
        assertEquals(2, tools.size)
        val called = runBlocking { McpClient(mcp.url, "tok-1").apply { initialize() }.callTool("add_note", "{\"title\":\"pm-test\"}") }
        assertEquals(McpResult.Ok("called add_note"), called)
    }

    @Test fun `event stream reply is read without waiting for the stream to close`() {
        // A raw server that sends one event and then keeps the stream open.
        val socketServer = java.net.ServerSocket(0)
        val open = mutableListOf<java.net.Socket>()
        val thread = Thread {
            try {
                repeat(2) {
                    val socket = socketServer.accept().also { open.add(it) }
                    val input = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length).also { input.read(it) }
                    val out = socket.getOutputStream()
                    if (String(body).contains("\"id\"")) {
                        val envelope = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", JSONObject())
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                            "Transfer-Encoding: chunked\r\n\r\n").toByteArray())
                        val event = "data: $envelope\n\n".toByteArray()
                        out.write((Integer.toHexString(event.size) + "\r\n").toByteArray())
                        out.write(event)
                        out.write("\r\n".toByteArray())
                        out.flush()
                    } else {
                        out.write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        out.flush()
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        try {
            val start = System.currentTimeMillis()
            val result = runBlocking { McpClient("http://127.0.0.1:${socketServer.localPort}/").initialize() }
            assertEquals(McpResult.Ok(""), result)
            assertTrue(System.currentTimeMillis() - start < 5_000)
        } finally {
            open.forEach { runCatching { it.close() } }
            socketServer.close()
            thread.join(1000)
        }
    }

    @Test fun `401 becomes an error with that code`() {
        val result = runBlocking { McpClient(mcp.url, "wrong").initialize() }
        assertTrue(result is McpResult.Error)
        assertEquals(401, (result as McpResult.Error).code)
        assertFalse(result.message.contains("wrong"))
    }

    @Test fun `403 becomes an error with that code`() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(403))
            val result = runBlocking { McpClient(server.url("/").toString()).initialize() }
            assertEquals(403, (result as McpResult.Error).code)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `readOnlyHint parsing`() {
        mcp.tools = JSONArray()
            .put(McpTestServer.tool("a", true))
            .put(McpTestServer.tool("b", false))
            .put(McpTestServer.tool("c"))
            .put(McpTestServer.tool("d", "true"))
            .put(McpTestServer.tool("e").put("annotations", JSONObject().put("destructiveHint", true)))
        val tools = listedTools(McpClient(mcp.url, "tok-1"))
        assertEquals(listOf(true, false, false, false, false), tools.map { it.readOnly })
    }

    @Test fun `session header sent after the server returns one`() {
        listedTools(McpClient(mcp.url, "tok-1"))
        assertNull(mcp.server.takeRequest().getHeader("Mcp-Session-Id"))
        assertEquals(McpTestServer.SESSION, mcp.server.takeRequest().getHeader("Mcp-Session-Id"))
        assertEquals(McpTestServer.SESSION, mcp.server.takeRequest().getHeader("Mcp-Session-Id"))
    }

    @Test fun `no authorization header without a token`() {
        mcp.token = null
        listedTools(McpClient(mcp.url, null))
        assertNull(mcp.server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `tools call sends name and arguments and returns text`() {
        val client = McpClient(mcp.url, "tok-1")
        val result = runBlocking { client.initialize(); client.callTool("add_note", "{\"title\":\"pm-test\"}") }
        assertEquals(McpResult.Ok("called add_note"), result)
        assertEquals("add_note", mcp.calls.single().first)
        assertEquals("pm-test", mcp.calls.single().second.getString("title"))
    }

    @Test fun `json rpc error becomes an error with its code`() {
        val client = McpClient(mcp.url, "tok-1")
        runBlocking { client.initialize() }
        mcp.errorMessage = "nope"
        assertEquals(McpResult.Error(-32001, "nope"), runBlocking { client.listTools() })
    }

    @Test fun `invalid arguments are refused before sending`() {
        val result = runBlocking { McpClient(mcp.url, "tok-1").callTool("add_note", "not json") }
        assertEquals(-32602, (result as McpResult.Error).code)
        assertEquals(0, mcp.server.requestCount)
    }

    @Test fun `unreachable server is an error`() {
        val server = MockWebServer().apply { start() }
        val url = server.url("/").toString()
        server.shutdown()
        assertTrue(runBlocking { McpClient(url).initialize() } is McpResult.Error)
    }
}
