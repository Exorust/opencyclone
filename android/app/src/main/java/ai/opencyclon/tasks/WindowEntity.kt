package ai.opencyclon.tasks

import androidx.room.*

@Entity(tableName = "windows")
data class WindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ends_at") val endsAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    val handled: Int = 0,
    val hidden: Int = 0,
    @ColumnInfo(name = "needs_you") val needsYou: Int = 0
)

@Dao
interface WindowDao {
    @Insert suspend fun insert(window: WindowEntity): Long
    @Query("SELECT * FROM windows ORDER BY id DESC LIMIT 1") suspend fun latest(): WindowEntity?
    @Query("SELECT * FROM windows ORDER BY id DESC LIMIT 1") fun observeLatest(): kotlinx.coroutines.flow.Flow<WindowEntity?>
    @Query("UPDATE windows SET ends_at = :at WHERE id = :id") suspend fun end(id: Long, at: Long)
    @Query("UPDATE windows SET handled = handled + :handled, hidden = hidden + :hidden, needs_you = needs_you + :needsYou WHERE started_at <= :at AND ends_at > :at")
    suspend fun count(at: Long, handled: Int = 0, hidden: Int = 0, needsYou: Int = 0)
}
