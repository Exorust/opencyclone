package ai.opencyclon.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The ChatGPT default, the one-time replacement of gpt-5.5, the by-hand flag, and the switch notice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelConfigTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clear() {
        context.getSharedPreferences("opencyclon_config", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun chatGptDefaultIsGpt56Terra() {
        assertEquals("gpt-5.6-terra", ConfigRepository.DEFAULT_CHATGPT_MODEL)
        assertEquals("gpt-5.6-terra", ConfigRepository.CHATGPT_FALLBACK.first())
    }

    @Test fun storedGpt55ForChatGptIsReplacedOnce() {
        val config = ConfigRepository(context)
        config.apiProvider = ConfigRepository.PROVIDER_CHATGPT
        config.model = "gpt-5.5"
        assertEquals("gpt-5.6-terra", config.model)
        // After the one check, a gpt-5.5 written later (for example by hand) stays.
        config.model = "gpt-5.5"
        assertEquals("gpt-5.5", ConfigRepository(context).model)
    }

    @Test fun otherProvidersKeepGpt55() {
        val config = ConfigRepository(context)
        config.apiProvider = ConfigRepository.PROVIDER_OPENAI
        config.model = "gpt-5.5"
        assertEquals("gpt-5.5", config.model)
    }

    @Test fun anotherChatGptModelIsKept() {
        val config = ConfigRepository(context)
        config.apiProvider = ConfigRepository.PROVIDER_CHATGPT
        config.model = "gpt-6-sol"
        assertEquals("gpt-6-sol", config.model)
    }

    @Test fun profileWithOldChatGptDefaultGetsTheNewOne() {
        val config = ConfigRepository(context)
        config.applyProfile(ConfigRepository.PROVIDER_CHATGPT, "", "gpt-5.5", ConfigRepository.DEFAULT_CHATGPT_BASE_URL, "plan")
        assertEquals("gpt-5.6-terra", config.model)
    }

    @Test fun chooseModelMarksTheChoiceAsByHand() {
        val config = ConfigRepository(context)
        assertFalse(config.modelChosenByHand)
        config.chooseModel(" gpt-6-luna ")
        assertEquals("gpt-6-luna", config.model)
        assertTrue(config.modelChosenByHand)
    }

    @Test fun switchNoticeIsTakenOnce() {
        val config = ConfigRepository(context)
        config.modelSwitchNotice = "I switched to b, because this account cannot use a."
        assertEquals("I switched to b, because this account cannot use a.", config.takeModelSwitchNotice())
        assertNull(config.takeModelSwitchNotice())
    }

    @Test fun phrasesSaveAndDescribeTheModel() {
        val config = ConfigRepository(context)
        assertEquals("The next task uses gpt-6.1-sol.",
            ModelPhrases.handle(config, ModelPhrase.Use("GPT 6.1 Sol"), listOf("gpt-6.1-sol")))
        assertTrue(config.modelChosenByHand)
        assertEquals("I use gpt-6.1-sol.", ModelPhrases.handle(config, ModelPhrase.Which, emptyList()))
    }
}
