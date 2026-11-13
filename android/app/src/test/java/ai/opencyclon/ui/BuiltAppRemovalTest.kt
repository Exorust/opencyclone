package ai.opencyclon.ui

import ai.opencyclon.db.AppDatabase
import ai.opencyclon.miniapp.Bridge
import ai.opencyclon.miniapp.MiniAppStore
import ai.opencyclon.ui.home.BuiltAppRemoval
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BuiltAppRemovalTest {
    @Test fun theDeleteSentenceMatchesOnlyAWholeRequest() {
        assertEquals("Lingo", BuiltAppRemoval.requestedName("delete the Lingo app"))
        assertEquals("My lessons", BuiltAppRemoval.requestedName("Delete the My lessons app."))
        assertNull(BuiltAppRemoval.requestedName("do not delete the Lingo app"))
        assertNull(BuiltAppRemoval.requestedName("delete the app"))
    }

    @Test fun removalDeletesSelectedVersionsAndDataAndKeepsTheOtherApp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val store = MiniAppStore(context, db, pin = { _, _ -> })
            val selected = store.saveNewVersion(null, "Lingo", "first").first
            store.saveNewVersion(selected, "Lingo", "second")
            val other = store.saveNewVersion(null, "Lingo", "keep").first
            val prefs = context.getSharedPreferences("${Bridge.PREFS_PREFIX}$selected", Context.MODE_PRIVATE)
            prefs.edit().putString("streak", "4").commit()
            assertTrue(BuiltAppRemoval.remove(context, selected, db))
            assertNull(db.miniAppDao().get(selected))
            assertTrue(db.miniAppVersionDao().all(selected).isEmpty())
            assertFalse(java.io.File(context.filesDir, "miniapps/$selected").exists())
            assertTrue(prefs.all.isEmpty())
            assertEquals("keep", store.currentHtml(other))
            assertEquals(listOf(other), db.miniAppDao().all().map { it.id })
        } finally {
            Dispatchers.resetMain()
            db.close()
        }
    }
}
