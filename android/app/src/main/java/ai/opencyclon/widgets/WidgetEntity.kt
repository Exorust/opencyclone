package ai.opencyclon.widgets

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** The three widget sizes (F16 assumption 5). Heights are the card's height on the Widgets page. */
enum class WidgetSize(val id: String, val heightDp: Int, val span: Int) {
    SMALL("small", 160, 1),  // half width
    WIDE("wide", 160, 2),    // full width, short
    TALL("tall", 320, 2);    // full width, up to 320 dp

    companion object {
        fun of(id: String?): WidgetSize = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: SMALL
    }
}

/** One saved widget. The file is `files/widgets/<id>/v<version>.html`. A draft is never stored here. */
@Entity(tableName = "widgets")
data class WidgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val size: String,
    /** Source ids the owner allowed, comma-separated (see [WidgetSources]). */
    val sources: String,
    /** Order on the Widgets page, smallest first. */
    val position: Int,
    val version: Int,
    val accent: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    val sourceList: List<String> get() = splitSources(sources)
    val widgetSize: WidgetSize get() = WidgetSize.of(size)

    companion object {
        fun splitSources(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

@Dao
interface WidgetDao {
    @Insert suspend fun insert(widget: WidgetEntity): Long
    @Update suspend fun update(widget: WidgetEntity)
    @Query("SELECT * FROM widgets WHERE id = :id") suspend fun get(id: Long): WidgetEntity?
    @Query("SELECT * FROM widgets ORDER BY position, id") suspend fun all(): List<WidgetEntity>
    @Query("SELECT * FROM widgets ORDER BY position, id") fun observeAll(): Flow<List<WidgetEntity>>
    @Query("SELECT COUNT(*) FROM widgets") suspend fun count(): Int
    @Query("DELETE FROM widgets WHERE id = :id") suspend fun delete(id: Long)
    @Query("UPDATE widgets SET position = :position WHERE id = :id") suspend fun setPosition(id: Long, position: Int)
}
