package ai.opencyclone.debug

import ai.opencyclone.feed.FeedLocator
import ai.opencyclone.feed.HomeCommands
import ai.opencyclone.feed.ThreadItem
import ai.opencyclone.feed.ThreadKind
import ai.opencyclone.feed.ThreadState
import ai.opencyclone.gate.ApprovalSession
import ai.opencyclone.miniapp.Bridge
import ai.opencyclone.miniapp.BuiltAppStyle
import ai.opencyclone.miniapp.MiniAppStore
import ai.opencyclone.tasks.ProactiveRuntime
import ai.opencyclone.ui.widgets.WidgetHost
import ai.opencyclone.widgets.WidgetDraft
import ai.opencyclone.widgets.WidgetEntity
import ai.opencyclone.widgets.WidgetHtml
import ai.opencyclone.widgets.WidgetSize
import ai.opencyclone.widgets.WidgetSources
import ai.opencyclone.widgets.WidgetStore
import ai.opencyclone.widgets.Widgets
import androidx.room.withTransaction
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Version 2 of the walk harness's commands (lane X2): they use the home page's own entry, [FeedLocator.commands],
 * so the answer, edit and yes routing, the missing-model path, and the own-screen phrases are exercised.
 * Parsed before [DebugCommand]; a name this file does not know falls through to it.
 */
sealed class DebugCommandV2 {
    data class Start(val text: String) : DebugCommandV2()
    object Approve : DebugCommandV2()
    object Reject : DebugCommandV2()
    /** [item]: a row id, "last" (the newest open CONFIRM row), or "draft" (the newest widget draft card). */
    data class Confirm(val item: String, val yes: Boolean) : DebugCommandV2()
    data class Page(val value: String) : DebugCommandV2()
    /** [html]: the file text, or a path to it on the phone. [sources]: comma-separated source ids. */
    data class WidgetSeed(val html: String, val name: String, val size: String, val sources: String) : DebugCommandV2()
    /** [data]: a JSON object; each key is stored as the app's bridge data, as the app would store it. */
    data class AppSeed(val name: String, val data: String) : DebugCommandV2()
    data class UnseedNamed(val name: String) : DebugCommandV2()
    data class UnseedApp(val id: Long) : DebugCommandV2()
    data class UnseedWidget(val id: Long) : DebugCommandV2()
    object DiscardWidget : DebugCommandV2()
    data class Suggestions(val enabled: Boolean) : DebugCommandV2()
    data class Invalid(val reason: String) : DebugCommandV2()

    companion object {
        val NAMES = listOf("start", "approve", "reject", "confirm", "page", "widget_seed", "app_seed", "suggestions", "unseed_app", "unseed_named", "unseed_widget", "widget_discard")
        private val PAGES = setOf(WidgetHost.PAGE_HOME, WidgetHost.PAGE_WIDGETS, "history")

        /** The command for [cmd], or null when [DebugCommand] handles it. */
        fun parse(cmd: String?, extra: (String) -> String?): DebugCommandV2? {
            fun value(name: String) = extra(name)?.takeIf { it.isNotEmpty() }
            return when (cmd) {
                "unseed_named" -> value("name")?.let { UnseedNamed(it) }
                    ?: Invalid("unseed_named needs extra 'name'")
                "unseed_app" -> value("id")?.toLongOrNull()?.let { UnseedApp(it) }
                    ?: Invalid("unseed_app needs extra 'id'")
                "unseed_widget" -> value("id")?.toLongOrNull()?.let { UnseedWidget(it) }
                    ?: Invalid("unseed_widget needs extra 'id'")
                "widget_discard" -> DiscardWidget
                "suggestions" -> when (value("value")) {
                    "on" -> Suggestions(true)
                    "off" -> Suggestions(false)
                    else -> Invalid("suggestions needs extra 'value': on or off")
                }
                "start" -> value("text")?.let { Start(it) } ?: Invalid("start needs extra 'text'")
                "approve" -> Approve
                "reject" -> Reject
                "confirm" -> when (val yes = value("yes")?.lowercase() ?: "true") {
                    "true", "false" -> Confirm(value("item") ?: "last", yes == "true")
                    else -> Invalid("confirm extra 'yes' must be true or false")
                }
                "page" -> value("value")?.takeIf { it in PAGES }?.let { Page(it) }
                    ?: Invalid("page needs extra 'value': home, history, or widgets")
                "widget_seed" -> {
                    val html = value("html") ?: return Invalid("widget_seed needs extra 'html' (inline or a path)")
                    WidgetSeed(html, value("name") ?: "Widget", value("size") ?: "small", value("sources") ?: "")
                }
                "app_seed" -> {
                    val name = value("name") ?: return Invalid("app_seed needs extra 'name'")
                    val data = value("data") ?: "{}"
                    if (runCatching { JSONObject(data) }.isFailure) Invalid("app_seed extra 'data' must be a JSON object")
                    else AppSeed(name, data)
                }
                else -> null
            }
        }

        /** A row id for [item] among [rows] (today's thread, oldest first), or null. */
        fun pickRow(item: String, rows: List<ThreadItem>): Long? = when (item) {
            "last" -> rows.lastOrNull { it.kind == ThreadKind.CONFIRM && it.state == ThreadState.OPEN }?.id
            "draft" -> rows.lastOrNull { it.kind == ThreadKind.RESULT && it.actionWord == ai.opencyclone.widgets.WidgetFlow.DRAFT_CARD }?.id
            else -> item.toLongOrNull()
        }
    }
}

/** Runs [DebugCommandV2]. The parameters are functions only so unit tests need no runtime. */
class DebugCommandsV2(
    private val context: Context,
    private val commands: () -> HomeCommands = { FeedLocator.commands },
    private val session: () -> ApprovalSession = { ProactiveRuntime.init(context).session },
    private val today: suspend () -> List<ThreadItem> = { ProactiveRuntime.init(context).recorder.today().first() },
    private val openPage: (String) -> Unit = { page -> context.startActivity(WidgetHost.homeIntent(context, page)) },
    private val widgetStore: () -> WidgetStore = { ProactiveRuntime.init(context); Widgets.store },
    private val appStore: () -> MiniAppStore = { MiniAppStore(context, pin = { _, _ -> }) },
    /** The home page's calls run on the main thread; tests pass an immediate dispatcher. */
    private val main: kotlin.coroutines.CoroutineContext = Dispatchers.Main,
    private val database: () -> ai.opencyclone.db.AppDatabase = { ai.opencyclone.db.AppDatabase.getInstance(context) },
    private val discardWidget: suspend () -> Unit = { ProactiveRuntime.init(context).widgets.discardDraft() },
) {
    suspend fun run(command: DebugCommandV2): DebugCommands.Outcome = when (command) {
        is DebugCommandV2.UnseedNamed -> unseedNamed(command.name)
        is DebugCommandV2.UnseedApp -> unseedApp(command.id)
        is DebugCommandV2.UnseedWidget -> {
            if (!seeded.getBoolean("widget:${command.id}", false)) fail("not a seeded widget")
            else {
                widgetStore().remove(command.id)
                seeded.edit().remove("widget:${command.id}").commit()
                ok("widget removed")
            }
        }
        DebugCommandV2.DiscardWidget -> {
            discardWidget()
            withContext(main) { openPage(WidgetHost.PAGE_HOME) }
            ok("widget draft discarded")
        }
        is DebugCommandV2.Suggestions -> {
            ai.opencyclone.proactive.Suggestions(context).enabled = command.enabled
            ok("suggestions ${if (command.enabled) "on" else "off"}")
        }
        is DebugCommandV2.Invalid -> fail(command.reason)
        is DebugCommandV2.Start -> {
            val home = runCatching { commands() }.getOrNull()
            if (home == null) fail("the feed is not ready; open the app first")
            else withContext(main) { home.submit(command.text); ok("submitted: ${command.text}") }
        }
        DebugCommandV2.Approve -> {
            val pending = session().pending()
            if (pending == null) fail("no approval is waiting")
            else if (commands().approve(pending.taskId)) ok("approved task ${pending.taskId}")
            else fail("approve not accepted for task ${pending.taskId} (locked, gone, or title unreadable)")
        }
        DebugCommandV2.Reject -> {
            val pending = session().pending()
            if (pending == null) fail("no approval is waiting")
            else {
                commands().reject(pending.taskId)
                ok("rejected task ${pending.taskId}")
            }
        }
        is DebugCommandV2.Confirm -> {
            val id = DebugCommandV2.pickRow(command.item, today())
            if (id == null) fail("no row for item '${command.item}'")
            else withContext(main) { commands().confirm(id, command.yes); ok("confirmed row $id yes=${command.yes}") }
        }
        is DebugCommandV2.Page -> withContext(main) { openPage(command.value); ok("page ${command.value}") }
        is DebugCommandV2.WidgetSeed -> seedWidget(command)
        is DebugCommandV2.AppSeed -> seedApp(command)
    }

    /** Stores a widget as a saved draft would be, without a model: the phone's blocks and the source check apply. */
    private suspend fun seedWidget(c: DebugCommandV2.WidgetSeed): DebugCommands.Outcome {
        val text = if (c.html.trimStart().startsWith("<")) c.html
            else withContext(Dispatchers.IO) { runCatching { File(c.html).readText() }.getOrNull() }
                ?: return fail("cannot read ${c.html}")
        val sources = WidgetEntity.splitSources(c.sources).map { WidgetSources.sourceOf(it.trim()) }.filter { it.isNotEmpty() }
        val checked = WidgetHtml.check(WidgetHtml.apply(text), sources)
        val store = widgetStore()
        if (store.isFull()) return fail(WidgetStore.FULL)
        val file = store.writeDraft(checked.html)
        val id = store.save(WidgetDraft(file, c.name, WidgetSize.of(c.size), sources, BuiltAppStyle.accentOf(checked.html)))
        seeded.edit().putBoolean("widget:$id", true).commit()
        return DebugCommands.Outcome(true, "widget $id stored", data = JSONObject().put("widget_id", id)
            .put("removed_sources", org.json.JSONArray(checked.removedSources)).toString())
    }

    /** Stores a built app with a plain page and [DebugCommandV2.AppSeed.data] as its bridge data. */
    private suspend fun seedApp(c: DebugCommandV2.AppSeed): DebugCommands.Outcome {
        val (id, _) = appStore().saveNewVersion(null, c.name, seedPage(c.name))
        seeded.edit().putBoolean("app:$id", true).commit()
        val data = JSONObject(c.data)
        val editor = context.getSharedPreferences("${Bridge.PREFS_PREFIX}$id", Context.MODE_PRIVATE).edit()
        data.keys().forEach { key ->
            val value = data.get(key)
            // As an app's own bridge.set stores it: text as is, numbers and objects as JSON text.
            editor.putString(key, value.toString())
        }
        editor.commit()
        return DebugCommands.Outcome(true, "app $id stored", data = JSONObject().put("app_id", id).toString())
    }

    private val seeded get() = context.getSharedPreferences("walk_seeded", Context.MODE_PRIVATE)

    /** Only ids recorded by app_seed are eligible; an owner's app with the same name is never touched. */
    private suspend fun unseedApp(id: Long): DebugCommands.Outcome = withContext(Dispatchers.IO) {
        if (!seeded.getBoolean("app:$id", false)) return@withContext fail("not a seeded app")
        removeSeed(id)
        ok("app removed")
    }

    /** Legacy seeds had no registry entry. Require the exact seed page and no later version. */
    private suspend fun unseedNamed(name: String): DebugCommands.Outcome = withContext(Dispatchers.IO) {
        val db = database()
        var removed = 0
        db.withTransaction {
            for (app in db.miniAppDao().all().filter { it.name == name && !it.received && it.currentVersion == 1 }) {
                val versions = db.miniAppVersionDao().all(app.id)
                if (versions.size != 1 || versions.single().version != 1) continue
                val page = runCatching { File(versions.single().htmlPath).readText(Charsets.UTF_8) }.getOrNull()
                if (page != seedPage(name)) continue
                removeSeed(app.id)
                removed++
            }
        }
        ok("removed $removed seeded apps")
    }

    private fun removeSeed(id: Long) {
        val db = database()
        db.runInTransaction {
            db.openHelper.writableDatabase.execSQL("DELETE FROM miniapp_versions WHERE app_id = ?", arrayOf<Any>(id))
            db.openHelper.writableDatabase.execSQL("DELETE FROM miniapps WHERE id = ?", arrayOf<Any>(id))
        }
        File(context.filesDir, "miniapps/$id").deleteRecursively()
        context.getSharedPreferences("${Bridge.PREFS_PREFIX}$id", Context.MODE_PRIVATE).edit().clear().commit()
        seeded.edit().remove("app:$id").commit()
    }

    private fun ok(line: String) = DebugCommands.Outcome(true, line)
    private fun fail(line: String) = DebugCommands.Outcome(false, line)

    companion object {
        fun seedPage(name: String): String =
            "<!doctype html><html><head><meta name=\"accent\" content=\"#4733D6\"><title>$name</title></head>" +
                "<body><h1>$name</h1></body></html>"
    }
}
