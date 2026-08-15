package com.quickgit.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.quickgit.app.data.models.AuthType
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Stores per-host HTTPS tokens, a single SSH identity, and a GPG secret key,
 * encrypted at rest via Android Keystore-backed crypto.
 *
 * GPG armored keys are often several KB (and can exceed practical limits of
 * EncryptedSharedPreferences on some devices). They are stored in an
 * [EncryptedFile] under the app's private files directory; the passphrase
 * stays in EncryptedSharedPreferences.
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext
    private val masterKey: MasterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        appContext,
        "quickgit_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val gpgKeyFile: File
        get() = File(appContext.filesDir, "gpg_secret_key.asc.enc")

    // ---- HTTPS personal access tokens, keyed by host (e.g. "github.com") ----

    fun saveHttpsToken(host: String, username: String, token: String) {
        val ok = prefs.edit()
            .putString("https_user_$host", username)
            .putString("https_token_$host", token)
            .commit()
        if (!ok) throw IllegalStateException("Failed to persist HTTPS credentials")
    }

    fun getHttpsUsername(host: String): String? = prefs.getString("https_user_$host", null)
    fun getHttpsToken(host: String): String? = prefs.getString("https_token_$host", null)

    fun hasHttpsCredential(host: String): Boolean = getHttpsToken(host) != null

    fun clearHttpsToken(host: String) {
        prefs.edit().remove("https_user_$host").remove("https_token_$host").commit()
    }

    // ---- SSH identity (single key pair imported by the user) ----

    fun saveSshKey(privateKeyPem: String, passphrase: String?) {
        val ok = prefs.edit()
            .putString("ssh_private_key", privateKeyPem)
            .putString("ssh_passphrase", passphrase ?: "")
            .commit()
        if (!ok) throw IllegalStateException("Failed to persist SSH key")
    }

    /** Update passphrase only (keeps existing private key). */
    fun saveSshPassphrase(passphrase: String?) {
        if (!hasSshKey()) throw IllegalStateException("No SSH key stored — paste a private key first")
        val ok = prefs.edit()
            .putString("ssh_passphrase", passphrase ?: "")
            .commit()
        if (!ok) throw IllegalStateException("Failed to persist SSH passphrase")
    }

    fun getSshPrivateKey(): String? = prefs.getString("ssh_private_key", null)
    fun getSshPassphrase(): String? = prefs.getString("ssh_passphrase", null)?.takeIf { it.isNotEmpty() }
    fun hasSshKey(): Boolean = getSshPrivateKey() != null

    fun clearSshKey() {
        prefs.edit().remove("ssh_private_key").remove("ssh_passphrase").commit()
    }

    // ---- GPG / OpenPGP signing key (armored secret key + optional passphrase) ----

    fun saveGpgKey(armoredPrivateKey: String, passphrase: String?) {
        val normalized = normalizeArmoredKey(armoredPrivateKey)
        if (normalized.isBlank()) {
            throw IllegalArgumentException("GPG key is empty after normalization")
        }
        writeEncryptedGpgFile(normalized)
        // Passphrase is small — keep in encrypted prefs. Clear any legacy prefs key blob.
        val ok = prefs.edit()
            .putString("gpg_passphrase", passphrase ?: "")
            .remove("gpg_private_key") // migrate away from prefs storage
            .commit()
        if (!ok) {
            // Key file is already written; passphrase failure is still a problem for encrypted keys
            throw IllegalStateException("GPG key file saved but passphrase could not be persisted")
        }
        AppLog.i(TAG, "GPG key saved (${normalized.length} chars armored)")
    }

    fun saveGpgPassphrase(passphrase: String?) {
        if (!hasGpgKey()) throw IllegalStateException("No GPG key stored — paste an armored secret key first")
        val ok = prefs.edit()
            .putString("gpg_passphrase", passphrase ?: "")
            .commit()
        if (!ok) throw IllegalStateException("Failed to persist GPG passphrase")
    }

    fun getGpgPrivateKey(): String? {
        // Prefer encrypted file (new storage). Fall back to legacy EncryptedSharedPreferences.
        val fromFile = readEncryptedGpgFile()
        if (!fromFile.isNullOrBlank()) return fromFile
        val legacy = prefs.getString("gpg_private_key", null)?.takeIf { it.isNotBlank() }
        if (legacy != null) {
            // One-time migration into the file store
            try {
                writeEncryptedGpgFile(normalizeArmoredKey(legacy))
                prefs.edit().remove("gpg_private_key").commit()
                AppLog.i(TAG, "Migrated GPG key from prefs to encrypted file")
            } catch (e: Exception) {
                AppLog.w(TAG, "GPG key migration failed, using legacy prefs value: ${e.message}")
                return legacy
            }
            return readEncryptedGpgFile() ?: legacy
        }
        return null
    }

    fun getGpgPassphrase(): String? = prefs.getString("gpg_passphrase", null)?.takeIf { it.isNotEmpty() }

    fun hasGpgKey(): Boolean = getGpgPrivateKey() != null

    fun clearGpgKey() {
        try {
            if (gpgKeyFile.exists()) gpgKeyFile.delete()
        } catch (_: Exception) { }
        prefs.edit()
            .remove("gpg_private_key")
            .remove("gpg_passphrase")
            .commit()
    }

    private fun writeEncryptedGpgFile(armored: String) {
        // EncryptedFile refuses to overwrite; delete first.
        if (gpgKeyFile.exists()) {
            if (!gpgKeyFile.delete()) {
                throw IllegalStateException("Could not replace existing GPG key file")
            }
        }
        try {
            val encrypted = EncryptedFile.Builder(
                appContext,
                gpgKeyFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            encrypted.openFileOutput().use { out ->
                out.write(armored.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (e: Exception) {
            // Clean up partial file
            try { if (gpgKeyFile.exists()) gpgKeyFile.delete() } catch (_: Exception) { }
            throw IllegalStateException(
                "Failed to persist GPG key (${armored.length} chars): ${e.message ?: e.javaClass.simpleName}",
                e
            )
        }
        // Verify round-trip so we never report "saved" if we can't read it back
        val roundTrip = readEncryptedGpgFile()
        if (roundTrip.isNullOrBlank() || roundTrip.length < armored.length / 2) {
            throw IllegalStateException("GPG key was written but could not be read back — storage failed")
        }
    }

    private fun readEncryptedGpgFile(): String? {
        if (!gpgKeyFile.exists() || gpgKeyFile.length() == 0L) return null
        return try {
            val encrypted = EncryptedFile.Builder(
                appContext,
                gpgKeyFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            encrypted.openFileInput().use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to read encrypted GPG key file", e)
            null
        }
    }

    /**
     * Normalize pasted armor: strip BOM, unify newlines, ensure header/footer lines exist.
     */
    private fun normalizeArmoredKey(raw: String): String {
        var s = raw
            .replace("\uFEFF", "") // BOM
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        // Some paste sources collapse to a single line — recover if BEGIN/END still present
        if (!s.contains('\n') && s.contains("-----BEGIN") && s.contains("-----END")) {
            s = s
                .replace("-----BEGIN", "\n-----BEGIN")
                .replace("-----END", "\n-----END")
                .replace("-----", "-----\n")
                .replace("\n\n", "\n")
                .trim()
        }
        return s
    }

    fun preferredAuthType(remoteUrl: String): AuthType {
        return when {
            remoteUrl.startsWith("git@") || remoteUrl.startsWith("ssh://") ->
                if (hasSshKey()) AuthType.SSH_KEY else AuthType.NONE
            remoteUrl.startsWith("https://") -> {
                val host = hostOf(remoteUrl)
                if (hasHttpsCredential(host)) AuthType.HTTPS_TOKEN else AuthType.NONE
            }
            else -> AuthType.NONE
        }
    }

    companion object {
        private const val TAG = "CredentialStore"

        fun hostOf(url: String): String {
            return try {
                val stripped = url.substringAfter("://")
                stripped.substringBefore("/").substringAfter("@")
            } catch (e: Exception) {
                url
            }
        }
    }
}
