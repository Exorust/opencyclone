package ai.opencyclon.tasks

import androidx.room.*

@Entity(tableName = "tasks", indices = [Index(value = ["source_key"], unique = true)])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val origin: String,
    val goal: String,
    val state: String,
    @ColumnInfo(name = "source_key") val sourceKey: String? = null,
    @ColumnInfo(name = "held_action") val heldAction: String? = null,
    @ColumnInfo(name = "held_facts") val heldFacts: String? = null,
    val result: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
