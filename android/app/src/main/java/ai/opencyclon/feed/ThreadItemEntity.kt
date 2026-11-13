package ai.opencyclon.feed

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/** One stored row of the thread; mirrors [ThreadItem]. Kind and state are enum names, evidence is JSON. */
@Entity(tableName = "thread_items")
data class ThreadItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val kind: String,
    val state: String,
    @ColumnInfo(name = "task_id") val taskId: Long? = null,
    @ColumnInfo(name = "app_package") val appPackage: String? = null,
    val title: String? = null,
    val text: String = "",
    val detail: String? = null,
    @ColumnInfo(name = "action_word") val actionWord: String? = null,
    val evidence: String? = null,
    @ColumnInfo(name = "can_approve") val canApprove: Boolean = true,
    @ColumnInfo(name = "open_app_id") val openAppId: Long? = null,
) {
    fun toItem() = ThreadItem(
        id = id, at = at, kind = ThreadKind.valueOf(kind), state = ThreadState.valueOf(state), taskId = taskId,
        appPackage = appPackage, title = title, text = text, detail = detail, actionWord = actionWord,
        evidence = evidence?.let(::evidenceFromJson), canApprove = canApprove, openAppId = openAppId,
    )

    companion object {
        fun from(item: ThreadItem) = ThreadItemEntity(
            id = item.id, at = item.at, kind = item.kind.name, state = item.state.name, taskId = item.taskId,
            appPackage = item.appPackage, title = item.title, text = item.text, detail = item.detail,
            actionWord = item.actionWord, evidence = item.evidence?.let(::evidenceToJson),
            canApprove = item.canApprove, openAppId = item.openAppId,
        )

        fun evidenceToJson(evidence: Evidence): String = JSONObject().apply {
            put("count", evidence.count ?: JSONObject.NULL)
            put("compose_text", evidence.composeText ?: JSONObject.NULL)
            put("thumbs", JSONArray(evidence.thumbs))
            put("frame_path", evidence.framePath ?: JSONObject.NULL)
        }.toString()

        fun evidenceFromJson(json: String): Evidence? = try {
            val obj = JSONObject(json)
            val thumbs = obj.optJSONArray("thumbs")
            Evidence(
                count = if (obj.isNull("count")) null else obj.getInt("count"),
                composeText = if (obj.isNull("compose_text")) null else obj.getString("compose_text"),
                thumbs = (0 until (thumbs?.length() ?: 0)).map { thumbs!!.getString(it) },
                framePath = if (obj.isNull("frame_path")) null else obj.getString("frame_path"),
            )
        } catch (_: Exception) {
            null
        }
    }
}

@Dao
interface ThreadItemDao {
    @Insert suspend fun insert(item: ThreadItemEntity): Long
    @Update suspend fun update(item: ThreadItemEntity)
    @Query("SELECT * FROM thread_items WHERE id = :id") suspend fun get(id: Long): ThreadItemEntity?
    @Query("DELETE FROM thread_items WHERE id = :id") suspend fun delete(id: Long)

    /** Rows at or after [since], oldest first; emits again on every change to the table. */
    @Query("SELECT * FROM thread_items WHERE at >= :since ORDER BY at, id") fun since(since: Long): Flow<List<ThreadItemEntity>>
    @Query("SELECT * FROM thread_items WHERE at >= :since ORDER BY at, id") suspend fun sinceNow(since: Long): List<ThreadItemEntity>

    @Query("SELECT * FROM thread_items WHERE state = 'OPEN' ORDER BY id") suspend fun open(): List<ThreadItemEntity>
    @Query("SELECT * FROM thread_items WHERE task_id = :taskId AND state = 'OPEN' ORDER BY id") suspend fun openForTask(taskId: Long): List<ThreadItemEntity>

    /**
     * The change of day: deletes the notification rows older than [cutoff] that never became a task, so that
     * notification text never outlives its day (F13). The rows of a task, the owner's words, the phone's
     * sentences, and results stay for the history (F15.5).
     */
    @Query("DELETE FROM thread_items WHERE at < :cutoff AND task_id IS NULL AND kind = 'NOTIFICATION'")
    suspend fun pruneBefore(cutoff: Long): Int

    // ── Task history (lane H1) ──────────────────────────────────────────

    @Query("SELECT * FROM thread_items ORDER BY at, id") fun observeAll(): Flow<List<ThreadItemEntity>>
    @Query("SELECT * FROM thread_items WHERE task_id = :taskId ORDER BY at, id")
    fun observeForTask(taskId: Long): Flow<List<ThreadItemEntity>>
    @Query("SELECT * FROM thread_items ORDER BY at, id") suspend fun allNow(): List<ThreadItemEntity>
    @Query("DELETE FROM thread_items WHERE task_id = :taskId") suspend fun deleteForTask(taskId: Long)
}
