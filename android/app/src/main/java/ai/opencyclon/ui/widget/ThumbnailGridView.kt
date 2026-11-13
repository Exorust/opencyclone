package ai.opencyclon.ui.widget

import ai.opencyclon.R
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The thumbnail grid of UI.md section 4: squares of `thumb_size`, `thumb_gap` apart, radius 12 dp,
 * [COLUMNS] in a row, at most [MAX_ROWS] rows. When there are more items than tiles, the last tile
 * shows "+N". Tiles without a picture (the count is larger than the number of crops) are plain grey.
 */
class ThumbnailGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val tileSize = resources.getDimensionPixelSize(R.dimen.thumb_size)
    private val gap = resources.getDimensionPixelSize(R.dimen.thumb_gap)

    /** Called with the tile index when a tile is tapped. */
    var onTileClick: ((index: Int) -> Unit)? = null

    /** Number of tiles shown. */
    val tileCount: Int get() = childCount

    /** The "+N" text of the last tile, or null when every item has its own tile. */
    var overflowText: String? = null
        private set

    /**
     * Shows [paths] (files in the app's private storage). [count] is the number of items the
     * evidence stands for; when it is larger than the number of paths, the grid has that many tiles.
     */
    fun setThumbs(paths: List<String>, count: Int? = null) {
        removeAllViews()
        val total = maxOf(paths.size, count ?: 0)
        val plan = layoutFor(total)
        overflowText = plan.overflow?.let { "+$it" }
        for (i in 0 until plan.tiles) {
            val isOverflow = plan.overflow != null && i == plan.tiles - 1
            addView(if (isOverflow) overflowTile(plan.overflow!!) else photoTile(paths.getOrNull(i), i, total))
        }
        requestLayout()
    }

    /**
     * Fades the tiles in one after the other: [staggerMs] apart, [durationMs] each (UI.md section 10, 5.6).
     * Returns the time until the last tile is fully shown. With animations off, everything shows at once.
     */
    fun revealTiles(staggerMs: Long = STAGGER_MS, durationMs: Long = TILE_MS): Long {
        if (!ValueAnimator.areAnimatorsEnabled() || childCount == 0) {
            showTiles()
            return 0
        }
        for (i in 0 until childCount) {
            getChildAt(i).apply {
                animate().cancel()
                alpha = 0f
                animate().alpha(1f).setStartDelay(i * staggerMs).setDuration(durationMs).start()
            }
        }
        return (childCount - 1) * staggerMs + durationMs
    }

    /** Shows every tile at once. */
    fun showTiles() {
        for (i in 0 until childCount) getChildAt(i).apply { animate().cancel(); alpha = 1f }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = MeasureSpec.makeMeasureSpec(tileSize, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(spec, spec)
        val columns = minOf(childCount, COLUMNS)
        val rows = (childCount + COLUMNS - 1) / COLUMNS
        val w = if (columns == 0) 0 else columns * tileSize + (columns - 1) * gap
        val h = if (rows == 0) 0 else rows * tileSize + (rows - 1) * gap
        setMeasuredDimension(
            resolveSize(w + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(h + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val x = paddingLeft + (i % COLUMNS) * (tileSize + gap)
            val y = paddingTop + (i / COLUMNS) * (tileSize + gap)
            getChildAt(i).layout(x, y, x + tileSize, y + tileSize)
        }
    }

    private fun photoTile(path: String?, index: Int, total: Int): View = ImageView(context).apply {
        setBackgroundResource(R.drawable.bg_thumb)
        clipToOutline = true
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = context.getString(R.string.thread_photo_n_of_m, index + 1, total)
        // ponytail: synchronous decode on the main thread; the crops are small files, at most 10 per row
        path?.let { decode(it) }?.let { setImageBitmap(it) }
        setOnClickListener { onTileClick?.invoke(index) }
    }

    private fun overflowTile(more: Int): View = FrameLayout(context).apply {
        setBackgroundResource(R.drawable.bg_thumb)
        clipToOutline = true
        contentDescription = context.getString(R.string.thread_more_photos, more)
        val index = COLUMNS * MAX_ROWS - 1
        setOnClickListener { onTileClick?.invoke(index) }
        addView(TextView(context).apply {
            setTextAppearance(R.style.TextAppearance_App_Body)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            textFontWeight(500)
            text = "+$more"
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun TextView.textFontWeight(w: Int) {
        typeface = android.graphics.Typeface.create(typeface, w, false)
    }

    private fun decode(path: String) = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= tileSize && bounds.outHeight / (sample * 2) >= tileSize) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** How many tiles to show for [total] items, and the N of "+N" (null when all fit). */
    data class Plan(val tiles: Int, val overflow: Int?)

    companion object {
        const val COLUMNS = 5
        const val MAX_ROWS = 2
        const val STAGGER_MS = 60L
        const val TILE_MS = 170L

        fun layoutFor(total: Int): Plan {
            val max = COLUMNS * MAX_ROWS
            return if (total <= max) Plan(total.coerceAtLeast(0), null) else Plan(max, total - (max - 1))
        }
    }
}
