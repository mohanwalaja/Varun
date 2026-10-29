package dev.varun.studio.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.varun.studio.core.AppLog
import java.io.File
import java.security.KeyStore
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted key/value store for the GitHub token and other sensitive settings.
 *
 * Design decisions, and why:
 *  - androidx.security-crypto (EncryptedSharedPreferences) is deprecated and has
 *    a history of unrecoverable-keystore crashes, so we implement the same
 *    guarantee directly: a symmetric key that never leaves the Android Keystore
 *    (hardware-backed where available), used for AES-256-GCM.
 *  - The ciphertext is written to a file in app-private storage, NOT to shared
 *    prefs, and it is excluded from cloud backup (see res/xml/backup_rules.xml).
 *  - Every value carries its own random 12-byte IV, stored alongside it.
 *  - If the keystore key is lost (OS reinstall, keystore corruption) we detect
 *    it, discard the unreadable blob and return null rather than crashing. The
 *    user is then asked to reconnect GitHub.
 *
 * No secret is ever logged: [AppLog.redact] is a second line of defense.
 */
class SecureVault(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, VAULT_FILE)

    private val keyStore: KeyStore? = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }.getOrNull()

    /** True when the Keystore is usable on this device. */
    val isHardwareBackedStorageAvailable: Boolean
        get() = getOrCreateKey() != null

    private fun getOrCreateKey(): SecretKey? {
        val ks = keyStore ?: return null
        return try {
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user authentication required: forcing a fingerprint prompt
                // on every token read makes the app unusable, and the token is
                // already scoped to the minimum GitHub permissions.
                .setUserAuthenticationRequired(false)
                .build()
            generator.init(spec)
            generator.generateKey()
        } catch (t: Throwable) {
            AppLog.e("SecureVault: could not create keystore key", t)
            null
        }
    }

    private fun cipher(mode: Int, key: SecretKey, iv: ByteArray?): Cipher? = runCatching {
        Cipher.getInstance(TRANSFORMATION).apply {
            if (iv == null) init(mode, key)
            else init(mode, key, GCMParameterSpec(TAG_BITS, iv))
        }
    }.getOrNull()

    /** Store a value. Returns false if the keystore is unavailable. */
    fun putString(key: String, value: String?): Boolean {
        return try {
            val props = readAll()
            if (value == null) {
                props.remove(key)
                props.remove(ivKey(key))
            } else {
                val secret = getOrCreateKey() ?: return false
                val enc = cipher(Cipher.ENCRYPT_MODE, secret, null) ?: return false
                val ciphertext = enc.doFinal(value.toByteArray(Charsets.UTF_8))
                val iv = enc.iv
                props.setProperty(key, java.util.Base64.getEncoder().encodeToString(ciphertext))
                props.setProperty(ivKey(key), java.util.Base64.getEncoder().encodeToString(iv))
            }
            writeAll(props)
            true
        } catch (t: Throwable) {
            AppLog.e("SecureVault.putString failed for key=${key.length} chars", t)
            false
        }
    }

    /** Read a value, or null if absent/unreadable. Never throws. */
    fun getString(key: String): String? {
        return try {
            val props = readAll()
            val b64 = props.getProperty(key) ?: return null
            val b64iv = props.getProperty(ivKey(key)) ?: return null
            val secret = getOrCreateKey() ?: return null
            val ciphertext = java.util.Base64.getDecoder().decode(b64)
            val iv = java.util.Base64.getDecoder().decode(b64iv)
            val dec = cipher(Cipher.DECRYPT_MODE, secret, iv) ?: return null
            String(dec.doFinal(ciphertext), Charsets.UTF_8)
        } catch (t: Throwable) {
            // Most likely AEADBadTagException after a keystore reset. Drop the
            // entry so the app recovers by asking the user to reconnect.
            AppLog.w("SecureVault: unreadable value for a key; discarding it")
            runCatching {
                val props = readAll()
                props.remove(key)
                props.remove(ivKey(key))
                writeAll(props)
            }
            null
        }
    }

    fun contains(key: String): Boolean = runCatching { readAll().containsKey(key) }.getOrDefault(false)

    fun remove(key: String): Boolean = putString(key, null)

    /** Erase every secret. Used by "Disconnect account" and "Erase all data". */
    fun clearAll(): Boolean {
        return try {
            if (file.exists()) file.delete()
            true
        } catch (t: Throwable) {
            AppLog.e("SecureVault.clearAll failed", t)
            false
        }
    }

    private fun readAll(): Properties {
        val props = Properties()
        if (!file.exists()) return props
        return try {
            file.inputStream().use { props.load(it) }
            props
        } catch (t: Throwable) {
            AppLog.w("SecureVault: vault file unreadable, starting empty")
            Properties()
        }
    }

    private fun writeAll(props: Properties) {
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            props.store(out, "Varun Studio encrypted store. Values are AES-256-GCM ciphertext.")
            runCatching { out.fd.sync() }
        }
    }

    private fun ivKey(key: String) = "$key.iv"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "varun_studio_vault_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val VAULT_FILE = "varun_secure_vault.properties"

        // Well-known keys. Keeping them as constants avoids typos spreading.
        const val KEY_GITHUB_TOKEN = "github_token"
        const val KEY_GITHUB_LOGIN = "github_login"
        const val KEY_GITHUB_SCOPES = "github_scopes"
        const val KEY_MODEL_DOWNLOAD_URLS = "model_download_urls"
    }
}
