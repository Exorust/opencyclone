package ai.opencyclon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Lane X1: what must no longer exist or be called, read from the sources. */
class X1SourceTest {
    private fun dir(vararg candidates: String): File = candidates.map(::File).first { it.exists() }
    private val src get() = dir("src", "app/src")

    private fun sourcesUnder(path: String): List<File> =
        File(src, path).walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList()

    @Test fun nothingReferencesTheFloatingOverlayOrThePairingDialog() {
        val deleted = listOf("OverlayService", "OverlayWindow", "OverlayStatePolicy", "PairingDialogActivity",
            "lastForegroundActivityClass", "EXTRA_FOCUS_ASK", "overlay_window", "overlay_minimized",
            "activity_pairing_dialog", "isOverlayActiveState")
        val offenders = (sourcesUnder("main") + sourcesUnder("test") + sourcesUnder("androidTest"))
            .filter { it.name != "X1SourceTest.kt" }
            .flatMap { f ->
                val text = f.readText().lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                    .joinToString("\n")
                deleted.filter { text.contains(it) }.map { "${f.name}: $it" }
            }
        assertEquals(emptyList<String>(), offenders)
        assertFalse(File(src, "main/java/ai/opencyclon/overlay").exists())
        assertFalse(File(src, "main/AndroidManifest.xml").readText().contains("SYSTEM_ALERT_WINDOW"))
    }

    @Test fun theServiceForcesNoScreenOverTheOwnersApp() {
        val service = File(src, "main/java/ai/opencyclon/AgentService.kt").readText()
        // enterViewMode is only defined (the owner opens the live view himself); nothing in the service calls it.
        assertEquals(1, Regex("enterViewMode\\(").findAll(service).count())
        assertEquals(1, Regex("enterTakeoverMode\\(").findAll(service).count())
        for (dead in listOf("fun pauseAgent", "fun getEngine", "bringOpenCyclonUiToFrontAfterRescue", "launchHomeOnDisplay")) {
            assertFalse(dead, service.contains(dead))
        }
    }

    @Test fun rebootKeepsQueuedRequestsAndProposals() {
        val receiver = File(src, "main/java/ai/opencyclon/schedule/BootReceiver.kt").readText()
        // The same recovery as at process start (TaskCoordinatorTest.recoveryKeepsQueuedProposedAndFinishedRows).
        assertTrue(receiver.contains("recoverAfterProcessStart()"))
        assertFalse(receiver.contains("cancelAllOnRestart"))
        assertFalse("the boot receiver does not start the service", receiver.contains("startForegroundService") ||
            receiver.contains("startService"))
    }
}
