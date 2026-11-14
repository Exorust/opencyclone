package ai.opencyclone.miniapp

import androidx.room.*

@Entity(tableName = "miniapps")
data class MiniAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "current_version") val currentVersion: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** "#RRGGBB" from the current version's accent meta tag; null for apps saved before version 8. */
    val accent: String? = null,
    /** True for an app opened from a file someone sent (and every later version of it); its bridge has no fetch. */
    @ColumnInfo(defaultValue = "0") val received: Boolean = false
)

@Entity(tableName = "miniapp_versions", primaryKeys = ["app_id", "version"])
data class MiniAppVersionEntity(
    @ColumnInfo(name = "app_id") val appId: Long,
    val version: Int,
    @ColumnInfo(name = "html_path") val htmlPath: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Dao
interface MiniAppDao {
    @Insert suspend fun insert(app: MiniAppEntity): Long
    @Update suspend fun update(app: MiniAppEntity)
    @Query("SELECT * FROM miniapps WHERE id = :id") suspend fun get(id: Long): MiniAppEntity?
    @Query("SELECT * FROM miniapps ORDER BY created_at") suspend fun all(): List<MiniAppEntity>
    @Query("SELECT * FROM miniapps ORDER BY created_at") fun observeAll(): kotlinx.coroutines.flow.Flow<List<MiniAppEntity>>
}

@Dao
interface MiniAppVersionDao {
    @Insert suspend fun insert(version: MiniAppVersionEntity)
    @Query("SELECT * FROM miniapp_versions WHERE app_id = :appId ORDER BY version") suspend fun all(appId: Long): List<MiniAppVersionEntity>
}
