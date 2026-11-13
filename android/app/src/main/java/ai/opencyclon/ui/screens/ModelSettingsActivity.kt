package ai.opencyclon.ui.screens

import ai.opencyclon.R
import ai.opencyclon.config.ConfigRepository
import ai.opencyclon.feed.FeedText
import ai.opencyclon.llm.LLMClientInterface
import ai.opencyclon.llm.LLMException
import ai.opencyclon.llm.ModelChoice
import ai.opencyclon.llm.ModelClients
import ai.opencyclon.llm.ModelError
import ai.opencyclon.llm.ModelLists
import ai.opencyclon.llm.ModelSource
import ai.opencyclon.ui.FirstRunActivity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * "Model" (UI.md 5.12): how the app is connected, with "Change"; the models of that connection as rows with a
 * check on the chosen one (a tap chooses at once); "Other…" for a typed name; "Test" sends one tiny request.
 */
class ModelSettingsActivity : AppCompatActivity() {

    companion object {
        const val TEST_TIMEOUT_MS = 30_000L
        const val TAG_CONNECTION = "model_connection"
        const val TAG_STATUS = "model_status"
        const val TAG_CHOSEN_LINE = "model_chosen_line"
        const val TAG_OTHER = "model_other"
        const val TAG_SEARCH = "model_search"
        const val TAG_TEST_RESULT = "model_test_result"
        fun rowTag(id: String) = "model:$id"

        fun intent(context: Context): Intent = Intent(context, ModelSettingsActivity::class.java)

        @VisibleForTesting internal var listsFor: (Context) -> ModelLists = { ModelLists(it) }
        @VisibleForTesting internal var clientFor: (ConfigRepository) -> LLMClientInterface = { ModelClients.forConfig(it) }

        /** The first row's words: how the app reaches a model. */
        fun connectionLabel(context: Context, config: ConfigRepository): String = when (ModelLists.sourceOf(config)) {
            ModelSource.CHATGPT -> context.getString(R.string.model_via_chatgpt)
            ModelSource.OPENROUTER -> context.getString(R.string.model_via_openrouter)
            ModelSource.OLLAMA -> context.getString(R.string.model_via_network)
            else -> context.getString(R.string.model_via_key, Uri.parse(config.baseUrl).host ?: config.baseUrl)
        }

        /** One tiny text-only request. Null when the model answered, else the owner-facing sentence. */
        suspend fun testModel(client: LLMClientInterface, timeoutMs: Long = TEST_TIMEOUT_MS): String? = try {
            withTimeout(timeoutMs) { client.completeText("Reply with the word ok.") }
            null
        } catch (_: TimeoutCancellationException) {
            FeedText.MODEL_UNREACHABLE
        } catch (e: CancellationException) {
            throw e
        } catch (e: LLMException) {
            (e.error ?: ModelError.find(e.message))?.let { FeedText.modelFailure(it) } ?: FeedText.MODEL_OTHER
        } catch (_: Exception) {
            FeedText.MODEL_OTHER
        } finally {
            client.shutdown()
        }
    }

    private lateinit var config: ConfigRepository
    private lateinit var screen: PlainScreen
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var searchBox: LinearLayout
    private lateinit var chosenLine: TextView
    private lateinit var other: EditText
    private lateinit var testResult: TextView
    private var search: EditText? = null
    private var choices: List<ModelChoice> = emptyList()
    private var shownSource: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = ConfigRepository(this)
        screen = PlainScreen.install(this, getString(R.string.model_title))
        screen.primary(getString(R.string.model_test)) { test() }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from "Change" may have changed the connection: rebuild only then.
        val source = "${config.apiProvider}|${config.baseUrl}"
        if (source != shownSource) {
            shownSource = source
            build()
        }
    }

    private fun build() {
        screen.rows.removeAllViews()
        search = null
        screen.textRow(
            title = connectionLabel(this, config),
            action = getString(R.string.model_change),
            onAction = { startActivity(Intent(this, FirstRunActivity::class.java)) },
        ).tag = TAG_CONNECTION
        status = screen.sentence(getString(R.string.model_loading)).apply { tag = TAG_STATUS }
        searchBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.rows.addView(searchBox)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.rows.addView(list)
        other = screen.fieldRow(
            getString(R.string.model_other),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            hint = getString(R.string.model_other_hint),
        ).apply {
            tag = TAG_OTHER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) { chooseTyped(v.text.toString()); true } else false
            }
        }
        screen.textRow(getString(R.string.model_use), onClick = { chooseTyped(other.text.toString()) })
        chosenLine = screen.caption("").apply { tag = TAG_CHOSEN_LINE; visibility = View.GONE }
        testResult = screen.sentence("").apply { tag = TAG_TEST_RESULT; visibility = View.GONE }
        load()
    }

    private fun load() {
        val lists = listsFor(this)
        lifecycleScope.launch {
            val loaded = lists.load(config)
            if (loaded.isNullOrEmpty()) {
                status.text = getString(R.string.model_load_failed)
                other.setText(config.model)
                return@launch
            }
            status.visibility = View.GONE
            choices = loaded
            if (ModelLists.sourceOf(config) == ModelSource.OPENROUTER) addSearch()
            renderList()
        }
    }

    private fun addSearch() {
        val field = screen.fieldRow(getString(R.string.model_search), InputType.TYPE_CLASS_TEXT, parent = searchBox)
        field.tag = TAG_SEARCH
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = renderList()
        })
        search = field
    }

    private fun renderList() {
        list.removeAllViews()
        val query = search?.text?.toString()?.trim().orEmpty()
        val shown = if (query.isEmpty()) choices
        else choices.filter { it.id.contains(query, ignoreCase = true) || it.label.contains(query, ignoreCase = true) }
        val chosen = config.model
        for (choice in shown) {
            val detail = listOfNotNull(
                choice.id.takeIf { it != choice.label },
                choice.pricePerMillionIn?.let { getString(R.string.model_price, ModelLists.price(it)) },
            ).joinToString(". ").ifEmpty { null }
            val row = screen.textRow(choice.label, detail = detail, onClick = { choose(choice.id) }, parent = list)
            row.tag = rowTag(choice.id)
            if (choice.id == chosen) {
                row.findViewById<ImageView>(R.id.row_chevron).apply {
                    setImageResource(R.drawable.ic_check_16)
                    contentDescription = getString(R.string.model_chosen)
                    visibility = View.VISIBLE
                }
            }
        }
    }

    private fun chooseTyped(text: String) {
        val name = text.trim()
        if (name.isNotEmpty()) choose(name)
    }

    private fun choose(id: String) {
        config.chooseModel(id)
        chosenLine.text = getString(R.string.model_next_task, id)
        chosenLine.visibility = View.VISIBLE
        testResult.visibility = View.GONE
        renderList()
    }

    private fun test() {
        screen.primary.isEnabled = false
        testResult.text = getString(R.string.model_testing)
        testResult.visibility = View.VISIBLE
        val client = clientFor(config)
        lifecycleScope.launch {
            testResult.text = testModel(client) ?: getString(R.string.model_answered)
            screen.primary.isEnabled = true
        }
    }
}
