package ai.opencyclone.ui.widget

import ai.opencyclone.R
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory

/**
 * App icons in the shape of UI.md section 3.4: a rounded square with radius 28% of its size.
 * Other apps' icons come from the package manager (the manifest `<queries>` launcher intent makes them visible).
 */
object AppIcons {
    /** Corner radius as a fraction of the icon size. */
    const val CORNER_FRACTION = 0.28f

    // ponytail: synchronous load on the calling thread with a 64-entry cache; fine for a few icons per screen
    private val cache = LruCache<String, Bitmap>(64)

    /** Another app's icon at [sizePx], clipped to the rounded square, or null when the package is not installed. */
    fun load(context: Context, packageName: String, sizePx: Int): Drawable? {
        val key = "$packageName@$sizePx"
        val bitmap = cache.get(key) ?: run {
            val icon: Drawable? = try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            if (icon == null) return null
            render(icon, sizePx).also { cache.put(key, it) }
        }
        return rounded(context, bitmap, sizePx)
    }

    /** Sets [packageName]'s icon on [view] at the view's layout size; falls back to a letter tile of [fallbackName]. */
    fun into(view: ImageView, packageName: String?, fallbackName: String = "") {
        val size = view.layoutParams?.width?.takeIf { it > 0 } ?: view.width.takeIf { it > 0 }
            ?: view.resources.getDimensionPixelSize(R.dimen.icon_small)
        val icon = packageName?.let { load(view.context, it, size) }
        view.setImageDrawable(icon ?: letterTile(view.context, fallbackName, ContextCompat.getColor(view.context, R.color.ink_3), size))
    }

    /** A built app's tile: the accent colour with one white character, rounded like an app icon. */
    fun letterTile(context: Context, name: String, @ColorInt accent: Int, sizePx: Int): Drawable {
        val size = sizePx.coerceAtLeast(1)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val radius = size * CORNER_FRACTION
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
        val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
        if (letter.isNotEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = size * 0.5f
                typeface = interSemiBold(context)
            }
            val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(letter, size / 2f, y, paint)
        }
        return BitmapDrawable(context.resources, bitmap)
    }

    /** Parses an accent "#RRGGBB"; returns the default accent when it cannot be read. */
    @ColorInt
    fun parseAccent(context: Context, hex: String?): Int =
        hex?.let { runCatching { Color.parseColor(it) }.getOrNull() }
            ?: ContextCompat.getColor(context, R.color.accent_default)

    /** Empties the cache (tests, or after a package change). */
    fun clearCache() = cache.evictAll()

    private fun render(icon: Drawable, size: Int): Bitmap {
        val s = size.coerceAtLeast(1)
        val bitmap = createBitmap(s, s)
        val canvas = Canvas(bitmap)
        icon.setBounds(0, 0, s, s)
        icon.draw(canvas)
        return bitmap
    }

    private fun rounded(context: Context, bitmap: Bitmap, size: Int): Drawable =
        RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply {
            cornerRadius = size * CORNER_FRACTION
            setAntiAlias(true)
        }

    private fun interSemiBold(context: Context): Typeface {
        val family = runCatching { ResourcesCompat.getFont(context, R.font.inter) }.getOrNull() ?: Typeface.DEFAULT
        return Typeface.create(family, 600, false)
    }
}
