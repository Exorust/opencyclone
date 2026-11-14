package ai.opencyclone.integrations

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

/** A small MCP server on MockWebServer that echoes request ids. */
internal class McpTestServer(
    var token: String? = null,
    var eventStream: Boolean = false,
    var tools: JSONArray = defaultTools()
) {
    val server = MockWebServer()
    val calls = mutableListOf<Pair<String, JSONObject>>()
    var errorMessage: String? = null

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.clone().readUtf8())
                val method = body.getString("method")
                if (token != null && request.getHeader("Authorization") != "Bearer $token") {
                    return MockResponse().setResponseCode(401).setBody("bad token ${request.getHeader("Authorization")}")
                }
                if (!body.has("id")) return MockResponse().setResponseCode(202)
                val result = when (method) {
                    "initialize" -> JSONObject().put("protocolVersion", "2024-11-05")
                        .put("capabilities", JSONObject().put("tools", JSONObject()))
                        .put("serverInfo", JSONObject().put("name", "test").put("version", "1"))
                    "tools/list" -> JSONObject().put("tools", tools)
                    "tools/call" -> {
                        val params = body.getJSONObject("params")
                        calls.add(params.getString("name") to params.getJSONObject("arguments"))
                        JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text")
                            .put("text", "called ${params.getString("name")}")))
                    }
                    else -> null
                }
                val envelope = JSONObject().put("jsonrpc", "2.0").put("id", body.get("id"))
                val error = errorMessage
                if (error != null) envelope.put("error", JSONObject().put("code", -32001).put("message", error))
                else if (result == null) envelope.put("error", JSONObject().put("code", -32601).put("message", "no method"))
                else envelope.put("result", result)
                val response = MockResponse().setResponseCode(200)
                if (method == "initialize") response.addHeader("Mcp-Session-Id", SESSION)
                return if (eventStream) {
                    response.addHeader("Content-Type", "text/event-stream")
                        .setBody("event: message\ndata: $envelope\n\n")
                } else {
                    response.addHeader("Content-Type", "application/json").setBody(envelope.toString())
                }
            }
        }
        server.start()
    }

    val url: String get() = server.url("/mcp").toString()

    fun shutdown() = server.shutdown()

    companion object {
        const val SESSION = "session-abc"

        fun tool(name: String, readOnly: Any? = null): JSONObject = JSONObject().put("name", name)
            .put("description", "Tool $name")
            .put("inputSchema", JSONObject().put("type", "object"))
            .apply { if (readOnly != null) put("annotations", JSONObject().put("readOnlyHint", readOnly)) }

        fun defaultTools(): JSONArray = JSONArray().put(tool("list_notes", true)).put(tool("add_note", false))
    }
}
