package ai.opencyclone.miniapp

import ai.opencyclone.db.AppDatabase
import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class MiniAppStore(
    context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context),
    private val now: () -> Long = System::currentTimeMillis,
    private val pin: (Long, String) -> Unit = { id, name -> MiniAppActivity.pinShortcut(context, id, name) },
    /**
     * Called with the app id each time [currentHtml] is read.
     * ponytail: the builder reads the current file only when a change starts, so the runtime's store uses this to
     * learn which app is being changed; another reader of the same store instance would look like a change.
     */
    private val onCurrentHtmlRead: (Long) -> Unit = {}
) {
    private val context = context.applicationContext
    private val root = File(this.context.filesDir, "miniapps")

    /** [received] marks a new app opened from a file someone sent; a later version of a received app stays received. */
    suspend fun saveNewVersion(appId: Long?, name: String, html: String, received: Boolean = false): Pair<Long, Int> = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            var written: File? = null
            try {
                database.withTransaction {
                    val old = appId?.let { requireNotNull(database.miniAppDao().get(it)) { MISSING_APP } }
                    val time = now()
                    val id = old?.id ?: database.miniAppDao().insert(MiniAppEntity(name = name, currentVersion = 0, createdAt = time, received = received))
                    val version = (old?.currentVersion ?: 0) + 1
                    val directory = File(root, id.toString())
                    check(directory.isDirectory || directory.mkdirs()) { WRITE_FAILED }
                    val file = File(directory, "v$version.html")
                    check(!file.exists()) { WRITE_FAILED }
                    val temporary = File.createTempFile("version-", ".tmp", directory)
                    try {
                        temporary.writeText(html, Charsets.UTF_8)
                        check(temporary.renameTo(file)) { WRITE_FAILED }
                        written = file
                    } finally {
                        temporary.delete()
                    }
                    database.miniAppVersionDao().insert(MiniAppVersionEntity(id, version, file.absolutePath, time))
                    database.miniAppDao().update(MiniAppEntity(id, name, version, old?.createdAt ?: time,
                        accent = BuiltAppStyle.accentOf(html), received = (old?.received ?: false) || received))
                    id to version
                }
            } catch (error: Throwable) {
                written?.delete()
                throw error
            }
        }
    }

    suspend fun currentFile(appId: Long): File? = withContext(Dispatchers.IO) {
        val app = database.miniAppDao().get(appId) ?: return@withContext null
        File(File(root, app.id.toString()), "v${app.currentVersion}.html").takeIf { it.isFile }
    }

    suspend fun currentHtml(appId: Long): String? {
        onCurrentHtmlRead(appId)
        return withContext(Dispatchers.IO) { currentFile(appId)?.readText(Charsets.UTF_8) }
    }

    suspend fun list(): List<MiniAppEntity> = database.miniAppDao().all()

    /** True when [appId] was opened from a file someone sent; such an app may not use the internet. */
    suspend fun isReceived(appId: Long): Boolean = database.miniAppDao().get(appId)?.received == true

    suspend fun findByName(text: String): List<MiniAppEntity> = if (text.isBlank()) emptyList() else list().filter {
        it.name.contains(text.trim(), ignoreCase = true) || text.contains(it.name, ignoreCase = true)
    }

    suspend fun recent(): List<MiniAppEntity> {
        val time = now()
        return list().filter { app ->
            database.miniAppVersionDao().all(app.id).any { it.createdAt <= time && it.createdAt > time - RECENT_MS }
        }
    }

    suspend fun shareFile(appId: Long): File? = withContext(Dispatchers.IO) {
        val source = currentFile(appId) ?: return@withContext null
        val external = context.getExternalFilesDir(null) ?: return@withContext null
        val directory = File(external, "miniapps/$appId")
        if (!directory.isDirectory && !directory.mkdirs()) return@withContext null
        val name = database.miniAppDao().get(appId)?.name ?: return@withContext null
        // The recipient sees this name; the internal "v<n>.html" says nothing to them.
        directory.listFiles()?.forEach { it.delete() }
        source.copyTo(File(directory, shareFileName(name)), overwrite = true)
    }

    suspend fun pinShortcut(appId: Long, name: String) = withContext(Dispatchers.Main.immediate) { pin(appId, name) }

    companion object {
        private val saveMutex = Mutex()
        const val RECENT_MS = 10 * 60 * 1000L
        const val MISSING_APP = "App not found"
        const val WRITE_FAILED = "Could not save the app file"
        private const val FALLBACK_SHARE_NAME = "app"

        /** "<app name>.html" with characters that are unsafe in file names removed. */
        fun shareFileName(appName: String): String {
            val safe = appName.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
                .replace(Regex("""\s+"""), " ").trim().trim('.').trim().take(80).trim()
            return (safe.ifEmpty { FALLBACK_SHARE_NAME }) + ".html"
        }
    }
}
