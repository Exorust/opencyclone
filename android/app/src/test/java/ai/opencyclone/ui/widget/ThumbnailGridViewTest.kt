package ai.opencyclone.ui.widget

import ai.opencyclone.R
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThumbnailGridViewTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun grid(thumbs: Int, count: Int? = null) = ThumbnailGridView(context).apply {
        setThumbs((1..thumbs).map { "/no/such/crop_$it.png" }, count)
    }

    private fun measuredHeight(g: ThumbnailGridView): Int {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        g.measure(unspecified, unspecified)
        return g.measuredHeight
    }

    @Test fun threeThumbsGiveThreeTilesInOneRow() {
        val g = grid(3)
        assertEquals(3, g.tileCount)
        assertNull(g.overflowText)
        assertEquals(context.resources.getDimensionPixelSize(R.dimen.thumb_size), measuredHeight(g))
    }

    @Test fun nineThumbsGiveNineTilesInTwoRows() {
        val g = grid(9)
        assertEquals(9, g.tileCount)
        assertNull(g.overflowText)
        val res = context.resources
        assertEquals(2 * res.getDimensionPixelSize(R.dimen.thumb_size) + res.getDimensionPixelSize(R.dimen.thumb_gap), measuredHeight(g))
    }

    @Test fun tenThumbsFitWithoutOverflow() {
        val g = grid(10)
        assertEquals(10, g.tileCount)
        assertNull(g.overflowText)
    }

    @Test fun fourteenThumbsShowNineAndPlusFive() {
        val g = grid(14)
        assertEquals(10, g.tileCount)
        assertEquals("+5", g.overflowText)
        assertEquals("5 more photos", g.getChildAt(9).contentDescription)
    }

    @Test fun countLargerThanThumbsSetsTheTiles() {
        assertEquals(9, grid(3, count = 9).tileCount)
        val big = grid(3, count = 14)
        assertEquals(10, big.tileCount)
        assertEquals("+5", big.overflowText)
        // a smaller count never hides crops
        assertEquals(4, grid(4, count = 2).tileCount)
    }

    @Test fun tilesDescribeTheirPlace() {
        val g = grid(9)
        assertEquals("Photo 3 of 9", g.getChildAt(2).contentDescription)
    }

    @Test fun tapReportsTheTile() {
        var tapped = -1
        val g = grid(4).apply { onTileClick = { tapped = it } }
        g.getChildAt(2).performClick()
        assertEquals(2, tapped)
    }

    @Test fun planIsFiveByTwoAtMost() {
        assertEquals(ThumbnailGridView.Plan(0, null), ThumbnailGridView.layoutFor(0))
        assertEquals(ThumbnailGridView.Plan(10, 2), ThumbnailGridView.layoutFor(11))
    }
}
