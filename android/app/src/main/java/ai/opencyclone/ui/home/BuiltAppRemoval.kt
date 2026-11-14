package ai.opencyclone.ui.home

import ai.opencyclone.R
import ai.opencyclone.db.AppDatabase
import ai.opencyclone.miniapp.Bridge
import ai.opencyclone.miniapp.MiniAppActivity
import android.content.Context
import android.webkit.WebStorage
import androidx.core.content.pm.ShortcutManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Removal is offered only after the owner confirms the selected tile. */
object BuiltAppRemoval {
    fun requestedName(text: String): String? =
        Regex("^delete the (.+) app[.]?$", RegexOption.IGNORE_CASE).matchEntire(text.trim())
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    suspend fun remove(context: Context, id: Long, database: AppDatabase = AppDatabase.getInstance(context)): Boolean {
        try {
            withContext(Dispatchers.IO) {
                database.runInTransaction {
                    database.openHelper.writableDatabase.execSQL("DELETE FROM miniapp_versions WHERE app_id = ?", arrayOf<Any>(id))
                    database.openHelper.writableDatabase.execSQL("DELETE FROM miniapps WHERE id = ?", arrayOf<Any>(id))
                }
                File(context.filesDir, "miniapps/$id").deleteRecursively()
                context.getExternalFilesDir(null)?.let { File(it, "miniapps/$id").deleteRecursively() }
                context.getSharedPreferences("${Bridge.PREFS_PREFIX}$id", Context.MODE_PRIVATE).edit().clear().commit()
            }
            withContext(Dispatchers.Main) {
                WebStorage.getInstance().deleteOrigin(MiniAppActivity.origin(id.toString()).trimEnd('/'))
                ShortcutManagerCompat.disableShortcuts(context, listOf("miniapp-$id"), context.getString(R.string.x4_app_removed))
            }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
    }
}
