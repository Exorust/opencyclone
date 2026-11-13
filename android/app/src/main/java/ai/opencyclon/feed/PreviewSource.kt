package ai.opencyclon.feed

import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * [Feed.previews]: a cold flow that asks [capture] for a small picture of the task's hidden display at most
 * every [intervalMs], only while it is collected. [capture] returns null when the task is not the running
 * one or no picture is available; it must never return a picture of the main screen.
 */
class PreviewSource(
    private val capture: suspend (taskId: Long, widthPx: Int) -> Bitmap?,
    private val intervalMs: Long = INTERVAL_MS,
) {
    fun previews(taskId: Long, widthPx: Int): Flow<Bitmap> = flow {
        while (currentCoroutineContext().isActive) {
            capture(taskId, widthPx)?.let { emit(it) }
            delay(intervalMs)
        }
    }

    companion object {
        /** Two pictures each second at most. */
        const val INTERVAL_MS = 500L
        const val QUALITY = 60
    }
}
