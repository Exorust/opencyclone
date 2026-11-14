package ai.opencyclone.voice

import java.io.File

/**
 * The recognizer's model files (decision 13). The two native libraries are packaged in the app from the
 * recognizer dependencies; only the model is downloaded, and the archive is checked against [ARCHIVE_SHA256]
 * before anything is unpacked.
 */
object SherpaOnnxModelFiles {
    const val FILES_DIR = "asr/zh_en_small_int8"

    // ponytail: the release has only an arm64-v8a archive. The model files in it are the same for every
    // ABI; the .so files in it are skipped (they equal the packaged ones, byte for byte, checked 2026-11-12).
    const val DOWNLOAD_URL =
        "https://github.com/opencyvis/opencyvis-phone/releases/download/asr-v1/asr-arm64-v8a.zip"

    /** SHA-256 of the archive at [DOWNLOAD_URL] (63,858,107 bytes), computed 2026-11-12. */
    const val ARCHIVE_SHA256 = "b9f9f5c1eb8951239fb5b20d18857df61a70ab5081e898d141e0652102760d7b"

    /** When true, an archive whose hash differs from [ARCHIVE_SHA256] is deleted and never unpacked. */
    const val VERIFY_ARCHIVE = true

    val requiredFiles = listOf(
        "encoder-epoch-99-avg-1.int8.onnx",
        "decoder-epoch-99-avg-1.onnx",
        "joiner-epoch-99-avg-1.int8.onnx",
        "tokens.txt",
        "bpe.model"
    )

    /** Library names for System.loadLibrary, packaged in the app; ONNX Runtime first, it is a dependency. */
    val nativeLibs = listOf("onnxruntime", "sherpa-onnx-jni")

    fun downloadUrl(): String = DOWNLOAD_URL

    fun allFilesPresent(baseDir: File): Boolean {
        val modelDir = File(baseDir, FILES_DIR)
        return requiredFiles.all {
            File(modelDir, it).let { f -> f.exists() && f.length() > 0L }
        }
    }

    fun paths(baseDir: File): Paths {
        val modelDir = File(baseDir, FILES_DIR)
        return Paths(
            modelDir = modelDir,
            encoder = File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
            decoder = File(modelDir, "decoder-epoch-99-avg-1.onnx").absolutePath,
            joiner = File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
            tokens = File(modelDir, "tokens.txt").absolutePath,
            bpeModel = File(modelDir, "bpe.model").absolutePath
        )
    }

    data class Paths(
        val modelDir: File,
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
        val bpeModel: String
    )
}
