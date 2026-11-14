package ai.opencyclone.ui.home

import ai.opencyclone.R
import ai.opencyclone.config.ConfigRepository
import ai.opencyclone.feed.FakeFeed
import ai.opencyclone.feed.FeedLocator
import ai.opencyclone.ui.ControlPanelActivity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Process
import android.view.View
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The page's home-mode hooks (lane P1): Back, a new HOME intent, the swipe up, and "open <name>". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeModePageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var feed: FakeFeed
    private lateinit var config: ConfigRepository
    private val started = mutableListOf<LaunchableApp>()
    private val clock = LaunchableApp("Clock", "com.google.android.deskclock",
        ComponentName("com.google.android.deskclock", "Main"), Process.myUserHandle())

    @Before fun setUp() {
        feed = FakeFeed()
        FeedLocator.feed = feed
        FeedLocator.commands = feed
        config = ConfigRepository(context)
        config.apiProvider = ConfigRepository.PROVIDER_OPENAI
        config.apiKey = "sk-test"
        AppSearch.loader = { listOf(clock) }
        AppSearch.starter = { _, app -> started += app }
    }

    @After fun tearDown() {
        config.apiKey = ""
        AppSearch.loader = AppSearch::installedApps
        AppSearch.starter = AppSearch::startApp
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun homeIntent() = Intent(context, ControlPanelActivity::class.java)
        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    private fun startAsHome() = Robolectric.buildActivity(ControlPanelActivity::class.java, homeIntent()).setup().also { idle() }
    private fun startNormally() = Robolectric.buildActivity(ControlPanelActivity::class.java).setup().also { idle() }

    @Test fun asAnOrdinaryAppBackFinishesAndSwipeUpDoesNothing() {
        val a = startNormally().get()
        assertFalse(a.isBackHandled())
        assertFalse(a.onSwipeUp())
        assertFalse(a.appSearch().isOpen)
        a.onBackPressedDispatcher.onBackPressed()
        assertTrue(a.isFinishing)
    }

    @Test fun asTheHomeScreenBackDoesNothing() {
        val a = startAsHome().get()
        assertTrue(a.isBackHandled())
        a.onBackPressedDispatcher.onBackPressed()
        assertFalse(a.isFinishing)
    }

    @Test fun swipeUpOpensTheAppSearchAndBackClosesIt() {
        val a = startAsHome().get()
        assertTrue(a.onSwipeUp())
        idle()
        val search = a.appSearch()
        assertTrue(search.isOpen)
        assertTrue(search.field!!.hasFocus())
        search.field!!.setText("clo")
        assertEquals(listOf(clock), search.shown)
        a.onBackPressedDispatcher.onBackPressed()
        assertFalse(search.isOpen)
        assertFalse(a.isFinishing)
    }

    @Test fun tappingAnAppStartsItAndClosesTheSearch() {
        val a = startAsHome().get()
        a.onSwipeUp()
        idle()
        val list = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.app_search_list)
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 1080, 1920)
        list.getChildAt(0).performClick()
        assertEquals(listOf(clock), started)
        assertFalse(a.appSearch().isOpen)
    }

    @Test fun aNewHomeIntentClosesTheInputAndTheSearch() {
        val controller = startAsHome()
        val a = controller.get()
        a.findViewById<View>(R.id.footer_type).performClick()
        idle()
        assertTrue(a.isInputOpen())
        controller.newIntent(homeIntent())
        idle()
        assertFalse(a.isInputOpen())
        a.onSwipeUp()
        assertTrue(a.appSearch().isOpen)
        controller.newIntent(homeIntent())
        idle()
        assertFalse(a.appSearch().isOpen)
    }

    @Test fun typedOpenNameStartsTheAppAndSendsNothingToTheAgent() {
        val a = startNormally().get()
        a.findViewById<View>(R.id.footer_type).performClick()
        idle()
        a.typeField()!!.setText("open Clock")
        a.findViewById<Button>(R.id.type_send).performClick()
        idle()
        assertEquals(listOf(clock), started)
        assertTrue(feed.submitted.isEmpty())

        a.findViewById<View>(R.id.footer_type).performClick()
        idle()
        a.typeField()!!.setText("open Gate Test")
        a.findViewById<Button>(R.id.type_send).performClick()
        idle()
        assertEquals(listOf("open Gate Test"), feed.submitted)
        assertEquals(1, started.size)
    }
}
