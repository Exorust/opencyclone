package ai.opencyclon.integrations

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.opencyclon.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IntegrationStore internal constructor(
    private val dao: IntegrationDao,
    private val tokens: SharedPreferences
) {
    constructor(context: Context) : this(
        AppDatabase.getInstance(context.applicationContext).integrationDao(),
        encryptedPreferences(context.applicationContext)
    )

    suspend fun all(): List<IntegrationEntity> = dao.all()

    internal suspend fun save(name: String, address: String, token: String?, toolCount: Int): Long =
        withContext(Dispatchers.IO) {
            val id = dao.insert(IntegrationEntity(name = name, address = address,
                toolCount = toolCount, status = CONNECTED))
            try {
                check(tokens.edit().putString(id.toString(), token).commit()) { STORAGE_FAILED }
            } catch (_: Exception) {
                dao.delete(id)
                throw IllegalStateException(STORAGE_FAILED)
            }
            id
        }

    internal suspend fun token(id: Long): String? = withContext(Dispatchers.IO) {
        tokens.getString(id.toString(), null)
    }

    internal suspend fun setStatus(entry: IntegrationEntity, status: String) = withContext(Dispatchers.IO) {
        if (entry.status != status) dao.update(entry.copy(status = status))
    }

    suspend fun remove(id: Long) = withContext(Dispatchers.IO) {
        check(tokens.edit().remove(id.toString()).commit()) { STORAGE_FAILED }
        dao.delete(id)
    }

    companion object {
        const val CONNECTED = "Connected"
        const val DISCONNECTED = "Not connected"
        const val STORAGE_FAILED = "Could not save integration"

        private fun encryptedPreferences(context: Context): SharedPreferences {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(context, "integration_tokens", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }
    }
}
