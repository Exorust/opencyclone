package ai.opencyclone.ui.widget

import ai.opencyclone.R
import android.content.Context
import android.graphics.Bitmap
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
class LivePreviewViewTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun measure(v: LivePreviewView, widthPx: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    }

    @Test fun heightFollowsThePictureAspectRatio() {
        val v = LivePreviewView(context)
        val width = context.resources.getDimensionPixelSize(R.dimen.preview_width)
        assertNull(v.bitmap)
        v.setBitmap(Bitmap.createBitmap(100, 250, Bitmap.Config.ARGB_8888))
        measure(v, width)
        assertEquals(width, v.measuredWidth)
        assertEquals((width * 2.5f).toInt(), v.measuredHeight)
    }

    @Test fun greyRectangleUsesTheScreenAspectBeforeThePicture() {
        val v = LivePreviewView(context)
        measure(v, 300)
        val dm = context.resources.displayMetrics
        assertEquals((300 * (dm.heightPixels.toFloat() / dm.widthPixels)).toInt(), v.measuredHeight)
        v.setBitmap(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        v.setBitmap(null)
        assertNull(v.bitmap)
    }
}
