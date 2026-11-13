package ai.opencyclon.ui

import ai.opencyclon.R
import ai.opencyclon.SettingsActivity
import ai.opencyclon.config.ConfigRepository
import ai.opencyclon.db.AppDatabase
import ai.opencyclon.gate.EditActivity
import ai.opencyclon.integrations.IntegrationStore
import ai.opencyclon.integrations.IntegrationsActivity
import ai.opencyclon.integrations.ToolRegistry
import ai.opencyclon.ui.screens.MoreActivity
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The plain look of the secondary screens (UI.md 5.12): titles, one primary button, field rows, and the More list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlainScreensTest {
    private lateinit var db: AppDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val registryField = ToolRegistry::class.java.getDeclaredField("instance").apply { isAccessible = true }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        RulesActivity.databaseFor = { db }
        FirstRunActivity.databaseFor = { db }
        val prefs = context.getSharedPreferences("integration_tokens_screens_test", Context.MODE_PRIVATE)
        // The real store uses the Android keystore, which Robolectric lacks; hand the screen a test registry.
        registryField.set(null, ToolRegistry(IntegrationStore(db.integrationDao(), prefs)))
    }

    @After fun close() {
        RulesActivity.databaseFor = { AppDatabase.getInstance(it) }
        FirstRunActivity.databaseFor = { AppDatabase.getInstance(it) }
        registryField.set(null, null)
        db.close()
    }

    private fun waitFor(done: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        fail("condition not met")
    }

    private fun allViews(group: ViewGroup): List<View> =
        (0 until group.childCount).flatMap { i ->
            val v = group.getChildAt(i)
            listOf(v) + if (v is ViewGroup) allViews(v) else emptyList()
        }

    private fun views(activity: Activity) = allViews(activity.window.decorView as ViewGroup)

    private fun title(activity: Activity) = activity.findViewById<TextView>(R.id.screen_title).text.toString()

    /** Shown buttons drawn in the primary style: dark pill with on-ink text. */
    private fun primaryButtons(activity: Activity): List<Button> {
        val onInk = ContextCompat.getColor(activity, R.color.on_ink)
        return views(activity).filterIsInstance<Button>().filter { it.isShown && it.currentTextColor == onInk }
    }

    private fun assertFieldsOnlyInFieldRows(activity: Activity) {
        for (field in views(activity).filterIsInstance<EditText>()) {
            assertEquals("EditText outside a text-field row", R.id.row_field, (field.parent as View).id)
        }
    }

    private fun <T : Activity> start(cls: Class<T>, intent: Intent? = null): T {
        val controller = if (intent == null) Robolectric.buildActivity(cls) else Robolectric.buildActivity(cls, intent)
        return controller.setup().get()
    }

    private fun editIntent() = Intent(context, EditActivity::class.java)
        .putExtra(EditActivity.EXTRA_TASK_ID, 7L)
        .putExtra(EditActivity.EXTRA_SUMMARY, "Send\nSee you at six.")

    private fun allScreens(): List<Activity> = listOf(
        start(FirstRunActivity::class.java),
        start(PrivacyActivity::class.java),
        start(RulesActivity::class.java),
        start(IntegrationsActivity::class.java),
        start(EditActivity::class.java, editIntent()),
        start(MoreActivity::class.java),
    )

    @Test fun everyScreenHasItsTitle() {
        val titles = allScreens().map { title(it) }
        assertEquals(
            listOf("Set up", "Privacy", "Rules", "Integrations", "Change the request", context.getString(R.string.app_name)),
            titles
        )
    }

    @Test fun everyScreenHasAtMostOnePrimaryButtonAndNoLooseTextFields() {
        val expectedPrimary = listOf(1, 0, 0, 1, 1, 0)
        allScreens().forEachIndexed { i, activity ->
            assertEquals(activity.javaClass.simpleName, expectedPrimary[i], primaryButtons(activity).size)
            assertFieldsOnlyInFieldRows(activity)
        }
    }

    @Test fun firstRunHasPermissionStatusRowsMatchingPermissionStatus() {
        val activity = start(FirstRunActivity::class.java)
        val expected = PermissionStatus.rows(activity)
        val rows = views(activity).filter { it.id == R.id.row_status }
        assertEquals(expected.size, rows.size)
        expected.zip(rows).forEach { (perm, row) ->
            assertEquals(perm.name, row.findViewById<TextView>(R.id.status_text).text.toString())
            val check = row.findViewById<View>(R.id.status_check)
            val grant = row.findViewById<Button>(R.id.status_grant)
            assertEquals(perm.granted, check.visibility == View.VISIBLE)
            assertEquals(!perm.granted, grant.visibility == View.VISIBLE)
            if (!perm.granted) {
                assertEquals(perm.howToFix, row.findViewById<TextView>(R.id.status_detail).text.toString())
                grant.performClick()
                assertNotNull("Grant opens a page", shadowOf(activity).nextStartedActivity)
            }
        }
        assertEquals("Granted", rows.first().findViewById<View>(R.id.status_check).contentDescription)
    }

    @Test fun firstRunUseAKeyOpensTheManualFields() {
        val activity = start(FirstRunActivity::class.java)
        val address = views(activity).filterIsInstance<EditText>().first()
        assertFalse(address.isShown)
        activity.window.decorView.findViewWithTag<View>(FirstRunActivity.TAG_USE_KEY).performClick()
        assertTrue(address.isShown)
        assertEquals(ConfigRepository(context).baseUrl, address.text.toString())
    }

    @Test fun firstRunKeyIntentShowsTheManualFieldsAtOnce() {
        val activity = start(FirstRunActivity::class.java, FirstRunActivity.keyIntent(context))
        val address = views(activity).filterIsInstance<EditText>().first()
        assertTrue(address.isShown)
        assertEquals(90f, activity.window.decorView.findViewWithTag<View>(FirstRunActivity.TAG_USE_KEY)
            .findViewById<View>(R.id.row_chevron).rotation, 0.01f)
    }

    @Test fun firstRunHasSignInPillsStatusAndTheMoreHint() {
        val activity = start(FirstRunActivity::class.java)
        val decor = activity.window.decorView
        assertEquals("Sign in with ChatGPT", decor.findViewWithTag<Button>(FirstRunActivity.TAG_SIGN_IN_CHATGPT).text.toString())
        assertEquals("Sign in with OpenRouter", decor.findViewWithTag<Button>(FirstRunActivity.TAG_SIGN_IN_OPENROUTER).text.toString())
        assertTrue(decor.findViewWithTag<Button>(FirstRunActivity.TAG_SIGN_IN_OPENROUTER).minHeight >=
            activity.resources.getDimensionPixelSize(R.dimen.button_height))
        assertNotNull(decor.findViewWithTag<TextView>(FirstRunActivity.TAG_SIGN_IN_STATUS))
        assertEquals(
            "Tap menu for history, rules, privacy and settings.",
            decor.findViewWithTag<TextView>(FirstRunActivity.TAG_MORE_HINT).text.toString()
        )
        decor.findViewWithTag<Button>(FirstRunActivity.TAG_SIGN_IN_OPENROUTER).performClick()
        assertEquals(
            context.getString(R.string.signin_waiting),
            decor.findViewWithTag<TextView>(FirstRunActivity.TAG_SIGN_IN_STATUS).text.toString()
        )
    }

    @Test fun rulesEmptyStateIsOneBodySentence() {
        val activity = start(RulesActivity::class.java)
        waitFor { activity.rowViews().isNotEmpty() }
        val empty = activity.rowViews().single() as TextView
        assertEquals(RulesActivity.TAG_EMPTY, empty.tag)
        assertEquals(17f * activity.resources.displayMetrics.scaledDensity, empty.textSize, 0.5f)
    }

    @Test fun integrationsPrefillsFromTheIntentAndFocusesTheToken() {
        val intent = Intent(context, IntegrationsActivity::class.java)
            .putExtra(IntegrationsActivity.EXTRA_NAME, "Notes")
            .putExtra(IntegrationsActivity.EXTRA_ADDRESS, "https://notes.example/mcp")
        val activity = start(IntegrationsActivity::class.java, intent)
        val fields = views(activity).filterIsInstance<EditText>()
        assertEquals(3, fields.size)
        assertEquals("Notes", fields[0].text.toString())
        assertEquals("https://notes.example/mcp", fields[1].text.toString())
        assertTrue(fields[2].isFocused)
        val labels = views(activity).filter { it.id == R.id.field_label }.map { (it as TextView).text.toString() }
        assertEquals(listOf("Name", "Address", "Token"), labels)
    }

    @Test fun integrationsConnectReportsAnErrorWithoutAnAddress() {
        val intent = Intent(context, IntegrationsActivity::class.java).putExtra(IntegrationsActivity.EXTRA_NAME, "Notes")
        val activity = start(IntegrationsActivity::class.java, intent)
        waitFor { views(activity).any { it is TextView && it.text.toString() == IntegrationsActivity.NO_SERVICES } }
        val connect = primaryButtons(activity).single()
        assertEquals("Connect", connect.text.toString())
        connect.performClick()
        val message = activity.window.decorView.findViewWithTag<TextView>(IntegrationsActivity.TAG_MESSAGE)
        waitFor { message.text.startsWith(IntegrationsActivity.CONNECT_FAILED) }
        assertTrue(connect.isEnabled)
    }

    @Test fun editShowsTheHeldRequestAndCancelCloses() {
        val activity = start(EditActivity::class.java, editIntent())
        assertEquals("Send\nSee you at six.", activity.findViewById<TextView>(R.id.screen_lead).text.toString())
        val save = activity.findViewById<Button>(R.id.screen_primary)
        val cancel = activity.findViewById<Button>(R.id.screen_pale)
        assertEquals("Save", save.text.toString())
        assertEquals("Cancel", cancel.text.toString())
        assertEquals(1, views(activity).filterIsInstance<EditText>().size)
        // Save with an empty field does nothing.
        save.performClick()
        assertFalse(activity.isFinishing)
        cancel.performClick()
        assertTrue(activity.isFinishing)
    }

    @Test fun moreRowsStartTheRightScreens() {
        val activity = start(MoreActivity::class.java)
        val rows = views(activity).filter { it.id == R.id.row_text }
        assertEquals(
            listOf("History", "Rules", "Privacy", "Integrations", "Set up", "Settings"),
            rows.map { it.findViewById<TextView>(R.id.row_title).text.toString() }
        )
        val expected = listOf(
            ControlPanelActivity::class.java, RulesActivity::class.java, PrivacyActivity::class.java,
            IntegrationsActivity::class.java, FirstRunActivity::class.java, SettingsActivity::class.java,
        )
        rows.zip(expected).forEach { (row, cls) ->
            assertEquals(View.VISIBLE, row.findViewById<View>(R.id.row_chevron).visibility)
            row.performClick()
            assertEquals(cls.name, shadowOf(activity).nextStartedActivity.component?.className)
        }
        assertEquals(MoreActivity::class.java.name, MoreActivity.intent(context).component?.className)
    }
}
