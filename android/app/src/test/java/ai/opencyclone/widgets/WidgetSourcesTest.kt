package ai.opencyclone.widgets

import ai.opencyclone.feed.Phase
import ai.opencyclone.feed.RunningTask
import ai.opencyclone.feed.ThreadItem
import ai.opencyclone.feed.ThreadKind
import ai.opencyclone.tasks.TaskEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetSourcesTest {
    private var fixture: WidgetFixture? = null
    @After fun tearDown() { fixture?.close() }

    private fun run(block: suspend (WidgetFixture) -> Unit) = runBlocking { block(WidgetFixture(this).also { fixture = it }) }

    @Test fun clockHasDateTimeAndWeekday() = run { f ->
        val json = JSONObject(f.sources.read("clock")!!)
        assertTrue(json.getString("date").matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        assertTrue(json.getString("time").matches(Regex("\\d{2}:\\d{2}")))
        assertTrue(json.getString("weekday").isNotEmpty())
        assertEquals(f.time, json.getLong("epoch"))
    }

    @Test fun todayNeverContainsNotificationText() = run { f ->
        f.rows += ThreadItem(1, f.time, ThreadKind.NOTIFICATION, appPackage = "com.whatsapp", title = "Alex",
            text = "My bank code is 492817")
        f.rows += ThreadItem(2, f.time, ThreadKind.OWNER, text = "Send Alex the photos")
        f.running = RunningTask(7, "Send Alex the photos", "Picking the photos", listOf("com.whatsapp"), f.time, Phase.RUNNING)
        val text = f.sources.read("today")!!
        assertFalse(text.contains("492817"))
        val json = JSONObject(text)
        val items = json.getJSONArray("items")
        val notification = items.getJSONObject(0)
        assertEquals("notification", notification.getString("kind"))
        assertEquals("Alex", notification.getString("sender"))
        assertEquals("WhatsApp", notification.getString("app"))
        assertFalse(notification.has("text"))
        assertEquals("Send Alex the photos", items.getJSONObject(1).getString("text"))
        assertEquals("Picking the photos", json.getJSONObject("running").getString("caption"))
        assertEquals("Send Alex the photos", json.getJSONObject("running").getString("goal"))
    }

    @Test fun historyHasTheLastTasksAndCounts() = run { f ->
        val day = 24 * 60 * 60 * 1000L
        f.tasks += TaskEntity(1, "user", "Old task", "done", createdAt = f.time - 30 * day, updatedAt = 0)
        f.tasks += TaskEntity(2, "user", "Earlier this week", "failed", createdAt = f.time - 2 * day, updatedAt = 0)
        f.tasks += TaskEntity(3, "user", "Today one", "done", createdAt = f.time - 1, updatedAt = 0)
        f.tasks += TaskEntity(4, "notification", "Proposed only", "proposed", createdAt = f.time - 1, updatedAt = 0)
        val json = JSONObject(f.sources.read("history")!!)
        assertEquals(1, json.getInt("today"))
        assertEquals(2, json.getInt("week"))
        val tasks = json.getJSONArray("tasks")
        assertEquals(3, tasks.length())
        assertEquals("Today one", tasks.getJSONObject(0).getString("words"))
        assertEquals("done", tasks.getJSONObject(0).getString("status"))
        repeat(60) { f.tasks += TaskEntity(10L + it, "user", "t$it", "done", createdAt = f.time - 10, updatedAt = 0) }
        assertEquals(50, JSONObject(f.sources.read("history")!!).getJSONArray("tasks").length())
    }

    @Test fun appDataIsReadOnlyKeyValues() = run { f ->
        val json = JSONObject(f.sources.read("app:3")!!)
        assertEquals("Lingo", json.getString("name"))
        assertEquals(5, json.getJSONObject("data").getInt("streak"))
        assertEquals("hola", json.getJSONObject("data").getJSONArray("words").getString(0))
        assertNull(f.sources.read("app:99"))
    }

    @Test fun aToolThatIsNotReadOnlyIsRefusedAndNeverCalled() = run { f ->
        assertNull(f.sources.read("tool:notes/add_note"))
        assertNull(f.sources.read("tool:other/list_notes"))
        assertTrue(f.toolCalls.isEmpty())
        val json = JSONObject(f.sources.read("tool:notes/list_notes", "{\"limit\":5}")!!)
        assertEquals("a", json.getJSONArray("notes").getString(0))
        assertEquals(listOf("notes/list_notes {\"limit\":5}"), f.toolCalls)
    }

    @Test fun webReadsOneHostOverHttps() = run { f ->
        assertEquals("{\"ok\":true}", f.sources.read("web:api.example.com/v1/today?x=1"))
        assertEquals(listOf("https://api.example.com/v1/today?x=1"), f.fetched)
        f.fetch = { "plain words" }
        assertEquals("\"plain words\"", f.sources.read("web:api.example.com"))
        assertNull(f.sources.read("web:localhost/x"))
        assertNull(f.sources.read("web:10.0.0.1/x"))
        f.fetch = { throw java.io.IOException("No connection") }
        assertNull(f.sources.read("web:api.example.com"))
    }

    @Test fun availableListsWhatTheOwnerCanAllow() = run { f ->
        val available = f.sources.available()
        assertEquals(listOf("today", "history", "app:3", "tool:notes/list_notes"), available.map { it.id })
        assertEquals("your app Lingo", f.sources.ownerName("app:3"))
        assertEquals("your task history", f.sources.ownerName("history"))
        assertEquals("the website example.com", f.sources.ownerName("web:example.com"))
        assertNull(f.sources.ownerName("tool:notes/add_note"))
        assertEquals(listOf("history", "web:example.com"), f.sources.valid(listOf("history", "contacts", "web:example.com", "history")))
    }
}
