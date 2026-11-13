package ai.opencyclon.ui.theme

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Inset types that content must not sit under: status bar, navigation bar, keyboard, cutout. */
private val BAR_INSETS = WindowInsetsCompat.Type.systemBars() or
    WindowInsetsCompat.Type.ime() or
    WindowInsetsCompat.Type.displayCutout()

/**
 * Pads [view] by the system bar, keyboard, and cutout insets on top of its own padding from the layout.
 * Left and right are always padded; [top] and [bottom] choose the vertical edges.
 */
fun applySystemBarPadding(view: View, top: Boolean = true, bottom: Boolean = true) {
    val start = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val i = insets.getInsets(BAR_INSETS)
        v.setPadding(
            start[0] + i.left,
            start[1] + if (top) i.top else 0,
            start[2] + i.right,
            start[3] + if (bottom) i.bottom else 0,
        )
        insets
    }
    if (view.isAttachedToWindow) ViewCompat.requestApplyInsets(view)
}

/** Draws the window behind transparent status and navigation bars, with dark bar icons. */
@Suppress("DEPRECATION")
fun Activity.drawBehindSystemBars() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    window.isStatusBarContrastEnforced = false
    window.isNavigationBarContrastEnforced = false
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = true
        isAppearanceLightNavigationBars = true
    }
}
