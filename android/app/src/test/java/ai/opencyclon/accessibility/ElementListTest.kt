package ai.opencyclon.accessibility

import org.junit.Assert.*
import org.junit.Test

class ElementListTest {
    private fun node(
        cls: String, text: String? = null, depth: Int = 1, l: Int = 0, t: Int = 0, r: Int = 100, b: Int = 100,
        clickable: Boolean = false, editable: Boolean = false, scrollable: Boolean = false, checkable: Boolean = false,
        longClickable: Boolean = false, visible: Boolean = true, window: Int = 0, keyboard: Boolean = false,
        desc: String? = null, spinner: Boolean = false, pkg: String = "app", decoration: Boolean = false,
    ) = UiNode(cls, text, desc, l, t, r, b, depth, window, pkg, clickable, longClickable, editable, scrollable,
        checkable, false, visible, keyboard, spinner, decoration)

    private fun taskbar(left: Int) = listOf(
        node("android.widget.FrameLayout", depth = 0, t = 1900, r = 1000, b = 2000, window = 1,
            pkg = "launcher", decoration = true),
        node("android.widget.TextView", "Phone", l = left, t = 1900, r = left + 100, b = 2000, clickable = true,
            window = 1, pkg = "launcher", decoration = true),
    )

    @Test fun `the screen identity ignores the taskbar, its numbers and the keyboard`() {
        val plain = ElementList.build(screen, 1000, 2000)
        val withTaskbar = ElementList.build(taskbar(300) + screen, 1000, 2000)
        val movedTaskbar = ElementList.build(taskbar(160) + screen, 1000, 2000)
        val withKeyboard = ElementList.build(screen + node("android.widget.FrameLayout", "q", window = 2,
            keyboard = true, clickable = true), 1000, 2000)
        assertNotEquals(plain.text, withTaskbar.text)
        assertEquals(plain.identity, withTaskbar.identity)
        assertEquals(plain.identity, movedTaskbar.identity)
        assertEquals(plain.identity, withKeyboard.identity)
    }

    @Test fun `the screen identity changes with the app's content`() {
        val other = screen.map { if (it.text == "Alex") it.copy(text = "Sam") else it }
        assertNotEquals(ElementList.build(screen, 1000, 2000).identity, ElementList.build(other, 1000, 2000).identity)
        val moved = screen.map { if (it.text == "Alex") it.copy(top = 300, bottom = 400) else it }
        assertNotEquals(ElementList.build(screen, 1000, 2000).identity, ElementList.build(moved, 1000, 2000).identity)
    }

    private val screen = listOf(
        node("android.widget.FrameLayout", depth = 0, r = 1000, b = 2000),
        node("android.widget.TextView", "Alex", l = 0, t = 0, r = 500, b = 100),
        node("android.widget.LinearLayout", depth = 1, l = 0, t = 200, r = 1000, b = 300, clickable = true),
        node("android.widget.TextView", "SEND", depth = 2, l = 10, t = 210, r = 200, b = 290),
        node("android.widget.EditText", "hello", l = 0, t = 400, r = 1000, b = 500, editable = true),
        node("android.widget.ListView", depth = 1, t = 600, r = 1000, b = 1800, scrollable = true),
        node("android.widget.CheckBox", "Agree", depth = 2, t = 700, r = 400, b = 760, checkable = true),
        node("android.widget.ImageButton", depth = 2, t = 800, r = 100, b = 900, longClickable = true),
    )

    @Test fun `only elements that can be acted on get numbers in list order`() {
        val list = ElementList.build(screen, 1000, 2000)
        assertEquals(listOf(1, 2, 3, 4, 5), list.elements.keys.toList())
        val lines = list.text.lines()
        assertTrue(lines.first { "Alex" in it }.trimStart().startsWith("TextView"))
        assertTrue(lines.any { it.startsWith("[1] ") && "LinearLayout" in it && "clickable" in it })
        assertTrue(lines.any { it.startsWith("[2] ") && "EditText \"hello\" editable" in it })
        assertTrue(lines.any { it.startsWith("[3] ") && "ListView" in it })
        assertTrue(lines.any { it.startsWith("[4] ") && "CheckBox" in it && "unchecked" in it })
        assertTrue(lines.any { it.startsWith("[5] ") && "long-clickable" in it })
        assertFalse(lines.first { "SEND" in it }.startsWith("["))
    }

    @Test fun `a container without text takes the label of its first text descendant`() {
        val list = ElementList.build(screen, 1000, 2000)
        assertEquals("SEND", list.elements[1]!!.label)
        assertEquals("hello", list.elements[2]!!.label)
        assertEquals("", list.elements[5]!!.label)
        assertEquals("LinearLayout", list.elements[1]!!.className)
        assertEquals(500 to 250, list.elements[1]!!.centerX to list.elements[1]!!.centerY)
    }

    @Test fun `bounds are on the 0-1000 scale in the text and in pixels in the map`() {
        val list = ElementList.build(screen, 1000, 2000)
        assertTrue(list.text.lines().first { it.startsWith("[1] ") }.endsWith("(0,100)-(1000,150)"))
        assertEquals(200, list.elements[1]!!.top)
    }

    @Test fun `the map is stable for the same screen and keys survive a small move`() {
        val a = ElementList.build(screen, 1000, 2000)
        val b = ElementList.build(screen, 1000, 2000)
        assertEquals(a, b)
        val moved = screen.map { if (it.clickable) it.copy(top = it.top + 2, bottom = it.bottom + 2) else it }
        assertEquals(a.elements[1]!!.key, ElementList.build(moved, 1000, 2000).elements[1]!!.key)
        val far = screen.map { if (it.clickable) it.copy(top = it.top + 600, bottom = it.bottom + 600) else it }
        assertNotEquals(a.elements[1]!!.key, ElementList.build(far, 1000, 2000).elements[1]!!.key)
    }

    @Test fun `an invisible node hides its subtree`() {
        val nodes = listOf(
            node("FrameLayout", depth = 0),
            node("LinearLayout", depth = 1, visible = false),
            node("Button", "Hidden", depth = 2, clickable = true),
            node("Button", "Shown", depth = 1, clickable = true),
        )
        val list = ElementList.build(nodes, 1000, 1000)
        assertEquals(listOf("Shown"), list.elements.values.map { it.label })
        assertFalse("Hidden" in list.text)
    }

    @Test fun `the keyboard is left out and named in one line`() {
        val nodes = screen + listOf(
            node("android.inputmethodservice.KeyboardView", depth = 0, window = 1, keyboard = true, pkg = "ime"),
        )
        val list = ElementList.build(nodes, 1000, 2000)
        assertTrue(list.keyboardOpen)
        assertEquals(ElementList.KEYBOARD_LINE, list.text.lines().last())
        assertEquals(5, list.elements.size)
        assertFalse(ElementList.build(screen, 1000, 2000).keyboardOpen)
    }

    @Test fun `the list stops at 200 lines and depth 15`() {
        val many = (1..300).map { node("Button", "b$it", clickable = true) }
        assertEquals(200, ElementList.build(many, 1000, 1000).elements.size)
        val deep = listOf(node("Button", "deep", depth = 16, clickable = true))
        assertTrue(ElementList.build(deep, 1000, 1000).elements.isEmpty())
    }

    @Test fun `a visible spinner marks the screen as loading`() {
        assertTrue(ElementList.build(screen + node("android.widget.ProgressBar", spinner = true), 1000, 2000).loading)
        assertFalse(ElementList.build(screen, 1000, 2000).loading)
    }

    @Test fun `a centre maps back into the same pixel`() {
        val list = ElementList.build(screen, 1080, 2400)
        for (px in listOf(0, 1, 539, 540, 777, 1079)) {
            val (nx, _) = list.toNormalized(px, 0)
            // The same formula as input/CoordinateMapper.normalizedToPixel.
            val back = ((nx / 1000.0) * 1080).toInt().coerceIn(0, 1079)
            assertTrue("$px -> $nx -> $back", kotlin.math.abs(back - px) <= 1)
        }
    }
}
