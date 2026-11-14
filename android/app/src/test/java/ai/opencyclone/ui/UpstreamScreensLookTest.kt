package ai.opencyclone.ui

import ai.opencyclone.R
import ai.opencyclone.SettingsActivity
import ai.opencyclone.SettingsFragment
import ai.opencyclone.ui.theme.Wash
import ai.opencyclone.ui.theme.currentWash
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The upstream screens kept in the new look (lane L6b): settings, setup and pairing, history, memory,
 * and the live view. Every layout they own must show no emoji or decorative symbol,
 * no exclamation mark, and only the text colours ink, ink_2, ink_3, and on_ink.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpstreamScreensLookTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val ownedLayouts = mapOf(
        "activity_settings" to R.layout.activity_settings,
        "settings_row" to R.layout.settings_row,
        "settings_category" to R.layout.settings_category,
        "activity_setup" to R.layout.activity_setup,
        "activity_memory" to R.layout.activity_memory,
        "item_memory" to R.layout.item_memory,
        "activity_view" to R.layout.activity_view,
    )

    /** String names these screens own, by prefix. */
    private val ownedStringPrefixes = listOf(
        "setup_", "pref_", "profile_", "memory_", "history_", "detail_", "view_", "vd_",
        "handoff_title", "handoff_active", "handoff_pending", "handoff_return", "handoff_cancel", "supplement_",
    )

    private fun allowedColours(): Set<Int> =
        listOf(R.color.ink, R.color.ink_2, R.color.ink_3, R.color.on_ink, R.color.primary, R.color.on_primary)
            .map { ContextCompat.getColor(context, it) }.toSet()

    /** Emoji and decorative symbols: arrows, technical symbols, shapes, dingbats, the emoji planes. */
    private fun decorativeIn(text: CharSequence): String? {
        var i = 0
        val s = text.toString()
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val bad = cp in 0x2190..0x21FF || cp in 0x2300..0x23FF || cp in 0x25A0..0x25FF ||
                cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp == 0xFE0F || cp in 0x1F000..0x1FAFF
            if (bad) return String(Character.toChars(cp))
            i += Character.charCount(cp)
        }
        return null
    }

    private fun textViews(view: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        // The text field library draws its own hint, prefix and error views; only its EditText is ours.
        if (view is TextInputLayout) {
            view.editText?.let { out.add(it) }
            return out
        }
        if (view is TextView) out.add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) textViews(view.getChildAt(i), out)
        return out
    }

    private fun inflate(id: Int): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_OpenCyclone)
        return LayoutInflater.from(themed).inflate(id, FrameLayout(themed), false)
    }

    @Test fun ownedLayoutsHaveNoEmojiNoExclamationAndOnlyInkColours() {
        val allowed = allowedColours()
        val problems = mutableListOf<String>()
        ownedLayouts.forEach { (name, id) ->
            val views = textViews(inflate(id))
            assertTrue("$name shows no text", views.isNotEmpty())
            views.forEach { tv ->
                val label = "$name/${runCatching { tv.resources.getResourceEntryName(tv.id) }.getOrDefault(tv.javaClass.simpleName)}"
                for (t in listOfNotNull(tv.text, tv.hint, tv.contentDescription)) {
                    decorativeIn(t)?.let { problems.add("$label has the symbol '$it' in \"$t\"") }
                    if (t.contains('!')) problems.add("$label has an exclamation mark in \"$t\"")
                }
                if (tv.currentTextColor !in allowed) {
                    problems.add("$label uses the text colour #${Integer.toHexString(tv.currentTextColor)}")
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun ownedStringsHaveNoEmojiNoExclamationAndNoOldProductName() {
        val problems = mutableListOf<String>()
        R.string::class.java.fields
            .filter { f -> ownedStringPrefixes.any { f.name.startsWith(it) } && !f.name.startsWith("overlay_permission") }
            .forEach { f ->
                val text = context.getString(f.getInt(null))
                decorativeIn(text)?.let { problems.add("${f.name} has the symbol '$it'") }
                if (text.contains('!')) problems.add("${f.name} has an exclamation mark")
                if (text.contains("OpenCyclone")) problems.add("${f.name} names the product; use app_name")
            }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun numberPadKeysAreInkWordsAndAtLeast48dpTall() {
        val pad = inflate(R.layout.activity_setup).findViewById<ViewGroup>(R.id.setup_number_pad)
        val keys = (0 until pad.childCount).map { pad.getChildAt(it) }.filterIsInstance<TextView>()
        val minPx = 48 * context.resources.displayMetrics.density
        assertTrue(keys.size == 11)
        keys.forEach { key ->
            assertTrue("key ${key.text} is shorter than 48 dp", key.layoutParams.height >= minPx)
            assertTrue(key.currentTextColor == ContextCompat.getColor(context, R.color.ink))
        }
    }

    @Test fun settingsListContainerKeepsTheNavigationInsetWhenPreferencesConsumeInsets() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
        val root = fragment.requireView()
        val original = root.paddingBottom
        val insets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, 63))
            .build()
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, insets)
        assertTrue(root.paddingBottom == original + 63)
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, insets)
        assertTrue(root.paddingBottom == original + 63)
        assertTrue(!fragment.listView.clipToPadding)
        activity.finish()
    }

    @Test fun settingsShowsTheSixGroupsAndHistoryOpensTheHistoryPage() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as SettingsFragment
        val screen = fragment.preferenceScreen
        val groups = (0 until screen.preferenceCount).map { screen.getPreference(it) }
        assertTrue(groups.all { it is PreferenceCategory && it.layoutResource == R.layout.settings_category })
        val expected = listOf(R.string.pref_group_model, R.string.suggestions_group, R.string.pref_group_connection, R.string.pref_group_limits,
            R.string.pref_group_blocked, R.string.pref_group_data).map { activity.getString(it) }
        assertTrue("groups: ${groups.map { it.title }}", groups.map { it.title.toString() } == expected)

        fun rows(g: PreferenceGroup): List<Preference> = (0 until g.preferenceCount).map { g.getPreference(it) }
        val allRows = groups.flatMap { rows(it as PreferenceGroup) }
        assertTrue(allRows.all { it.layoutResource == R.layout.settings_row })
        val history = allRows.first { it.title == activity.getString(R.string.pref_history_title) }
        history.performClick()
        val started = shadowOf(activity).nextStartedActivity
        // Version 2 (F15): the history is the left page of the home page.
        assertTrue(started?.component?.className == ControlPanelActivity::class.java.name)
        assertTrue(started?.getStringExtra(ControlPanelActivity.EXTRA_PAGE) == ControlPanelActivity.PAGE_HISTORY)
    }

    @Test fun memoryOpensOnTheWarmWashWithItsTitle() {
        val memory = Robolectric.buildActivity(MemoryActivity::class.java).setup().get()
        assertTrue(memory.findViewById<View>(R.id.memory_root).currentWash == Wash.Warm)
        assertTrue(memory.findViewById<TextView>(R.id.memory_title).text == memory.getString(R.string.memory_title))
    }
}
