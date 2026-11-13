package ai.opencyclon.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** Both pages inflate item_widget_card, including its shadow and clipped WebView container. */
class WidgetCardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    init {
        background = WidgetHost.cardBackground(context)
        clipToOutline = true
    }
}
