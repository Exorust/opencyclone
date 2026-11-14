package ai.opencyclone.feed

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The engine's [Feed]: live state, today's stored thread, preview pictures, and the task history. */
class RealFeed(
    private val liveSource: () -> LiveStateSource,
    private val recorder: () -> ThreadRecorder,
    private val previewSource: () -> PreviewSource,
    private val taskHistory: () -> TaskHistory,
) : Feed {
    override val live: StateFlow<LiveState> get() = liveSource().state
    override fun today(): Flow<List<ThreadItem>> = recorder().today()
    override fun previews(taskId: Long, widthPx: Int): Flow<Bitmap> = previewSource().previews(taskId, widthPx)
    override fun history(query: String): Flow<List<HistoryEntry>> = taskHistory().history(query)
    override fun record(taskId: Long): Flow<List<ThreadItem>> = taskHistory().record(taskId)
}
