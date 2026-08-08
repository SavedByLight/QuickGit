package com.quickgit.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.net.URI

/**
 * Secure storage for HTTPS tokens, usernames, SSH keys, and GPG keys.
 *
 * Uses EncryptedSharedPreferences backed by Android Keystore (AES256-GCM).
 * Never stores secrets in plain SharedPreferences or files.
 *
 * Requires:
 *   implementation("androidx.security:security-crypto:1.1.0-alpha06") // or latest
 */
class CredentialStore(context: Context) {

    private val masterKey = MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ── HTTPS token + username ──────────────────────────────────────────────

    fun getHttpsToken(host: String): String? =
        prefs.getString(tokenKey(host), null)?.takeIf { it.isNotBlank() }

    fun hasHttpsCredential(host: String): Boolean =
        !getHttpsToken(host).isNullOrBlank()

    fun getHttpsUsername(host: String): String? =
        prefs.getString(usernameKey(host), null)?.takeIf { it.isNotBlank() }

    /**
     * Saves (or updates) the HTTPS token for [host].
     * Optionally also stores the username used with that token.
     */
    fun saveHttpsToken(host: String, token: String, username: String? = null) {
        val editor = prefs.edit()
            .putString(tokenKey(host), token.trim())
        if (!username.isNullOrBlank()) {
            editor.putString(usernameKey(host), username.trim())
        }
        editor.apply()
    }

    /** Convenience overload used by some call sites. */
    fun setHttpsToken(host: String, token: String?) {
        if (token.isNullOrBlank()) {
            clearHttpsToken(host)
        } else {
            saveHttpsToken(host, token)
        }
    }

    fun clearHttpsToken(host: String) {
        prefs.edit()
            .remove(tokenKey(host))
            .remove(usernameKey(host))
            .apply()
    }

    // ── SSH key + passphrase ────────────────────────────────────────────────

    fun hasSshKey(): Boolean =
        !prefs.getString(KEY_SSH_PRIVATE, null).isNullOrBlank()

    fun getSshPrivateKey(): String? =
        prefs.getString(KEY_SSH_PRIVATE, null)?.takeIf { it.isNotBlank() }

    fun getSshPassphrase(): String? =
        prefs.getString(KEY_SSH_PASSPHRASE, null)?.takeIf { it.isNotBlank() }

    fun saveSshKey(privateKey: String, passphrase: String? = null) {
        val editor = prefs.edit()
            .putString(KEY_SSH_PRIVATE, privateKey.trim())
        if (passphrase != null) {
            editor.putString(KEY_SSH_PASSPHRASE, passphrase)
        } else {
            editor.remove(KEY_SSH_PASSPHRASE)
        }
        editor.apply()
    }

    fun saveSshPassphrase(passphrase: String?) {
        if (passphrase.isNullOrBlank()) {
            prefs.edit().remove(KEY_SSH_PASSPHRASE).apply()
        } else {
            prefs.edit().putString(KEY_SSH_PASSPHRASE, passphrase).apply()
        }
    }

    fun clearSshKey() {
        prefs.edit()
            .remove(KEY_SSH_PRIVATE)
            .remove(KEY_SSH_PASSPHRASE)
            .apply()
    }

    // ── GPG key + passphrase ────────────────────────────────────────────────

    fun hasGpgKey(): Boolean =
        !prefs.getString(KEY_GPG_PRIVATE, null).isNullOrBlank()

    fun getGpgPrivateKey(): String? =
        prefs.getString(KEY_GPG_PRIVATE, null)?.takeIf { it.isNotBlank() }

    fun getGpgPassphrase(): String? =
        prefs.getString(KEY_GPG_PASSPHRASE, null)?.takeIf { it.isNotBlank() }

    fun saveGpgKey(privateKey: String, passphrase: String? = null) {
        val editor = prefs.edit()
            .putString(KEY_GPG_PRIVATE, privateKey.trim())
        if (passphrase != null) {
            editor.putString(KEY_GPG_PASSPHRASE, passphrase)
        } else {
            editor.remove(KEY_GPG_PASSPHRASE)
        }
        editor.apply()
    }

    fun saveGpgPassphrase(passphrase: String?) {
        if (passphrase.isNullOrBlank()) {
            prefs.edit().remove(KEY_GPG_PASSPHRASE).apply()
        } else {
            prefs.edit().putString(KEY_GPG_PASSPHRASE, passphrase).apply()
        }
    }

    fun clearGpgKey() {
        prefs.edit()
            .remove(KEY_GPG_PRIVATE)
            .remove(KEY_GPG_PASSPHRASE)
            .apply()
    }

    // ── Global wipe ─────────────────────────────────────────────────────────

    /** Removes every stored secret. Call on logout. */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "quickgit_secure_creds"

        private const val KEY_PREFIX_TOKEN = "https_token_"
        private const val KEY_PREFIX_USER = "https_user_"

        private const val KEY_SSH_PRIVATE = "ssh_private_key"
        private const val KEY_SSH_PASSPHRASE = "ssh_passphrase"

        private const val KEY_GPG_PRIVATE = "gpg_private_key"
        private const val KEY_GPG_PASSPHRASE = "gpg_passphrase"

        private fun tokenKey(host: String) = KEY_PREFIX_TOKEN + normalizeHost(host)
        private fun usernameKey(host: String) = KEY_PREFIX_USER + normalizeHost(host)

        /** Extracts and normalizes the host from a remote URL or bare host string. */
        fun hostOf(remoteUrlOrHost: String?): String {
            if (remoteUrlOrHost.isNullOrBlank()) return ""
            val raw = remoteUrlOrHost.trim()
            return try {
                val uri = if (raw.contains("://")) URI(raw) else URI("https://$raw")
                (uri.host ?: raw).lowercase().removePrefix("www.")
            } catch (_: Exception) {
                raw.lowercase()
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .removePrefix("git@")
                    .substringBefore("/")
                    .substringBefore(":")
                    .removePrefix("www.")
            }
        }

        private fun normalizeHost(host: String): String =
            hostOf(host).ifBlank { host.lowercase().trim() }
    }
}
