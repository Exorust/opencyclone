package ai.opencyclon.debug

import ai.opencyclon.action.Action
import ai.opencyclon.db.AppDatabase
import ai.opencyclon.feed.FakeFeed
import ai.opencyclon.feed.ThreadItem
import ai.opencyclon.feed.ThreadKind
import ai.opencyclon.feed.ThreadState
import ai.opencyclon.gate.ApprovalSession
import ai.opencyclon.gate.HeldAction
import ai.opencyclon.gate.HoldReason
import ai.opencyclon.gate.ScreenFacts
import ai.opencyclon.miniapp.Bridge
import ai.opencyclon.miniapp.MiniAppStore
import ai.opencyclon.widgets.WidgetFlow
import ai.opencyclon.widgets.WidgetStore
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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

/** Lane X2 item 10: the walk harness's commands go through the home page's entry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebugCommandsV2Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val feed = FakeFeed()
    private val session = ApprovalSession()
    private var rows: List<ThreadItem> = emptyList()
    private val pages = mutableListOf<String>()
    private val widgetStore = WidgetStore(ApplicationProvider.getApplicationContext(), db)

    private fun commands() = DebugCommandsV2(context, commands = { feed }, session = { session }, today = { rows },
        openPage = { pages += it }, widgetStore = { widgetStore },
        appStore = { MiniAppStore(context, db, pin = { _, _ -> }) }, main = kotlinx.coroutines.Dispatchers.Unconfined, database = { db })

    @org.junit.Before fun clearSeedRegistry() {
        context.getSharedPreferences("walk_seeded", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun close() = db.close()

    private fun parse(cmd: String, vararg extras: Pair<String, String>) =
        DebugCommandV2.parse(cmd) { name -> extras.toMap()[name] }

    @Test fun unseedRemovesOnlyTheRecordedIdAndKeepsAnOwnersAppWithTheSameName() = runBlocking {
        val store = MiniAppStore(context, db, pin = { _, _ -> })
        val owner = store.saveNewVersion(null, "Lingo", DebugCommandsV2.seedPage("Lingo")).first
        val outcome = commands().run(DebugCommandV2.AppSeed("Lingo", "{}"))
        val seed = JSONObject(outcome.data!!).getLong("app_id")
        assertFalse(commands().run(DebugCommandV2.UnseedApp(owner)).ok)
        assertTrue(commands().run(DebugCommandV2.UnseedApp(seed)).ok)
        assertEquals(listOf(owner), db.miniAppDao().all().map { it.id })
        assertTrue(db.miniAppVersionDao().all(seed).isEmpty())
    }

    @Test fun unseedNamedRemovesOnlyExactUnchangedLegacySeedPages() = runBlocking {
        val store = MiniAppStore(context, db, pin = { _, _ -> })
        val legacy = store.saveNewVersion(null, "Lingo", DebugCommandsV2.seedPage("Lingo")).first
        val second = store.saveNewVersion(null, "Lingo", DebugCommandsV2.seedPage("Lingo")).first
        val owner = store.saveNewVersion(null, "Lingo", "<html><body>My lessons</body></html>").first
        val other = store.saveNewVersion(null, "lingo", DebugCommandsV2.seedPage("lingo")).first
        val changed = store.saveNewVersion(null, "Lingo", DebugCommandsV2.seedPage("Lingo")).first
        store.saveNewVersion(changed, "Lingo", DebugCommandsV2.seedPage("Lingo"))
        val received = store.saveNewVersion(null, "Lingo", DebugCommandsV2.seedPage("Lingo"), received = true).first
        val missing = store.saveNewVersion(null, "Lingo", DebugCommandsV2.seedPage("Lingo")).first
        store.currentFile(missing)!!.delete()
        val prefs = context.getSharedPreferences("${Bridge.PREFS_PREFIX}$legacy", Context.MODE_PRIVATE)
        prefs.edit().putString("streak", "4").commit()
        assertTrue(commands().run(DebugCommandV2.UnseedNamed("Lingo")).ok)
        assertEquals(setOf(owner, other, changed, received, missing), db.miniAppDao().all().map { it.id }.toSet())
        for (id in listOf(legacy, second)) {
            assertTrue(db.miniAppVersionDao().all(id).isEmpty())
            assertFalse(java.io.File(context.filesDir, "miniapps/$id").exists())
        }
        assertTrue(prefs.all.isEmpty())
        assertTrue(commands().run(DebugCommandV2.UnseedNamed("Lingo")).ok)
    }

    @Test fun seededWidgetCanBeRemovedButAnOwnersWidgetCannot() = runBlocking {
        val owner = widgetStore.save(ai.opencyclon.widgets.WidgetDraft(
            widgetStore.writeDraft(ai.opencyclon.widgets.WidgetHtml.apply(DebugCommandsV2.seedPage("Clock"))),
            "Clock", ai.opencyclon.widgets.WidgetSize.SMALL, emptyList(), "#4733D6"))
        val outcome = commands().run(DebugCommandV2.WidgetSeed(DebugCommandsV2.seedPage("Clock"), "Clock", "small", ""))
        val seed = JSONObject(outcome.data!!).getLong("widget_id")
        assertFalse(commands().run(DebugCommandV2.UnseedWidget(owner)).ok)
        assertTrue(commands().run(DebugCommandV2.UnseedWidget(seed)).ok)
        assertEquals(listOf(owner), widgetStore.list().map { it.id })
    }

    @Test fun parsesTheNewNamesAndLeavesTheOthersToTheOldParser() {
        assertEquals(DebugCommandV2.UnseedNamed("Lingo"), parse("unseed_named", "name" to "Lingo"))
        assertTrue(parse("unseed_named") is DebugCommandV2.Invalid)
        assertEquals(DebugCommandV2.UnseedApp(7), parse("unseed_app", "id" to "7"))
        assertEquals(DebugCommandV2.UnseedWidget(8), parse("unseed_widget", "id" to "8"))
        assertEquals(DebugCommandV2.DiscardWidget, parse("widget_discard"))
        assertTrue(parse("unseed_app") is DebugCommandV2.Invalid)
        assertEquals(DebugCommandV2.Start("Open the clock"), parse("start", "text" to "Open the clock"))
        assertTrue(parse("start") is DebugCommandV2.Invalid)
        assertEquals(DebugCommandV2.Approve, parse("approve"))
        assertEquals(DebugCommandV2.Reject, parse("reject"))
        assertEquals(DebugCommandV2.Confirm("last", true), parse("confirm"))
        assertEquals(DebugCommandV2.Confirm("12", false), parse("confirm", "item" to "12", "yes" to "false"))
        assertTrue(parse("confirm", "yes" to "maybe") is DebugCommandV2.Invalid)
        assertEquals(DebugCommandV2.Page("widgets"), parse("page", "value" to "widgets"))
        assertTrue(parse("page", "value" to "settings") is DebugCommandV2.Invalid)
        assertEquals(DebugCommandV2.WidgetSeed("<html></html>", "Lingo streak", "small", "app:1,history"),
            parse("widget_seed", "html" to "<html></html>", "name" to "Lingo streak", "sources" to "app:1,history"))
        assertTrue(parse("app_seed", "name" to "Lingo", "data" to "[1]") is DebugCommandV2.Invalid)
        assertEquals(DebugCommandV2.AppSeed("Lingo", "{\"streak\":4}"), parse("app_seed", "name" to "Lingo", "data" to "{\"streak\":4}"))
        for (old in listOf("state", "notify", "set_model", "answer", "edit", "stop", "list_models", "voice_selftest")) {
            assertNull(old, parse(old))
        }
    }

    @Test fun startGoesThroughTheHomePagesSubmit() = runBlocking {
        val outcome = commands().run(DebugCommandV2.Start("which model"))
        assertTrue(outcome.ok)
        assertEquals(listOf("which model"), feed.submitted)
    }

    private val held = HeldAction(Action.Tap(500, 500), HoldReason.COMMIT_WORD, "Send",
        intArrayOf(0, 0, 10, 10), 5, 5, ScreenFacts("ai.opencyclon.testapp", "Alex", null))

    @Test fun approveAndRejectUseTheHomePagesCommandsForTheWaitingTask() = runBlocking {
        assertFalse(commands().run(DebugCommandV2.Approve).ok)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { session.awaitResult(5, held) }
        assertTrue(commands().run(DebugCommandV2.Approve).ok)
        assertEquals(listOf(5L), feed.approved)
        assertTrue(commands().run(DebugCommandV2.Reject).ok)
        assertEquals(listOf(5L), feed.rejected)
        feed.approveResult = false
        assertFalse(commands().run(DebugCommandV2.Approve).ok)
        session.reject(5) // the fake commands do not answer the session
        waiting.await()
        Unit
    }

    @Test fun confirmPicksTheLastOpenRowOrTheDraftCard() = runBlocking {
        rows = listOf(
            ThreadItem(1, 0, ThreadKind.CONFIRM, ThreadState.REJECTED, text = "old"),
            ThreadItem(2, 0, ThreadKind.CONFIRM, ThreadState.OPEN, text = "Reply to Alex?"),
            ThreadItem(3, 0, ThreadKind.RESULT, text = WidgetFlow.DRAFT_READY, actionWord = WidgetFlow.DRAFT_CARD),
            ThreadItem(4, 0, ThreadKind.SENTENCE, text = "x"),
        )
        assertTrue(commands().run(DebugCommandV2.Confirm("last", false)).ok)
        assertTrue(commands().run(DebugCommandV2.Confirm("draft", true)).ok)
        assertTrue(commands().run(DebugCommandV2.Confirm("4", true)).ok)
        assertEquals(listOf(2L to false, 3L to true, 4L to true), feed.confirmed)
        rows = emptyList()
        assertFalse(commands().run(DebugCommandV2.Confirm("last", true)).ok)
    }

    @Test fun pageOpensTheRequestedPage() = runBlocking {
        assertTrue(commands().run(DebugCommandV2.Page("widgets")).ok)
        assertEquals(listOf("widgets"), pages)
    }

    @Test fun appSeedStoresTheAppAndItsBridgeData() = runBlocking {
        val outcome = commands().run(DebugCommandV2.AppSeed("Lingo", "{\"streak\":4,\"lang\":\"es\"}"))
        assertTrue(outcome.ok)
        val id = JSONObject(outcome.data!!).getLong("app_id")
        assertEquals("Lingo", db.miniAppDao().get(id)!!.name)
        val prefs = context.getSharedPreferences("${Bridge.PREFS_PREFIX}$id", Context.MODE_PRIVATE)
        assertEquals("4", prefs.getString("streak", null))
        assertEquals("es", prefs.getString("lang", null))
    }

    @Test fun widgetSeedStoresAWidgetWithoutAModelAndRemovesUndeclaredSources() = runBlocking {
        val html = "<html><head><meta name=\"accent\" content=\"#4733D6\"><meta name=\"sources\" content=\"app:7,history,notifications\">" +
            "<title>Lingo streak</title></head><body><div class=\"big\" id=\"s\">-</div>" +
            "<script>phone.onrefresh=function(){};phone.onrefresh();</script></body></html>"
        val outcome = commands().run(DebugCommandV2.WidgetSeed(html, "Lingo streak", "small", "app:7,history"))
        assertTrue(outcome.line, outcome.ok)
        val id = JSONObject(outcome.data!!).getLong("widget_id")
        val widget = widgetStore.get(id)!!
        assertEquals("Lingo streak", widget.name)
        assertEquals(listOf("app:7", "history"), widget.sourceList)
        assertTrue(widgetStore.html(widget)!!.contains("Lingo streak"))
    }
    @Test fun suggestionsCommandChangesOnlyTheConsentPreference() = runBlocking {
        assertTrue(parse("suggestions") is DebugCommandV2.Invalid)
        assertTrue(parse("suggestions", "value" to "maybe") is DebugCommandV2.Invalid)
        assertEquals(DebugCommandV2.Suggestions(true), parse("suggestions", "value" to "on"))
        assertEquals(DebugCommandV2.Suggestions(false), parse("suggestions", "value" to "off"))
        assertTrue(commands().run(DebugCommandV2.Suggestions(true)).ok)
        assertTrue(ai.opencyclon.proactive.Suggestions(context).enabled)
        assertTrue(commands().run(DebugCommandV2.Suggestions(false)).ok)
        assertFalse(ai.opencyclon.proactive.Suggestions(context).enabled)
    }

}
