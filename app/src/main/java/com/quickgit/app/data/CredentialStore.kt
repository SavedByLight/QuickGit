package com.quickgit.app.data

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * All sensitive data (HTTPS tokens per host, SSH key + passphrase, GPG secret key +
 * passphrase, commit drafts, etc.) is stored in an [EncryptedSharedPreferences] instance
 * whose master key lives only inside the Android Keystore.
 */
class CredentialStore(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "tink_keyset_pref"

        private val masterKey = MasterKeys.getOrCreate(
            MasterKeys.AES256_GCM_SPEC
        )

        @Volatile
        private var INSTANCE: CredentialStore? = null

        fun getInstance(context: Context): CredentialStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CredentialStore(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun prefsFor(context: Context) = EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        /** Delete everything (for debug / data wipe) */
        fun clear(context: Context) {
            prefsFor(context).edit().clear().apply()
            INSTANCE = null
        }

        /**
         * Extracts a normalized host (e.g. "github.com") from a git remote URL, whether it's
         * an https:// URL, an ssh:// URL, or scp-like syntax (git@host:owner/repo.git).
         */
        fun hostOf(url: String): String {
            val trimmed = url.trim()
            val host = try {
                when {
                    trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
                        trimmed.startsWith("ssh://") -> Uri.parse(trimmed).host
                    trimmed.contains("@") && trimmed.contains(":") ->
                        trimmed.substringAfter("@").substringBefore(":").substringBefore("/")
                    else -> null
                }
            } catch (e: Exception) {
                null
            } ?: trimmed
            return host.lowercase()
        }
    }

    private val prefs get() = prefsFor(context)

    // ==================== HTTPS credentials (per host) ====================
    fun hasHttpsCredential(host: String): Boolean =
        !prefs.getString("https_token_${host.lowercase()}", null).isNullOrBlank()

    fun getHttpsUsername(host: String): String? =
        prefs.getString("https_user_${host.lowercase()}", null)

    fun getHttpsToken(host: String): String? =
        prefs.getString("https_token_${host.lowercase()}", null)

    fun saveHttpsToken(host: String, username: String, token: String) {
        prefs.edit()
            .putString("https_user_${host.lowercase()}", username)
            .putString("https_token_${host.lowercase()}", token)
            .apply()
    }

    fun clearHttpsToken(host: String) {
        prefs.edit()
            .remove("https_user_${host.lowercase()}")
            .remove("https_token_${host.lowercase()}")
            .apply()
    }

    // ==================== SSH key ====================
    fun hasSshKey(): Boolean = !prefs.getString("ssh_private_key", null).isNullOrBlank()

    fun getSshPrivateKey(): String? = prefs.getString("ssh_private_key", null)

    fun getSshPassphrase(): String? = prefs.getString("ssh_passphrase", null)

    fun saveSshKey(key: String, passphrase: String?) {
        val editor = prefs.edit().putString("ssh_private_key", key)
        if (passphrase.isNullOrEmpty()) editor.remove("ssh_passphrase") else editor.putString("ssh_passphrase", passphrase)
        editor.apply()
    }

    fun saveSshPassphrase(passphrase: String?) {
        val editor = prefs.edit()
        if (passphrase.isNullOrEmpty()) editor.remove("ssh_passphrase") else editor.putString("ssh_passphrase", passphrase)
        editor.apply()
    }

    fun clearSshKey() {
        prefs.edit().remove("ssh_private_key").remove("ssh_passphrase").apply()
    }

    // ==================== GPG key ====================
    fun hasGpgKey(): Boolean = !prefs.getString("gpg_secret_key", null).isNullOrBlank()

    fun getGpgPrivateKey(): String? = prefs.getString("gpg_secret_key", null)

    fun getGpgPassphrase(): String? = prefs.getString("gpg_passphrase", null)

    fun saveGpgKey(key: String, passphrase: String?) {
        val editor = prefs.edit().putString("gpg_secret_key", key)
        if (passphrase.isNullOrEmpty()) editor.remove("gpg_passphrase") else editor.putString("gpg_passphrase", passphrase)
        editor.apply()
    }

    fun saveGpgPassphrase(passphrase: String?) {
        val editor = prefs.edit()
        if (passphrase.isNullOrEmpty()) editor.remove("gpg_passphrase") else editor.putString("gpg_passphrase", passphrase)
        editor.apply()
    }

    fun clearGpgKey() {
        prefs.edit().remove("gpg_secret_key").remove("gpg_passphrase").apply()
    }

    // ==================== Commit draft (per-repo) ====================
    fun getCommitDraft(repoId: String): String? = prefs.getString("draft_$repoId", null)

    fun saveCommitDraft(repoId: String, message: String) {
        prefs.edit().putString("draft_$repoId", message).apply()
    }

    // ==================== Clear ====================
    fun clear() {
        prefs.edit().clear().apply()
        INSTANCE = null
    }
}
