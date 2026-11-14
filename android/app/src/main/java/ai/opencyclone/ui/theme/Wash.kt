package ai.opencyclone.ui.theme

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.view.View
import androidx.annotation.ColorInt

/**
 * One soft colour area. [cx], [cy] are its centre as a fraction of the bounds.
 * A [band] covers the full width from the top edge and fades to white at [Wash.FADE_OF_HEIGHT] of the height;
 * otherwise the area is round and fades out at a radius of [Wash.FADE_OF_HEIGHT] of the height.
 */
data class WashSpot(@ColorInt val color: Int, val cx: Float, val cy: Float, val band: Boolean = false)

/**
 * The page backgrounds of UI.md section 11 (replaces 3.2 and section 10): the white page with a soft area
 * that starts at the top edge. Names are kept from version 2. The values match the `wash_*` colours in colors.xml.
 */
enum class Wash(@ColorInt val base: Int, val spots: List<WashSpot>) {
    /** Idle: one lavender area at the top. */
    Warm(
        0xFFFFFFFF.toInt(),
        listOf(WashSpot(0xFFECEBFA.toInt(), 0.5f, 0.0f, band = true)),
    ),
    /** Work, approval, results: a blue-lavender area at the top and a second, pinker area at the upper right. */
    Cool(
        0xFFFFFFFF.toInt(),
        listOf(
            WashSpot(0xFFE2E4FB.toInt(), 0.5f, 0.0f, band = true),
            WashSpot(0xFFEEE3F8.toInt(), 1.0f, 0.0f),
        ),
    ),
    /** Listening and speaking: one very pale lavender area at the top. */
    Voice(
        0xFFFFFFFF.toInt(),
        listOf(WashSpot(0xFFF1F0FA.toInt(), 0.5f, 0.0f, band = true)),
    );

    companion object {
        /** Each area has faded to white at this fraction of the height. */
        const val FADE_OF_HEIGHT = 0.4f
    }
}

/** Paints a [Wash]: the base colour and its radial colour areas. Supports alpha for cross-fades. */
class WashDrawable(val wash: Wash) : Drawable() {
    private val basePaint = Paint().apply { color = wash.base }
    private val spotPaints = wash.spots.map { Paint(Paint.ANTI_ALIAS_FLAG) }
    private var alphaValue = 255

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val fade = (bounds.height() * Wash.FADE_OF_HEIGHT).coerceAtLeast(1f)
        wash.spots.forEachIndexed { i, s ->
            val y = bounds.top + bounds.height() * s.cy
            spotPaints[i].shader = if (s.band) {
                LinearGradient(0f, y, 0f, y + fade, s.color, s.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            } else {
                RadialGradient(bounds.left + bounds.width() * s.cx, y, fade, s.color, s.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, basePaint)
        spotPaints.forEach { canvas.drawRect(bounds, it) }
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        basePaint.alpha = alpha
        spotPaints.forEach { it.alpha = alpha }
        invalidateSelf()
    }

    override fun getAlpha(): Int = alphaValue

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        spotPaints.forEach { it.colorFilter = colorFilter }
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int =
        if (alphaValue == 255 && Color.alpha(wash.base) == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
}

/** The wash a view shows now, or null when its background is not a wash. */
val View.currentWash: Wash?
    get() = when (val bg = background) {
        is WashDrawable -> bg.wash
        is TransitionDrawable -> (bg.getDrawable(bg.numberOfLayers - 1) as? WashDrawable)?.wash
        else -> null
    }

/** Sets the view's background to [wash] at once. */
fun View.setWash(wash: Wash) {
    background = WashDrawable(wash)
}

/**
 * Cross-fades the view's background from its present wash to [to] in [durationMs] (600 ms).
 * Without a present wash it sets [to] at once; with the same wash it does nothing.
 */
fun View.crossFadeWash(to: Wash, durationMs: Int = Motion.WASH_MS) {
    val from = currentWash
    if (from == to) return
    if (from == null) {
        setWash(to)
        return
    }
    val transition = TransitionDrawable(arrayOf(WashDrawable(from), WashDrawable(to)))
    transition.isCrossFadeEnabled = true
    background = transition
    transition.startTransition(durationMs)
}
