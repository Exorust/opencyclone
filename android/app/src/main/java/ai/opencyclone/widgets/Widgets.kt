package ai.opencyclone.widgets

import ai.opencyclone.tasks.ProactiveRuntime
import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting

/**
 * The process's widget objects, set once by ProactiveRuntime and read by the Widgets page, the draft page and the
 * Privacy screen. Tests [install] their own and [reset] after.
 */
object Widgets {
    private class Parts(val store: WidgetStore, val sources: WidgetSources, val flow: WidgetFlow)
    @Volatile private var parts: Parts? = null

    val store: WidgetStore get() = checkNotNull(parts).store
    val sources: WidgetSources get() = checkNotNull(parts).sources
    val flow: WidgetFlow get() = checkNotNull(parts).flow

    val isReady: Boolean get() = parts != null

    fun install(store: WidgetStore, sources: WidgetSources, flow: WidgetFlow) {
        parts = Parts(store, sources, flow)
    }

    @VisibleForTesting fun reset() {
        parts = null
    }

    /** True when the objects exist; builds the runtime on first use (not under Robolectric, where tests install them). */
    fun ready(context: Context): Boolean {
        if (!isReady && Build.FINGERPRINT != "robolectric") runCatching { ProactiveRuntime.init(context.applicationContext) }
        return isReady
    }
}
