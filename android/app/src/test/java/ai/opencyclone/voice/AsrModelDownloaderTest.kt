package ai.opencyclone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AsrModelDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun archiveHashIsPinned() {
        assertTrue(SherpaOnnxModelFiles.VERIFY_ARCHIVE)
        assertEquals(64, SherpaOnnxModelFiles.ARCHIVE_SHA256.length)
        assertTrue(SherpaOnnxModelFiles.downloadUrl().startsWith("https://"))
    }

    @Test fun sha256OfAKnownFile() {
        val f = tmp.newFile("abc").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AsrModelDownloader.sha256Hex(f))
    }

    @Test fun aWrongHashFailsClosed() {
        val good = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        AsrModelDownloader.checkHash(good, good.uppercase(), verify = true)
        assertThrows(IOException::class.java) { AsrModelDownloader.checkHash(good, "00" + good.drop(2), verify = true) }
        assertThrows(IOException::class.java) { AsrModelDownloader.checkHash(good, "", verify = true) }
        AsrModelDownloader.checkHash(good, "", verify = false) // the one switch that turns the check off
    }

    @Test fun extractTakesOnlyModelFilesAndNeverNativeCode() {
        val zip = tmp.newFile("bundle.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (name in SherpaOnnxModelFiles.requiredFiles + listOf("libonnxruntime.so", "libsherpa-onnx-jni.so", "evil.txt")) {
                z.putNextEntry(ZipEntry("nested/$name"))
                z.write(name.toByteArray())
                z.closeEntry()
            }
        }
        val dir = tmp.newFolder("asr", "zh_en_small_int8")
        AsrModelDownloader.extract(zip, dir)
        assertEquals(SherpaOnnxModelFiles.requiredFiles.toSet(), dir.list()!!.toSet())
        assertFalse(File(dir, "libonnxruntime.so").exists())
        assertTrue(SherpaOnnxModelFiles.allFilesPresent(dir.parentFile!!.parentFile!!))
    }

    @Test fun nativeLibrariesAreLoadedByNameFromThePackage() {
        assertEquals(listOf("onnxruntime", "sherpa-onnx-jni"), SherpaOnnxModelFiles.nativeLibs)
    }
}
