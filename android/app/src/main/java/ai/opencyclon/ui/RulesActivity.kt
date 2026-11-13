package ai.opencyclon.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.opencyclon.R
import ai.opencyclon.db.AppDatabase
import ai.opencyclon.proactive.RuleEntity
import ai.opencyclon.ui.screens.PlainScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lists the owner's notification rules (F6) as rows with a pale "Remove" pill on each. */
class RulesActivity : AppCompatActivity() {

    companion object {
        const val TAG_EMPTY = "rules_empty"
        const val TAG_ROW = "rules_row"

        @VisibleForTesting
        internal var databaseFor: (Context) -> AppDatabase = { AppDatabase.getInstance(it) }

        /** What a row shows: the app label for a package rule, the owner's words for a text rule. */
        fun rowText(rule: RuleEntity, labelOf: (String) -> String?): String {
            val pkg = rule.packageName
            return if (rule.kind == "package" && pkg != null) labelOf(pkg) ?: pkg else rule.text
        }
    }

    private lateinit var screen: PlainScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = PlainScreen.install(this, getString(R.string.screens_rules_title))
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val dao = databaseFor(this).ruleDao()
        lifecycleScope.launch {
            val rules = withContext(Dispatchers.IO) { dao.all() }
            render(rules)
        }
    }

    private fun render(rules: List<RuleEntity>) {
        screen.rows.removeAllViews()
        if (rules.isEmpty()) {
            screen.sentence(getString(R.string.h_rules_empty)).tag = TAG_EMPTY
            return
        }
        val dao = databaseFor(this).ruleDao()
        for (rule in rules) {
            val text = rowText(rule, ::appLabel)
            val row = screen.textRow(text, action = getString(R.string.screens_remove), onAction = { button ->
                button.isEnabled = false
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.remove(rule.id) }
                    reload()
                }
            })
            row.tag = TAG_ROW
            row.findViewById<View>(R.id.row_action).contentDescription = getString(R.string.screens_remove_named, text)
        }
    }

    private fun appLabel(packageName: String): String? = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    @VisibleForTesting
    internal fun rowViews(): List<View> = (0 until screen.rows.childCount).map { screen.rows.getChildAt(it) }
}
