package ai.opencyclone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lane X2's small corrections that live in wiring a unit test cannot build (the runtime object, screens). */
class X2SourceTest {
    private val src = listOf(File("src"), File("app/src"), File("android/app/src")).first { File(it, "main").isDirectory }
    private fun read(path: String) = File(src, "main/java/ai/opencyclone/$path").readText()

    @Test fun ruleSavedIsOneSentenceAndNoReceipt() {
        val runtime = read("tasks/ProactiveRuntime.kt")
        assertFalse(runtime.contains("onRuleSaved"))
        assertFalse(runtime.contains("RULE_SAVED"))
    }

    @Test fun theDeadRepliesFlowIsGone() {
        val runtime = read("tasks/ProactiveRuntime.kt")
        assertFalse(runtime.contains("_replies"))
        assertFalse(runtime.contains("class Reply"))
    }

    @Test fun theWindowNotificationsOpenTheHomePageAndTheSummaryIsOneMultiLineSentence() {
        val runtime = read("tasks/ProactiveRuntime.kt")
        val window = runtime.substring(runtime.indexOf("private fun showWindow"), runtime.indexOf("private fun hideWindow"))
        assertEquals(2, Regex("setContentIntent\\(homeTap\\(\\)\\)").findAll(window).count())
        assertTrue(window.contains("say(\"\$WINDOW_ENDED.\\n\$text\")"))
    }

    @Test fun theEditScreenRecordsTheOwnersWords() {
        assertTrue(read("gate/EditActivity.kt").contains("recordOwnerWords(text)"))
    }

    @Test fun historyOpensThePagerAndTheForwarderIsGone() {
        assertFalse(File(src, "main/java/ai/opencyclone/ui/ConversationHistoryActivity.kt").exists())
        assertFalse(File(src, "main/AndroidManifest.xml").readText().contains("ConversationHistoryActivity"))
        assertTrue(read("tasks/ProactiveRuntime.kt").contains("OwnScreen.HISTORY -> ControlPanelActivity.historyIntent(app)"))
    }

    @Test fun theOverlayRowIsGoneFromTheSetUpPage() {
        assertFalse(read("ui/FirstRunActivity.kt").contains("h_perm_overlay"))
        assertFalse(File(src, "main/res/values/strings.xml").readText().contains("h_perm_overlay"))
    }

    @Test fun theWidgetsPageIsCreatedDirectly() {
        assertFalse(File(src, "main/java/ai/opencyclone/ui/home/WidgetsSlot.kt").exists())
        assertTrue(read("ui/ControlPanelActivity.kt").contains("WidgetsPageView(this)"))
    }

    @Test fun theHomePageShowsNoMissingModelDialog() {
        assertFalse(read("ui/ControlPanelActivity.kt").contains("showMissingModel"))
        assertFalse(read("ui/AppEntries.kt").contains("AlertDialog"))
    }

    @Test fun oneListOfWakeNames() {
        assertFalse(read("miniapp/MiniAppRequests.kt").contains("cyclone|phone"))
        assertFalse(read("tasks/RequestRouter.kt").contains("WAKE_NAMES"))
        assertEquals(listOf("open cyclone", "cyclone"), WakeNames.ALL)
    }
}
