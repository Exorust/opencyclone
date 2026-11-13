package ai.opencyclon.feed

import ai.opencyclon.action.Action
import ai.opencyclon.gate.Box
import ai.opencyclon.gate.HeldAction
import ai.opencyclon.gate.HoldReason
import ai.opencyclon.gate.ScreenFacts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Lane N5: crops of the attached items from the frame captured at hold time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EvidenceCropTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun boundsAreScaledLikeTheTapMark() {
        // The frame is half the size of the 1080 x 2400 display.
        val rects = EvidenceStore.cropRects(listOf(Box(100, 1000, 300, 1200)), 540, 1200, Point(1080, 2400))
        assertEquals(listOf(Box(50, 500, 150, 600)), rects)
    }

    @Test fun smallCropsAreSkippedAndAtMostTenAreKept() {
        val small = Box(0, 0, 40, 40) // 20 x 20 in the half-size frame
        val many = (0 until 14).map { Box(it * 70, 0, it * 70 + 60, 60) }
        val rects = EvidenceStore.cropRects(listOf(small) + many, 540, 1200, Point(1080, 2400))
        assertEquals(EvidenceStore.MAX_THUMBS, rects.size)
        assertTrue(rects.all { it.right - it.left >= EvidenceStore.MIN_CROP_PX && it.bottom - it.top >= EvidenceStore.MIN_CROP_PX })
    }

    @Test fun boundsOutsideTheFrameAreClamped() {
        val rects = EvidenceStore.cropRects(listOf(Box(1000, 2300, 1200, 2600)), 540, 1200, Point(1080, 2400))
        assertEquals(listOf(Box(500, 1150, 540, 1200)), rects)
    }

    @Test fun saveHoldWritesFrameAndThumbsAndReplacesOldThumbs() {
        val store = EvidenceStore(tmp.root)
        val frame = Bitmap.createBitmap(540, 1200, Bitmap.Config.ARGB_8888)
        fun held(bounds: List<Box>) = HeldAction(Action.Tap(980, 2250), HoldReason.COMMIT_WORD, "Send", null, 980, 2250,
            ScreenFacts("p", "Alex", bounds.size, attachmentNoun = "photos", attachmentBounds = bounds))
        val path = store.saveHold(7, frame, held(listOf(Box(0, 1000, 200, 1200), Box(220, 1000, 420, 1200), Box(440, 1000, 640, 1200))), Point(1080, 2400))
        assertEquals(File(tmp.root, "evidence/7/frame.jpg").absolutePath, path)
        val thumbs = EvidenceStore.thumbsBeside(path)
        assertEquals((1..3).map { File(tmp.root, "evidence/7/thumb_$it.jpg").absolutePath }, thumbs)
        assertEquals(100, BitmapFactory.decodeFile(thumbs[0]).width)
        // A second hold of the same task with one item leaves one crop.
        val again = store.saveHold(7, frame, held(listOf(Box(0, 1000, 200, 1200))), Point(1080, 2400))
        assertEquals(1, EvidenceStore.thumbsBeside(again).size)
        assertEquals(emptyList<String>(), EvidenceStore.thumbsBeside(null))
    }
}
