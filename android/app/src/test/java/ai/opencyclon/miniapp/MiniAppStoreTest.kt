package ai.opencyclon.miniapp

import ai.opencyclon.db.AppDatabase
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** In-memory database, a settable clock and a recorded shortcut pin, shared by the miniapp tests. */
class StoreFixture {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    var time = 1_000_000L
    val pins = mutableListOf<Pair<Long, String>>()
    val store = MiniAppStore(context, db, now = { time }, pin = { id, name -> pins += id to name })

    fun close() {
        db.close()
        File(context.filesDir, "miniapps").deleteRecursively()
    }
}

fun page(name: String, body: String = "Hola") = "<html><head><title>$name</title></head><body>$body</body></html>"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MiniAppStoreTest {
    private lateinit var f: StoreFixture

    @Before fun setUp() { f = StoreFixture() }
    @After fun tearDown() = f.close()

    @Test fun versionsAreWrittenAsFilesAndTheLatestIsCurrent() = runBlocking {
        val (id, v1) = f.store.saveNewVersion(null, "Spanish", page("Spanish", "one"))
        assertEquals(1, v1)
        val (sameId, v2) = f.store.saveNewVersion(id, "Spanish", page("Spanish", "two"))
        assertEquals(id, sameId)
        assertEquals(2, v2)
        val file = f.store.currentFile(id)!!
        assertEquals(File(f.context.filesDir, "miniapps/$id/v2.html").absolutePath, file.absolutePath)
        assertTrue(File(f.context.filesDir, "miniapps/$id/v1.html").isFile)
        assertEquals(page("Spanish", "two"), f.store.currentHtml(id))
        assertEquals(listOf(1, 2), f.db.miniAppVersionDao().all(id).map { it.version })
        assertEquals(2, f.db.miniAppDao().get(id)!!.currentVersion)
        assertEquals(1, f.store.list().size)
    }

    @Test fun unknownAppHasNoFile() = runBlocking {
        assertNull(f.store.currentFile(42))
        assertNull(f.store.currentHtml(42))
    }

    @Test fun findByNameMatchesCaseInsensitivelyInBothDirections() = runBlocking {
        f.store.saveNewVersion(null, "Spanish", page("Spanish"))
        f.store.saveNewVersion(null, "SF Weather", page("SF Weather"))
        assertEquals(listOf("Spanish"), f.store.findByName("spanish").map { it.name })
        assertEquals(listOf("Spanish"), f.store.findByName("open my SPANISH app please").map { it.name })
        assertEquals(listOf("SF Weather"), f.store.findByName("weather").map { it.name })
        assertEquals(emptyList<String>(), f.store.findByName("mandarin").map { it.name })
        assertEquals(emptyList<String>(), f.store.findByName(" ").map { it.name })
    }

    @Test fun recentListsAppsChangedInTheLast10Minutes() = runBlocking {
        val (old, _) = f.store.saveNewVersion(null, "Old", page("Old"))
        f.time += MiniAppStore.RECENT_MS + 1
        val (new, _) = f.store.saveNewVersion(null, "New", page("New"))
        assertEquals(listOf(new), f.store.recent().map { it.id })
        f.store.saveNewVersion(old, "Old", page("Old", "changed"))
        assertEquals(setOf(old, new), f.store.recent().map { it.id }.toSet())
    }

    @Test fun shareFileCopiesTheCurrentVersionToExternalFiles() = runBlocking {
        val (id, _) = f.store.saveNewVersion(null, "Spanish", page("Spanish"))
        val shared = f.store.shareFile(id)
        assertNotNull(shared)
        assertTrue(shared!!.absolutePath.startsWith(f.context.getExternalFilesDir(null)!!.absolutePath))
        assertTrue(shared.name.endsWith(".html"))
        assertEquals(page("Spanish"), shared.readText())
    }

    @Test fun shareFileIsNamedAfterTheApp() = runBlocking {
        val (id, _) = f.store.saveNewVersion(null, "Spanish", page("Spanish"))
        f.store.saveNewVersion(id, "Spanish", page("Spanish"))
        assertEquals("Spanish.html", f.store.shareFile(id)!!.name)
    }

    @Test fun shareFileNameDropsUnsafeCharacters() {
        assertEquals("Spanish Practice.html", MiniAppStore.shareFileName("Spanish/Practice?"))
        assertEquals("Notes 2.html", MiniAppStore.shareFileName("Notes: 2"))
        assertEquals("app.html", MiniAppStore.shareFileName("../"))
    }
}
