package ai.opencyclon.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ai.opencyclon.R
import ai.opencyclon.config.ConfigRepository
import ai.opencyclon.gate.BlockedApps
import ai.opencyclon.ui.screens.PlainScreen
import ai.opencyclon.ui.widgets.WidgetHost
import ai.opencyclon.widgets.WidgetSources
import ai.opencyclon.widgets.Widgets
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Shows what leaves the phone, what stays, and which apps are blocked (F13.1), in the plain look. */
class PrivacyActivity : AppCompatActivity() {

    private lateinit var screen: PlainScreen
    private var renders = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = PlainScreen.install(this, getString(R.string.h_privacy_title), getString(R.string.h_privacy_dropped))
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        screen.rows.removeAllViews()
        val config = ConfigRepository(this)

        screen.section(getString(R.string.h_privacy_data_heading))
        screen.textRow(getString(R.string.h_privacy_model_address, config.baseUrl))
        screen.textRow(getString(if (ai.opencyclon.proactive.Suggestions(this).enabled)
            R.string.suggestions_privacy_on else R.string.suggestions_privacy_off))
        screen.textRow(getString(R.string.h_privacy_data_screens))
        val voice = ai.opencyclon.ui.voice.VoiceFactory.choice(this)
        screen.textRow(
            getString(R.string.voice_privacy_row, ai.opencyclon.ui.voice.VoiceChoices.name(this, voice)),
            detail = ai.opencyclon.ui.voice.VoiceChoices.where(this, voice),
        )

        screen.section(getString(R.string.screens_privacy_stays_heading))
        screen.textRow(getString(R.string.screens_privacy_stays_blocked))
        screen.textRow(getString(R.string.screens_privacy_stays_codes))

        screen.section(getString(R.string.h_privacy_blocked_heading))
        screen.caption(getString(R.string.h_privacy_blocked_fixed))
        // FIXED leaves out this app; BlockedApps.all adds it at run time by its real package name.
        screen.textRow(getString(R.string.screens_this_app), detail = packageName)
        BlockedApps.FIXED.sorted().forEach { appRow(it) }
        screen.caption(getString(R.string.h_privacy_blocked_owner))
        val ownerAdded = config.blacklistedPackages - BlockedApps.FIXED - packageName
        if (ownerAdded.isEmpty()) {
            screen.sentence(getString(R.string.h_privacy_blocked_owner_none))
        } else {
            ownerAdded.sorted().forEach { appRow(it) }
        }

        screen.section(getString(R.string.screens_rules_title))
        screen.textRow(getString(R.string.h_privacy_open_rules), chevron = true, onClick = {
            startActivity(Intent(this, RulesActivity::class.java))
        })

        widgetsSection(++renders)
    }

    /** F16.3: each saved widget with the names of the sources it may read; a tap opens the Widgets page. */
    private fun widgetsSection(render: Int) {
        if (!Widgets.ready(this)) return
        lifecycleScope.launch {
            val rows = Widgets.store.list().map { widget ->
                val names = widget.sourceList.mapNotNull { Widgets.sources.ownerName(it) }
                    .ifEmpty { listOf(WidgetSources.CLOCK_NAME) }
                widget.name to getString(R.string.widgets_privacy_reads, names.joinToString(", "))
            }
            if (render != renders) return@launch // a newer render replaced the rows
            screen.section(getString(R.string.widgets_privacy_heading))
            screen.caption(getString(R.string.widgets_privacy_lead))
            if (rows.isEmpty()) screen.sentence(getString(R.string.widgets_privacy_none))
            rows.forEach { (name, reads) ->
                screen.textRow(name, detail = reads, chevron = true, onClick = {
                    startActivity(WidgetHost.homeIntent(this@PrivacyActivity, WidgetHost.PAGE_WIDGETS))
                })
            }
        }
    }

    /** The app label with the package name below when the app is installed, otherwise the package name. */
    private fun appRow(packageName: String) {
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        if (label == null) screen.textRow(packageName) else screen.textRow(label, detail = packageName)
    }
}
