package ai.opencyclone.ui

import android.content.Context
import android.content.Intent
import ai.opencyclone.config.ConfigRepository
import ai.opencyclone.llm.ModelClients

/** Entry points that the home page and the launcher shortcut share: voice start, first run. */
object AppEntries {
    /** Action of the static launcher shortcut "Ask" (res/xml/shortcuts.xml). */
    const val ACTION_ASK = "ai.opencyclone.action.ASK"

    /** The quick-settings tile and the launcher shortcut ask the home page to open the listening screen. */
    fun wantsVoice(intent: Intent?): Boolean = intent != null &&
        (intent.action == ACTION_ASK || intent.getBooleanExtra(QuickAskTileService.EXTRA_START_VOICE, false))

    fun needsFirstRun(config: ConfigRepository): Boolean = !ModelClients.isConfigured(config)

    fun openFirstRun(context: Context) {
        context.startActivity(Intent(context, FirstRunActivity::class.java))
    }
}
