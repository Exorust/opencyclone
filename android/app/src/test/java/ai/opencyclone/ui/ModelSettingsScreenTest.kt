package ai.opencyclone.ui

import ai.opencyclone.R
import ai.opencyclone.config.ConfigRepository
import ai.opencyclone.feed.FeedText
import ai.opencyclone.llm.LLMClientInterface
import ai.opencyclone.llm.ModelChoice
import ai.opencyclone.llm.ModelError
import ai.opencyclone.llm.ModelErrorKind
import ai.opencyclone.llm.ModelLists
import ai.opencyclone.llm.modelException
import ai.opencyclone.ui.screens.ModelSettingsActivity
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Model screen: connection row, rows with the chosen mark, "Other…", loading and failure sentences, Test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelSettingsScreenTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var config: ConfigRepository
    private var gate = CompletableDeferred<List<ModelChoice>>()

    @Before fun setUp() {
        context.getSharedPreferences("opencyclone_config", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("model_lists", Context.MODE_PRIVATE).edit().clear().commit()
        config = ConfigRepository(context).apply {
            apiProvider = ConfigRepository.PROVIDER_CHATGPT
            model = "gpt-5.6-terra"
        }
        gate = CompletableDeferred()
        ModelSettingsActivity.listsFor = { ModelLists(it, chatGptList = { gate.await() }) }
    }

    @After fun tearDown() {
        ModelSettingsActivity.listsFor = { ModelLists(it) }
        ModelSettingsActivity.clientFor = { ai.opencyclone.llm.ModelClients.forConfig(it) }
    }

    private fun idleUntil(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!check()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) throw AssertionError("condition not reached")
            Thread.sleep(10)
        }
    }

    private fun start() = Robolectric.buildActivity(ModelSettingsActivity::class.java).setup().get()

    private fun ModelSettingsActivity.tagged(tag: String): View? = window.decorView.findViewWithTag(tag)

    @Test fun showsConnectionLoadingThenRowsWithTheChosenMark() {
        val activity = start()
        val connection = activity.tagged(ModelSettingsActivity.TAG_CONNECTION)!!
        assertEquals("Signed in with ChatGPT", connection.findViewById<TextView>(R.id.row_title).text.toString())
        assertEquals("Change", connection.findViewById<Button>(R.id.row_action).text.toString())
        val status = activity.tagged(ModelSettingsActivity.TAG_STATUS) as TextView
        assertEquals("Getting the list of models.", status.text.toString())

        gate.complete(listOf(ModelChoice("gpt-6.1-sol", "GPT-6.1-Sol"), ModelChoice("gpt-5.6-terra", "GPT-5.6-Terra")))
        idleUntil { activity.tagged(ModelSettingsActivity.rowTag("gpt-6.1-sol")) != null }
        assertEquals(View.GONE, status.visibility)
        val chosen = activity.tagged(ModelSettingsActivity.rowTag("gpt-5.6-terra"))!!.findViewById<ImageView>(R.id.row_chevron)
        assertEquals(View.VISIBLE, chosen.visibility)
        assertEquals("Chosen", chosen.contentDescription)
        assertEquals(View.GONE,
            activity.tagged(ModelSettingsActivity.rowTag("gpt-6.1-sol"))!!.findViewById<ImageView>(R.id.row_chevron).visibility)
    }

    @Test fun tapChoosesAtOnceAndSaysSo() {
        val activity = start()
        gate.complete(listOf(ModelChoice("gpt-6.1-sol"), ModelChoice("gpt-5.6-terra")))
        idleUntil { activity.tagged(ModelSettingsActivity.rowTag("gpt-6.1-sol")) != null }
        activity.tagged(ModelSettingsActivity.rowTag("gpt-6.1-sol"))!!.performClick()
        assertEquals("gpt-6.1-sol", ConfigRepository(context).model)
        assertTrue(ConfigRepository(context).modelChosenByHand)
        val line = activity.tagged(ModelSettingsActivity.TAG_CHOSEN_LINE) as TextView
        assertEquals("The next task uses gpt-6.1-sol.", line.text.toString())
        assertEquals(View.VISIBLE,
            activity.tagged(ModelSettingsActivity.rowTag("gpt-6.1-sol"))!!.findViewById<ImageView>(R.id.row_chevron).visibility)
    }

    @Test fun otherTakesATypedName() {
        val activity = start()
        gate.complete(listOf(ModelChoice("gpt-6.1-sol")))
        idleUntil { activity.tagged(ModelSettingsActivity.rowTag("gpt-6.1-sol")) != null }
        val other = activity.tagged(ModelSettingsActivity.TAG_OTHER) as EditText
        other.setText("my-own-model")
        other.onEditorAction(EditorInfo.IME_ACTION_DONE)
        assertEquals("my-own-model", ConfigRepository(context).model)
    }

    @Test fun failedListAsksForAName() {
        config.apiProvider = ConfigRepository.PROVIDER_OPENAI
        config.baseUrl = "http://127.0.0.1:1/v1"
        config.model = "local-model"
        val activity = start()
        val status = activity.tagged(ModelSettingsActivity.TAG_STATUS) as TextView
        idleUntil { status.text.toString() == "I could not get the list. Type a model name." }
        assertEquals("local-model", (activity.tagged(ModelSettingsActivity.TAG_OTHER) as EditText).text.toString())
        assertEquals("Your own key at 127.0.0.1",
            activity.tagged(ModelSettingsActivity.TAG_CONNECTION)!!.findViewById<TextView>(R.id.row_title).text.toString())
    }

    private class FakeClient(val fail: ModelError?) : LLMClientInterface {
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> = emptyMap()
        override suspend fun completeText(prompt: String): String {
            fail?.let { throw modelException("ChatGPT error (400): {}", it) }
            return "ok"
        }
        override fun shutdown() {}
    }

    @Test fun testButtonReportsAnswer() {
        ModelSettingsActivity.clientFor = { FakeClient(null) }
        val activity = start()
        val button = activity.findViewById<Button>(R.id.screen_primary)
        assertEquals("Test", button.text.toString())
        button.performClick()
        val result = activity.tagged(ModelSettingsActivity.TAG_TEST_RESULT) as TextView
        idleUntil { result.text.toString() == "The model answered." }
        assertTrue(button.isEnabled)
    }

    @Test fun testButtonReportsThePlainFailureSentence() {
        ModelSettingsActivity.clientFor = { FakeClient(ModelError(ModelErrorKind.MODEL_NOT_FOUND, "gpt-5.5", 400)) }
        val activity = start()
        activity.findViewById<Button>(R.id.screen_primary).performClick()
        val result = activity.tagged(ModelSettingsActivity.TAG_TEST_RESULT) as TextView
        idleUntil { result.text.toString().startsWith("This account") }
        assertEquals(FeedText.modelFailure(ModelError(ModelErrorKind.MODEL_NOT_FOUND, "gpt-5.5")), result.text.toString())
    }

    @Test fun setUpPageHasAModelRowThatOpensTheScreen() {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, ai.opencyclone.db.AppDatabase::class.java)
            .allowMainThreadQueries().build()
        FirstRunActivity.databaseFor = { db }
        try {
            config.apiProvider = ConfigRepository.PROVIDER_OLLAMA
            config.model = "gemma4"
            val activity = Robolectric.buildActivity(FirstRunActivity::class.java).setup().get()
            val row = activity.window.decorView.findViewWithTag<View>(FirstRunActivity.TAG_MODEL_ROW)
            assertNotNull(row)
            assertEquals(View.VISIBLE, row.visibility)
            assertEquals("Model: gemma4", row.findViewById<TextView>(R.id.row_title).text.toString())
            row.performClick()
            assertEquals(ModelSettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        } finally {
            FirstRunActivity.databaseFor = { ai.opencyclone.db.AppDatabase.getInstance(it) }
            db.close()
        }
    }

    @Test fun settingsHasAModelRowFirstInTheGroup() {
        val xml = java.io.File(listOf("src/main/res/xml/settings_root.xml", "app/src/main/res/xml/settings_root.xml")
            .first { java.io.File(it).exists() }).readText()
        val group = xml.substringAfter("@string/pref_group_model").substringBefore("</PreferenceCategory>")
        val firstKey = Regex("android:key=\"([^\"]+)\"").find(group)?.groupValues?.get(1)
        assertEquals("model_choice", firstKey)
        assertNull(Regex("android:key=\"model_choice\"").findAll(xml).drop(1).firstOrNull())
    }
}
