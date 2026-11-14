package ai.opencyclone.voice

import android.content.Context
import java.io.File

/**
 * One model the recognizer inside the app can load: a streaming zipformer transducer, the same recognizer
 * type and config for every entry; only the file names differ. Its files live in `files/asr/<id>`.
 */
data class SherpaModel(
    val id: String,
    val name: String,
    val languages: String,
    /** Bytes the download transfers. */
    val downloadBytes: Long,
    val source: Source,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String = "tokens.txt",
    /** Files that must be present; extra entries (such as `bpe.model`) are kept but not given to the recognizer. */
    val requiredFiles: List<String> = listOf(encoder, decoder, joiner, tokens),
) {
    /** Where the files come from. Every byte is checked against a pinned SHA-256 before it is kept. */
    sealed class Source {
        /** One `.zip` archive; only [requiredFiles] are unpacked from it. */
        data class Zip(val url: String, val sha256: String) : Source()

        /** Each file on its own, each with its own hash. */
        data class Files(val files: List<RemoteFile>) : Source()
    }

    data class RemoteFile(val name: String, val url: String, val sha256: String, val bytes: Long)

    val dirName: String get() = "asr/$id"

    fun dir(baseDir: File): File = File(baseDir, dirName)

    fun installed(baseDir: File): Boolean =
        requiredFiles.all { File(dir(baseDir), it).let { f -> f.exists() && f.length() > 0L } }

    fun paths(baseDir: File): SherpaOnnxModelFiles.Paths {
        val d = dir(baseDir)
        return SherpaOnnxModelFiles.Paths(
            modelDir = d,
            encoder = File(d, encoder).absolutePath,
            decoder = File(d, decoder).absolutePath,
            joiner = File(d, joiner).absolutePath,
            tokens = File(d, tokens).absolutePath,
            bpeModel = File(d, "bpe.model").absolutePath,
        )
    }
}

/** The catalogue of models on this phone, the one in use (preference `voice_model`), and removal. */
object SherpaModels {
    private const val PREFS = "voice"
    const val KEY_MODEL = "voice_model"

    /** The bilingual model the app has always used. */
    val BILINGUAL = SherpaModel(
        id = "zh_en_small_int8",
        name = "Bilingual, small",
        languages = "Chinese and English",
        downloadBytes = 63_858_107L,
        source = SherpaModel.Source.Zip(SherpaOnnxModelFiles.DOWNLOAD_URL, SherpaOnnxModelFiles.ARCHIVE_SHA256),
        encoder = "encoder-epoch-99-avg-1.int8.onnx",
        decoder = "decoder-epoch-99-avg-1.onnx",
        joiner = "joiner-epoch-99-avg-1.int8.onnx",
        requiredFiles = SherpaOnnxModelFiles.requiredFiles,
    )

    // sherpa-onnx-streaming-zipformer-en-20M-2023-02-17 (Apache-2.0). The release archive on GitHub
    // (k2-fsa/sherpa-onnx, tag asr-models) is 127,887,156 bytes .tar.bz2 with float and int8 copies; these four
    // files are the int8 set from the maintainer's Hugging Face copy, byte-identical to the archive's files
    // (SHA-256 compared 2026-11-12). The decoder is the float one, as in the bilingual model.
    private const val EN_20M = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main"

    val ENGLISH_SMALL = SherpaModel(
        id = "en_20m_int8",
        name = "English, small",
        languages = "English",
        downloadBytes = 45_202_074L,
        source = SherpaModel.Source.Files(
            listOf(
                SherpaModel.RemoteFile(
                    "encoder-epoch-99-avg-1.int8.onnx", "$EN_20M/encoder-epoch-99-avg-1.int8.onnx",
                    "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3", 42_845_182L,
                ),
                SherpaModel.RemoteFile(
                    "decoder-epoch-99-avg-1.onnx", "$EN_20M/decoder-epoch-99-avg-1.onnx",
                    "45a7f940ecfb53d89fa270ad11b88b961e53a317203eb24b1c8e95ed208b0f30", 2_092_272L,
                ),
                SherpaModel.RemoteFile(
                    "joiner-epoch-99-avg-1.int8.onnx", "$EN_20M/joiner-epoch-99-avg-1.int8.onnx",
                    "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e", 259_572L,
                ),
                SherpaModel.RemoteFile(
                    "tokens.txt", "$EN_20M/tokens.txt",
                    "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb", 5_048L,
                ),
            )
        ),
        encoder = "encoder-epoch-99-avg-1.int8.onnx",
        decoder = "decoder-epoch-99-avg-1.onnx",
        joiner = "joiner-epoch-99-avg-1.int8.onnx",
    )

    val ALL: List<SherpaModel> = listOf(BILINGUAL, ENGLISH_SMALL)

    fun byId(id: String?): SherpaModel? = ALL.firstOrNull { it.id == id }

    /** The model in use; the bilingual one until the owner picks another. */
    fun selected(context: Context): SherpaModel =
        byId(prefs(context).getString(KEY_MODEL, null)) ?: BILINGUAL

    /** Returns false for an unknown id. */
    fun select(context: Context, id: String): Boolean {
        if (byId(id) == null) return false
        prefs(context).edit().putString(KEY_MODEL, id).apply()
        return true
    }

    fun anyInstalled(baseDir: File): Boolean = ALL.any { it.installed(baseDir) }

    /** Deletes the files of [model]. Refused (false) for the model in use, [inUseId]. */
    fun remove(baseDir: File, model: SherpaModel, inUseId: String): Boolean {
        if (model.id == inUseId) return false
        val dir = model.dir(baseDir)
        return !dir.exists() || dir.deleteRecursively()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
