package ai.opencyclone.ui

import ai.opencyclone.R
import ai.opencyclone.config.ConfigRepository
import ai.opencyclone.db.AppDatabase
import ai.opencyclone.llm.LLMClientInterface
import ai.opencyclone.proactive.RuleEntity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import android.app.Activity
import android.content.Context
import android.os.Looper
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreensRobolectricTest {
    private lateinit var db: AppDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        RulesActivity.databaseFor = { db }
        FirstRunActivity.databaseFor = { db }
    }

    @After fun close() {
        RulesActivity.databaseFor = { AppDatabase.getInstance(it) }
        FirstRunActivity.databaseFor = { AppDatabase.getInstance(it) }
        db.close()
    }

    /** Runs the main looper until [done] holds; database work happens on a background thread. */
    private fun waitFor(done: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        fail("condition not met")
    }

    private fun <T : Activity> start(cls: Class<T>): ActivityController<T> =
        Robolectric.buildActivity(cls).setup()

    private fun allViews(group: ViewGroup): List<android.view.View> =
        (0 until group.childCount).flatMap { i ->
            val v = group.getChildAt(i)
            listOf(v) + if (v is ViewGroup) allViews(v) else emptyList()
        }

    @Test fun rulesShowsEmptyState() {
        val activity = start(RulesActivity::class.java).get()
        waitFor { activity.rowViews().isNotEmpty() }
        val views = activity.rowViews()
        assertEquals(1, views.size)
        assertEquals(RulesActivity.TAG_EMPTY, views[0].tag)
        assertEquals("No rules. Say 'Hide <app> notifications' to add one.", (views[0] as TextView).text.toString())
    }

    @Test fun rulesShowsOneRowPerRuleAndDeleteRemovesIt() {
        runBlocking {
            db.ruleDao().insert(RuleEntity(kind = "package", packageName = "com.example.vpn", text = "hide vpn", createdAt = 1))
            db.ruleDao().insert(RuleEntity(kind = "text", text = "Hide promotions from shopping apps", createdAt = 2))
        }
        val activity = start(RulesActivity::class.java).get()
        waitFor { activity.rowViews().size == 2 }
        val rows = activity.rowViews()
        assertTrue(rows.all { it.tag == RulesActivity.TAG_ROW })
        // Package not installed: the package name stands in for the label.
        assertEquals("com.example.vpn", rows[0].findViewById<TextView>(R.id.row_title).text.toString())
        assertEquals("Hide promotions from shopping apps", rows[1].findViewById<TextView>(R.id.row_title).text.toString())
        val remove = rows[0].findViewById<Button>(R.id.row_action)
        assertEquals("Remove", remove.text.toString())
        assertEquals(View.VISIBLE, remove.visibility)

        remove.performClick()
        waitFor { activity.rowViews().size == 1 && activity.rowViews()[0].tag == RulesActivity.TAG_ROW }
        assertEquals(listOf("Hide promotions from shopping apps"), runBlocking { db.ruleDao().all() }.map { it.text })
    }

    @Test fun ruleRowTextUsesLabelForPackageRules() {
        val pkgRule = RuleEntity(kind = "package", packageName = "vpn.app", text = "hide nordvpn notifications", createdAt = 0)
        assertEquals("NordVPN", RulesActivity.rowText(pkgRule) { if (it == "vpn.app") "NordVPN" else null })
        assertEquals("vpn.app", RulesActivity.rowText(pkgRule) { null })
        val textRule = RuleEntity(kind = "text", text = "Hide sales", createdAt = 0)
        assertEquals("Hide sales", RulesActivity.rowText(textRule) { "unused" })
    }

    @Test fun firstRunDoneDisabledWithoutKey() {
        ConfigRepository(context).apiKey = ""
        val activity = start(FirstRunActivity::class.java).get()
        val done = activity.window.decorView.findViewWithTag<Button>(FirstRunActivity.TAG_DONE)
        assertNotNull(done)
        assertFalse(done.isEnabled)
    }

    @Test fun firstRunDoneEnabledWithSavedKey() {
        ConfigRepository(context).apiKey = "sk-test"
        val activity = start(FirstRunActivity::class.java).get()
        val done = activity.window.decorView.findViewWithTag<Button>(FirstRunActivity.TAG_DONE)
        assertTrue(done.isEnabled)
    }

    @Test fun firstRunDoneEnabledForLocalProviderWithoutKey() {
        val config = ConfigRepository(context)
        config.apiKey = ""
        config.apiProvider = ConfigRepository.PROVIDER_OLLAMA
        try {
            val activity = start(FirstRunActivity::class.java).get()
            val done = activity.window.decorView.findViewWithTag<Button>(FirstRunActivity.TAG_DONE)
            assertTrue(done.isEnabled)
            assertTrue(FirstRunActivity.isDoneEnabled(config))
            config.apiProvider = ConfigRepository.PROVIDER_OPENAI
            assertFalse(FirstRunActivity.isDoneEnabled(config))
        } finally {
            config.apiProvider = ConfigRepository.PROVIDER_OPENAI
        }
    }

    @Test fun firstRunNamesTheSavedAddressAndShowsPermissionRows() {
        ConfigRepository(context).baseUrl = "http://192.168.1.20:8080/v1"
        val activity = start(FirstRunActivity::class.java).get()
        val texts = allViews(activity.window.decorView as ViewGroup).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.contains("Notification text and pictures of the hidden screen are sent to http://192.168.1.20:8080/v1."))
        assertTrue(texts.any { it.endsWith(activity.getString(R.string.h_perm_notification_listener)) })
        assertTrue(texts.contains(activity.getString(R.string.h_allowlist_explain)))
    }

    @Test fun modelTestReportsReachableOrReason() = runBlocking {
        val ok = object : LLMClientInterface {
            override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> = mapOf("action_type" to "wait")
            override fun shutdown() {}
        }
        assertNull(FirstRunActivity.testModel(ok))
        val bad = object : LLMClientInterface {
            override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> =
                throw IllegalStateException("HTTP 401")
            override fun shutdown() {}
        }
        assertEquals("HTTP 401", FirstRunActivity.testModel(bad))
        val slow = object : LLMClientInterface {
            override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
                kotlinx.coroutines.delay(10_000); return emptyMap()
            }
            override fun shutdown() {}
        }
        assertEquals("no answer within 0 seconds", FirstRunActivity.testModel(slow, timeoutMs = 50))
    }

    @Test fun privacyScreenNamesAddressDataAndBlockedApps() {
        val config = ConfigRepository(context)
        config.baseUrl = "https://api.example.com/v1"
        config.blacklistedPackages = setOf("com.owner.added")
        val activity = start(PrivacyActivity::class.java).get()
        val texts = allViews(activity.window.decorView as ViewGroup).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.contains("Model address: https://api.example.com/v1"))
        assertTrue(texts.contains(activity.getString(R.string.suggestions_privacy_off)))
        assertTrue(texts.contains("Pictures of the hidden screen"))
        assertTrue(texts.contains("com.chase.sig.android"))
        assertTrue(texts.contains("com.owner.added"))
        // This app is not in BlockedApps.FIXED; it is shown first under "Always blocked".
        val rows = allViews(activity.window.decorView as ViewGroup).filter { it.id == R.id.row_text }
            .map { it.findViewById<TextView>(R.id.row_title).text.toString() }
        val fixedStart = rows.indexOf("This app")
        assertTrue(fixedStart >= 0)
        assertEquals("com.agilebits.onepassword", rows[fixedStart + 1])
        assertTrue(texts.contains(activity.getString(R.string.h_privacy_dropped)))
        assertTrue(texts.contains(activity.getString(R.string.h_privacy_open_rules)))
    }

    @Test fun tileIntentAsksControlPanelToStartVoice() {
        val intent = QuickAskTileService.askIntent(context)
        assertEquals(ControlPanelActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(QuickAskTileService.EXTRA_START_VOICE, false))
    }
}
