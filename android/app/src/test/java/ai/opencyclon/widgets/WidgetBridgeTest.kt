package ai.opencyclon.widgets

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WidgetBridgeTest {
    private val delivered = mutableListOf<String>()
    private val logged = mutableListOf<String>()
    private val asked = mutableListOf<String>()
    private val reads = mutableListOf<String>()
    private var gesture = false
    private var now = 10_000L
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    /** A bridge whose `web:example.com` reads go to the mock server instead of the internet. */
    private fun bridge(allowed: Set<String>) = WidgetBridge(
        widgetKey = "w1",
        allowed = allowed,
        read = { request ->
            reads += request
            if (request.startsWith("web:")) {
                val path = request.removePrefix("web:example.com")
                client.newCall(Request.Builder().url(server.url(path.ifEmpty { "/" })).build()).execute().use { it.body!!.string() }
            } else "{\"streak\":5}"
        },
        deliver = { delivered += it },
        scope = CoroutineScope(Dispatchers.Unconfined),
        userGesture = { gesture },
        ask = { asked += it },
        log = { logged += it },
        now = { now },
    )

    @Test fun anAllowedReadReturnsData() {
        bridge(setOf("app:3")).read("app:3", "1")
        assertEquals(listOf("app:3"), reads)
        assertEquals("window.__widgetResult && window.__widgetResult(\"1\", \"{\\\"streak\\\":5}\");", delivered.single())
        assertTrue(logged.isEmpty())
    }

    @Test fun aRefusedReadReturnsNullAndLogsOneLine() {
        bridge(setOf("app:3")).read("today", "2")
        assertTrue(reads.isEmpty())
        assertEquals("window.__widgetResult && window.__widgetResult(\"2\", null);", delivered.single())
        assertEquals(listOf("widget w1 refused read today"), logged)
    }

    @Test fun noRequestLeavesForARefusedWebSource() {
        server.enqueue(MockResponse().setBody("{\"temp\":12}"))
        bridge(setOf("app:3", "web:other.com")).read("web:example.com/v1", "3")
        assertEquals(0, server.requestCount)
        assertTrue(delivered.single().endsWith("(\"3\", null);"))
        assertEquals(1, logged.size)

        bridge(setOf("web:example.com")).read("web:example.com/v1", "4")
        assertEquals(1, server.requestCount)
        assertTrue(delivered.last().contains("temp"))
    }

    @Test fun askNeedsATapAndWaitsTwoSeconds() {
        val b = bridge(emptySet())
        assertFalse(b.ask("Tell Alex I'm running late"))
        assertTrue(asked.isEmpty())
        gesture = true
        assertTrue(b.ask("Tell Alex I'm running late"))
        now += 1_000
        assertFalse(b.ask("Tell Alex I'm running late"))
        now += 1_500
        assertTrue(b.ask("Tell Alex I'm running late"))
        assertEquals(2, asked.size)
        assertEquals("Tell Alex I'm running late", asked.first())
    }

    @Test fun askIsOffWithoutASender() {
        gesture = true
        val b = WidgetBridge("t", emptySet(), read = { null }, deliver = {}, scope = CoroutineScope(Dispatchers.Unconfined),
            userGesture = { true })
        assertFalse(b.ask("anything"))
    }

    @Test fun theBridgeHasNoStorageNotifyOrFetch() {
        val names = WidgetBridge::class.java.declaredMethods
            .filter { it.isAnnotationPresent(android.webkit.JavascriptInterface::class.java) }.map { it.name }.toSet()
        assertEquals(setOf("read", "ask", "failed"), names)
    }
}
