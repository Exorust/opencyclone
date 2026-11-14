package ai.opencyclone.proactive

import androidx.room.*

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    @ColumnInfo(name = "package") val packageName: String? = null,
    val text: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Dao
interface RuleDao {
    @Insert suspend fun insert(rule: RuleEntity): Long
    @Query("SELECT * FROM rules ORDER BY id") suspend fun all(): List<RuleEntity>
    @Query("DELETE FROM rules WHERE kind = 'package' AND package = :packageName")
    suspend fun removePackage(packageName: String)
    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun remove(id: Long)
}

@Entity(tableName = "allowlist")
data class AllowlistEntity(@PrimaryKey @ColumnInfo(name = "package") val packageName: String)

@Dao
interface AllowlistDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(entry: AllowlistEntity)
    @Query("DELETE FROM allowlist WHERE package = :packageName") suspend fun remove(packageName: String)
    @Query("SELECT EXISTS(SELECT 1 FROM allowlist WHERE package = :packageName)") suspend fun contains(packageName: String): Boolean
    @Query("SELECT * FROM allowlist") suspend fun all(): List<AllowlistEntity>
}
