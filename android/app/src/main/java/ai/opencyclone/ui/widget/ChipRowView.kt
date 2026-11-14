package ai.opencyclone.ui.widget

import ai.opencyclone.R
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * One row of chips (UI.md section 4): `surface` pills, 32 dp high, `label` text in `ink`, 8 dp apart.
 * A done chip ends with a check mark. Each chip sits in a 48 dp touch area. The row scrolls sideways
 * when the chips do not fit.
 */
class ChipRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {

    /** One chip. [icon] is drawn at the start, for example an app's letter tile; null for none. */
    data class Chip(val id: Long, val label: String, val done: Boolean = false, val icon: Drawable? = null)

    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val touchHeight = (48 * resources.displayMetrics.density).toInt()

    /** Called when a chip is tapped. Chips are not tappable while this is null. */
    var onChipClick: ((Chip) -> Unit)? = null
        set(value) { field = value; setChips(chips) }

    var chips: List<Chip> = emptyList()
        private set

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setChips(chips: List<Chip>) {
        this.chips = chips
        row.removeAllViews()
        val gap = resources.getDimensionPixelSize(R.dimen.gap_chip)
        chips.forEachIndexed { i, chip ->
            val frame = FrameLayout(context).apply {
                minimumHeight = touchHeight
                val click = onChipClick
                if (click != null) setOnClickListener { click(chip) } else isClickable = false
                contentDescription = if (chip.done) context.getString(R.string.thread_chip_done, chip.label) else chip.label
            }
            frame.addView(chipView(chip), FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            row.addView(frame, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, touchHeight).apply {
                if (i > 0) marginStart = gap
            })
        }
    }

    /** The chip text views, in order (tests). */
    internal fun chipViews(): List<TextView> =
        (0 until row.childCount).map { (row.getChildAt(it) as FrameLayout).getChildAt(0) as TextView }

    private fun chipView(chip: Chip): TextView =
        TextView(context, null, 0, R.style.Widget_App_Chip).apply {
            text = chip.label
            maxLines = 1
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            val small = resources.getDimensionPixelSize(R.dimen.icon_glyph)
            val start = chip.icon?.mutate()?.apply { setBounds(0, 0, small, small) }
            val end = if (chip.done) {
                ContextCompat.getDrawable(context, R.drawable.ic_check_16)?.mutate()?.apply {
                    setTint(ContextCompat.getColor(context, R.color.ink))
                    setBounds(0, 0, small, small)
                }
            } else null
            setCompoundDrawablesRelative(start, null, end, null)
            compoundDrawablePadding = resources.getDimensionPixelSize(R.dimen.gap_chip)
        }
}
