package ai.opencyclon.widgets

import ai.opencyclon.db.AppDatabase
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetStoreTest {
    private var fixture: WidgetFixture? = null
    @After fun tearDown() { fixture?.close() }

    private fun run(block: suspend (WidgetFixture) -> Unit) = runBlocking { block(WidgetFixture(this).also { fixture = it }) }

    @Test fun newWidgetsGoLastAndOrderIsSaved() = run { f ->
        val a = f.saved("A")
        val b = f.saved("B", WidgetSize.WIDE)
        val c = f.saved("C", WidgetSize.TALL)
        assertEquals(listOf(a, b, c), f.store.list().map { it.id })
        assertEquals(listOf(0, 1, 2), f.store.list().map { it.position })
        f.store.reorder(listOf(c, a, b))
        assertEquals(listOf(c, a, b), f.store.list().map { it.id })
        assertEquals("tall", f.store.get(c)!!.size)
    }

    @Test fun aNewVersionKeepsThePositionAndReplacesTheFile() = run { f ->
        val a = f.saved("A")
        val b = f.saved("B")
        val v1 = f.store.file(f.store.get(a)!!)
        val draft = f.store.draftOf(f.store.get(a)!!)!!
        assertEquals(a, draft.widgetId)
        assertEquals(a, f.store.save(draft.copy(name = "A2", size = WidgetSize.WIDE)))
        val widget = f.store.get(a)!!
        assertEquals(2, widget.version)
        assertEquals(0, widget.position)
        assertEquals("A2", widget.name)
        assertFalse(v1.exists())
        assertTrue(f.store.file(widget).isFile)
        assertFalse(draft.file.exists())
        assertEquals(listOf(a, b), f.store.list().map { it.id })
    }

    @Test fun atMostEightWidgets() = run { f ->
        repeat(8) { f.saved("W$it") }
        assertTrue(f.store.isFull())
        try {
            f.saved("Ninth")
            fail("a ninth widget was saved")
        } catch (error: IllegalStateException) {
            assertEquals(WidgetStore.FULL, error.message)
        }
        assertEquals(8, f.store.count())
    }

    @Test fun draftsAreFilesThatGoAway() = run { f ->
        val file = f.store.writeDraft("<html></html>")
        assertTrue(file.path.contains("widgets${File.separator}drafts"))
        f.store.deleteDraft(WidgetDraft(file, "x", WidgetSize.SMALL, emptyList(), "#4733D6"))
        assertFalse(file.exists())
        f.store.writeDraft("<html></html>")
        f.store.deleteAllDrafts()
        assertFalse(f.draftsDir.exists())
    }

    @Test fun removeDeletesTheRowAndItsFiles() = run { f ->
        val a = f.saved("A")
        val dir = f.store.file(f.store.get(a)!!).parentFile!!
        f.store.remove(a)
        assertEquals(0, f.store.count())
        assertFalse(dir.exists())
    }

    @Test fun migration9To10AddsTheWidgetsTable() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()).name(null).callback(object : SupportSQLiteOpenHelper.Callback(9) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            val db = helper.writableDatabase
            listOf(AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9)
                .forEach { it.migrate(db) }
            db.execSQL("INSERT INTO miniapps (name, current_version, created_at) VALUES ('Lingo', 1, 1)")
            AppDatabase.MIGRATION_9_10.migrate(db)
            db.execSQL("INSERT INTO widgets (name, size, sources, position, version, accent, created_at) VALUES ('S', 'small', 'history', 0, 1, '#4733D6', 1)")
            db.query("SELECT COUNT(*) FROM miniapps").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            val fresh = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
                .allowMainThreadQueries().build()
            try {
                assertEquals(TableInfo.read(fresh.openHelper.writableDatabase, "widgets"), TableInfo.read(db, "widgets"))
            } finally { fresh.close() }
        } finally { helper.close() }
    }
}
