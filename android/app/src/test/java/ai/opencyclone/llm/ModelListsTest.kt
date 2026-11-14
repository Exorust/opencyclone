package ai.opencyclone.llm

import ai.opencyclone.config.ConfigRepository
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Each source of the list of models, against MockWebServer: parsing, the image+tools filter, the day cache. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelListsTest {
    private lateinit var server: MockWebServer
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var now = 1_800_000_000_000L
    private lateinit var config: ConfigRepository

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        context.getSharedPreferences("model_lists", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("opencyclone_config", Context.MODE_PRIVATE).edit().clear().commit()
        config = ConfigRepository(context)
    }

    @After fun tearDown() = server.shutdown()

    private fun lists(chatGpt: suspend (ConfigRepository) -> List<ModelChoice> = { error("not used") }) = ModelLists(
        context, chatGptList = chatGpt, clock = { now },
        rewriteUrl = { it.replace(ConfigRepository.OPENROUTER_BASE_URL, server.url("/api/v1").toString().trimEnd('/')) },
    )

    private val chatGptModels = """{"models":[
        {"slug":"gpt-5.6-terra","display_name":"GPT-5.6-Terra","visibility":"list","priority":8,"input_modalities":["text","image"]},
        {"slug":"gpt-6.1-sol","display_name":"GPT-6.1-Sol","visibility":"list","priority":1,"input_modalities":["text","image"]},
        {"slug":"gpt-daybreak-blue-latest","visibility":"hide","priority":11},
        {"slug":"codex-auto-review","visibility":"list","priority":43},
        {"slug":"gpt-text-only","visibility":"list","priority":2,"input_modalities":["text"]}
    ]}"""

    private val openRouterModels = """{"data":[
        {"id":"google/gemini-3.8-flash","name":"Gemini 3.8 Flash","architecture":{"input_modalities":["text","image"]},
         "supported_parameters":["tools","temperature"],"pricing":{"prompt":"0.0000003"}},
        {"id":"text/only","name":"Text only","architecture":{"input_modalities":["text"]},"supported_parameters":["tools"]},
        {"id":"no/tools","name":"No tools","architecture":{"input_modalities":["text","image"]},"supported_parameters":["temperature"]}
    ]}"""

    @Test fun chatGptListComesFromTheCodexModelsEndpoint() = runBlocking {
        val store = ChatGptTokenStore(context).also { it.save("access-1", "refresh-1", "acct-1", Long.MAX_VALUE) }
        server.enqueue(MockResponse().setBody(chatGptModels))
        val client = ChatGptPlanClient(store, "", server.url("/backend-api/codex").toString())
        val list = client.listModels()
        assertEquals(listOf("gpt-6.1-sol", "gpt-5.6-terra"), list.map { it.id })
        assertEquals("GPT-6.1-Sol", list[0].label)
        val request = server.takeRequest()
        assertEquals("/backend-api/codex/models?client_version=${ChatGptPlanClient.CLIENT_VERSION}", request.path)
        assertEquals("Bearer access-1", request.getHeader("Authorization"))
        assertEquals("acct-1", request.getHeader("chatgpt-account-id"))
        assertEquals(ChatGptPlanClient.ORIGINATOR, request.getHeader("originator"))
        store.clear()
    }

    @Test fun chatGptFallsBackToTheStaticList() = runBlocking {
        config.apiProvider = ConfigRepository.PROVIDER_CHATGPT
        val list = lists { throw LLMException("down") }.load(config)
        assertEquals(ModelLists.CHATGPT_STATIC, list)
        assertTrue(list!!.any { it.id == "gpt-5.6-terra" })
        assertTrue(list.none { it.id.contains("review") || it.id == "gpt-5.5" })
    }

    @Test fun chatGptListIsCachedForADay() = runBlocking {
        config.apiProvider = ConfigRepository.PROVIDER_CHATGPT
        var calls = 0
        val l = lists { calls++; listOf(ModelChoice("gpt-6-sol")) }
        assertEquals("gpt-6-sol", l.load(config)!!.single().id)
        assertEquals("gpt-6-sol", l.load(config)!!.single().id)
        assertEquals(1, calls)
        now += ModelLists.CACHE_MS + 1
        l.load(config)
        assertEquals(2, calls)
    }

    @Test fun openRouterKeepsImageAndToolModelsWithPrice() = runBlocking {
        config.apiProvider = ConfigRepository.PROVIDER_OPENAI
        config.baseUrl = ConfigRepository.OPENROUTER_BASE_URL
        server.enqueue(MockResponse().setBody(openRouterModels))
        val list = lists().load(config)!!
        assertEquals(listOf("google/gemini-3.8-flash"), list.map { it.id })
        assertEquals("Gemini 3.8 Flash", list[0].label)
        assertEquals(0.3, list[0].pricePerMillionIn!!, 1e-9)
        assertEquals("$0.30", ModelLists.price(list[0].pricePerMillionIn!!))
        assertEquals("/api/v1/models", server.takeRequest().path)
        // Cached: a second load makes no request.
        lists().load(config)
        assertEquals(1, server.requestCount)
    }

    @Test fun openAiCompatibleSendsTheKeyAndFailsToNull() = runBlocking {
        config.apiProvider = ConfigRepository.PROVIDER_OPENAI
        config.baseUrl = server.url("/v1").toString().trimEnd('/')
        config.apiKey = "sk-test"
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"b-model"},{"id":"a-model"}]}"""))
        assertEquals(listOf("a-model", "b-model"), lists().load(config)!!.map { it.id })
        assertEquals("Bearer sk-test", server.takeRequest().getHeader("Authorization"))
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(lists().load(config, refresh = true))
    }

    @Test fun ollamaReadsTags() = runBlocking {
        config.apiProvider = ConfigRepository.PROVIDER_OLLAMA
        config.baseUrl = server.url("").toString().trimEnd('/')
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"gemma4:26b"},{"name":"qwen3-vl:8b"}]}"""))
        assertEquals(listOf("gemma4:26b", "qwen3-vl:8b"), lists().load(config)!!.map { it.id })
        assertEquals("/api/tags", server.takeRequest().path)
    }

    @Test fun anthropicIsAStaticList() = runBlocking {
        config.apiProvider = ConfigRepository.PROVIDER_ANTHROPIC
        assertEquals(ModelLists.ANTHROPIC_STATIC, lists().load(config))
        assertEquals(0, server.requestCount)
    }
}
