package ai.opencyclon.widgets

import ai.opencyclon.miniapp.HtmlChecks
import ai.opencyclon.widgets.WidgetRequests.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRequestsAndHtmlTest {
    private fun widget(id: Long, name: String) = WidgetEntity(id, name, "small", "history", id.toInt(), 1, "#4733D6", 0)

    @Test fun widgetSentences() {
        assertEquals(Request.Build, WidgetRequests.parse("Make a widget that shows my Lingo streak and how many tasks I ran today"))
        assertEquals(Request.Build, WidgetRequests.parse("Hey Cyclon, please build me a widget with the weather."))
        assertEquals(Request.Build, WidgetRequests.parse("create widget for my history"))
        assertEquals(Request.Build, WidgetRequests.parse("Make a widget with a button that tells Alex I'm running late"))
        assertEquals(Request.Change("streak"), WidgetRequests.parse("Make the streak widget wide and add the date"))
        assertEquals(Request.Change("Lingo streak"), WidgetRequests.parse("Rebuild the Lingo streak widget"))
        assertEquals(Request.Change("weather"), WidgetRequests.parse("change my weather widget to show tomorrow"))
        assertNull(WidgetRequests.parse("Build me an app to learn Spanish"))
        assertNull(WidgetRequests.parse("Make the Spanish app weekly"))
        assertNull(WidgetRequests.parse("Tell Alex I'm running late"))
    }

    @Test fun findMatchesNamesLoosely() {
        val all = listOf(widget(1, "Lingo streak"), widget(2, "Weather"), widget(3, "Streak and tasks"))
        assertEquals(listOf(1L), WidgetRequests.find("Lingo streak", all).map { it.id })
        assertEquals(listOf(1L, 3L), WidgetRequests.find("streak", all).map { it.id })
        assertEquals(listOf(2L), WidgetRequests.find("the weather one", all).map { it.id })
        assertTrue(WidgetRequests.find("calendar", all).isEmpty())
    }

    @Test fun styleAndPhoneScriptAreInsertedOnceAndStripped() {
        val raw = widgetHtml()
        val applied = WidgetHtml.apply(raw)
        assertEquals(applied, WidgetHtml.apply(applied))
        assertTrue(applied.contains("id=\"${WidgetHtml.STYLE_ID}\""))
        assertTrue(applied.contains("window.phone="))
        assertTrue(applied.contains("--accent:#2F6FDE"))
        assertEquals(raw, WidgetHtml.strip(applied))
    }

    @Test fun checksRejectOutsideAddressesAndLargeFilesButAllowSvg() {
        val svg = widgetHtml(body = "<svg xmlns=\"http://www.w3.org/2000/svg\"><circle r=\"3\"/></svg><div id=\"s\">-</div>")
        assertTrue(WidgetHtml.check(WidgetHtml.apply(svg), listOf("app:3", "history")).problems.isEmpty())
        val outside = widgetHtml(body = "<img src=\"https://tracker.example.com/p.png\"><div id=\"s\">-</div>")
        assertTrue(WidgetHtml.OUTSIDE_ADDRESS in WidgetHtml.check(outside, listOf("app:3")).problems)
        val comment = widgetHtml(body = "<script>// phone.read gives the data\n</script><div id=\"s\">-</div>")
        assertFalse(WidgetHtml.OUTSIDE_ADDRESS in WidgetHtml.check(comment, listOf("app:3", "history")).problems)
        val big = widgetHtml(body = "<p>" + "x".repeat(WidgetHtml.MAX_BYTES) + "</p>")
        assertTrue(WidgetHtml.TOO_LARGE in WidgetHtml.check(big, listOf("app:3")).problems)
        val external = widgetHtml(body = "<script src=\"lib.js\"></script>")
        assertTrue(HtmlChecks.EXTERNAL_RESOURCE in WidgetHtml.check(external, listOf("app:3")).problems)
    }

    @Test fun extraDeclaredSourcesAreRemovedFromTheMeta() {
        val checked = WidgetHtml.check(widgetHtml(sources = "app:3,today,web:example.com/x,clock"), listOf("app:3", "history"))
        assertEquals(listOf("today", "web:example.com/x"), checked.removedSources)
        assertEquals(listOf("app:3", "clock"), WidgetHtml.declaredSources(checked.html))
        assertTrue(checked.problems.isEmpty())
        val allowedWeb = WidgetHtml.check(widgetHtml(sources = "web:example.com/x"), listOf("web:example.com"))
        assertTrue(allowedWeb.removedSources.isEmpty())
    }

    @Test fun toolArgumentsComeFromTheFile() {
        val html = "<html><head><meta name=\"tool-args\" content='{\"tool:notes/list_notes\":{\"limit\":5}}'></head></html>"
        assertEquals("{\"limit\":5}", WidgetHtml.toolArgs(html, "tool:notes/list_notes"))
        assertEquals("{}", WidgetHtml.toolArgs(html, "tool:notes/other"))
        assertEquals("{}", WidgetHtml.toolArgs("<html></html>", "tool:notes/list_notes"))
    }

    @Test fun capitalLabelCssIsAFixableFindingRegardlessOfSpacingOrCase() {
        for (rule in listOf("text-transform:uppercase", "TEXT-TRANSFORM : UPPERCASE", "text-transform\n:\tUpperCase")) {
            val checked = WidgetHtml.check(widgetHtml(body = "<p style='$rule'>Tasks</p>"), emptyList())
            assertTrue(WidgetHtml.CAPITAL_LABELS in checked.problems)
        }
        assertFalse(WidgetHtml.CAPITAL_LABELS in WidgetHtml.check(widgetHtml(), emptyList()).problems)
    }

    @Test fun displayUsesStandardsModeAndOneDeviceWidthViewport() {
        val html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=980'></head><body>Tasks</body></html>"
        val display = WidgetHtml.forDisplay(html)
        assertTrue(display.startsWith("<!doctype html>"))
        assertFalse(display.contains("width=980"))
        assertTrue(display.contains("width=device-width, initial-scale=1"))
        assertEquals(display, WidgetHtml.forDisplay(display))
        assertTrue(WidgetHtml.forDisplay(widgetHtml()).startsWith("<!doctype html>"))
    }

    @Test fun fixedStyleKeepsNumbersInInkAndLabelsInSentenceCase() {
        assertTrue(WidgetHtml.CSS.contains(".big{color:#15173A!important;"))
        assertTrue(WidgetHtml.CSS.contains("font-size:15px"))
        assertTrue(WidgetHtml.CSS.contains("--ink-2:#5B5E7A"))
        assertTrue(WidgetHtml.CSS.contains(".label{font-size:13px;color:var(--ink-2)}"))
        assertFalse(WidgetHtml.CSS.contains("uppercase", ignoreCase = true))
        assertFalse(WidgetHtml.CSS.contains("body{height:100%"))
    }

    @Test fun planRepliesAreReadLeniently() {
        assertEquals("X", WidgetFlow.lenientObject("Sure: ```json\n{\"name\":\"X\"}\n```")!!.getString("name"))
        assertNull(WidgetFlow.lenientObject("no json here"))
        assertEquals(WidgetSize.TALL, WidgetSize.of(" Tall "))
        assertEquals(WidgetSize.SMALL, WidgetSize.of("huge"))
    }
}
