package ai.opencyclone.widgets

import ai.opencyclone.R
import android.content.res.Resources

/** Native dp and CSS pixels agree at the widget's device-width viewport. */
object WidgetDimensions {
    const val MIN_TALL_DP = 120

    fun width(size: WidgetSize, contentWidth: Int, gap: Int): Int =
        if (size.span == 2) contentWidth else (contentWidth - gap) / 2

    fun widthPx(resources: Resources, size: WidgetSize): Int = width(size,
        resources.displayMetrics.widthPixels - 2 * resources.getDimensionPixelSize(R.dimen.margin_page),
        resources.getDimensionPixelSize(R.dimen.gap_buttons))

    fun height(size: WidgetSize, contentHeight: Int = size.heightDp): Int =
        if (size == WidgetSize.TALL) contentHeight.coerceIn(MIN_TALL_DP, size.heightDp) else size.heightDp

    fun sizeNote(resources: Resources, size: WidgetSize): String {
        val cssWidth = (widthPx(resources, size) / resources.displayMetrics.density).toInt()
        val lines = if (size == WidgetSize.TALL) 10 else 6
        return "$cssWidth by ${size.heightDp} CSS pixels. Everything must be visible without scrolling. " +
            "Use at most $lines lines of text." +
            if (size == WidgetSize.TALL) " The height follows content between 120 and 320 CSS pixels; use natural content height." else ""
    }
}
