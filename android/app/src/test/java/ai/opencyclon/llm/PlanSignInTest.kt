package ai.opencyclon.llm

import ai.opencyclon.config.ConfigRepository
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/** Sign-in helpers: authorize URLs, state check, code exchanges, the loopback listener, the import file, "configured". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlanSignInTest {
    private lateinit var server: MockWebServer
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val http = OkHttpClient()
    private val now = 1_800_000_000_000L

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        ChatGptTokenStore(context).clear()
    }

    @After fun tearDown() {
        server.shutdown()
        ChatGptTokenStore(context).clear()
        ConfigRepository(context).apply { apiProvider = ConfigRepository.PROVIDER_OPENAI; apiKey = "" }
    }

    private fun jwt(claims: JSONObject): String {
        fun b(s: String) = Base64.encodeToString(s.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return b("""{"alg":"none"}""") + "." + b(claims.toString()) + ".sig"
    }

    @Test fun pkceChallengeIsSha256OfVerifier() {
        val pkce = PlanSignIn.generatePkce()
        assertEquals(86, pkce.verifier.length) // 64 bytes, URL-safe base64, no padding
        val digest = MessageDigest.getInstance("SHA-256").digest(pkce.verifier.toByteArray())
        assertEquals(Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), pkce.challenge)
        assertFalse(pkce.verifier.contains('=') || pkce.verifier.contains('+') || pkce.verifier.contains('/'))
    }

    @Test fun chatGptAuthorizeUrlCarriesCodexParameters() {
        val pkce = PlanSignIn.Pkce("verifier", "challenge-1")
        val uri = Uri.parse(PlanSignIn.chatGptAuthorizeUrl(pkce, "state-1"))
        assertEquals("https", uri.scheme)
        assertEquals("auth.openai.com", uri.host)
        assertEquals("/oauth/authorize", uri.path)
        assertEquals("code", uri.getQueryParameter("response_type"))
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", uri.getQueryParameter("client_id"))
        assertEquals("http://127.0.0.1:1455/auth/callback", uri.getQueryParameter("redirect_uri"))
        assertEquals("challenge-1", uri.getQueryParameter("code_challenge"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        assertEquals("state-1", uri.getQueryParameter("state"))
        assertEquals("openid profile email offline_access api.connectors.read api.connectors.invoke", uri.getQueryParameter("scope"))
        assertEquals("true", uri.getQueryParameter("id_token_add_organizations"))
        assertEquals("true", uri.getQueryParameter("codex_cli_simplified_flow"))
        assertEquals("codex_cli_rs", uri.getQueryParameter("originator"))
    }

    @Test fun openRouterAuthorizeUrl() {
        val uri = Uri.parse(PlanSignIn.openRouterAuthorizeUrl(PlanSignIn.Pkce("v", "c-1"), "s-1"))
        assertEquals("openrouter.ai", uri.host)
        assertEquals("/auth", uri.path)
        assertEquals("http://localhost:1455/openrouter/callback", uri.getQueryParameter("callback_url"))
        assertEquals("c-1", uri.getQueryParameter("code_challenge"))
        assertEquals("S256", uri.getQueryParameter("code_challenge_method"))
        assertEquals("s-1", uri.getQueryParameter("state"))
    }

    @Test fun stateIsCheckedBeforeTheCode() {
        assertEquals("abc", PlanSignIn.codeFromCallback(mapOf("code" to "abc", "state" to "s"), "s"))
        for (params in listOf(mapOf("code" to "abc", "state" to "other"), mapOf("code" to "abc"),
                mapOf("error" to "access_denied", "state" to "s"), mapOf("state" to "s"))) {
            try {
                PlanSignIn.codeFromCallback(params, "s")
                throw AssertionError("accepted $params")
            } catch (_: IOException) {}
        }
    }

    @Test fun chatGptCodeExchange() {
        val idToken = jwt(JSONObject().put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", "acct-9")))
        server.enqueue(MockResponse().setResponseCode(200).setBody(JSONObject()
            .put("id_token", idToken).put("access_token", "a-1").put("refresh_token", "r-1").put("expires_in", 600).toString()))
        val tokens = PlanSignIn.exchangeChatGptCode(http, "code-1", "verifier-1", server.url("/oauth/token").toString(), now)
        assertEquals("a-1", tokens.accessToken)
        assertEquals("r-1", tokens.refreshToken)
        assertEquals("acct-9", tokens.accountId)
        assertEquals(now + 600_000, tokens.expiresAtMs)
        val request = server.takeRequest()
        assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type")!!.substringBefore(';'))
        val form = LoopbackListener.parseQuery(request.body.readUtf8())
        assertEquals("authorization_code", form["grant_type"])
        assertEquals(PlanSignIn.CHATGPT_CLIENT_ID, form["client_id"])
        assertEquals("code-1", form["code"])
        assertEquals("http://127.0.0.1:1455/auth/callback", form["redirect_uri"])
        assertEquals("verifier-1", form["code_verifier"])
    }

    @Test fun accessTokenExpiryIsUsedWithoutExpiresIn() {
        val access = jwt(JSONObject().put("exp", 1_900_000_000L))
        assertEquals(1_900_000_000_000L, ChatGptTokens.expiresAtMs(access, null, now))
    }

    @Test fun openRouterCodeExchange() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"key":"sk-or-1","user_id":"u"}"""))
        assertEquals("sk-or-1", PlanSignIn.exchangeOpenRouterCode(http, "code-2", "verifier-2", server.url("/api/v1/auth/keys").toString()))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("code-2", body.getString("code"))
        assertEquals("verifier-2", body.getString("code_verifier"))
        assertEquals("S256", body.getString("code_challenge_method"))

        PlanSignIn.saveOpenRouter(context, "sk-or-1")
        val config = ConfigRepository(context)
        assertEquals(ConfigRepository.PROVIDER_OPENAI, config.apiProvider)
        assertEquals("https://openrouter.ai/api/v1", config.baseUrl)
        assertEquals("sk-or-1", config.apiKey)
        assertTrue(PlanSignIn.isOpenRouterConnected(config))
    }

    @Test fun loopbackListenerAnswersOneCallbackAndIgnoresOtherPaths() {
        val listener = LoopbackListener(0)
        var received: Map<String, String>? = null
        val waiter = thread {
            listener.use { it.awaitOne("/auth/callback", 10_000) { params -> received = params; "<p>ok</p>" } }
        }
        fun get(path: String): String = Socket("127.0.0.1", listener.port).use { s ->
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            s.getInputStream().bufferedReader().readText()
        }
        assertTrue(get("/favicon.ico").startsWith("HTTP/1.1 404"))
        val reply = get("/auth/callback?code=c%2B1&state=s-1")
        waiter.join(5_000)
        assertTrue(reply.startsWith("HTTP/1.1 200"))
        assertTrue(reply.contains("<p>ok</p>"))
        assertEquals(mapOf("code" to "c+1", "state" to "s-1"), received)
    }

    @Test fun resultPageLinksBackToTheApp() {
        assertTrue(PlanSignIn.resultPage(null).contains("href=\"opencyclon://signed-in\""))
        assertTrue(PlanSignIn.resultPage("bad <x>").contains("bad &lt;x&gt;"))
    }

    @Test fun importsFlatAuthFile() {
        File(context.filesDir, PlanSignIn.IMPORT_FILE).writeText(
            """{"access_token":"a-1","refresh_token":"r-1","account_id":"acct-1"}""")
        assertTrue(PlanSignIn.importAuthFileIfPresent(context, now))
        val store = ChatGptTokenStore(context)
        assertEquals("a-1", store.accessToken)
        assertEquals("r-1", store.refreshToken)
        assertEquals("acct-1", store.accountId)
        val config = ConfigRepository(context)
        assertEquals(ConfigRepository.PROVIDER_CHATGPT, config.apiProvider)
        assertEquals(ConfigRepository.DEFAULT_CHATGPT_MODEL, config.model)
        assertFalse(File(context.filesDir, PlanSignIn.IMPORT_FILE).exists())
        assertFalse(PlanSignIn.importAuthFileIfPresent(context, now))
    }

    @Test fun importsCodexCliShapedAuthFile() {
        val idToken = jwt(JSONObject().put("https://api.openai.com/auth", JSONObject().put("chatgpt_account_id", "acct-7")))
        File(context.filesDir, PlanSignIn.IMPORT_FILE).writeText(JSONObject()
            .put("OPENAI_API_KEY", JSONObject.NULL)
            .put("tokens", JSONObject().put("id_token", idToken).put("access_token", "a-2").put("refresh_token", "r-2"))
            .toString())
        assertTrue(PlanSignIn.importAuthFileIfPresent(context, now))
        val store = ChatGptTokenStore(context)
        assertEquals("a-2", store.accessToken)
        assertEquals("r-2", store.refreshToken)
        assertEquals("acct-7", store.accountId)
        assertFalse(File(context.filesDir, PlanSignIn.IMPORT_FILE).exists())
    }

    @Test fun chatGptCountsAsConfiguredOnlyWithARefreshToken() {
        val config = ConfigRepository(context)
        config.apiProvider = ConfigRepository.PROVIDER_CHATGPT
        config.apiKey = ""
        assertFalse(ModelClients.needsKey(ConfigRepository.PROVIDER_CHATGPT))
        assertFalse(ModelClients.isConfigured(config))
        ChatGptTokenStore(context).save("", "r-1", "", 0)
        assertTrue(ModelClients.isConfigured(config))
        assertTrue(PlanSignIn.isChatGptConnected(config))
        assertTrue(ModelClients.forConfig(config) is ChatGptFallback)
        ChatGptTokenStore(context).clear()
        config.apiKey = "sk-ignored"
        assertFalse(ModelClients.isConfigured(config))
    }
}
