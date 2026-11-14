package ai.opencyclone.ui

import ai.opencyclone.config.ConfigRepository
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppEntriesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun tileAndShortcutStartVoice() {
        assertTrue(AppEntries.wantsVoice(QuickAskTileService.askIntent(context)))
        assertTrue(AppEntries.wantsVoice(Intent(AppEntries.ACTION_ASK)))
        assertFalse(AppEntries.wantsVoice(Intent(Intent.ACTION_MAIN)))
        assertFalse(AppEntries.wantsVoice(null))
    }

    @Test fun firstRunIsNeededOnlyWithoutAUsableModel() {
        val config = ConfigRepository(context)
        try {
            config.apiProvider = ConfigRepository.PROVIDER_OPENAI
            config.apiKey = ""
            assertTrue(AppEntries.needsFirstRun(config))
            config.apiKey = "sk-test"
            assertFalse(AppEntries.needsFirstRun(config))
            config.apiKey = ""
            config.apiProvider = ConfigRepository.PROVIDER_OLLAMA
            assertFalse(AppEntries.needsFirstRun(config))
        } finally {
            config.apiProvider = ConfigRepository.PROVIDER_OPENAI
            config.apiKey = ""
        }
    }
}
