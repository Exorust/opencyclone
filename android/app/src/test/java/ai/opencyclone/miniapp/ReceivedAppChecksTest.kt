package ai.opencyclone.miniapp

import ai.opencyclone.miniapp.ReceivedAppChecks.Finding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A generated app in the shape the builder makes: our style block with the font path, inline SVG, bridge storage. */
fun receivedFixture(name: String = "Spanish", head: String = "", body: String = "") = BuiltAppStyle.apply("""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>$name</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="accent" content="#2E7D5B">$head
<style>.x{background:url(data:image/png;base64,AAAA)}</style>
</head><body>
<div class="top"><span class="name">$name</span><a class="link" href="#today">Today</a></div>
<h1 class="display">Hola</h1>
<button class="row"><span class="glyph"><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M4 4h16v16H4z"/></svg></span><span class="t">Lesson</span></button>
<p id="streak" class="small">0</p>$body
<script>
  var s = Number(phone.get("streak") || 0);
  document.getElementById("streak").textContent = s;
  function done() { phone.set("streak", String(s + 1)); phone.notify("Spanish", "Done for today"); }
</script>
</body></html>""")

class ReceivedAppChecksTest {
    private fun check(html: String) = ReceivedAppChecks.check(html)
    private fun findsIn(finding: Finding, vararg bodies: String) = bodies.forEach { body ->
        assertTrue("$finding not found in $body: ${check(receivedFixture(body = body))}", finding in check(receivedFixture(body = body)))
    }

    @Test fun aCleanGeneratedFilePasses() {
        val html = receivedFixture()
        assertTrue(html.contains(BuiltAppStyle.FONT_PATH))
        assertEquals(emptyList<Finding>(), check(html))
    }

    @Test fun anOutsideReferenceInsideOurOwnStyleBlockPasses() {
        val block = "<style id=\"${BuiltAppStyle.STYLE_ID}\">@font-face{src:url(https://fonts.example.com/x.woff2)}</style>"
        val html = receivedFixture().replace("<head>", "<head>$block")
        assertEquals(emptyList<Finding>(), check(html))
    }

    @Test fun outsideAddressesInAttributes() = findsIn(Finding.OUTSIDE_ADDRESS,
        """<img src="https://example.com/a.png">""",
        """<img src='//example.com/a.png'>""",
        """<a href="http://example.com">x</a>""",
        """<form action="https://example.com/f"></form>""",
        """<img srcset="a.png 1x, https://example.com/b.png 2x">""",
        """<img src="&#104;ttps://example.com/a.png">""")

    @Test fun outsideAddressesInStyles() = findsIn(Finding.OUTSIDE_ADDRESS,
        """<div style="background:url(https://example.com/a.png)"></div>""",
        """<style>.a{background:url('//example.com/a.png')}</style>""",
        """<style>@import "x.css";</style>""")

    @Test fun outsideAddressesInScriptsAndMetaRefresh() = findsIn(Finding.OUTSIDE_ADDRESS,
        """<script>new Image().src = "https://example.com/p?d=" + 1;</script>""",
        """<script>var u = "ws://example.com/s";</script>""",
        """<script>var u = "//example.com/s";</script>""",
        """<meta http-equiv="refresh" content="0; url=https://example.com">""")

    @Test fun networkCalls() = findsIn(Finding.NETWORK_CALL,
        """<script>fetch("/x")</script>""",
        """<script>var f = window.fetch; f("/x")</script>""",
        """<script>new XMLHttpRequest()</script>""",
        """<script>new WebSocket(u)</script>""",
        """<script>new EventSource(u)</script>""",
        """<script>importScripts(u)</script>""",
        """<script>navigator.sendBeacon(u, d)</script>""")

    @Test fun bridgeFetchAsksForTheInternet() {
        val found = check(receivedFixture(body = """<script>phone.fetch(u, "{}")</script>"""))
        assertTrue(Finding.ASKS_INTERNET in found)
        assertTrue(Finding.NETWORK_CALL !in found)
    }

    @Test fun windowOpen() = findsIn(Finding.OPENS_WINDOW, """<script>window.open(u)</script>""")

    @Test fun embeddingTags() = findsIn(Finding.EMBEDS, "<iframe srcdoc=\"x\"></iframe>", "<object></object>", "<embed type=\"a\">")

    @Test fun baseTag() = findsIn(Finding.BASE, "<base target=\"_top\">")

    @Test fun textRunAsCode() = findsIn(Finding.RUNS_TEXT_AS_CODE,
        """<script>eval(x)</script>""", """<script>new Function("a", b)</script>""", """<script>window["fe" + "tch"](u)</script>""")

    @Test fun relativeFileIsASecondFile() = findsIn(Finding.SECOND_FILE, """<img src="photo.png">""", """<script src="app.js"></script>""")

    @Test fun sizeLimit() {
        assertTrue(Finding.FILE_TOO_LARGE in ReceivedAppChecks.check(receivedFixture(), byteCount = ReceivedAppChecks.MAX_BYTES + 1))
        assertTrue(Finding.FILE_TOO_LARGE !in ReceivedAppChecks.check(receivedFixture(), byteCount = ReceivedAppChecks.MAX_BYTES))
        // The built-app limit of HtmlChecks counts too.
        assertTrue(Finding.APP_TOO_LARGE in check(receivedFixture(body = "<p>${"a".repeat(HtmlChecks.MAX_BYTES)}</p>")))
    }

    @Test fun moreThanOneFile() = assertTrue(Finding.NOT_ONE_FILE in ReceivedAppChecks.check(receivedFixture(), fileCount = 2))

    @Test fun missingTitle() {
        val found = check(receivedFixture().replace("<title>Spanish</title>", ""))
        assertEquals(listOf(Finding.NO_TITLE), found)
    }

    @Test fun notAWebPage() = assertTrue(Finding.NOT_HTML in check("<title>x</title> plain text"))

    @Test fun missingAccentIsNotAFinding() {
        val html = receivedFixture().replace("<meta name=\"accent\" content=\"#2E7D5B\">", "")
        assertEquals(emptyList<Finding>(), check(html))
    }

    @Test fun embeddedSecretIsFound() = findsIn(Finding.SECRET, """<script>var apiKey = "${"A1".repeat(20)}";</script>""")

    @Test fun nameComesFromTheTitle() {
        assertEquals("Spanish", ReceivedAppChecks.name(receivedFixture()))
        assertEquals(null, ReceivedAppChecks.name("<html></html>"))
    }

    @Test fun uniqueNameAddsANumber() {
        assertEquals("Spanish", ReceivedAppChecks.uniqueName("Spanish", listOf("French")))
        assertEquals("Spanish 2", ReceivedAppChecks.uniqueName("Spanish", listOf("spanish")))
        assertEquals("Spanish 3", ReceivedAppChecks.uniqueName("Spanish", listOf("Spanish", "Spanish 2")))
    }
}
