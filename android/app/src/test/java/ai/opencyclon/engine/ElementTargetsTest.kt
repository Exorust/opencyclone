package ai.opencyclon.engine

import ai.opencyclon.accessibility.ElementList
import ai.opencyclon.accessibility.UiElement
import org.junit.Assert.*
import org.junit.Test

class ElementTargetsTest {
    private fun el(n: Int, label: String, top: Int, cls: String = "Button") =
        UiElement(n, 0, top, 400, top + 100, label, cls, 0, "app")
    private fun list(vararg e: UiElement) = ElementList("", e.associateBy { it.number }, 1000, 2000)

    private val seen = list(el(1, "Chat", 100), el(2, "Send", 300))

    @Test fun `the same element on the fresh screen is found by its key`() {
        val fresh = list(el(1, "Chat", 102), el(2, "Send", 301))
        val r = ElementTargets.resolve(2, seen, fresh) as ElementTargets.Resolution.Found
        assertEquals(301, r.element.top)
    }

    @Test fun `an element that moved keeps its number and label and is tapped where it is now`() {
        val fresh = list(el(1, "Chat", 100), el(2, "Send", 900))
        val r = ElementTargets.resolve(2, seen, fresh) as ElementTargets.Resolution.Found
        assertEquals(950, r.element.centerY)
    }

    @Test fun `a moved element is found by key even under another number`() {
        val fresh = list(el(1, "Banner", 0), el(2, "Chat", 100), el(3, "Send", 300))
        val r = ElementTargets.resolve(2, seen, fresh) as ElementTargets.Resolution.Found
        assertEquals("Send", r.element.label)
    }

    @Test fun `a changed label or a missing element is a changed screen`() {
        assertSame(ElementTargets.Resolution.Changed, ElementTargets.resolve(2, seen, list(el(1, "Chat", 100), el(2, "Delete", 900))))
        assertSame(ElementTargets.Resolution.Changed, ElementTargets.resolve(2, seen, list(el(1, "Chat", 100))))
        assertSame(ElementTargets.Resolution.Changed, ElementTargets.resolve(2, seen, null))
    }

    @Test fun `a number the model never saw is unknown`() {
        assertSame(ElementTargets.Resolution.Unknown, ElementTargets.resolve(7, seen, seen))
        assertSame(ElementTargets.Resolution.Unknown, ElementTargets.resolve(1, null, seen))
        assertEquals("There is no element 7 on this screen.", ElementTargets.noElement(7))
        assertEquals("The screen changed. Look again.", ElementTargets.SCREEN_CHANGED)
    }
}
