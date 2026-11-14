package ai.opencyclone.accessibility

import ai.opencyclone.accessibility.VdAccessibilityService.Companion.GateNode
import ai.opencyclone.accessibility.VdAccessibilityService.Companion.WindowRef
import ai.opencyclone.accessibility.VdAccessibilityService.Companion.factsFor
import ai.opencyclone.gate.Box
import ai.opencyclone.gate.ElementInfo
import android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lane N5: the text to be sent and the attached items, read from node fixtures of one window (1080 x 2400 pixels,
 * density 2.625, so 40 dp = 105 px). The tap is on the Send button at (980, 2250).
 */
class ScreenEvidenceTest {
    private val pkg = "com.example.chat"
    private val window = WindowRef(TYPE_APPLICATION, 1, pkg, 0, 0, 1080, 2400)
    private val density = 2.625f
    private val send = 980 to 2250

    private fun node(
        l: Int, t: Int, r: Int, b: Int, depth: Int = 3, text: String? = null, desc: String? = null,
        cls: String = "android.view.View", editable: Boolean = false, focused: Boolean = false,
        hint: String? = null, showingHint: Boolean = false, checked: Boolean = false, selected: Boolean = false,
        scrollable: Boolean = false, collection: Boolean = false, heading: Boolean = false,
    ) = GateNode(ElementInfo(text, desc, null, cls, l, t, r, b, editable), pkg, 0, depth, focused, heading, false, true,
        hint = hint, showingHint = showingHint, checked = checked, selected = selected,
        scrollable = scrollable, collection = collection || scrollable)

    private val chrome = listOf(
        node(0, 0, 1080, 2400, depth = 0),
        node(40, 100, 1040, 220, depth = 2, text = "Alex", heading = true),
        node(900, 2180, 1060, 2320, depth = 2, text = "Send", cls = "android.widget.Button"))
    private val messageList = node(0, 240, 1080, 2150, depth = 1, cls = "androidx.recyclerview.widget.RecyclerView", scrollable = true)
    private val messages = listOf(
        node(40, 300, 700, 400, depth = 2, text = "Are you coming?"),
        node(380, 420, 1040, 520, depth = 2, text = "Yes, at 6"))

    private fun facts(nodes: List<GateNode>) = factsFor(nodes, listOf(window), emptySet(), send, density)

    @Test fun `composer with typed text and focus`() {
        val nodes = chrome + messageList + messages + listOf(
            node(40, 2180, 880, 2320, depth = 2, text = "See you at 6", cls = "android.widget.EditText",
                editable = true, focused = true, hint = "Message"),
            node(40, 20, 600, 90, depth = 2, text = "search", cls = "android.widget.EditText", editable = true))
        val f = facts(nodes)
        assertEquals("See you at 6", f.composeText)
        assertNull(f.attachmentCount)
        assertEquals("Alex", f.title)
    }

    @Test fun `hint text only is no text to send`() {
        val nodes = chrome + messageList + listOf(
            node(40, 2180, 880, 2320, depth = 2, text = "Message", cls = "android.widget.EditText",
                editable = true, focused = true, hint = "Message", showingHint = true))
        assertNull(facts(nodes).composeText)
    }

    @Test fun `the single field with text counts when none has focus`() {
        val nodes = chrome + listOf(
            node(40, 2180, 880, 2320, depth = 2, text = "Hello", cls = "android.widget.EditText", editable = true),
            node(40, 20, 600, 90, depth = 2, text = "Search", cls = "android.widget.EditText", editable = true,
                hint = "Search", showingHint = true))
        assertEquals("Hello", facts(nodes).composeText)
    }

    @Test fun `two fields with text and none focused is unknown`() {
        val nodes = chrome + listOf(
            node(40, 2180, 880, 2320, depth = 2, text = "Hello", editable = true),
            node(40, 20, 600, 90, depth = 2, text = "cats", editable = true))
        assertNull(facts(nodes).composeText)
    }

    @Test fun `long text is cut to 500 characters`() {
        val nodes = chrome + node(40, 2180, 880, 2320, depth = 2, text = "x".repeat(800), editable = true, focused = true)
        assertEquals(500, facts(nodes).composeText!!.length)
    }

    @Test fun `photo picker with 3 selected items`() {
        val grid = node(0, 240, 1080, 2150, depth = 1, cls = "androidx.recyclerview.widget.RecyclerView", scrollable = true)
        val cells = (0 until 9).map { i ->
            val l = (i % 3) * 360; val t = 240 + (i / 3) * 360
            node(l, t, l + 350, t + 350, depth = 2, desc = "Photo ${i + 1}", cls = "android.widget.FrameLayout")
        }
        // Each selected cell has a check box child that is checked; the cell itself is selected too.
        val checks = listOf(0, 4, 8).map { i ->
            val c = cells[i].info
            node(c.right - 80, c.top + 10, c.right - 10, c.top + 80, depth = 3, cls = "android.widget.CheckBox", checked = true)
        }
        val marked = cells.mapIndexed { i, c -> if (i in listOf(0, 4, 8)) c.copy(selected = true) else c }
        val f = facts(chrome + grid + marked + checks)
        assertEquals(3, f.attachmentCount)
        assertEquals("items", f.attachmentNoun)
        assertEquals(listOf(0, 4, 8).map { cells[it].info.let { c -> Box(c.left, c.top, c.right, c.bottom) } }, f.attachmentBounds)
    }

    @Test fun `picker count and a matching counting text keep the noun`() {
        val grid = node(0, 240, 1080, 2150, depth = 1, scrollable = true)
        val cells = (0 until 2).map { i -> node(i * 360, 240, i * 360 + 350, 590, depth = 2, desc = "Photo", checked = true) }
        val f = facts(chrome + grid + cells + node(40, 2180, 600, 2300, depth = 2, text = "2 photos"))
        assertEquals(2, f.attachmentCount)
        assertEquals("photos", f.attachmentNoun)
    }

    @Test fun `attachment strip with 4 images under a message list`() {
        val strip = node(0, 1900, 1080, 2150, depth = 1, cls = "androidx.recyclerview.widget.RecyclerView", collection = true)
        val thumbs = (0 until 4).map { i ->
            node(20 + i * 230, 1920, 20 + i * 230 + 210, 2130, depth = 2, cls = "android.widget.ImageView")
        }
        // A remove button on each thumbnail is too small to count.
        val removes = thumbs.map { node(it.info.right - 50, 1920, it.info.right, 1970, depth = 3, desc = "Remove") }
        val list = messageList.copy(info = messageList.info.copy(bottom = 1890))
        val f = facts(chrome + list + messages + strip + thumbs + removes)
        assertEquals(4, f.attachmentCount)
        assertEquals("items", f.attachmentNoun)
        assertEquals(4, f.attachmentBounds.size)
    }

    @Test fun `an album inside the message list is not an attachment strip`() {
        val album = node(380, 1700, 1040, 1950, depth = 2, cls = "androidx.recyclerview.widget.RecyclerView", collection = true)
        val pics = (0 until 2).map { i -> node(390 + i * 320, 1710, 390 + i * 320 + 300, 1940, depth = 3, cls = "android.widget.ImageView") }
        assertNull(facts(chrome + messageList + messages + album + pics).attachmentCount)
    }

    @Test fun `a 3 selected text alone gives the count without bounds`() {
        val f = facts(chrome + messageList + messages + node(40, 230, 400, 290, depth = 2, text = "3 selected"))
        assertEquals(3, f.attachmentCount)
        assertEquals("items", f.attachmentNoun)
        assertTrue(f.attachmentBounds.isEmpty())
    }

    @Test fun `singular noun in the text is kept as plural`() {
        val f = facts(chrome + node(40, 230, 400, 290, depth = 2, text = "1 Photo"))
        assertEquals(1, f.attachmentCount)
        assertEquals("photos", f.attachmentNoun)
    }

    @Test fun `conflicting numbers give no count`() {
        val grid = node(0, 240, 1080, 2150, depth = 1, scrollable = true)
        val cells = (0 until 2).map { i -> node(i * 360, 240, i * 360 + 350, 590, depth = 2, checked = true) }
        val f = facts(chrome + grid + cells + node(40, 2180, 600, 2300, depth = 2, text = "3 selected"))
        assertNull(f.attachmentCount)
        assertNull(f.attachmentNoun)
        assertTrue(f.attachmentBounds.isEmpty())
    }

    @Test fun `two counting texts that differ give no count`() {
        val f = facts(chrome + node(40, 230, 400, 290, depth = 2, text = "2 selected") +
            node(40, 300, 400, 360, depth = 2, text = "5 files"))
        assertNull(f.attachmentCount)
    }

    @Test fun `plain chat without attachments gives no count`() {
        val f = facts(chrome + messageList + messages)
        assertNull(f.attachmentCount)
        assertNull(f.attachmentNoun)
        assertNull(f.composeText)
    }

    @Test fun `a selected text tab is not a selected item`() {
        val tabs = node(0, 240, 1080, 360, depth = 1, cls = "android.widget.HorizontalScrollView", scrollable = true)
        val tab = node(0, 240, 360, 360, depth = 2, selected = true)
        val label = node(20, 260, 340, 340, depth = 3, text = "Chats")
        assertNull(facts(chrome + tabs + tab + label).attachmentCount)
    }

    @Test fun `a checked switch outside any list does not count`() {
        val f = facts(chrome + node(40, 2000, 300, 2100, depth = 2, text = "HD", cls = "android.widget.Switch", checked = true))
        assertNull(f.attachmentCount)
    }
}
