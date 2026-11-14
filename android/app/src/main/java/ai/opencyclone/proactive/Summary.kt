package ai.opencyclone.proactive

import androidx.room.*

@Entity(tableName = "notification_log")
data class NotificationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package") val packageName: String,
    val title: String,
    val result: String,
    @ColumnInfo(name = "rule_text") val ruleText: String? = null,
    val at: Long
)

@Dao
interface NotificationLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(entry: NotificationLogEntity): Long
    // Proposal entries use -taskId. This preserves titles without persisting notification bodies.
    @Query("""SELECT l.id, l.package, l.title,
        CASE WHEN t.id IS NOT NULL THEN t.state ELSE l.result END AS result, l.rule_text, l.at
        FROM notification_log l LEFT JOIN tasks t ON l.id = -t.id
        WHERE l.at >= :timestamp ORDER BY l.at, l.id""")
    suspend fun since(timestamp: Long): List<NotificationLogEntity>
}

data class NotificationSummary(
    val needsReply: List<NotificationLogEntity>,
    val forInformation: List<NotificationLogEntity>,
    val hidden: List<NotificationLogEntity>
)

class Summary(private val dao: NotificationLogDao) {
    suspend fun since(timestamp: Long): NotificationSummary {
        val entries = dao.since(timestamp)
        return NotificationSummary(
            entries.filter { it.result in setOf("proposed", "failed") },
            entries.filter { it.result == "summarize" },
            entries.filter { it.result == "hide" }
        )
    }
}
