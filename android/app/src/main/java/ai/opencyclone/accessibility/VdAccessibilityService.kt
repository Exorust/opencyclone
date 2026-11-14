package ai.opencyclone.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import ai.opencyclone.gate.Box
import ai.opencyclone.gate.ElementInfo
import ai.opencyclone.gate.ScreenFacts
import ai.opencyclone.gate.ScreenReader
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class VdAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VdA11yService"
        private const val MAX_DEPTH = 15
        private const val MAX_NODES = 200

        @Volatile
        var instance: VdAccessibilityService? = null
            private set

        /**
         * The flattened component name for the secure accessibility settings. The class keeps its
         * package `ai.opencyclone.accessibility` on every flavor; only the application id changes
         * (`ai.opencyclone.standard` on the standard build).
         */
        fun componentName(applicationId: String): String =
            "$applicationId/${VdAccessibilityService::class.java.name}"

        val screenReader: ScreenReader = object : ScreenReader {
            override fun elementAt(displayId: Int, xPx: Int, yPx: Int): ScreenReader.Result<ElementInfo?> =
                readDisplay(displayId) { nodes, _, _ ->
                    // Windows are ordered front to back. Pick the deepest hit in the first hit window.
                    val hits = nodes.filter { it.visible && it.info.left <= xPx && xPx < it.info.right &&
                        it.info.top <= yPx && yPx < it.info.bottom }
                    val window = hits.firstOrNull()?.window
                    hits.filter { it.window == window }.maxByOrNull { it.depth }?.info
                }

            override fun focusedElement(displayId: Int): ScreenReader.Result<ElementInfo?> =
                readDisplay(displayId) { nodes, _, _ -> nodes.firstOrNull { it.focused }?.info }

            override fun screenFacts(displayId: Int): ScreenReader.Result<ScreenFacts> =
                readDisplay(displayId) { nodes, windows, home -> factsFor(nodes, windows, home, null, density()) }

            override fun screenFactsAt(displayId: Int, xPx: Int, yPx: Int): ScreenReader.Result<ScreenFacts> =
                readDisplay(displayId) { nodes, windows, home -> factsFor(nodes, windows, home, xPx to yPx, density()) }
        }

        /** One window on a display, as far as choosing the top application window needs it. Bounds in display pixels. */
        internal data class WindowRef(
            val type: Int, val layer: Int, val packageName: String?,
            val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0
        ) {
            val area: Long get() = if (right > left && bottom > top) (right - left).toLong() * (bottom - top) else 0L
        }

        /** A chosen window: its position in the input list and its package. */
        internal data class TopWindow(val index: Int, val packageName: String?)

        private val NOT_APP_WINDOW_TYPES = setOf(
            AccessibilityWindowInfo.TYPE_INPUT_METHOD, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)

        private fun isAppCandidate(w: WindowRef) =
            w.type !in NOT_APP_WINDOW_TYPES && w.packageName != "com.android.systemui"

        /**
         * The highest window that belongs to an app: the keyboard, accessibility overlays and
         * system UI are skipped. Decoration (a launcher taskbar or another small system strip)
         * is skipped too, unless nothing else is left (the home screen). Null when no app window exists.
         *
         * Decoration: a window that covers under half of the display (the union of all window bounds)
         * and either belongs to a home package or is a system-type window. Focus and `isActive` are
         * not used: on a phone without per-display focus they describe the main display, not the hidden one.
         */
        internal fun topAppWindow(windows: List<WindowRef>, homePackages: Set<String> = emptySet()): TopWindow? {
            val candidates = windows.withIndex().filter { isAppCandidate(it.value) }
            val displayArea = displayArea(windows)
            fun decoration(w: WindowRef) = isDecoration(w, displayArea, homePackages)
            val chosen = candidates.filterNot { decoration(it.value) }.maxByOrNull { it.value.layer }
                ?: candidates.maxByOrNull { it.value.layer }
            return chosen?.let { TopWindow(it.index, it.value.packageName) }
        }

        /**
         * A window that only decorates the display: under half of [displayArea] and a home package (a launcher
         * taskbar) or a system-type window. The same rule as [topAppWindow].
         */
        internal fun isDecoration(w: WindowRef, displayArea: Long, homePackages: Set<String>): Boolean =
            w.area > 0 && w.area * 2 < displayArea &&
                (w.packageName in homePackages || w.type == AccessibilityWindowInfo.TYPE_SYSTEM)

        /**
         * A spinner: a ProgressBar without a range, or (API 36 on) with the indeterminate range type, which an
         * indeterminate ProgressBar reports from then on.
         */
        internal fun isIndeterminateProgress(className: String?, rangeType: Int?): Boolean =
            className == "android.widget.ProgressBar" && (rangeType == null || rangeType == RANGE_TYPE_INDETERMINATE)

        /** AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INDETERMINATE (API 36). */
        private const val RANGE_TYPE_INDETERMINATE = 3

        /**
         * The windows to look for the focused field in, front to back (F18.3): no keyboard, no system UI, and no
         * decoration. A launcher taskbar on the hidden display keeps an input focus of its own (a TextView), which
         * would hide the app's field.
         */
        internal fun fieldWindowOrder(windows: List<WindowRef>, homePackages: Set<String>): List<Int> {
            val area = displayArea(windows)
            return windows.indices
                .filter { windows[it].type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    windows[it].packageName != "com.android.systemui" && !isDecoration(windows[it], area, homePackages) }
                .sortedByDescending { windows[it].layer }
        }

        private fun displayArea(windows: List<WindowRef>): Long {
            val sized = windows.filter { it.area > 0 }
            if (sized.isEmpty()) return 0L
            return (sized.maxOf { it.right } - sized.minOf { it.left }).toLong() *
                (sized.maxOf { it.bottom } - sized.minOf { it.top })
        }

        /**
         * One node of a display, flattened in tree order. [hint] and [showingHint]: the field's placeholder and whether
         * the platform reports that the text shown is that placeholder. [collection]: a list or grid (scrollable,
         * collection info, or a list class name).
         */
        internal data class GateNode(
            val info: ElementInfo, val packageName: String?, val window: Int, val depth: Int,
            val focused: Boolean, val heading: Boolean, val inToolbar: Boolean, val visible: Boolean,
            val hint: String? = null, val showingHint: Boolean = false,
            val checked: Boolean = false, val selected: Boolean = false,
            val scrollable: Boolean = false, val collection: Boolean = false,
        )

        private fun GateNode.contains(x: Int, y: Int) =
            visible && info.left <= x && x < info.right && info.top <= y && y < info.bottom

        /**
         * Package and title of one window. With a point, the window the point lands in (front to back),
         * when that is an app window, including a launcher taskbar; otherwise the top app window.
         * `nodes[i].window` and `windows` indices refer to the same front-to-back window list.
         */
        internal fun factsFor(
            nodes: List<GateNode>, windows: List<WindowRef>, homePackages: Set<String>, point: Pair<Int, Int>?,
            density: Float = 1f,
        ): ScreenFacts {
            val hit = point?.let { (x, y) -> nodes.firstOrNull { it.contains(x, y) }?.window }
                ?.takeIf { windows.getOrNull(it)?.let(::isAppCandidate) == true }
            val index = hit ?: topAppWindow(windows, homePackages)?.index
            val windowNodes = nodes.filter { it.window == index }
            val title = windowNodes.firstOrNull { it.heading && !it.info.text.isNullOrBlank() }?.info?.text
                ?: windowNodes.firstOrNull { it.inToolbar && !it.info.text.isNullOrBlank() }?.info?.text
            val w = index?.let { windows.getOrNull(it) }
            val windowBox = w?.takeIf { it.area > 0 }?.let { Box(it.left, it.top, it.right, it.bottom) }
            val attachments = ScreenEvidence.attachments(windowNodes, windowBox, density)
            return ScreenFacts(w?.packageName, title, attachments?.count,
                composeText = ScreenEvidence.composeText(windowNodes),
                attachmentNoun = attachments?.noun,
                attachmentBounds = attachments?.bounds.orEmpty())
        }

        // ponytail: the main display's density; the hidden display may use another one, which shifts the 40 dp rule.
        private fun density(): Float = instance?.resources?.displayMetrics?.density ?: 1f

        @Volatile private var cachedHomePackages: Set<String> = emptySet()

        /** Home packages without the resolver ("android") and without our own package (home mode makes it a HOME candidate). */
        internal fun homePackagesOf(packages: List<String>, ownPackage: String): Set<String> =
            packages.filter { it != "android" && it != ownPackage }.toSet()

        /** Packages that act as home on this phone (main and secondary displays); resolved once. */
        private fun homePackages(service: AccessibilityService): Set<String> {
            cachedHomePackages.takeIf { it.isNotEmpty() }?.let { return it }
            val found = try {
                val pm = service.packageManager
                listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME).flatMap { category ->
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
                    pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName } +
                        listOfNotNull(pm.resolveActivity(intent, 0)?.activityInfo?.packageName)
                }.let { homePackagesOf(it, service.packageName) }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot resolve home packages", e)
                emptySet()
            }
            cachedHomePackages = found
            return found
        }

        private fun windowRef(window: AccessibilityWindowInfo, pkg: String?): WindowRef {
            val b = Rect()
            window.getBoundsInScreen(b)
            return WindowRef(window.type, window.layer, pkg, b.left, b.top, b.right, b.bottom)
        }

        private fun <T> readDisplay(
            displayId: Int, read: (List<GateNode>, List<WindowRef>, Set<String>) -> T
        ): ScreenReader.Result<T> {
            val service = instance ?: return ScreenReader.Result.Unreadable
            return try {
                // Resource IDs are omitted unless this flag is enabled before requesting nodes.
                val info = service.serviceInfo ?: return ScreenReader.Result.Unreadable
                if (info.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS == 0) {
                    info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    service.serviceInfo = info
                }
                val displayWindows = service.windowsOnAllDisplays?.get(displayId)
                    ?: return ScreenReader.Result.Unreadable
                val nodes = mutableListOf<GateNode>()
                val refs = mutableListOf<WindowRef>()
                for ((windowIndex, window) in displayWindows.sortedByDescending { it.layer }.withIndex()) {
                    val root = window.getRoot(0) ?: return ScreenReader.Result.Unreadable
                    try {
                        val pkg = root.packageName?.toString()
                        refs.add(windowRef(window, pkg))
                        if (pkg != "com.android.systemui") {
                            collectGateNodes(root, pkg, windowIndex, 0, false, nodes)
                        }
                    } finally {
                        root.recycle()
                    }
                }
                ScreenReader.Result.Ok(read(nodes, refs, homePackages(service)))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read approval screen on display $displayId", e)
                ScreenReader.Result.Unreadable
            }
        }

        private fun collectGateNodes(
            node: AccessibilityNodeInfo, packageName: String?, window: Int, depth: Int,
            parentToolbar: Boolean, nodes: MutableList<GateNode>
        ) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val className = node.className?.toString()
            val inToolbar = parentToolbar || className?.contains("Toolbar") == true ||
                className?.contains("ActionBar") == true
            nodes.add(GateNode(ElementInfo(node.text?.toString(), node.contentDescription?.toString(),
                node.viewIdResourceName, className, bounds.left, bounds.top, bounds.right, bounds.bottom,
                node.isEditable), packageName, window, depth, node.isFocused, node.isHeading, inToolbar,
                node.isVisibleToUser,
                hint = node.hintText?.toString(), showingHint = node.isShowingHintText,
                checked = node.isChecked, selected = node.isSelected, scrollable = node.isScrollable,
                collection = node.isScrollable || node.collectionInfo != null || ScreenEvidence.isListClass(className)))
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: throw IllegalStateException("Unavailable accessibility child")
                try {
                    collectGateNodes(child, packageName, window, depth + 1, inToolbar, nodes)
                } finally {
                    child.recycle()
                }
            }
        }

        fun captureViewTree(displayId: Int, displayWidth: Int, displayHeight: Int): String? =
            captureElements(displayId, displayWidth, displayHeight)?.text

        /** The numbered element list of a display with the map of its numbers (F18.1); null when unreadable. */
        fun captureElements(displayId: Int, displayWidth: Int, displayHeight: Int): ElementList? =
            instance?.captureElementsInternal(displayId, displayWidth, displayHeight)

        /** The focused editable field of a display, for the check that typed text arrived (F18.3); null when none. */
        fun focusedField(displayId: Int): FocusedField? = try {
            instance?.focusedFieldInternal(displayId)
        } catch (e: Exception) {
            Log.w(TAG, "focusedField failed", e)
            null
        }

        private const val ANY_DISPLAY = -1
        private val contentChangeCounts = java.util.concurrent.ConcurrentHashMap<Int, Long>()

        /**
         * How many window or content changes the service saw on [displayId] (F18.2): two equal reads mean nothing
         * changed in between. Without per-event display ids (before API 33) every display's events count.
         */
        fun contentChanges(displayId: Int): Long =
            (contentChangeCounts[displayId] ?: 0L) + (contentChangeCounts[ANY_DISPLAY] ?: 0L)

        internal fun recordContentChange(displayId: Int?, packageName: String?) {
            if (packageName == "com.android.systemui") return
            contentChangeCounts.merge(displayId ?: ANY_DISPLAY, 1L, Long::plus)
        }

        fun getTopPackageOnDisplay(displayId: Int): String? {
            val service = instance ?: return null
            return try {
                val windowList = if (displayId == 0) {
                    service.windows
                } else {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val allWindows = service.javaClass.getMethod("getWindowsOnAllDisplays")
                            .invoke(service) as? android.util.SparseArray<List<android.view.accessibility.AccessibilityWindowInfo>>
                        allWindows?.get(displayId)
                    } catch (e: Exception) {
                        service.windows
                    }
                }
                val refs = windowList.orEmpty().mapNotNull { window ->
                    val root = window.getRoot(0) ?: return@mapNotNull null
                    val pkg = root.packageName?.toString()
                    root.recycle()
                    pkg?.let { windowRef(window, it) }
                }
                topAppWindow(refs, homePackages(service))?.packageName
            } catch (e: Exception) {
                Log.w(TAG, "getTopPackageOnDisplay failed", e)
                null
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Trees are read on demand; events only tell the quiet-screen wait that something changed (F18.2).
        event ?: return
        val displayId = if (android.os.Build.VERSION.SDK_INT >= 33) event.displayId else null
        recordContentChange(displayId, event.packageName?.toString())
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun captureElementsInternal(displayId: Int, displayWidth: Int, displayHeight: Int): ElementList? {
        return try {
            val windowsMap = windows?.takeIf { displayId == 0 }
                ?: try {
                    @Suppress("UNCHECKED_CAST")
                    val allWindows = javaClass.getMethod("getWindowsOnAllDisplays").invoke(this)
                        as? android.util.SparseArray<List<android.view.accessibility.AccessibilityWindowInfo>>
                    allWindows?.get(displayId)
                } catch (e: Exception) {
                    Log.w(TAG, "getWindowsOnAllDisplays failed, falling back to getWindows()", e)
                    windows
                }

            if (windowsMap.isNullOrEmpty()) {
                Log.w(TAG, "No windows found for displayId=$displayId")
                return null
            }

            val nodes = mutableListOf<UiNode>()
            val displayArea = displayWidth.toLong() * displayHeight
            val home = homePackages(this)
            for ((windowIndex, window) in windowsMap.withIndex()) {
                val root = window.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
                    ?: continue
                try {
                    val packageName = root.packageName?.toString()
                    if (packageName == "com.android.systemui") continue
                    val keyboard = window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    val decoration = isDecoration(windowRef(window, packageName), displayArea, home)
                    collectUiNodes(root, 0, windowIndex, packageName, keyboard, decoration, nodes)
                } finally {
                    root.recycle()
                }
            }
            val list = ElementList.build(nodes, displayWidth, displayHeight)
            if (list.text.isEmpty()) null else list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture view tree", e)
            null
        }
    }

    private fun collectUiNodes(
        node: AccessibilityNodeInfo, depth: Int, window: Int, packageName: String?, keyboard: Boolean,
        decoration: Boolean, out: MutableList<UiNode>,
    ) {
        // Deeper levels are never listed; the list stops at ElementList.MAX_DEPTH.
        if (depth > ElementList.MAX_DEPTH + 1) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val className = node.className?.toString()
        out.add(UiNode(className, node.text?.toString(), node.contentDescription?.toString(),
            bounds.left, bounds.top, bounds.right, bounds.bottom, depth, window, packageName,
            clickable = node.isClickable, longClickable = node.isLongClickable, editable = node.isEditable,
            scrollable = node.isScrollable, checkable = node.isCheckable, checked = node.isChecked,
            visible = node.isVisibleToUser, keyboard = keyboard,
            indeterminateProgress = isIndeterminateProgress(className, node.rangeInfo?.type), decoration = decoration))
        // An invisible node's subtree is not listed; the keyboard is only noted, never listed.
        if (!node.isVisibleToUser || keyboard) return
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectUiNodes(child, depth + 1, window, packageName, keyboard, decoration, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun focusedFieldInternal(displayId: Int): FocusedField? {
        val displayWindows = try { windowsOnAllDisplays?.get(displayId) } catch (_: Exception) { null } ?: return null
        val roots = displayWindows.map { it.getRoot(0) }
        try {
            val refs = displayWindows.mapIndexed { i, w -> windowRef(w, roots[i]?.packageName?.toString()) }
            for (i in fieldWindowOrder(refs, homePackages(this))) {
                val root = roots[i] ?: continue
                val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
                try {
                    if (!focus.isEditable) {
                        Log.d(TAG, "focusedField: focus on display $displayId is a ${focus.className}, not a field")
                        return null
                    }
                    return FocusedField(
                        text = focus.text?.toString(),
                        password = focus.isPassword,
                        showingHint = focus.isShowingHintText,
                        inputType = focus.inputType,
                    )
                } finally {
                    focus.recycle()
                }
            }
            return null
        } finally {
            roots.forEach { it?.recycle() }
        }
    }
}
