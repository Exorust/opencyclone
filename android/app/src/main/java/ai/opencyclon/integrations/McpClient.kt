package ai.opencyclon.integrations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class McpTool(val name: String, val description: String, val inputSchemaJson: String, val readOnly: Boolean)

sealed class McpResult {
    data class Ok(val text: String) : McpResult()
    data class Error(val code: Int, val message: String) : McpResult()
}

/** One HTTP MCP session. Credentials never leave the request header or private storage. */
class McpClient(
    private val address: String,
    private val token: String? = null,
    client: OkHttpClient = OkHttpClient()
) {
    private val http = client.newBuilder().callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val ids = AtomicLong()
    @Volatile private var sessionId: String? = null

    suspend fun initialize(): McpResult {
        val result = rpc("initialize", JSONObject()
            .put("protocolVersion", "2024-11-05")
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "OpenCyclon").put("version", "1")))
        if (result is McpResult.Error) return result
        return rpc("notifications/initialized", null, notification = true)
    }

    suspend fun listTools(): McpResult = rpc("tools/list", JSONObject())

    suspend fun callTool(tool: String, argumentsJson: String): McpResult {
        val arguments = try { JSONObject(argumentsJson) } catch (_: Exception) {
            return McpResult.Error(-32602, INVALID_ARGUMENTS)
        }
        return when (val response = rpc("tools/call", JSONObject().put("name", tool).put("arguments", arguments))) {
            is McpResult.Error -> response
            is McpResult.Ok -> try {
                val result = JSONObject(response.text)
                val content = result.optJSONArray("content") ?: JSONArray()
                val text = (0 until content.length()).mapNotNull {
                    val item = content.optJSONObject(it)
                    if (item?.optString("type") == "text") item.optString("text") else null
                }.joinToString("\n")
                if (result.optBoolean("isError", false)) McpResult.Error(-32000, text)
                else McpResult.Ok(text.ifEmpty { result.optJSONObject("structuredContent")?.toString() ?: "" })
            } catch (_: Exception) { McpResult.Error(-32700, INVALID_RESPONSE) }
        }
    }

    private suspend fun rpc(method: String, params: JSONObject?, notification: Boolean = false): McpResult =
        withContext(Dispatchers.IO) {
            try {
                val id = ids.incrementAndGet()
                val payload = JSONObject().put("jsonrpc", "2.0").put("method", method)
                if (!notification) payload.put("id", id)
                if (params != null) payload.put("params", params)
                val request = Request.Builder().url(address)
                    .header("Accept", "application/json, text/event-stream")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                if (!token.isNullOrEmpty()) request.header("Authorization", "Bearer $token")
                sessionId?.let { request.header("Mcp-Session-Id", it) }
                http.newCall(request.build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext McpResult.Error(response.code, HTTP_ERROR)
                    response.header("Mcp-Session-Id")?.let { sessionId = it }
                    if (notification) return@withContext McpResult.Ok("")
                    val body = response.body ?: return@withContext McpResult.Error(-32700, INVALID_RESPONSE)
                    val raw = if (response.header("Content-Type").orEmpty().startsWith("text/event-stream", true)) {
                        // Read one complete event without waiting for the server to close its stream.
                        val data = mutableListOf<String>()
                        val source = body.source()
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty() && data.isNotEmpty()) break
                            if (line.startsWith("data:")) data.add(line.substring(5).removePrefix(" "))
                        }
                        data.joinToString("\n")
                    } else body.string()
                    val envelope = JSONObject(raw)
                    if (envelope.optString("jsonrpc") != "2.0" || envelope.optLong("id", -1) != id) {
                        return@withContext McpResult.Error(-32700, INVALID_RESPONSE)
                    }
                    val error = envelope.optJSONObject("error")
                    if (error != null) return@withContext McpResult.Error(error.optInt("code", -32000),
                        redact(error.optString("message", RPC_ERROR)))
                    val result = envelope.optJSONObject("result")
                        ?: return@withContext McpResult.Error(-32700, INVALID_RESPONSE)
                    McpResult.Ok(clean(result).toString())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // URL, HTTP and JSON exceptions can contain credentials or a reflected server body.
                McpResult.Error(-32000, CONNECTION_FAILED)
            }
        }

    private fun redact(text: String): String = if (token.isNullOrEmpty()) text else text.replace(token, "[redacted]")

    private fun clean(value: Any?): Any? = when (value) {
        is String -> redact(value)
        is JSONObject -> JSONObject().also { safe ->
            value.keys().forEach { key -> safe.put(redact(key), clean(value.get(key))) }
        }
        is JSONArray -> JSONArray().also { safe ->
            for (i in 0 until value.length()) safe.put(clean(value.get(i)))
        }
        else -> value
    }

    companion object {
        const val INVALID_ARGUMENTS = "Invalid tool arguments"
        const val INVALID_RESPONSE = "Invalid MCP response"
        const val HTTP_ERROR = "MCP HTTP request failed"
        const val RPC_ERROR = "MCP request failed"
        const val CONNECTION_FAILED = "Could not connect"

        internal fun parseTools(json: String): List<McpTool> {
            val array = JSONObject(json).getJSONArray("tools")
            return (0 until array.length()).map { index ->
                val tool = array.getJSONObject(index)
                McpTool(tool.getString("name"), tool.optString("description"),
                    tool.getJSONObject("inputSchema").toString(),
                    tool.optJSONObject("annotations")?.opt("readOnlyHint") == true)
            }
        }
    }
}
