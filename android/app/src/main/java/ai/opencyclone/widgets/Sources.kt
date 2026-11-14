package ai.opencyclone.widgets

import ai.opencyclone.feed.RunningTask
import ai.opencyclone.feed.ThreadItem
import ai.opencyclone.feed.ThreadKind
import ai.opencyclone.integrations.McpResult
import ai.opencyclone.integrations.McpTool
import ai.opencyclone.miniapp.MiniAppEntity
import ai.opencyclone.tasks.TaskEntity
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One source a widget may read; [ownerName] is how the owner sees it ("your app Lingo"). */
data class SourceInfo(val id: String, val ownerName: String)

/**
 * The data a widget may read (F16 assumption 4). Each read returns JSON text, or null when there is nothing or the
 * source is refused. Ids: `clock`, `today`, `history`, `app:<id>`, `tool:<server>/<tool>`, `web:<host>`.
 * Every input is a function, so each read is tested without the runtime.
 */
class WidgetSources(
    private val todayRows: suspend () -> List<ThreadItem>,
    private val running: () -> RunningTask?,
    private val tasks: suspend () -> List<TaskEntity>,
    private val apps: suspend () -> List<MiniAppEntity>,
    /** The saved key-value data of one built app (its bridge storage). */
    private val appData: (Long) -> Map<String, String?>,
    private val tools: () -> List<Pair<String, McpTool>>,
    private val isReadOnly: (server: String, tool: String) -> Boolean,
    private val callTool: suspend (server: String, tool: String, argumentsJson: String) -> McpResult,
    /** A public https GET without credentials, under the built-app fetch rules. Throws on failure. */
    private val fetch: suspend (url: String) -> String,
    private val appLabel: (String) -> String = { it },
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The sources the owner can allow, for the plan step. `clock` is always allowed and not listed. */
    suspend fun available(): List<SourceInfo> = buildList {
        add(SourceInfo(TODAY, TODAY_NAME))
        add(SourceInfo(HISTORY, HISTORY_NAME))
        apps().forEach { add(SourceInfo("$APP${it.id}", "your app ${it.name}")) }
        tools().filter { (server, tool) -> isReadOnly(server, tool.name) }
            .forEach { (server, tool) -> add(SourceInfo("$TOOL$server/${tool.name}", "your $server tool ${tool.name}")) }
    }

    /** How the owner sees [id]; null for an id that is not a source or names something that does not exist. */
    suspend fun ownerName(id: String): String? = when {
        id == CLOCK -> CLOCK_NAME
        id.startsWith(WEB) -> webHost(id)?.let { "the website $it" }
        else -> available().firstOrNull { it.id == id }?.ownerName
    }

    /** [ids] that are real sources, without duplicates, in order. */
    suspend fun valid(ids: List<String>): List<String> = ids.map { it.trim() }.distinct().filter { ownerName(it) != null }

    /**
     * Reads [request]. For `web:` the request may carry a path after the host ("web:example.com/v1/x"); the
     * allowed source is the host alone. [toolArgs] are the fixed arguments chosen at build time.
     */
    suspend fun read(request: String, toolArgs: String = "{}"): String? = try {
        when {
            request == CLOCK -> clock()
            request == TODAY -> today()
            request == HISTORY -> history()
            request.startsWith(APP) -> request.removePrefix(APP).toLongOrNull()?.let { app(it) }
            request.startsWith(TOOL) -> tool(request.removePrefix(TOOL), toolArgs)
            request.startsWith(WEB) -> web(request)
            else -> null
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    fun clock(): String {
        val at = now()
        val date = Date(at)
        return JSONObject()
            .put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date))
            .put("time", SimpleDateFormat("HH:mm", Locale.getDefault()).format(date))
            .put("weekday", SimpleDateFormat("EEEE", Locale.getDefault()).format(date))
            .put("epoch", at)
            .toString()
    }

    /** Today's thread as short rows and the running task. Never the text of a notification: only sender and app. */
    suspend fun today(): String {
        val rows = JSONArray()
        todayRows().takeLast(MAX_TODAY_ROWS).forEach { item ->
            val row = JSONObject().put("kind", item.kind.name.lowercase()).put("time", time(item.at))
            if (item.kind == ThreadKind.NOTIFICATION) {
                row.put("sender", item.title ?: JSONObject.NULL)
                row.put("app", item.appPackage?.let(appLabel) ?: JSONObject.NULL)
            } else {
                row.put("text", item.text)
            }
            rows.put(row)
        }
        val task = running()
        return JSONObject().put("items", rows)
            .put("running", task?.let { JSONObject().put("goal", it.goal).put("caption", it.caption) } ?: JSONObject.NULL)
            .toString()
    }

    /** The last 50 tasks, newest first, and how many ran today and this week. */
    suspend fun history(): String {
        val all = tasks().filter { it.state !in NOT_RUN }
        val startOfDay = startOfDay(now())
        val startOfWeek = startOfDay - WEEK_DAYS_BACK * DAY_MS
        val list = JSONArray()
        all.sortedByDescending { it.createdAt }.take(MAX_HISTORY).forEach {
            list.put(JSONObject().put("words", it.goal).put("status", it.state).put("at", it.createdAt).put("time", time(it.createdAt)))
        }
        return JSONObject().put("tasks", list)
            .put("today", all.count { it.createdAt >= startOfDay })
            .put("week", all.count { it.createdAt >= startOfWeek })
            .toString()
    }

    suspend fun app(id: Long): String? {
        val app = apps().firstOrNull { it.id == id } ?: return null
        val data = JSONObject()
        appData(id).forEach { (key, value) -> data.put(key, value?.let(::jsonValue) ?: JSONObject.NULL) }
        return JSONObject().put("name", app.name).put("data", data).toString()
    }

    /** A read-only tool; any other tool is refused (null) and never called. */
    suspend fun tool(path: String, argumentsJson: String): String? {
        val server = path.substringBefore('/')
        val tool = path.substringAfter('/', "")
        if (server.isEmpty() || tool.isEmpty() || !isReadOnly(server, tool)) return null
        return when (val result = callTool(server, tool, argumentsJson)) {
            is McpResult.Ok -> jsonText(result.text)
            is McpResult.Error -> null
        }
    }

    suspend fun web(request: String): String? {
        val host = webHost(request) ?: return null
        val path = request.removePrefix(WEB).removePrefix(host).let { if (it.startsWith("/") || it.startsWith("?")) it else "" }
        return jsonText(fetch("https://$host$path"))
    }

    private fun time(at: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

    companion object {
        const val CLOCK = "clock"
        const val TODAY = "today"
        const val HISTORY = "history"
        const val APP = "app:"
        const val TOOL = "tool:"
        const val WEB = "web:"
        const val CLOCK_NAME = "the date and time"
        const val TODAY_NAME = "today's items and the running task"
        const val HISTORY_NAME = "your task history"
        const val MAX_TODAY_ROWS = 30
        const val MAX_HISTORY = 50
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val WEEK_DAYS_BACK = 6L
        /** Task states that never ran. */
        private val NOT_RUN = setOf("proposed", "dismissed")
        private val HOST = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$""")

        /** The host of a `web:` source or request, or null when it is not a plain public host name. */
        fun webHost(request: String): String? {
            if (!request.startsWith(WEB)) return null
            val host = request.removePrefix(WEB).substringBefore('/').substringBefore('?').lowercase()
            if (!HOST.matches(host) || host.endsWith(".localhost") || host.all { it.isDigit() || it == '.' }) return null
            return host
        }

        /** The allowed source a read request needs: the host for `web:`, the request itself otherwise. */
        fun sourceOf(request: String): String = webHost(request)?.let { "$WEB$it" } ?: request

        /** [text] when it is a JSON object or array; otherwise [text] as a JSON string. */
        fun jsonText(text: String): String = when (val value = jsonValue(text)) {
            is JSONObject, is JSONArray -> value.toString()
            else -> JSONObject.quote(text)
        }

        private fun jsonValue(text: String): Any = try {
            when (val value = JSONTokener(text.trim()).nextValue()) {
                is JSONObject, is JSONArray, is Number, is Boolean -> value
                else -> text
            }
        } catch (_: Exception) {
            text
        }

        fun startOfDay(at: Long): Long = Calendar.getInstance().apply {
            timeInMillis = at
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
