package ai.opencyclone.integrations

import androidx.room.*

@Entity(tableName = "integrations")
data class IntegrationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val address: String,
    @ColumnInfo(name = "tool_count") val toolCount: Int,
    val status: String
)

@Dao
interface IntegrationDao {
    @Insert suspend fun insert(integration: IntegrationEntity): Long
    @Update suspend fun update(integration: IntegrationEntity)
    @Query("SELECT * FROM integrations ORDER BY id") suspend fun all(): List<IntegrationEntity>
    @Query("DELETE FROM integrations WHERE id = :id") suspend fun delete(id: Long)
}
