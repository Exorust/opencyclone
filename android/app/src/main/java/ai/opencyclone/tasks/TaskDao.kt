package ai.opencyclone.tasks

import androidx.room.*

@Dao
interface TaskDao {
    @Insert suspend fun insert(task: TaskEntity): Long
    @Update suspend fun update(task: TaskEntity)
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun get(id: Long): TaskEntity?
    @Query("SELECT * FROM tasks ORDER BY created_at, id") suspend fun all(): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE source_key = :key AND state IN ('proposed', 'queued') LIMIT 1")
    suspend fun openProposal(key: String): TaskEntity?
    @Query("SELECT * FROM tasks WHERE state IN ('running', 'paused', 'awaiting_approval') LIMIT 1")
    suspend fun occupied(): TaskEntity?
    @Query("SELECT * FROM tasks WHERE state = 'queued' ORDER BY CASE origin WHEN 'user' THEN 0 ELSE 1 END, created_at, id LIMIT 1")
    suspend fun next(): TaskEntity?

    /** The row in the task slot, observed: the live state's running task. */
    @Query("SELECT * FROM tasks WHERE state IN ('running', 'paused', 'awaiting_approval') ORDER BY id LIMIT 1")
    fun observeOccupied(): kotlinx.coroutines.flow.Flow<TaskEntity?>
    @Query("SELECT COUNT(*) FROM tasks WHERE state = 'queued'")
    fun observeQueuedCount(): kotlinx.coroutines.flow.Flow<Int>

    // ── Task history (lane H1) ──────────────────────────────────────────

    /** Every task, newest first; emits again on every change. */
    @Query("SELECT * FROM tasks ORDER BY created_at DESC, id DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: Long): kotlinx.coroutines.flow.Flow<TaskEntity?>
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun delete(id: Long)
}
