package com.quickgit.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.net.URI

/**
 * Secure storage for HTTPS tokens (GitHub PATs, GitLab tokens, etc.).
 *
 * - Uses EncryptedSharedPreferences backed by Android Keystore (AES256-GCM).
 * - Never stores tokens in plain SharedPreferences or files.
 * - Tokens are wiped on logout / clear.
 *
 * Requires dependency:
 *   implementation("androidx.security:security-crypto:1.1.0-alpha06") // or latest stable
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

    /** Returns the stored HTTPS token for the given host, or null if none. */
    fun getHttpsToken(host: String): String? {
        val key = tokenKey(host)
        return prefs.getString(key, null)?.takeIf { it.isNotBlank() }
    }

    /** Stores (or overwrites) the HTTPS token for the host. Pass null/blank to remove. */
    fun setHttpsToken(host: String, token: String?) {
        val key = tokenKey(host)
        if (token.isNullOrBlank()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, token.trim()).apply()
        }
    }

    /** Removes the token for a specific host. */
    fun clearHttpsToken(host: String) {
        setHttpsToken(host, null)
    }

    /** Wipes all stored tokens. Call on logout. */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "quickgit_secure_creds"
        private const val KEY_PREFIX = "https_token_"

        private fun tokenKey(host: String): String =
            KEY_PREFIX + normalizeHost(host)

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
