package ai.opencyclone.config

import ai.opencyclone.llm.ChatGptTokenStore
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecretStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cipher = SoftwareCipher()
    private fun store(c: SecretCipher = cipher) = SecretStore(context, c)
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private val allFiles = listOf("opencyclone_config", "opencyclone_profiles", ChatGptTokenStore.PREFS_NAME, SecretStore.PREFS_NAME)

    /** No preferences file holds [secret] as plain text, neither in memory nor on disk. */
    private fun assertNowhere(secret: String) {
        for (name in allFiles) {
            assertFalse("$name holds a plain secret", prefs(name).all.values.any { it.toString().contains(secret) })
        }
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach {
            assertFalse("${it.name} holds a plain secret", it.readText().contains(secret))
        }
    }

    @Test fun roundTripIsEncryptedAtRest() {
        val s = store()
        assertTrue(s.put("k", "sk-round-trip-1"))
        assertEquals("sk-round-trip-1", s.get("k"))
        assertEquals("sk-round-trip-1", store().get("k"))
        assertNowhere("sk-round-trip-1")
        s.remove("k")
        assertNull(s.get("k"))
    }

    @Test fun emptyValueRemoves() {
        val s = store()
        s.put("k", "x")
        s.put("k", "")
        assertNull(s.get("k"))
        assertFalse(prefs(SecretStore.PREFS_NAME).contains("k"))
    }

    @Test fun lostKeyReadsAsAbsent() {
        store().put("k", "sk-lost-1")
        assertNull(store(SoftwareCipher()).get("k"))
        prefs(SecretStore.PREFS_NAME).edit().putString("k", "not base64 !!").commit()
        assertNull(store().get("k"))
    }

    @Test fun failedEncryptionStoresNothing() {
        val broken = object : SecretCipher {
            override fun encrypt(plain: ByteArray): ByteArray = error("keystore gone")
            override fun decrypt(blob: ByteArray): ByteArray = error("keystore gone")
        }
        assertFalse(store(broken).put("k", "sk-broken-1"))
        assertNull(store(broken).get("k"))
        assertNowhere("sk-broken-1")
    }

    @Test fun modelKeyMovesOutOfPlainPreferences() {
        prefs("opencyclone_config").edit().putString("api_key", "sk-plain-config").commit()
        val config = ConfigRepository(context, store())
        assertEquals("sk-plain-config", config.apiKey)
        assertFalse(prefs("opencyclone_config").contains("api_key"))
        assertEquals("sk-plain-config", ConfigRepository(context, store()).apiKey)
        assertNowhere("sk-plain-config")
    }

    @Test fun profileKeysMoveOutOfTheProfileList() {
        val old = JSONArray().put(JSONObject().put("name", "work").put("provider", "openai")
            .put("api_key", "sk-plain-profile").put("model", "m").put("base_url", "https://x"))
        prefs("opencyclone_profiles").edit().putString("profiles_json", old.toString()).commit()
        val repo = ProfileRepository(ConfigRepository(context, store()), context, store())
        assertEquals("sk-plain-profile", repo.getProfile("work")!!.apiKey)
        assertFalse(prefs("opencyclone_profiles").getString("profiles_json", "")!!.contains("sk-plain-profile"))
        assertNowhere("sk-plain-profile")
        // Switching applies the key to the current configuration, still encrypted.
        val config = ConfigRepository(context, store())
        assertTrue(ProfileRepository(config, context, store()).switchTo("work"))
        assertEquals("sk-plain-profile", config.apiKey)
        assertNowhere("sk-plain-profile")
        repo.deleteProfile("work")
        assertNull(store().get(ProfileRepository.secretName("work")))
    }

    @Test fun signInTokensMoveOutOfPlainPreferences() {
        prefs(ChatGptTokenStore.PREFS_NAME).edit()
            .putString("access_token", "plain-access").putString("refresh_token", "plain-refresh")
            .putString("account_id", "plain-account").putLong("expires_at_ms", 42L).commit()
        val tokens = ChatGptTokenStore(context, store())
        assertEquals("plain-access", tokens.accessToken)
        assertEquals("plain-refresh", tokens.refreshToken)
        assertEquals("plain-account", tokens.accountId)
        assertEquals(42L, tokens.expiresAtMs)
        assertTrue(tokens.hasRefreshToken())
        listOf("plain-access", "plain-refresh", "plain-account").forEach { assertNowhere(it) }
        tokens.save("a2", "r2", "acct2", 7L)
        assertEquals("r2", ChatGptTokenStore(context, store()).refreshToken)
        tokens.clear()
        assertFalse(ChatGptTokenStore(context, store()).hasRefreshToken())
    }

    @Test fun lostKeyMeansSignInAgain() {
        ChatGptTokenStore(context, store()).save("a", "r", "acct", 1L)
        ConfigRepository(context, store()).apiKey = "sk-lost-config"
        val other = SecretStore(context, SoftwareCipher())
        assertFalse(ChatGptTokenStore(context, other).hasRefreshToken())
        assertEquals("", ConfigRepository(context, other).apiKey)
    }
}
