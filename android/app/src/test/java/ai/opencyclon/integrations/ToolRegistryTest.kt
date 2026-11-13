package ai.opencyclon.integrations

import ai.opencyclon.db.AppDatabase
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolRegistryTest {
    private val token = "s3cret-token-XYZ"
    private lateinit var db: AppDatabase
    private lateinit var prefs: SharedPreferences
    private lateinit var store: IntegrationStore
    private lateinit var registry: ToolRegistry
    private lateinit var mcp: McpTestServer

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        prefs = context.getSharedPreferences("integration_tokens_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = IntegrationStore(db.integrationDao(), prefs)
        registry = ToolRegistry(store)
        mcp = McpTestServer(token = token)
        ShadowLog.clear()
    }

    @After fun tearDown() {
        mcp.shutdown()
        db.close()
    }

    @Test fun `connect stores the service and its tools`() = runBlocking {
        assertEquals(ConnectResult.Ok(2), registry.connect("notes", mcp.url, token))
        val entry = store.all().single()
        assertEquals("notes", entry.name)
        assertEquals(mcp.url, entry.address)
        assertEquals(2, entry.toolCount)
        assertEquals(IntegrationStore.CONNECTED, entry.status)
        assertEquals(token, store.token(entry.id))
        assertEquals(listOf("notes" to "list_notes", "notes" to "add_note"), registry.tools().map { it.first to it.second.name })
    }

    @Test fun `failed connect stores nothing`() = runBlocking {
        assertEquals(ConnectResult.Error(401), registry.connect("notes", mcp.url, "wrong-token"))
        assertTrue(store.all().isEmpty())
        assertTrue(registry.tools().isEmpty())
        assertTrue(prefs.all.isEmpty())
    }

    @Test fun `remove clears tools and stored data`() = runBlocking {
        registry.connect("notes", mcp.url, token)
        val id = store.all().single().id
        registry.remove(id)
        assertTrue(registry.tools().isEmpty())
        assertTrue(store.all().isEmpty())
        assertNull(store.token(id))
        assertFalse(registry.isReadOnly("notes", "list_notes"))
        assertEquals(-32601, (registry.call("notes", "list_notes", "{}") as McpResult.Error).code)
    }

    @Test fun `isReadOnly is false when unknown`() = runBlocking {
        assertFalse(registry.isReadOnly("notes", "list_notes"))
        registry.connect("notes", mcp.url, token)
        assertTrue(registry.isReadOnly("notes", "list_notes"))
        assertFalse(registry.isReadOnly("notes", "add_note"))
        assertFalse(registry.isReadOnly("notes", "missing"))
        assertFalse(registry.isReadOnly("other", "list_notes"))
    }

    @Test fun `call reaches the connected server`() = runBlocking {
        registry.connect("notes", mcp.url, token)
        assertEquals(McpResult.Ok("called add_note"), registry.call("notes", "add_note", "{\"title\":\"pm-test\"}"))
        assertEquals("pm-test", mcp.calls.single().second.getString("title"))
        assertTrue(registry.call("notes", "unknown", "{}") is McpResult.Error)
    }

    @Test fun `duplicate name is refused`() = runBlocking {
        registry.connect("notes", mcp.url, token)
        assertTrue(registry.connect("notes", mcp.url, token) is ConnectResult.Error)
        assertEquals(1, store.all().size)
    }

    @Test fun `restore reconnects stored services`() = runBlocking {
        registry.connect("notes", mcp.url, token)
        val fresh = ToolRegistry(store)
        assertTrue(fresh.tools().isEmpty())
        fresh.restore()
        assertEquals(2, fresh.tools().size)
        assertTrue(fresh.isReadOnly("notes", "list_notes"))
    }

    @Test fun `restore marks an unreachable service as not connected`() = runBlocking {
        registry.connect("notes", mcp.url, token)
        mcp.token = "rotated"
        val fresh = ToolRegistry(store)
        fresh.restore()
        assertTrue(fresh.tools().isEmpty())
        assertEquals(IntegrationStore.DISCONNECTED, store.all().single().status)
    }

    @Test fun `token never appears in logs, results or error text`() = runBlocking {
        val failed = registry.connect("notes", mcp.url, token + "-bad")
        val ok = registry.connect("notes", mcp.url, token)
        // A server that reflects the token in its error message.
        mcp.errorMessage = "rejected $token"
        val reflected = registry.call("notes", "add_note", "{}")
        mcp.errorMessage = null
        val invalidArgs = registry.call("notes", "add_note", "{bad")
        val texts = mutableListOf<String>()
        ShadowLog.getLogs().forEach { log ->
            texts.add(log.msg.orEmpty())
            log.throwable?.let { texts.add(it.toString()); texts.add(it.stackTraceToString()) }
        }
        texts += listOf(failed, ok, reflected, invalidArgs).map { it.toString() }
        texts += store.all().map { it.toString() }
        texts += registry.tools().map { it.toString() }
        texts += McpClient(mcp.url, token).toString()
        texts += registry.toString()
        assertTrue(reflected is McpResult.Error)
        assertTrue(texts.isNotEmpty())
        texts.forEach { assertFalse("token leaked in: $it", it.contains(token)) }
    }

    @Test fun `outcome maps ok to its text and error to its code`() {
        assertEquals(true to "note saved", ToolRegistry.outcome(McpResult.Ok("note saved")))
        assertEquals(false to "Tool error -32601", ToolRegistry.outcome(McpResult.Error(-32601, "secret detail")))
    }
}
