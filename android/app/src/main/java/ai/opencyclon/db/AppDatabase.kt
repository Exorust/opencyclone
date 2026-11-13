package ai.opencyclon.db

import ai.opencyclon.tasks.*
import ai.opencyclon.proactive.*
import ai.opencyclon.miniapp.*
import ai.opencyclon.integrations.*
import ai.opencyclon.feed.ThreadItemDao
import ai.opencyclon.feed.ThreadItemEntity
import ai.opencyclon.widgets.WidgetDao
import ai.opencyclon.widgets.WidgetEntity
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        ChatMessageEntity::class,
        GlobalMemoryEntity::class,
        TaskEntity::class, WindowEntity::class, RuleEntity::class, AllowlistEntity::class,
        MiniAppEntity::class, MiniAppVersionEntity::class, IntegrationEntity::class, NotificationLogEntity::class,
        ThreadItemEntity::class, WidgetEntity::class
    ],
    version = 10
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun globalMemoryDao(): GlobalMemoryDao

    abstract fun taskDao(): TaskDao
    abstract fun windowDao(): WindowDao
    abstract fun ruleDao(): RuleDao
    abstract fun allowlistDao(): AllowlistDao
    abstract fun miniAppDao(): MiniAppDao
    abstract fun miniAppVersionDao(): MiniAppVersionDao
    abstract fun integrationDao(): IntegrationDao
    abstract fun notificationLogDao(): NotificationLogDao
    abstract fun threadItemDao(): ThreadItemDao
    abstract fun widgetDao(): WidgetDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `global_memories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `key` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_global_memories_key` ON `global_memories` (`key`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN source TEXT NOT NULL DEFAULT 'local'")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN remoteChatId TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `routines` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `icon` TEXT NOT NULL,
                        `instruction` TEXT NOT NULL,
                        `description` TEXT,
                        `category` TEXT NOT NULL,
                        `isPinned` INTEGER NOT NULL,
                        `useCount` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routines ADD COLUMN scheduleEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE routines ADD COLUMN triggerType TEXT")
                db.execSQL("ALTER TABLE routines ADD COLUMN scheduleHour INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN scheduleMinute INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN scheduleRepeatDays TEXT")
                db.execSQL("ALTER TABLE routines ADD COLUMN intervalMinutes INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN intervalStartHour INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN intervalEndHour INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoLatitude REAL")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoLongitude REAL")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoRadiusMeters REAL")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoTriggerOnEnter INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoLocationName TEXT")
                db.execSQL("ALTER TABLE routines ADD COLUMN lastTriggeredAt INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN nextTriggerAt INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, origin TEXT NOT NULL, goal TEXT NOT NULL, state TEXT NOT NULL, source_key TEXT, held_action TEXT, held_facts TEXT, result TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tasks_source_key ON tasks(source_key)")
                db.execSQL("CREATE TABLE IF NOT EXISTS windows (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ends_at INTEGER NOT NULL, started_at INTEGER NOT NULL, handled INTEGER NOT NULL, hidden INTEGER NOT NULL, needs_you INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, kind TEXT NOT NULL, package TEXT, text TEXT NOT NULL, created_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS allowlist (package TEXT NOT NULL PRIMARY KEY)")
                db.execSQL("CREATE TABLE IF NOT EXISTS miniapps (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, current_version INTEGER NOT NULL, created_at INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS miniapp_versions (app_id INTEGER NOT NULL, version INTEGER NOT NULL, html_path TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(app_id, version))")
                db.execSQL("CREATE TABLE IF NOT EXISTS integrations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, address TEXT NOT NULL, tool_count INTEGER NOT NULL, status TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS notification_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, package TEXT NOT NULL, title TEXT NOT NULL, result TEXT NOT NULL, rule_text TEXT, at INTEGER NOT NULL)")
            }
        }

        /** Saved routines and time or location schedules were removed; drop their table. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS routines")
            }
        }

        /** Today's thread (feed), and the accent colour of each built app. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS thread_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, at INTEGER NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL, task_id INTEGER, app_package TEXT, title TEXT, text TEXT NOT NULL, detail TEXT, action_word TEXT, evidence TEXT, can_approve INTEGER NOT NULL, open_app_id INTEGER)")
                db.execSQL("ALTER TABLE miniapps ADD COLUMN accent TEXT")
            }
        }

        /** Marks apps opened from a file someone sent; they get no internet. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE miniapps ADD COLUMN received INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Saved widgets (F16). Drafts are files only. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS widgets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, size TEXT NOT NULL, sources TEXT NOT NULL, position INTEGER NOT NULL, version INTEGER NOT NULL, accent TEXT NOT NULL, created_at INTEGER NOT NULL)")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "opencyclon.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
