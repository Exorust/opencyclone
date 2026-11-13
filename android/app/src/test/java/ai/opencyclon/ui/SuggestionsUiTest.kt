package ai.opencyclon.ui

import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import ai.opencyclon.R
import ai.opencyclon.SettingsActivity
import ai.opencyclon.SettingsFragment
import ai.opencyclon.feed.FakeFeed
import ai.opencyclon.feed.FeedLocator
import ai.opencyclon.proactive.Suggestions
import androidx.preference.SwitchPreference
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SuggestionsUiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setup() {
        FeedLocator.feed = FakeFeed().also { FeedLocator.commands = it }
        context.getSharedPreferences(Suggestions.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun settingsNeedsConfirmationAndOffIsImmediate() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
            val pref = fragment.findPreference<SwitchPreference>(Suggestions.KEY)!!
            assertFalse(pref.isPersistent)
            assertFalse(pref.isChecked)
            assertFalse(pref.callChangeListener(true))
            var dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertEquals(context.getString(R.string.suggestions_confirmation),
                dialog.findViewById<TextView>(android.R.id.message)?.text.toString())
            assertEquals("Turn on", dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
            assertEquals("Not now", dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text.toString())
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(Suggestions(context).enabled)
            assertFalse(pref.isChecked)
            pref.callChangeListener(true)
            dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(Suggestions(context).enabled)
            assertTrue(pref.isChecked)
            assertTrue(pref.callChangeListener(false))
            assertFalse(Suggestions(context).enabled)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun privacyNamesTheSwitchStateAndWindowException() {
        val controller = Robolectric.buildActivity(PrivacyActivity::class.java).setup()
        fun texts(view: View): List<String> = (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
        try {
            val activity = controller.get()
            assertTrue(texts(activity.window.decorView).contains(context.getString(R.string.suggestions_privacy_off)))
            Suggestions(context).enabled = true
            controller.pause().resume()
            val rows = texts(activity.window.decorView)
            assertTrue(rows.contains(context.getString(R.string.suggestions_privacy_on)))
            assertFalse(rows.contains(context.getString(R.string.suggestions_privacy_off)))
        } finally { controller.pause().stop().destroy() }
    }
}
