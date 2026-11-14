package ai.opencyclone.voice

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceServiceClientTest {
    private lateinit var server: MockWebServer
    private val client = VoiceServiceClient()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun config(language: String? = null) = VoiceServiceConfig(
        address = server.url("/v1/").toString(), model = "gpt-4o-transcribe", key = "sk-test", language = language,
    )

    /** The multipart parts of [request]: name to (headers block, body bytes). */
    private fun parts(request: RecordedRequest): Map<String, Pair<String, ByteArray>> {
        val type = request.getHeader("Content-Type")!!
        assertTrue(type, type.startsWith("multipart/form-data; boundary="))
        val boundary = "--" + type.substringAfter("boundary=")
        val raw = request.body.readByteArray()
        val text = String(raw, Charsets.ISO_8859_1)
        return text.split(boundary).drop(1).dropLast(1).associate { chunk ->
            val headerEnd = chunk.indexOf("\r\n\r\n")
            val headers = chunk.substring(0, headerEnd)
            val body = chunk.substring(headerEnd + 4, chunk.length - 2).toByteArray(Charsets.ISO_8859_1)
            Regex("name=\"([^\"]+)\"").find(headers)!!.groupValues[1] to (headers to body)
        }
    }

    @Test fun oneRequestWithTheWavTheModelAndTheKey() {
        server.enqueue(MockResponse().setBody("""{"text":" Open the clock app "}"""))
        val samples = ShortArray(3200) { (it % 100).toShort() }
        val result = client.transcribe(config(language = "en"), samples)
        assertEquals(VoiceServiceClient.Result.Text("Open the clock app"), result)

        val request = server.takeRequest()
        assertEquals(1, server.requestCount)
        assertEquals("POST", request.method)
        assertEquals("/v1/audio/transcriptions", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val parts = parts(request)
        assertEquals(setOf("file", "model", "language"), parts.keys)
        val (fileHeaders, wav) = parts.getValue("file")
        assertTrue(fileHeaders, fileHeaders.contains("filename=\"speech.wav\""))
        assertTrue(fileHeaders, fileHeaders.contains("Content-Type: audio/wav"))
        assertEquals("gpt-4o-transcribe", String(parts.getValue("model").second))
        assertEquals("en", String(parts.getValue("language").second))

        // The WAV header matches the sample count.
        assertEquals(44 + samples.size * 2, wav.size)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + samples.size * 2, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16000, b.getInt(24))
        assertEquals(32000, b.getInt(28))
        assertEquals(2, b.getShort(32).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4))
        assertEquals(samples.size * 2, b.getInt(40))
        assertEquals(99, b.getShort(44 + 99 * 2).toInt())
    }

    @Test fun noLanguagePartWhenNoneIsSet() {
        server.enqueue(MockResponse().setBody("""{"text":"hi"}"""))
        client.transcribe(config(), ShortArray(10))
        assertEquals(setOf("file", "model"), parts(server.takeRequest()).keys)
    }

    @Test fun replyParsing() {
        assertEquals("Call Sam", VoiceServiceClient.parseText("""{"text":"Call Sam\n"}"""))
        assertEquals("", VoiceServiceClient.parseText("""{"text":""}"""))
        assertNull(VoiceServiceClient.parseText("""{"error":"x"}"""))
        assertNull(VoiceServiceClient.parseText("""{"text":null}"""))
        assertNull(VoiceServiceClient.parseText("not json"))
    }

    @Test fun refusedKeysServerFailuresAndNoConnection() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(VoiceServiceClient.Result.Refused, client.transcribe(config(), ShortArray(10)))
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(VoiceServiceClient.Result.Refused, client.transcribe(config(), ShortArray(10)))
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        assertTrue(client.transcribe(config(), ShortArray(10)) is VoiceServiceClient.Result.Failed)
        server.enqueue(MockResponse().setBody("""{"no":"text"}"""))
        assertTrue(client.transcribe(config(), ShortArray(10)) is VoiceServiceClient.Result.Failed)

        val address = config()
        server.shutdown()
        assertEquals(VoiceServiceClient.Result.Unreachable, client.transcribe(address, ShortArray(10)))
    }

    @Test fun theTestSendsOneSecondOfSilenceAndAcceptsAnyHttp200() {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(client.test(config()) is VoiceServiceClient.Result.Text)
        val wav = parts(server.takeRequest()).getValue("file").second
        assertEquals(44 + 16000 * 2, wav.size)
        assertTrue(wav.drop(44).all { it == 0.toByte() })

        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(VoiceServiceClient.Result.Refused, client.test(config()))
    }

    @Test fun onlyHttpsAddressesExceptLocalhostInDebugBuilds() {
        assertTrue(VoiceServiceStore.addressAllowed("https://api.openai.com/v1", debug = false))
        assertFalse(VoiceServiceStore.addressAllowed("http://api.openai.com/v1", debug = false))
        assertFalse(VoiceServiceStore.addressAllowed("http://api.openai.com/v1", debug = true))
        assertFalse(VoiceServiceStore.addressAllowed("http://localhost:8765/v1", debug = false))
        assertTrue(VoiceServiceStore.addressAllowed("http://localhost:8765/v1", debug = true))
        assertTrue(VoiceServiceStore.addressAllowed("http://127.0.0.1:8765/v1", debug = true))
        assertFalse(VoiceServiceStore.addressAllowed("http://192.168.1.2:8765/v1", debug = true))
        assertFalse(VoiceServiceStore.addressAllowed("ftp://x", debug = true))
        assertFalse(VoiceServiceStore.addressAllowed("", debug = true))
        assertFalse(VoiceServiceConfig("https://api.openai.com/v1", "m", key = "").usable(debug = false))
        assertTrue(VoiceServiceConfig("https://api.openai.com/v1", "m", key = "k").usable(debug = false))
    }
}
