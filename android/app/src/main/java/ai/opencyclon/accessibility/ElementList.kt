package ai.opencyclon.accessibility

/**
 * One accessibility node of the hidden display, flattened in tree order (pre-order, [depth] from the window root).
 * Bounds in display pixels. [windowType] is an AccessibilityWindowInfo type; [indeterminateProgress] is a spinner.
 */
data class UiNode(
    val className: String?, val text: String?, val description: String?,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val depth: Int, val window: Int, val packageName: String?,
    val clickable: Boolean = false, val longClickable: Boolean = false, val editable: Boolean = false,
    val scrollable: Boolean = false, val checkable: Boolean = false, val checked: Boolean = false,
    val visible: Boolean = true, val keyboard: Boolean = false, val indeterminateProgress: Boolean = false,
    /** In a decoration window (a launcher taskbar or another small system strip): listed, not part of [ElementList.identity]. */
    val decoration: Boolean = false,
)

/** An element the model can act on, under its number. Bounds in display pixels. */
data class UiElement(
    val number: Int, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val label: String, val className: String, val window: Int, val packageName: String?,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    /** Class, label and bounds rounded to [ElementList.KEY_GRID] pixels: the same element on a re-read screen. */
    val key: String get() = ElementList.key(className, label, left, top, right, bottom)
}

/**
 * The element list the model sees (F18.1): elements that can be acted on carry a number `[n]` at the start of their
 * line; text-only lines carry none. [elements] maps each number to its element on this screen.
 * [loading]: a spinner is visible, so the screen is not quiet yet (F18.2).
 * [identity]: the screen for the cycle check (F18.3): the listed nodes outside the keyboard and decoration windows,
 * by class, text, description and bounds on the [KEY_GRID]; numbers are left out (a taskbar that comes and goes
 * shifts them).
 */
data class ElementList(
    val text: String,
    val elements: Map<Int, UiElement>,
    val displayWidth: Int,
    val displayHeight: Int,
    val keyboardOpen: Boolean = false,
    val loading: Boolean = false,
    val identity: Int = text.hashCode(),
) {
    /** A display pixel as the 0-1000 scale of taps; it maps back into the same pixel (input/CoordinateMapper). */
    fun toNormalized(xPx: Int, yPx: Int): Pair<Int, Int> = norm(xPx, displayWidth) to norm(yPx, displayHeight)

    companion object {
        const val MAX_DEPTH = 15
        const val MAX_LINES = 200
        const val MAX_TEXT = 50
        const val KEY_GRID = 24
        /** The one line said instead of listing the keys: typing goes through injection, never through key taps. */
        const val KEYBOARD_LINE = "(keyboard is open; type with type_text, do not tap its keys)"

        fun key(className: String, label: String, left: Int, top: Int, right: Int, bottom: Int): String =
            "$className|$label|${left / KEY_GRID},${top / KEY_GRID},${right / KEY_GRID},${bottom / KEY_GRID}"

        private fun norm(px: Int, size: Int): Int =
            if (size <= 0) 0 else (((px + 0.5) * 1000.0) / size).toInt().coerceIn(0, 1000)

        /** An element list from a text without numbers (an old provider): nothing can be addressed by number. */
        fun textOnly(text: String): ElementList = ElementList(text, emptyMap(), 0, 0)

        fun build(nodes: List<UiNode>, displayWidth: Int, displayHeight: Int): ElementList {
            val sb = StringBuilder()
            val id = StringBuilder()
            val elements = linkedMapOf<Int, UiElement>()
            var lines = 0
            var skipBelow = Int.MAX_VALUE // depth of an invisible node whose subtree is skipped
            var skipWindow = -1
            val keyboardOpen = nodes.any { it.keyboard }
            val loading = nodes.any { it.visible && it.indeterminateProgress && !it.keyboard }
            for ((i, node) in nodes.withIndex()) {
                if (node.window != skipWindow || node.depth <= skipBelow) {
                    skipBelow = Int.MAX_VALUE
                    skipWindow = -1
                }
                if (skipBelow != Int.MAX_VALUE) continue
                if (node.keyboard) continue
                if (!node.visible) {
                    skipBelow = node.depth
                    skipWindow = node.window
                    continue
                }
                if (node.depth > MAX_DEPTH || lines >= MAX_LINES) continue
                val text = node.text?.take(MAX_TEXT)?.takeIf { it.isNotBlank() }
                val desc = node.description?.take(MAX_TEXT)?.takeIf { it.isNotBlank() }
                val actionable = node.clickable || node.longClickable || node.editable || node.scrollable || node.checkable
                if (text == null && desc == null && !actionable) continue
                lines++

                val cls = node.className?.substringAfterLast('.') ?: ""
                val nx1 = scale(node.left, displayWidth)
                val ny1 = scale(node.top, displayHeight)
                val nx2 = scale(node.right, displayWidth)
                val ny2 = scale(node.bottom, displayHeight)
                if (actionable) {
                    val number = elements.size + 1
                    elements[number] = UiElement(number, node.left, node.top, node.right, node.bottom,
                        labelOf(nodes, i), cls, node.window, node.packageName)
                    sb.append('[').append(number).append("] ")
                }
                sb.append("  ".repeat(node.depth)).append(cls)
                if (text != null) sb.append(" \"").append(text).append('"')
                if (desc != null && desc != text) sb.append(" (").append(desc).append(')')
                val attrs = buildList {
                    if (node.editable) add("editable")
                    if (node.scrollable) add("scrollable")
                    if (node.checkable) add(if (node.checked) "checked" else "unchecked")
                    if (node.clickable && !node.editable) add("clickable")
                    if (node.longClickable) add("long-clickable")
                }
                if (attrs.isNotEmpty()) sb.append(' ').append(attrs.joinToString(","))
                sb.append(" ($nx1,$ny1)-($nx2,$ny2)\n")
                if (!node.decoration) id.append(key(cls, (text ?: "") + "|" + (desc ?: ""), node.left, node.top, node.right, node.bottom)).append('\n')
            }
            if (keyboardOpen) sb.append(KEYBOARD_LINE).append('\n')
            return ElementList(sb.toString().trimEnd(), elements, displayWidth, displayHeight, keyboardOpen, loading,
                id.toString().hashCode())
        }

        private fun scale(px: Int, size: Int): Int = if (size <= 0) 0 else (px * 1000 / size).coerceIn(0, 1000)

        /** The node's own text or description, else the first text or description in its subtree; "" for none. */
        internal fun labelOf(nodes: List<UiNode>, index: Int): String {
            val node = nodes[index]
            own(node)?.let { return it }
            for (j in index + 1 until nodes.size) {
                val d = nodes[j]
                if (d.window != node.window || d.depth <= node.depth) break
                if (d.visible) own(d)?.let { return it }
            }
            return ""
        }

        private fun own(n: UiNode): String? =
            n.text?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)
                ?: n.description?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)
    }
}

/** The focused text field: its text, whether it hides its text, and its input type (android.text.InputType). */
data class FocusedField(val text: String?, val password: Boolean, val showingHint: Boolean, val inputType: Int)
