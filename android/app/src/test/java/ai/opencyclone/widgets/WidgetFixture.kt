package ai.opencyclone.widgets

import ai.opencyclone.db.AppDatabase
import ai.opencyclone.feed.RunningTask
import ai.opencyclone.feed.ThreadItem
import ai.opencyclone.integrations.McpResult
import ai.opencyclone.integrations.McpTool
import ai.opencyclone.miniapp.MiniAppEntity
import ai.opencyclone.tasks.TaskEntity
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File

/** What the flow wrote into the thread. */
class FakeThread : WidgetThread {
    data class Row(val id: Long, var kind: String, var text: String, var state: String = "OPEN", var actionWord: String? = null)
    val rows = mutableListOf<Row>()
    val said = mutableListOf<String>()
    private var next = 1L

    fun confirms() = rows.filter { it.kind == "CONFIRM" }
    fun row(id: Long) = rows.first { it.id == id }

    override suspend fun confirm(text: String, actionWord: String): Long =
        (next++).also { rows += Row(it, "CONFIRM", text, actionWord = actionWord) }
    override fun resolveConfirm(id: Long, yes: Boolean) { row(id).state = if (yes) "ANSWERED" else "REJECTED" }
    val progressTexts = mutableListOf<String>()
    override suspend fun progress(text: String): Long = (next++).also { rows += Row(it, "PROGRESS", text); progressTexts += text }
    override fun progressToSentence(id: Long, text: String) { row(id).apply { kind = "SENTENCE"; this.text = text; state = "FAILED" } }
    override fun progressToDraftCard(id: Long, name: String) {
        row(id).apply { kind = "RESULT"; text = WidgetFlow.DRAFT_READY; state = "DONE"; actionWord = WidgetFlow.DRAFT_CARD }
    }
    override fun say(text: String) { said += text }
}

/** A widget file as the scripted model writes it. */
fun widgetHtml(sources: String = "app:3,history", body: String = "<div class=\"big\" id=\"s\">-</div>") =
    """<html><head><meta name="accent" content="#2F6FDE"><meta name="sources" content="$sources"><title>Streak</title></head>""" +
        """<body>$body<script>phone.onrefresh=function(){phone.read('app:3').then(function(d){document.getElementById('s').textContent=d?d.data.streak:'-'})};phone.onrefresh();</script></body></html>"""

fun plan(name: String = "Lingo streak", size: String = "small", sources: List<String> = listOf("app:3", "history")) =
    """{"name":"$name","size":"$size","sources":[${sources.joinToString(",") { "\"$it\"" }}]}"""

class WidgetFixture(scope: CoroutineScope) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    var time = 1_800_000_000_000L + 12 * 60 * 60 * 1000L // midday in most time zones
    val store = WidgetStore(context, db, now = { time })

    val rows = mutableListOf<ThreadItem>()
    var running: RunningTask? = null
    val tasks = mutableListOf<TaskEntity>()
    val apps = mutableListOf(MiniAppEntity(3, "Lingo", 1, 0))
    val appData = mutableMapOf<Long, Map<String, String?>>(3L to mapOf("streak" to "5", "words" to "[\"hola\"]"))
    val tools = mutableListOf("notes" to McpTool("list_notes", "", "{}", readOnly = true),
        "notes" to McpTool("add_note", "", "{}", readOnly = false))
    val toolCalls = mutableListOf<String>()
    val fetched = mutableListOf<String>()
    var fetch: (String) -> String = { url -> fetched += url; "{\"ok\":true}" }

    val sources = WidgetSources(
        todayRows = { rows.toList() },
        running = { running },
        tasks = { tasks.toList() },
        apps = { apps.toList() },
        appData = { appData[it] ?: emptyMap() },
        tools = { tools.toList() },
        isReadOnly = { server, tool -> tools.any { it.first == server && it.second.name == tool && it.second.readOnly } },
        callTool = { server, tool, args -> toolCalls += "$server/$tool $args"; McpResult.Ok("{\"notes\":[\"a\"]}") },
        fetch = { fetch(it) },
        appLabel = { if (it == "com.whatsapp") "WhatsApp" else it },
        now = { time },
    )

    val thread = FakeThread()
    val replies = ArrayDeque<String>()
    val prompts = mutableListOf<String>()
    /** The load test's answers in order; empty means no problems. */
    val testResults = ArrayDeque<List<String>>()
    val tested = mutableListOf<Pair<String, Map<String, String>>>()
    var opened = 0

    val flow = WidgetFlow(
        model = { prompt -> prompts += prompt; replies.removeFirst() },
        test = { html, _, samples -> tested += html to samples; testResults.removeFirstOrNull() ?: emptyList() },
        store = store, sources = sources, thread = thread, scope = scope, openDraftPage = { opened++ },
        ownerText = { context.getString(it) },
    )

    val root get() = File(context.filesDir, WidgetStore.DIR)
    val draftsDir get() = File(root, WidgetStore.DRAFTS)

    suspend fun until(timeoutMs: Long = 5_000, check: () -> Boolean) = withTimeout(timeoutMs) { while (!check()) delay(5) }

    /** Saves a widget straight through the store. */
    suspend fun saved(name: String, size: WidgetSize = WidgetSize.SMALL, sources: List<String> = listOf("app:3", "history"),
                      html: String = WidgetHtml.apply(widgetHtml())): Long =
        store.save(WidgetDraft(store.writeDraft(html), name, size, sources, "#2F6FDE"))

    fun close() {
        db.close()
        root.deleteRecursively()
    }
}
