package ai.opencyclon.miniapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlChecksTest {
    private val good = "<!doctype html><html><head><meta name=\"accent\" content=\"#D3306B\"><title>Spanish</title><style>b{}</style></head>" +
        "<body><p>Hola</p><script>localStorage.setItem('streak','0')</script></body></html>"

    @Test fun completeInlineFilePasses() {
        assertEquals(emptyList<String>(), HtmlChecks.check(good))
        assertEquals("Spanish", HtmlChecks.name(good))
    }

    @Test fun missingHtmlElementIsRejected() {
        assertTrue(HtmlChecks.NO_HTML in HtmlChecks.check("<title>Spanish</title><p>Hola</p>"))
    }

    @Test fun missingOrEmptyTitleIsRejected() {
        assertTrue(HtmlChecks.NO_TITLE in HtmlChecks.check("<html><body>Hola</body></html>"))
        assertTrue(HtmlChecks.NO_TITLE in HtmlChecks.check("<html><title>  </title><body>Hola</body></html>"))
    }

    @Test fun externalScriptIsRejected() {
        val html = good.replace("<script>", "<script src=\"https://cdn.example.com/x.js\"></script><script>")
        assertTrue(HtmlChecks.EXTERNAL_RESOURCE in HtmlChecks.check(html))
        val relative = good.replace("<script>", "<script src='app.js'></script><script>")
        assertTrue(HtmlChecks.EXTERNAL_RESOURCE in HtmlChecks.check(relative))
    }

    @Test fun externalStylesheetIsRejected() {
        val html = good.replace("<style>", "<link rel=\"stylesheet\" href=\"https://cdn.example.com/x.css\"><style>")
        assertTrue(HtmlChecks.EXTERNAL_RESOURCE in HtmlChecks.check(html))
        val icon = good.replace("<style>", "<link rel=\"icon\" href=\"data:,\"><style>")
        assertEquals(emptyList<String>(), HtmlChecks.check(icon))
    }

    @Test fun fileAbove300KbIsRejected() {
        val big = good.replace("Hola", "a".repeat(HtmlChecks.MAX_BYTES))
        assertTrue(HtmlChecks.TOO_LARGE in HtmlChecks.check(big))
        val justUnder = good.replace("Hola", "a".repeat(HtmlChecks.MAX_BYTES - good.length))
        assertTrue(HtmlChecks.TOO_LARGE !in HtmlChecks.check(justUnder))
    }

    @Test fun missingOrMalformedAccentIsAFixableFinding() {
        assertEquals(listOf(HtmlChecks.NO_ACCENT), HtmlChecks.check(good.replace(Regex("<meta[^>]*>"), "")))
        assertEquals(listOf(HtmlChecks.NO_ACCENT), HtmlChecks.check(good.replace("#D3306B", "pink")))
    }

    @Test fun embeddedSecretIsRejected() {
        val key = "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6"
        assertTrue(HtmlChecks.SECRET in HtmlChecks.check(good.replace("<script>", "<script>const apiKey = '$key';")))
        assertTrue(HtmlChecks.SECRET in HtmlChecks.check(good.replace("<script>", "<script>var cfg={\"auth_token\":\"$key\"};")))
        assertTrue(HtmlChecks.SECRET in HtmlChecks.check(good.replace("<script>", "<script>let CLIENT_SECRET=\"${key}xyz\";")))
        // Short values and names without key, token or secret are fine.
        assertEquals(emptyList<String>(), HtmlChecks.check(good.replace("<script>", "<script>const apiKey = 'short';const word='$key';")))
    }
}
