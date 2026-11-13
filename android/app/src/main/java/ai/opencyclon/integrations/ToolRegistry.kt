package ai.opencyclon.integrations

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class ConnectResult {
    data class Ok(val toolCount: Int) : ConnectResult()
    data class Error(val code: Int) : ConnectResult()
}

class ToolRegistry(val store: IntegrationStore) {
    private class Connection(val id: Long, val name: String, val client: McpClient, val tools: List<McpTool>)
    private val mutex = Mutex()
    @Volatile private var connections = emptyList<Connection>()
    private var restored = false

    suspend fun connect(name: String, address: String, token: String?): ConnectResult = mutex.withLock {
        if (name.isBlank() || address.isBlank() ||
            (!token.isNullOrEmpty() && (name.contains(token) || address.contains(token)))) {
            return@withLock ConnectResult.Error(-32602)
        }
        try {
            if (store.all().any { it.name == name }) return@withLock ConnectResult.Error(-32602)
            val client = McpClient(address, token)
            val initialized = client.initialize()
            if (initialized is McpResult.Error) return@withLock ConnectResult.Error(initialized.code)
            val listed = client.listTools()
            if (listed is McpResult.Error) return@withLock ConnectResult.Error(listed.code)
            val tools = McpClient.parseTools((listed as McpResult.Ok).text)
            val id = store.save(name, address, token, tools.size)
            connections = connections + Connection(id, name, client, tools)
            ConnectResult.Ok(tools.size)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ConnectResult.Error(-32000)
        }
    }

    /** Re-establish sessions after process restart before supplying the tool list to the engine. */
    suspend fun restore() = mutex.withLock {
        if (restored) return@withLock
        for (entry in store.all()) {
            if (connections.any { it.id == entry.id }) continue
            val connection = try {
                val client = McpClient(entry.address, store.token(entry.id))
                val result = if (client.initialize() is McpResult.Ok) client.listTools() else null
                (result as? McpResult.Ok)?.let { Connection(entry.id, entry.name, client, McpClient.parseTools(it.text)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // Failed sessions supply no tools and cannot be called.
            if (connection != null) connections = connections + connection
            try {
                store.setStatus(entry, if (connection != null) IntegrationStore.CONNECTED else IntegrationStore.DISCONNECTED)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        restored = true
    }

    suspend fun remove(id: Long) = mutex.withLock {
        connections = connections.filterNot { it.id == id }
        store.remove(id)
    }

    fun tools(): List<Pair<String, McpTool>> = connections.flatMap { connection ->
        connection.tools.map { connection.name to it }
    }

    fun isReadOnly(server: String, tool: String): Boolean =
        connections.firstOrNull { it.name == server }?.tools?.firstOrNull { it.name == tool }?.readOnly == true

    suspend fun call(server: String, tool: String, argumentsJson: String): McpResult {
        val connection = connections.firstOrNull { it.name == server && it.tools.any { toolInfo -> toolInfo.name == tool } }
            ?: return McpResult.Error(-32601, UNKNOWN_TOOL)
        return connection.client.callTool(tool, argumentsJson)
    }

    companion object {
        const val UNKNOWN_TOOL = "Unknown integration tool"

        /** What the action executor reports for a tool call: success with the tool's text, or "Tool error <code>". */
        fun outcome(result: McpResult): Pair<Boolean, String> = when (result) {
            is McpResult.Ok -> true to result.text
            is McpResult.Error -> false to "Tool error ${result.code}"
        }
        @Volatile private var instance: ToolRegistry? = null

        fun getInstance(context: Context): ToolRegistry = instance ?: synchronized(this) {
            instance ?: ToolRegistry(IntegrationStore(context.applicationContext)).also { instance = it }
        }
    }
}
