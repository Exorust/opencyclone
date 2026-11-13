package ai.opencyclon.miniapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltAppStyleTest {
    private val file = "<!doctype html><html><head><meta name=\"accent\" content=\"#2f6fde\"><title>Spanish</title></head>" +
        "<body><h1 class=\"display\">Hola</h1></body></html>"

    private fun blocks(html: String) = Regex("id=\"${BuiltAppStyle.STYLE_ID}\"").findAll(html).count()

    @Test fun insertionGoesIntoHeadAndIsIdempotent() {
        val once = BuiltAppStyle.apply(file)
        assertEquals(1, blocks(once))
        assertTrue(once.indexOf(BuiltAppStyle.STYLE_ID) in once.indexOf("<head>")..once.indexOf("</head>"))
        assertEquals(once, BuiltAppStyle.apply(once))
        assertEquals(once, BuiltAppStyle.apply(BuiltAppStyle.apply(once)))
        assertEquals(file, BuiltAppStyle.strip(once))
    }

    @Test fun fileWithoutHeadGetsOne() {
        val html = BuiltAppStyle.apply("<html><body>Hola</body></html>")
        assertTrue(html.startsWith("<html><head>\n<style id=\"${BuiltAppStyle.STYLE_ID}\">"))
        assertEquals(html, BuiltAppStyle.apply(html))
    }

    @Test fun insertedBlockCarriesTheDeclaredAccent() {
        val html = BuiltAppStyle.apply(file)
        assertTrue(html.contains(":root{--accent:#2F6FDE}"))
        // Changing the declared accent and inserting again replaces the old value.
        val changed = BuiltAppStyle.apply(html.replace("#2f6fde", "#0A0"))
        assertTrue(changed.contains(":root{--accent:#00AA00}"))
        assertFalse(changed.contains("#2F6FDE"))
    }

    @Test fun accentPresent() {
        assertEquals("#2F6FDE", BuiltAppStyle.accentOf(file))
        assertEquals("#2F6FDE", BuiltAppStyle.accentOf("<meta content='#2F6FDE' name='accent'>"))
    }

    @Test fun accentThreeDigits() {
        assertEquals("#AABBCC", BuiltAppStyle.accentOf("<meta name=\"accent\" content=\"#abc\">"))
    }

    @Test fun accentAbsentOrMalformedGivesTheDefault() {
        assertEquals(BuiltAppStyle.DEFAULT_ACCENT, BuiltAppStyle.accentOf("<html><head></head></html>"))
        assertNull(BuiltAppStyle.declaredAccent("<html></html>"))
        listOf("pink", "#12345", "#GGGGGG", "rgb(1,2,3)", "", "#1234567").forEach {
            assertEquals(it, BuiltAppStyle.DEFAULT_ACCENT, BuiltAppStyle.accentOf("<meta name=\"accent\" content=\"$it\">"))
        }
        assertEquals(BuiltAppStyle.DEFAULT_ACCENT, BuiltAppStyle.accentOf("<meta name=\"theme-color\" content=\"#112233\">"))
    }

    @Test fun cssFollowsTheStyleRule() {
        val css = BuiltAppStyle.CSS
        listOf("html{background:#FFFFFF}", "background:#FFFFFF;color:var(--ink)", "--ink:#15173A", "--ink-2:#5B5E7A",
            "--surface-2:#F4F4FB", "--accent:#4733D6", "background:#ECEBFB", "rgba(255,255,255,.22)",
            ".title{margin:6px 0 4px;font-size:24px;font-weight:600", ".section h2{font-size:22px;font-weight:500",
            "font-family:Inter,system-ui,Roboto,sans-serif", "color-scheme:light", "url(/inter.woff2)",
            "font-size:40px;font-weight:500;letter-spacing:-0.04em", "height:68px", "width:36px;height:36px")
            .forEach { assertTrue(it, css.contains(it)) }
        listOf(".top", ".tabs", ".tab.on", ".display", ".progress", ".accent-card", ".chip", ".section", ".count", ".row", ".glyph", ".chev", ".link")
            .forEach { assertTrue(it, css.contains(it)) }
        listOf("#F7F7F4", "#1F2123", "#6B6B70", "#E6E6E1", "#D3306B").forEach { assertFalse(it, css.contains(it)) }
        assertFalse(css.contains("prefers-color-scheme"))
        assertFalse(Regex("https?:").containsMatchIn(css))
    }

    @Test fun styledFileStaysSelfContained() {
        // The shared file keeps the style inline; HtmlChecks still accepts it (no script or link resources).
        assertEquals(emptyList<String>(), HtmlChecks.check(BuiltAppStyle.apply(file)))
    }
}
