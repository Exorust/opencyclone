package ai.opencyclon.miniapp

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MiniAppActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val origin = MiniAppActivity.origin("7")
    private val host = Uri.parse(origin).host
    private val html = "<html><head><title>Spanish</title></head><body>Hola</body></html>"

    private fun get(url: String) = MiniAppActivity.respond(context, host, html, Uri.parse(url))

    @Test fun pageIsServedAtTheRoot() {
        val response = get(origin)
        assertEquals("text/html", response.mimeType)
        assertEquals(html, response.data.readBytes().toString(Charsets.UTF_8))
    }

    @Test fun fontIsServedFromThePackage() {
        val response = get(origin + "inter.woff2")
        assertEquals(200, response.statusCode)
        assertEquals("font/woff2", response.mimeType)
        val bytes = response.data.readBytes()
        val packaged = context.assets.open(MiniAppActivity.FONT_ASSET).readBytes()
        assertArrayEquals(packaged, bytes)
        // WOFF2 signature.
        assertEquals("wOF2", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
    }

    @Test fun everyOtherPathAndOriginIsRefused() {
        listOf(
            origin + "app.js",
            origin + "fonts/inter.woff2",
            origin + "inter.woff2/x",
            "https://example.com/inter.woff2",
            "http://$host/inter.woff2",
            "https://app-8${MiniAppActivity.HOST_SUFFIX}/inter.woff2"
        ).forEach { url ->
            val response = get(url)
            assertEquals(url, 403, response.statusCode)
            assertEquals(url, 0, response.data.readBytes().size)
        }
    }
}
