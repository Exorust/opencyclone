package ai.opencyclon

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import ai.opencyclon.config.ConfigRepository
import ai.opencyclon.ui.theme.Wash
import ai.opencyclon.ui.theme.applySystemBarPadding
import ai.opencyclon.ui.theme.drawBehindSystemBars
import ai.opencyclon.ui.theme.setWash

/** Settings: a title in the greeting style over the preference list. Back is the system gesture. */
class SettingsActivity : AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    lateinit var config: ConfigRepository
        private set

    private lateinit var titleView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawBehindSystemBars()
        setContentView(R.layout.activity_settings)
        config = ConfigRepository(this)

        val root = findViewById<View>(R.id.settings_root)
        root.setWash(Wash.Warm)
        // The list pads itself at the bottom so its last row scrolls above the navigation bar.
        applySystemBarPadding(root, top = true, bottom = false)
        titleView = findViewById(R.id.settings_title)

        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                titleView.text = getString(R.string.action_settings)
            }
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    // --- Sub-screen navigation ---

    override fun onPreferenceStartScreen(
        caller: PreferenceFragmentCompat,
        prefScreen: PreferenceScreen
    ): Boolean {
        val fragment = SettingsFragment()
        val args = Bundle()
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, prefScreen.key)
        fragment.arguments = args
        titleView.text = prefScreen.title
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, fragment)
            .addToBackStack(prefScreen.key)
            .commit()
        return true
    }
}
