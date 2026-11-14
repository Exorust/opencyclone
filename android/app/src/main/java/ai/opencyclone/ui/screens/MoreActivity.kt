package ai.opencyclone.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ai.opencyclone.R
import ai.opencyclone.SettingsActivity
import ai.opencyclone.integrations.IntegrationsActivity
import ai.opencyclone.ui.ControlPanelActivity
import ai.opencyclone.ui.FirstRunActivity
import ai.opencyclone.ui.PrivacyActivity
import ai.opencyclone.ui.RulesActivity

/** The plain list behind a long press on the greeting (plan decision 9, UI.md section 9 item 5). */
class MoreActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, MoreActivity::class.java)

        /** Row label and the screen it opens, in order. */
        val ENTRIES: List<Pair<Int, Class<*>>> = listOf(
            R.string.screens_more_history to ControlPanelActivity::class.java, // the history page (F15)
            R.string.screens_more_rules to RulesActivity::class.java,
            R.string.screens_more_privacy to PrivacyActivity::class.java,
            R.string.screens_more_integrations to IntegrationsActivity::class.java,
            R.string.screens_more_setup to FirstRunActivity::class.java,
            R.string.screens_more_settings to SettingsActivity::class.java,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = PlainScreen.install(this, getString(R.string.app_name))
        for ((label, target) in ENTRIES) {
            screen.textRow(getString(label), chevron = true, onClick = {
                startActivity(if (target == ControlPanelActivity::class.java) ControlPanelActivity.historyIntent(this)
                else Intent(this, target))
            })
        }
    }
}
