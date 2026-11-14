package ai.opencyclone.tasks

import ai.opencyclone.db.AppDatabase
import ai.opencyclone.proactive.*
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @Test fun migrationCreatesEveryTableAndPreservesExistingData() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()).name(null).callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE existing_data (value TEXT NOT NULL)")
                    db.execSQL("INSERT INTO existing_data VALUES ('preserve me')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_5_6.migrate(db)
            listOf("tasks", "rules", "windows", "allowlist", "miniapps", "miniapp_versions", "integrations", "notification_log").forEach { table ->
                db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { assertTrue(table, it.moveToFirst()) }
            }
            db.query("SELECT value FROM existing_data").use { assertTrue(it.moveToFirst()); assertEquals("preserve me", it.getString(0)) }
            db.query("PRAGMA index_list(tasks)").use {
                assertTrue(it.moveToFirst())
                assertEquals("index_tasks_source_key", it.getString(1))
                assertEquals(1, it.getInt(2))
            }
            // Later migrations change these tables too (7 -> 8 adds miniapps.accent); compare at the current version.
            AppDatabase.MIGRATION_6_7.migrate(db)
            AppDatabase.MIGRATION_7_8.migrate(db)
            AppDatabase.MIGRATION_8_9.migrate(db) // lane N6: miniapps.received
            // Compare every migrated column and index with Room's generated fresh schema.
            val fresh = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
                .allowMainThreadQueries().build()
            try {
                listOf("tasks", "rules", "windows", "allowlist", "miniapps", "miniapp_versions", "integrations", "notification_log").forEach { table ->
                    assertEquals(table, androidx.room.util.TableInfo.read(fresh.openHelper.writableDatabase, table), androidx.room.util.TableInfo.read(db, table))
                }
            } finally { fresh.close() }
        } finally { helper.close() }
    }

    @Test fun migration6To7DropsRoutinesAndKeepsEverythingElse() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()).name(null).callback(object : SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE existing_data (value TEXT NOT NULL)")
                    db.execSQL("INSERT INTO existing_data VALUES ('preserve me')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_3_4.migrate(db)
            AppDatabase.MIGRATION_4_5.migrate(db)
            AppDatabase.MIGRATION_5_6.migrate(db)
            db.execSQL("INSERT INTO routines (name, icon, instruction, category, isPinned, useCount, createdAt, sortOrder) VALUES ('r', 'i', 'x', 'custom', 0, 0, 1, 0)")
            db.execSQL("INSERT INTO rules (kind, package, text, created_at) VALUES ('hide', NULL, 'hide sales', 1)")
            AppDatabase.MIGRATION_6_7.migrate(db)
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='routines'").use { assertFalse(it.moveToFirst()) }
            db.query("SELECT value FROM existing_data").use { assertTrue(it.moveToFirst()); assertEquals("preserve me", it.getString(0)) }
            db.query("SELECT text FROM rules").use { assertTrue(it.moveToFirst()); assertEquals("hide sales", it.getString(0)) }
            // Running it twice (or on a database that never had the table) is harmless.
            AppDatabase.MIGRATION_6_7.migrate(db)
        } finally { helper.close() }
    }

    @Test fun summaryUsesTaskStateAndTitlesAndFiltersByTime() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val task = db.taskDao().insert(TaskEntity(origin = "notification", goal = "private goal", state = "proposed", createdAt = 10, updatedAt = 10))
            db.notificationLogDao().insert(NotificationLogEntity(id = -task, packageName = "chat", title = "Alex", result = "proposed", at = 10))
            db.notificationLogDao().insert(NotificationLogEntity(packageName = "app", title = "news", result = "summarize", at = 10))
            db.notificationLogDao().insert(NotificationLogEntity(packageName = "app", title = "sale", result = "hide", ruleText = "hide sales", at = 10))
            db.notificationLogDao().insert(NotificationLogEntity(packageName = "app", title = "old", result = "summarize", at = 1))
            val summary = Summary(db.notificationLogDao())
            assertEquals(listOf("Alex"), summary.since(10).needsReply.map { it.title })
            assertEquals(listOf("news"), summary.since(10).forInformation.map { it.title })
            assertEquals("hide sales", summary.since(10).hidden.single().ruleText)
            db.taskDao().update(db.taskDao().get(task)!!.copy(state = "failed"))
            assertEquals(listOf("Alex"), summary.since(10).needsReply.map { it.title })
            db.taskDao().update(db.taskDao().get(task)!!.copy(state = "done"))
            assertTrue(summary.since(10).needsReply.isEmpty())
        } finally { db.close() }
    }
}
