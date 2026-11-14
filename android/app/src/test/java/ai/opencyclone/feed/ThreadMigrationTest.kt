package ai.opencyclone.feed

import ai.opencyclone.db.AppDatabase
import androidx.room.Room
import androidx.room.util.TableInfo
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThreadMigrationTest {
    @Test fun migration7To8AddsTheThreadAndTheAccentAndKeepsApps() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()).name(null).callback(object : SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_5_6.migrate(db)
            AppDatabase.MIGRATION_6_7.migrate(db)
            db.execSQL("INSERT INTO miniapps (name, current_version, created_at) VALUES ('Lingo', 2, 1)")
            AppDatabase.MIGRATION_7_8.migrate(db)
            db.query("SELECT name, current_version, accent FROM miniapps").use {
                assertTrue(it.moveToFirst())
                assertEquals("Lingo", it.getString(0))
                assertEquals(2, it.getInt(1))
                assertTrue(it.isNull(2))
            }
            AppDatabase.MIGRATION_8_9.migrate(db) // lane N6: the current schema has miniapps.received
            val fresh = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
                .allowMainThreadQueries().build()
            try {
                listOf("thread_items", "miniapps").forEach { table ->
                    assertEquals(table, TableInfo.read(fresh.openHelper.writableDatabase, table), TableInfo.read(db, table))
                }
            } finally { fresh.close() }
        } finally { helper.close() }
    }
}
