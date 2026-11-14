package ai.opencyclone.config

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts and decrypts one secret value. Throws on any failure. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * Secrets (model keys, sign-in tokens) at rest: AES-256-GCM, Base64 of IV + ciphertext, in the private
 * preferences file `secrets`. Fails safe: a value that cannot be decrypted (key lost, data damaged) reads as
 * absent, so the owner signs in again; a value that cannot be encrypted is not stored. Never plain text.
 */
class SecretStore(context: Context, private val cipher: SecretCipher = defaultCipher()) {

    private val prefs = (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read a stored secret; treating it as absent (${e.javaClass.simpleName})")
            null
        }
    }

    /** Stores [value]; an empty value removes the entry. Returns false when the value could not be stored. */
    fun put(name: String, value: String): Boolean {
        if (value.isEmpty()) return remove(name)
        val encoded = try {
            Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            Log.w(TAG, "Could not encrypt a secret; not stored (${e.javaClass.simpleName})")
            prefs.edit().remove(name).commit()
            return false
        }
        return prefs.edit().putString(name, encoded).commit()
    }

    fun remove(name: String): Boolean = prefs.edit().remove(name).commit()

    companion object {
        const val PREFS_NAME = "secrets"
        private const val TAG = "SecretStore"

        // ponytail: Robolectric has no Android Keystore, so tests get a process-wide software AES key
        // (same check as App's). On a phone it is always the Keystore.
        fun defaultCipher(): SecretCipher =
            if (Build.FINGERPRINT == "robolectric") SoftwareCipher.shared else KeystoreCipher()
    }
}

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val IV_SIZE = 12
private const val TAG_BITS = 128

private fun gcmEncrypt(key: SecretKey, plain: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val iv = cipher.iv
    check(iv.size == IV_SIZE)
    return iv + cipher.doFinal(plain)
}

private fun gcmDecrypt(key: SecretKey, blob: ByteArray): ByteArray {
    require(blob.size > IV_SIZE)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE))
    return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
}

/** AES-256-GCM key in the Android Keystore; no user authentication, so background tasks can read secrets. */
class KeystoreCipher : SecretCipher {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run { init(spec); generateKey() }
    }

    override fun encrypt(plain: ByteArray): ByteArray = gcmEncrypt(key(), plain)

    // Never creates a key: with the key lost, decryption fails and the value reads as absent.
    override fun decrypt(blob: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(ALIAS, null) as? SecretKey ?: error("secret key missing")
        return gcmDecrypt(key, blob)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "app_secrets_aes_gcm"
    }
}

/** AES-256-GCM with a key held only in memory. For tests; values do not survive the process. */
class SoftwareCipher(private val key: SecretKey = newKey()) : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray = gcmEncrypt(key, plain)
    override fun decrypt(blob: ByteArray): ByteArray = gcmDecrypt(key, blob)

    companion object {
        val shared by lazy { SoftwareCipher() }
        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").run { init(256); generateKey() }
    }
}
