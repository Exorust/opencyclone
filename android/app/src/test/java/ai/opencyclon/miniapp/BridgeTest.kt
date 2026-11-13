package ai.opencyclon.miniapp

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BridgeTest {
    // No network in unit tests: example.com resolves to a fixed public address; literals and localhost resolve locally.
    private val resolve: (String) -> List<InetAddress> = { host ->
        when (host) {
            "example.com" -> listOf(InetAddress.getByAddress(host, byteArrayOf(93, 184.toByte(), 215.toByte(), 14)))
            "localhost" -> listOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
            "rebind.example" -> listOf(InetAddress.getByAddress(host, byteArrayOf(10, 1, 2, 3)))
            else -> InetAddress.getAllByName(host).toList()
        }
    }

    @Test fun publicHttpsIsAllowed() {
        assertTrue(Bridge.isAllowedUrl("https://example.com", resolve))
        assertTrue(Bridge.isAllowedUrl("https://example.com/weather?city=sf", resolve))
    }

    @Test fun plainHttpIsRefused() = assertFalse(Bridge.isAllowedUrl("http://example.com", resolve))

    @Test fun loopbackIsRefused() {
        assertFalse(Bridge.isAllowedUrl("https://127.0.0.1", resolve))
        assertFalse(Bridge.isAllowedUrl("https://localhost", resolve))
        assertFalse(Bridge.isAllowedUrl("https://[::1]/", resolve))
    }

    @Test fun privateRangesAreRefused() {
        assertFalse(Bridge.isAllowedUrl("https://192.168.1.5", resolve))
        assertFalse(Bridge.isAllowedUrl("https://10.0.0.1", resolve))
        assertFalse(Bridge.isAllowedUrl("https://172.16.0.9", resolve))
        assertFalse(Bridge.isAllowedUrl("https://169.254.169.254", resolve))
        assertFalse(Bridge.isAllowedUrl("https://100.64.0.1", resolve))
        assertFalse(Bridge.isAllowedUrl("https://[fd00::1]/", resolve))
        assertFalse(Bridge.isAllowedUrl("https://rebind.example", resolve))
    }

    @Test fun otherSchemesAreRefused() {
        assertFalse(Bridge.isAllowedUrl("file:///data/data/x", resolve))
        assertFalse(Bridge.isAllowedUrl("not a url", resolve))
    }

    @Test fun storageIsPerAppAndLimitedTo1Mb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val one = Bridge(context, 101, deliver = {}, allowNotify = false, resolve = resolve)
        val two = Bridge(context, 102, deliver = {}, allowNotify = false, resolve = resolve)
        assertTrue(one.set("streak", "1"))
        assertEquals("1", one.get("streak"))
        assertNull(two.get("streak"))
        assertFalse(one.set("big", "x".repeat(Bridge.MAX_STORAGE_BYTES.toInt())))
        assertTrue(one.set("big", "x".repeat(1000)))
        assertEquals("1", one.get("streak"))
    }
}
