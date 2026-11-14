package ai.opencyclone.widgets

import ai.opencyclone.db.AppDatabase
import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * A draft: a file `files/widgets/drafts/<n>.html` and this record in memory. [widgetId] is the saved widget it
 * changes, or null for a new widget.
 */
data class WidgetDraft(
    val file: File,
    val name: String,
    val size: WidgetSize,
    val sources: List<String>,
    val accent: String,
    val widgetId: Long? = null,
) {
    val number: String get() = file.nameWithoutExtension
}

/** Saved widgets (Room table `widgets` and files under `files/widgets/<id>/`) and draft files. */
class WidgetStore(
    context: Context,
    private val database: AppDatabase = AppDatabase.getInstance(context),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val resources = context.applicationContext.resources
    fun sizeNote(size: WidgetSize): String = WidgetDimensions.sizeNote(resources, size)

    private val root = File(context.applicationContext.filesDir, DIR)
    private val drafts = File(root, DRAFTS)
    private val dao get() = database.widgetDao()
    private val nextDraft = AtomicInteger(1)

    suspend fun list(): List<WidgetEntity> = dao.all()
    fun observe(): Flow<List<WidgetEntity>> = dao.observeAll()
    suspend fun get(id: Long): WidgetEntity? = dao.get(id)
    suspend fun count(): Int = dao.count()
    suspend fun isFull(): Boolean = count() >= MAX_WIDGETS

    fun file(widget: WidgetEntity): File = File(File(root, widget.id.toString()), "v${widget.version}.html")

    suspend fun html(widget: WidgetEntity): String? = withContext(Dispatchers.IO) {
        file(widget).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }

    /** Writes a new draft file and returns it. */
    suspend fun writeDraft(html: String): File = withContext(Dispatchers.IO) {
        check(drafts.isDirectory || drafts.mkdirs()) { WRITE_FAILED }
        var file: File
        do { file = File(drafts, "${nextDraft.getAndIncrement()}.html") } while (file.exists())
        file.writeText(html, Charsets.UTF_8)
        file
    }

    suspend fun deleteDraft(draft: WidgetDraft?) {
        if (draft == null) return
        withContext(Dispatchers.IO) { draft.file.delete() }
    }

    /** Process start: no draft record survived the old process, so no draft file is wanted. */
    suspend fun deleteAllDrafts() = withContext(Dispatchers.IO) { drafts.deleteRecursively() }

    /**
     * Saves [draft] as a new widget (last position) or as a new version of [WidgetDraft.widgetId] (same position).
     * The draft file is moved into place; the previous version's file is deleted. Returns the widget id.
     */
    suspend fun save(draft: WidgetDraft): Long = withContext(Dispatchers.IO) {
        val html = draft.file.readText(Charsets.UTF_8)
        var written: File? = null
        try {
            val saved = database.withTransaction {
                val old = draft.widgetId?.let { dao.get(it) }
                if (old == null && dao.count() >= MAX_WIDGETS) error(FULL)
                val base = old?.copy(name = draft.name, size = draft.size.id, sources = draft.sources.joinToString(","),
                    version = old.version + 1, accent = draft.accent)
                    ?: WidgetEntity(name = draft.name, size = draft.size.id, sources = draft.sources.joinToString(","),
                        position = (dao.all().maxOfOrNull { it.position } ?: -1) + 1, version = 1, accent = draft.accent,
                        createdAt = now())
                val id = if (old == null) dao.insert(base) else base.id.also { dao.update(base) }
                val entity = base.copy(id = id)
                val target = file(entity)
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { WRITE_FAILED }
                target.writeText(html, Charsets.UTF_8)
                written = target
                old to entity
            }
            saved.first?.let { file(it).delete() }
            draft.file.delete()
            saved.second.id
        } catch (error: Throwable) {
            written?.delete()
            throw error
        }
    }

    suspend fun remove(id: Long) {
        dao.delete(id)
        withContext(Dispatchers.IO) { File(root, id.toString()).deleteRecursively() }
    }

    /** Saves the order of the page: [ids] first to last. */
    suspend fun reorder(ids: List<Long>) = database.withTransaction {
        ids.forEachIndexed { index, id -> dao.setPosition(id, index) }
    }

    /** A draft of a saved widget, to change it: its current file copied into a new draft. */
    suspend fun draftOf(widget: WidgetEntity): WidgetDraft? {
        val html = html(widget) ?: return null
        return WidgetDraft(writeDraft(html), widget.name, widget.widgetSize, widget.sourceList, widget.accent, widget.id)
    }

    companion object {
        const val MAX_WIDGETS = 8
        const val DIR = "widgets"
        const val DRAFTS = "drafts"
        const val WRITE_FAILED = "Could not save the widget file"
        const val FULL = "You have 8 widgets. Remove one first."
    }
}
