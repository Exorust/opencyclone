package ai.opencyclon.voice

import android.os.StatFs
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Downloads a recognizer model (an archive or single files), checks each SHA-256, and keeps only the model files.
 * No code is taken from the archive: the native libraries are packaged in the app (decision 13).
 */
object AsrModelDownloader {

    private const val TAG = "AsrModelDownloader"
    private const val MIN_DISK_BYTES = 16L * 1024 * 1024 // headroom beyond the download
    private val downloadLock = Any()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun interface ProgressListener {
        fun onProgress(bytesRead: Long, totalBytes: Long)
    }

    /**
     * Downloads [model] into its folder under [baseDir] unless all its files are there. Every archive and every
     * file is checked against its pinned SHA-256 before anything is kept; nothing else is unpacked.
     */
    fun download(model: SherpaModel, baseDir: File, listener: ProgressListener? = null) {
        synchronized(downloadLock) {
            if (model.installed(baseDir)) return
            val targetDir = model.dir(baseDir)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw IOException("Cannot create directory: $targetDir")
            }
            checkDiskSpace(targetDir, model.downloadBytes)
            when (val source = model.source) {
                is SherpaModel.Source.Zip -> {
                    val tmpFile = File(targetDir, "download.tmp")
                    try {
                        val actual = fetch(source.url, tmpFile, listener)
                        checkHash(actual, source.sha256, SherpaOnnxModelFiles.VERIFY_ARCHIVE)
                        extract(tmpFile, targetDir, model.requiredFiles)
                    } finally {
                        tmpFile.delete()
                    }
                }
                is SherpaModel.Source.Files -> downloadFiles(source.files, targetDir, model.downloadBytes, listener)
            }
            Log.i(TAG, "ASR model download complete: $targetDir")
        }
    }

    /** Each file to a temporary name, checked, then renamed; a file already present with the right hash is kept. */
    private fun downloadFiles(
        files: List<SherpaModel.RemoteFile>,
        targetDir: File,
        total: Long,
        listener: ProgressListener?,
    ) {
        var done = 0L
        for (file in files) {
            val dest = File(targetDir, file.name)
            if (dest.exists() && sha256Hex(dest).equals(file.sha256, ignoreCase = true)) {
                done += dest.length()
                continue
            }
            val tmp = File(targetDir, "${file.name}.tmp")
            try {
                val before = done
                val actual = fetch(file.url, tmp, listener?.let { l ->
                    ProgressListener { read, _ -> l.onProgress(before + read, total) }
                })
                checkHash(actual, file.sha256, verify = true)
                dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("Cannot store ${file.name}")
                done += file.bytes
            } finally {
                tmp.delete()
            }
        }
    }

    /**
     * Throws when [verify] is on and [actualHex] is not [expectedHex]. An empty expected hash with
     * [verify] on also throws: the check fails closed.
     */
    internal fun checkHash(actualHex: String, expectedHex: String, verify: Boolean) {
        if (!verify) return
        if (expectedHex.isBlank() || !actualHex.equals(expectedHex, ignoreCase = true)) {
            throw IOException("Speech model download failed its integrity check")
        }
    }

    /** Lower-case hex SHA-256 of [file]. */
    internal fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun checkDiskSpace(dir: File, downloadBytes: Long) {
        val stat = StatFs(dir.absolutePath)
        if (stat.availableBytes < downloadBytes + MIN_DISK_BYTES) {
            throw IOException("Not enough disk space for the speech model")
        }
    }

    /** Downloads [url] to [dest] and returns the SHA-256 of what was written. */
    internal fun fetch(url: String, dest: File, listener: ProgressListener?): String {
        Log.i(TAG, "Downloading ASR model from $url")
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Download failed: HTTP ${response.code}")
        }
        val body = response.body ?: throw IOException("Empty response body")
        val totalBytes = body.contentLength()
        val digest = MessageDigest.getInstance("SHA-256")

        body.source().use { source ->
            dest.sink().buffer().use { sink ->
                var bytesRead = 0L
                val buf = ByteArray(8192)
                while (true) {
                    val read = source.read(buf)
                    if (read == -1) break
                    digest.update(buf, 0, read)
                    sink.write(buf, 0, read)
                    bytesRead += read
                    listener?.onProgress(bytesRead, totalBytes)
                }
            }
        }
        Log.i(TAG, "Download finished: ${dest.length()} bytes")
        return digest.digest().toHex()
    }

    /** Unpacks [files] from [zipFile] into [targetDir]; every other entry (the .so files) is skipped. */
    internal fun extract(zipFile: File, targetDir: File, files: List<String> = SherpaOnnxModelFiles.requiredFiles) {
        Log.i(TAG, "Extracting ASR model to $targetDir")
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = File(entry.name).name // strip any path prefix
                if (!entry.isDirectory && name in files) {
                    File(targetDir, name).outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
