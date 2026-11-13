package ai.opencyclon.feed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import ai.opencyclon.gate.Box
import ai.opencyclon.gate.HeldAction
import java.io.File

/** Pictures shown with an approval, in `files/evidence/<taskId>/`. Deleted with the day's thread. */
class EvidenceStore(private val filesDir: File) {
    private val root get() = File(filesDir, "evidence")

    /** Saves [frame] with a ring at the tap point (display pixels scaled to frame pixels); returns the path. */
    fun saveFrame(taskId: Long, frame: Bitmap, tapX: Int?, tapY: Int?, displaySize: Point?): String? = try {
        val dir = File(root, taskId.toString()).apply { mkdirs() }
        val file = File(dir, "frame.jpg")
        val marked = marked(frame, tapX, tapY, displaySize)
        file.outputStream().use { marked.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (marked !== frame) marked.recycle()
        file.absolutePath
    } catch (_: Exception) {
        null
    }

    /**
     * Saves the frame of a hold with the tap marked, and a crop of each attached item from the unmarked frame
     * (`thumb_<n>.jpg`, at most [MAX_THUMBS]). Crops of an earlier hold of the same task are removed first.
     * Returns the frame's path; [thumbsBeside] lists the crops.
     */
    fun saveHold(taskId: Long, frame: Bitmap, held: HeldAction, displaySize: Point?): String? {
        val dir = File(root, taskId.toString())
        dir.listFiles { f -> f.name.startsWith(THUMB_PREFIX) }?.forEach { it.delete() }
        cropRects(held.facts.attachmentBounds, frame.width, frame.height, displaySize).forEachIndexed { i, r ->
            try {
                dir.mkdirs()
                val crop = Bitmap.createBitmap(frame, r.left, r.top, r.right - r.left, r.bottom - r.top)
                File(dir, "$THUMB_PREFIX${i + 1}.jpg").outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                if (crop !== frame) crop.recycle()
            } catch (_: Exception) {
                // A crop that cannot be written is left out; the frame still shows the screen.
            }
        }
        return saveFrame(taskId, frame, held.tapX, held.tapY, displaySize)
    }

    /** Deletes the pictures of tasks whose folder was last changed before [cutoff]. */
    fun deleteBefore(cutoff: Long) {
        root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    companion object {
        const val MAX_THUMBS = 10
        const val MIN_CROP_PX = 24
        private const val THUMB_PREFIX = "thumb_"

        /**
         * Item bounds (display pixels) mapped to frame pixels with the same scale as the tap mark, clamped to the
         * frame; crops under [MIN_CROP_PX] on a side are skipped; at most [MAX_THUMBS].
         */
        fun cropRects(bounds: List<Box>, frameW: Int, frameH: Int, displaySize: Point?): List<Box> {
            val sx = displaySize?.takeIf { it.x > 0 }?.let { frameW.toFloat() / it.x } ?: 1f
            val sy = displaySize?.takeIf { it.y > 0 }?.let { frameH.toFloat() / it.y } ?: 1f
            return bounds.asSequence().map { b ->
                Box((b.left * sx).toInt().coerceIn(0, frameW), (b.top * sy).toInt().coerceIn(0, frameH),
                    (b.right * sx).toInt().coerceIn(0, frameW), (b.bottom * sy).toInt().coerceIn(0, frameH))
            }.filter { it.right - it.left >= MIN_CROP_PX && it.bottom - it.top >= MIN_CROP_PX }.take(MAX_THUMBS).toList()
        }

        /** The crops saved next to [framePath] by [saveHold], in order. */
        fun thumbsBeside(framePath: String?): List<String> {
            val dir = framePath?.let { File(it).parentFile } ?: return emptyList()
            return dir.listFiles { f -> f.name.startsWith(THUMB_PREFIX) && f.name.endsWith(".jpg") }.orEmpty()
                .sortedBy { it.name.removePrefix(THUMB_PREFIX).removeSuffix(".jpg").toIntOrNull() ?: Int.MAX_VALUE }
                .map { it.absolutePath }
        }
    }

    // The same mark as the approval notification draws (gate/ApprovalCard).
    private fun marked(frame: Bitmap, x: Int?, y: Int?, displaySize: Point?): Bitmap {
        if (x == null || y == null) return frame
        val copy = frame.copy(Bitmap.Config.ARGB_8888, true) ?: return frame
        val sx = displaySize?.takeIf { it.x > 0 }?.let { copy.width.toFloat() / it.x } ?: 1f
        val sy = displaySize?.takeIf { it.y > 0 }?.let { copy.height.toFloat() / it.y } ?: 1f
        val radius = copy.width / 18f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFE53935.toInt()
            strokeWidth = radius / 4f
        }
        Canvas(copy).drawCircle(x * sx, y * sy, radius, paint)
        return copy
    }
}
