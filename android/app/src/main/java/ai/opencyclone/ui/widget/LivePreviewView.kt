package ai.opencyclone.ui.widget

import ai.opencyclone.R
import ai.opencyclone.ui.theme.Motion
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * One picture of a task's hidden display (UI.md section 10, running panel): as wide as its layout width
 * (150 dp in the panel), as high as the picture's aspect ratio asks, radius 12 dp. Before the first picture
 * it is a grey rectangle in the screen's aspect ratio. The first picture fades in; later ones replace it at once.
 */
class LivePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val radius = resources.getDimension(R.dimen.radius_thumb)
    private val greyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.surface_2) }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val box = RectF()
    private val dst = Rect()
    private var fade: ValueAnimator? = null

    /** The picture shown now, or null for the grey rectangle. */
    var bitmap: Bitmap? = null
        private set

    /** Shows [bitmap]; null returns to the grey rectangle. The first picture after grey fades in. */
    fun setBitmap(bitmap: Bitmap?) {
        val old = this.bitmap
        val first = old == null && bitmap != null
        this.bitmap = bitmap
        if (old?.width != bitmap?.width || old?.height != bitmap?.height) requestLayout()
        fade?.cancel()
        if (first && ValueAnimator.areAnimatorsEnabled()) {
            bitmapPaint.alpha = 0
            fade = ValueAnimator.ofInt(0, 255).apply {
                duration = Motion.ENTER_MS
                addUpdateListener { bitmapPaint.alpha = it.animatedValue as Int; invalidate() }
                start()
            }
        } else {
            bitmapPaint.alpha = 255
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).takeIf { MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED }
            ?: resources.getDimensionPixelSize(R.dimen.preview_width)
        val b = bitmap
        val aspect = if (b != null && b.width > 0) b.height.toFloat() / b.width
        else resources.displayMetrics.let { it.heightPixels.toFloat() / it.widthPixels.coerceAtLeast(1) }
        val height = resolveSize((width * aspect).toInt(), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        box.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(box, radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(box, greyPaint)
        bitmap?.takeIf { !it.isRecycled }?.let {
            dst.set(0, 0, width, height)
            canvas.drawBitmap(it, null, dst, bitmapPaint)
        }
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        fade?.cancel()
        bitmapPaint.alpha = 255
        super.onDetachedFromWindow()
    }
}
