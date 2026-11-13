package ai.opencyclon.accessibility

import ai.opencyclon.accessibility.VdAccessibilityService.Companion.GateNode
import ai.opencyclon.gate.Box
import ai.opencyclon.gate.ScreenFacts
import kotlin.math.max
import kotlin.math.min

/**
 * Evidence read at hold time from the nodes of one window (the window of the held tap): the text that will be
 * sent and the number of attached items. Pure functions over the flattened node list. Every rule returns null
 * when it is unsure; nothing is guessed.
 */
internal object ScreenEvidence {
    /** Attached items: count, plural noun ("photos", "files", "items"), and their bounds when known. */
    data class Attachments(val count: Int, val noun: String, val bounds: List<Box>)

    const val DEFAULT_NOUN = "items"
    private const val MIN_IMAGE_DP = 40f
    private const val SIZE_RATIO = 1.5f
    private val COUNT_TEXT = Regex("^(\\d{1,3}) (selected|photos?|items?|files?|images?|videos?)$", RegexOption.IGNORE_CASE)
    private val LIST_CLASS = Regex("(RecyclerView|ListView|GridView|ScrollView|ViewPager|Lazy(Row|Column|VerticalGrid|HorizontalGrid))$")

    fun isListClass(className: String?): Boolean = className != null && LIST_CLASS.containsMatchIn(className)

    /**
     * The text of the field that will be sent: the focused editable field's text, else the only editable field
     * with text, else null. Placeholder text is ignored. At most [ScreenFacts.MAX_COMPOSE] characters.
     */
    fun composeText(windowNodes: List<GateNode>): String? {
        val fields = windowNodes.filter { it.visible && it.info.editable }
        val focused = fields.firstOrNull { it.focused }?.let(::typedText)
        val text = focused ?: fields.mapNotNull(::typedText).singleOrNull()
        return text?.let(ScreenFacts::capCompose)
    }

    private fun typedText(n: GateNode): String? {
        val t = n.info.text?.takeIf { it.isNotBlank() } ?: return null
        if (n.showingHint || (n.hint != null && t == n.hint)) return null
        return t
    }

    /**
     * a. checked or selected items inside one list (a picker with a Send button);
     * b. else a row or grid of two or more similar images in the lower 60% of the window that is not the message
     *    list (an attachment strip in a composer);
     * c. a text such as "3 selected" or "2 photos".
     * When (a or b) and (c) both give a number and they differ, the count is unreadable.
     */
    fun attachments(windowNodes: List<GateNode>, windowBox: Box?, density: Float): Attachments? {
        val nodes = windowNodes.filter { it.visible }
        if (nodes.isEmpty()) return null
        val picked = when (val a = selectedItems(nodes, density)) {
            Unsure -> return null
            is Found -> a
            None -> when (val b = attachmentStrip(nodes, windowBox ?: union(nodes), density)) {
                Unsure -> return null
                is Found -> b
                None -> null
            }
        }
        val counted = when (val c = countText(nodes)) {
            Unsure -> return null
            is Found -> c
            None -> null
        }
        if (picked != null && counted != null && picked.count != counted.count) return null
        val count = picked?.count ?: counted?.count ?: return null
        return Attachments(count, counted?.noun ?: DEFAULT_NOUN, picked?.bounds.orEmpty())
    }

    private sealed class Rule
    private object None : Rule()
    private object Unsure : Rule()
    private data class Found(val count: Int, val noun: String?, val bounds: List<Box>) : Rule()

    // ── a. checked or selected items in one list ────────────────────────

    private fun selectedItems(nodes: List<GateNode>, density: Float): Rule {
        val collections = nodes.filter { it.collection }
        val minPx = MIN_IMAGE_DP * density
        // A selected tab or menu entry carries text; a selected picture does not.
        val marked = nodes.filter { n ->
            n.checked || (n.selected && shortSide(n.box) >= minPx && nodes.none { it.depth > n.depth && inside(it.box, n.box) && !it.info.text.isNullOrBlank() } &&
                n.info.text.isNullOrBlank())
        }
        if (marked.isEmpty()) return None
        val cellsByList = LinkedHashMap<GateNode, LinkedHashSet<Box>>()
        for (n in marked) {
            // The innermost list that holds the item; an item outside any list (a "HD" switch) does not count.
            val list = collections.filter { it.depth < n.depth && inside(n.box, it.box) }.maxByOrNull { it.depth } ?: continue
            // The item's cell: the outermost node under the list that holds it, so a cell and its check box count once.
            val cell = nodes.filter { it.depth > list.depth && it.depth <= n.depth && inside(n.box, it.box) && inside(it.box, list.box) }
                .minByOrNull { it.depth } ?: n
            cellsByList.getOrPut(list) { LinkedHashSet() }.add(cell.box)
        }
        return when (cellsByList.size) {
            0 -> None
            1 -> cellsByList.values.single().let { Found(it.size, null, it.toList()) }
            else -> Unsure
        }
    }

    // ── b. a strip of images in the composer ────────────────────────────

    private fun attachmentStrip(nodes: List<GateNode>, window: Box, density: Float): Rule {
        val collections = nodes.filter { it.collection }
        val messageList = nodes.filter { it.scrollable || it.collection }.maxByOrNull { area(it.box) }
        val lowerEdge = window.top + (window.bottom - window.top) * 0.4f
        val minPx = MIN_IMAGE_DP * density
        val found = collections.asSequence()
            .filter { it !== messageList && it.box.top >= lowerEdge }
            .filter { c -> messageList == null || !(inside(c.box, messageList.box) && c.depth > messageList.depth) }
            .mapNotNull { c ->
                val images = nodes.filter { it.depth > c.depth && inside(it.box, c.box) && imageLike(it) && shortSide(it.box) >= minPx }
                // The innermost picture of each item: a framed picture counts once.
                val inner = images.filter { outer -> images.none { it !== outer && it.depth > outer.depth && inside(it.box, outer.box) } }
                inner.map { it.box }.distinct().takeIf { it.size >= 2 && similarSizes(it) && sideBySide(it) }
            }
            .distinct().toList()
        return when (found.size) {
            0 -> None
            1 -> Found(found.single().size, null, found.single())
            else -> Unsure
        }
    }

    private fun imageLike(n: GateNode): Boolean =
        n.info.className?.endsWith("ImageView") == true ||
            (!n.info.description.isNullOrBlank() && n.info.text.isNullOrBlank())

    private fun similarSizes(boxes: List<Box>): Boolean {
        val widths = boxes.map { (it.right - it.left).toFloat() }.sorted()
        val heights = boxes.map { (it.bottom - it.top).toFloat() }.sorted()
        return widths.last() <= widths.first() * SIZE_RATIO && heights.last() <= heights.first() * SIZE_RATIO
    }

    /** At least two pictures share a row: a row or a grid, not a column. */
    private fun sideBySide(boxes: List<Box>): Boolean = boxes.indices.any { i ->
        (i + 1 until boxes.size).any { j ->
            val a = boxes[i]; val b = boxes[j]
            min(a.bottom, b.bottom) > max(a.top, b.top) && (a.right <= b.left || b.right <= a.left)
        }
    }

    // ── c. a counting text ──────────────────────────────────────────────

    private fun countText(nodes: List<GateNode>): Rule {
        val matches = nodes.mapNotNull { n -> n.info.text?.trim()?.let { COUNT_TEXT.matchEntire(it) } }
        if (matches.isEmpty()) return None
        val counts = matches.map { it.groupValues[1].toInt() }.distinct()
        if (counts.size > 1) return Unsure
        val nouns = matches.mapNotNull { noun(it.groupValues[2]) }.distinct()
        return Found(counts.single(), nouns.singleOrNull(), emptyList())
    }

    /** "photo" and "Photos" -> "photos"; "selected" names nothing -> null. */
    private fun noun(word: String): String? {
        val w = word.lowercase()
        if (w == "selected") return null
        return if (w.endsWith("s")) w else w + "s"
    }

    // ── geometry ────────────────────────────────────────────────────────

    private val GateNode.box: Box get() = Box(info.left, info.top, info.right, info.bottom)
    private fun inside(a: Box, b: Box) = a.left >= b.left && a.top >= b.top && a.right <= b.right && a.bottom <= b.bottom
    private fun area(b: Box): Long = max(0, b.right - b.left).toLong() * max(0, b.bottom - b.top)
    private fun shortSide(b: Box): Int = min(b.right - b.left, b.bottom - b.top)
    private fun union(nodes: List<GateNode>) =
        Box(nodes.minOf { it.info.left }, nodes.minOf { it.info.top }, nodes.maxOf { it.info.right }, nodes.maxOf { it.info.bottom })
}
