package ai.opencyclone.miniapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The received-app entry: exported, exactly the three filters of the lane brief, never over the lock screen. */
class ReceivedAppManifestTest {
    private val manifest by lazy {
        listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }.readText()
    }
    private val block by lazy {
        Regex("""<activity\s+android:name="\.miniapp\.ReceivedAppActivity".*?</activity>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)!!.value
    }

    @Test fun exportedWithTheAppNameAndNotOverTheLockScreen() {
        assertTrue(block.contains("android:exported=\"true\""))
        assertTrue(block.contains("android:label=\"@string/app_name\""))
        assertFalse(block.contains("showWhenLocked"))
        assertFalse(block.contains("turnScreenOn"))
    }

    @Test fun exactlyThreeFilters() {
        val filters = Regex("""<intent-filter>(.*?)</intent-filter>""", RegexOption.DOT_MATCHES_ALL).findAll(block)
            .map { it.groupValues[1].replace(Regex("""\s+"""), " ").trim() }.toList()
        assertEquals(3, filters.size)
        val view = "<action android:name=\"android.intent.action.VIEW\" />"
        val send = "<action android:name=\"android.intent.action.SEND\" />"
        assertTrue(filters[0].contains(view) && filters[0].contains("android:scheme=\"content\"") && filters[0].contains("text/html"))
        assertTrue(filters[1].contains(view) && filters[1].contains("android:scheme=\"file\"") && filters[1].contains("text/html"))
        assertTrue(filters[2].contains(send) && !filters[2].contains("scheme") && filters[2].contains("text/html"))
        filters.forEach { assertTrue(it.contains("android.intent.category.DEFAULT")) }
    }
}
