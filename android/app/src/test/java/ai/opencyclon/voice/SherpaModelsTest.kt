package ai.opencyclon.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SherpaModelsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun theCatalogueHasUniqueIdsHttpsAddressesAndPinnedHashes() {
        val ids = SherpaModels.ALL.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        assertTrue(SherpaModels.ALL.size >= 2)
        for (m in SherpaModels.ALL) {
            assertTrue(m.name.isNotBlank() && m.languages.isNotBlank())
            assertTrue("${m.id} is at most 80 MB", m.downloadBytes in 1..80_000_000)
            assertTrue(m.requiredFiles.containsAll(listOf(m.encoder, m.decoder, m.joiner, m.tokens)))
            when (val s = m.source) {
                is SherpaModel.Source.Zip -> {
                    assertTrue(s.url.startsWith("https://"))
                    assertTrue(s.sha256.matches(Regex("[0-9a-f]{64}")))
                }
                is SherpaModel.Source.Files -> {
                    assertEquals(m.requiredFiles.toSet(), s.files.map { it.name }.toSet())
                    assertEquals(m.downloadBytes, s.files.sumOf { it.bytes })
                    s.files.forEach {
                        assertTrue(it.url.startsWith("https://"))
                        assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}")))
                    }
                }
            }
        }
        // The bilingual model keeps the folder and archive it always had.
        assertEquals(SherpaOnnxModelFiles.FILES_DIR, SherpaModels.BILINGUAL.dirName)
        assertEquals(SherpaOnnxModelFiles.ARCHIVE_SHA256, (SherpaModels.BILINGUAL.source as SherpaModel.Source.Zip).sha256)
    }

    @Test fun everyModelLoadsWithTheSameRecognizerConfig() {
        for (m in SherpaModels.ALL) {
            val paths = m.paths(File("/tmp/x"))
            val config = SherpaOnnxSpeechInputEngine.recognizerConfig(paths, 1)
            assertEquals("zipformer", config.modelConfig.modelType)
            assertTrue(config.modelConfig.transducer.encoder.endsWith("asr/${m.id}/${m.encoder}"))
            assertTrue(config.modelConfig.tokens.endsWith("asr/${m.id}/tokens.txt"))
        }
    }

    @Test fun theBilingualModelIsInUseUntilAnotherIsChosen() {
        assertEquals(SherpaModels.BILINGUAL, SherpaModels.selected(context))
        assertTrue(SherpaModels.select(context, SherpaModels.ENGLISH_SMALL.id))
        assertEquals(SherpaModels.ENGLISH_SMALL, SherpaModels.selected(context))
        assertFalse(SherpaModels.select(context, "nope"))
        assertEquals(SherpaModels.ENGLISH_SMALL, SherpaModels.selected(context))
    }

    @Test fun removeDeletesTheFilesButNeverTheModelInUse() {
        val base = tmp.newFolder()
        for (m in SherpaModels.ALL) {
            m.dir(base).mkdirs()
            m.requiredFiles.forEach { File(m.dir(base), it).writeText("x") }
            assertTrue(m.installed(base))
        }
        assertFalse(SherpaModels.remove(base, SherpaModels.BILINGUAL, inUseId = SherpaModels.BILINGUAL.id))
        assertTrue(SherpaModels.BILINGUAL.installed(base))
        assertTrue(SherpaModels.remove(base, SherpaModels.ENGLISH_SMALL, inUseId = SherpaModels.BILINGUAL.id))
        assertFalse(File(base, "asr/${SherpaModels.ENGLISH_SMALL.id}").exists())
    }

    private fun fileModel(files: Map<String, ByteArray>, hashes: Map<String, String> = files.mapValues { sha(it.value) }) =
        SherpaModel(
            id = "test_model", name = "Test", languages = "English",
            downloadBytes = files.values.sumOf { it.size.toLong() },
            source = SherpaModel.Source.Files(files.map { (name, bytes) ->
                SherpaModel.RemoteFile(name, server.url("/m/$name").toString(), hashes.getValue(name), bytes.size.toLong())
            }),
            encoder = "e.onnx", decoder = "d.onnx", joiner = "j.onnx",
        )

    @Test fun aModelOfSingleFilesDownloadsEachFileWithItsHashAndReportsProgress() {
        val files = linkedMapOf(
            "e.onnx" to ByteArray(3000) { 1 }, "d.onnx" to ByteArray(2000) { 2 },
            "j.onnx" to ByteArray(1000) { 3 }, "tokens.txt" to "a 0\nb 1\n".toByteArray(),
        )
        files.values.forEach { server.enqueue(MockResponse().setBody(Buffer().write(it))) }
        val model = fileModel(files)
        val base = tmp.newFolder()
        ShadowStatFs.registerStats(model.dir(base), 1_000_000, 1_000_000, 1_000_000)
        val progress = mutableListOf<Pair<Long, Long>>()
        AsrModelDownloader.download(model, base) { read, total -> progress += read to total }
        assertTrue(model.installed(base))
        files.forEach { (name, bytes) -> assertTrue(File(model.dir(base), name).readBytes().contentEquals(bytes)) }
        assertEquals(model.requiredFiles.toSet(), model.dir(base).list()!!.toSet()) // no temporary files left
        assertEquals(model.downloadBytes, progress.last().first)
        assertTrue(progress.all { it.second == model.downloadBytes })
        assertEquals(4, server.requestCount)

        // Installed: a second call downloads nothing.
        AsrModelDownloader.download(model, base)
        assertEquals(4, server.requestCount)
    }

    @Test fun aFileWithTheWrongHashIsNeverKept() {
        val files = linkedMapOf("e.onnx" to ByteArray(10) { 1 }, "d.onnx" to ByteArray(10) { 2 },
            "j.onnx" to ByteArray(10) { 3 }, "tokens.txt" to ByteArray(10) { 4 })
        val hashes = files.mapValues { sha(it.value) }.toMutableMap().apply { put("d.onnx", "0".repeat(64)) }
        files.values.forEach { server.enqueue(MockResponse().setBody(Buffer().write(it))) }
        val model = fileModel(files, hashes)
        val base = tmp.newFolder()
        ShadowStatFs.registerStats(model.dir(base), 1_000_000, 1_000_000, 1_000_000)
        assertThrows(IOException::class.java) { AsrModelDownloader.download(model, base) }
        assertFalse(model.installed(base))
        assertFalse(File(model.dir(base), "d.onnx").exists())
        assertFalse(File(model.dir(base), "d.onnx.tmp").exists())
    }
}
