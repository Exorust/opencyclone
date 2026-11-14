package ai.opencyclone.feed

import android.graphics.Bitmap
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewSourceTest {
    @Test fun capturesTwiceASecondOnlyWhileCollected() = runTest {
        var captures = 0
        val bitmap = mockk<Bitmap>()
        val source = PreviewSource({ _, width -> captures++; assertEquals(150, width); bitmap })
        val job = launch { source.previews(4, 150).collect { } }
        advanceTimeBy(2_050)
        assertEquals(5, captures) // at 0, 500, 1000, 1500, 2000 ms
        job.cancel()
        advanceTimeBy(10_000)
        assertEquals(5, captures)
    }

    @Test fun nothingIsCapturedBeforeCollection() = runTest {
        var captures = 0
        val flow = PreviewSource({ _, _ -> captures++; null }).previews(4, 150)
        advanceTimeBy(5_000)
        assertEquals(0, captures)
        val job = launch { flow.collect { } }
        advanceTimeBy(100)
        job.cancel()
        assertEquals(1, captures)
    }

    @Test fun nullPicturesAreSkipped() = runTest {
        val bitmap = mockk<Bitmap>()
        var n = 0
        val got = PreviewSource({ _, _ -> if (n++ % 2 == 0) null else bitmap }).previews(1, 100).take(2).toList()
        assertEquals(listOf(bitmap, bitmap), got)
    }
}
