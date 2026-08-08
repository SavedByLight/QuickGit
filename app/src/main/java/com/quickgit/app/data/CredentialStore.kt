package com.quickgit.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.net.URI

/**
 * Secure storage for HTTPS tokens, usernames, SSH keys, and GPG keys.
 *
 * Tries EncryptedSharedPreferences (Android Keystore). If that fails on the
 * device (known Keystore bugs on some OEMs / emulators), falls back to plain
 * SharedPreferences so the app still works — credentials can be saved and the
 * UI will not get stuck.
 *
 * Requires:
 *   implementation("androidx.security:security-crypto:1.1.0-alpha06")
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences = createPrefs(appContext)

    private val encrypted: Boolean =
        prefs.javaClass.name.contains("Encrypted", ignoreCase = true)

    init {
        if (!encrypted) {
            Log.w(TAG, "Using plaintext SharedPreferences fallback — Keystore/EncryptedSharedPreferences unavailable")
        }
    }

    // ── HTTPS token + username ──────────────────────────────────────────────

    fun getHttpsToken(host: String): String? =
        prefs.getString(tokenKey(host), null)?.takeIf { it.isNotBlank() }

    fun hasHttpsCredential(host: String): Boolean =
        !getHttpsToken(host).isNullOrBlank()

    fun getHttpsUsername(host: String): String? =
        prefs.getString(usernameKey(host), null)?.takeIf { it.isNotBlank() }

    /**
     * Saves (or updates) the HTTPS token for [host].
     * Uses commit() so the write is finished before the credentials screen continues.
     */
    fun saveHttpsToken(host: String, token: String, username: String? = null): Boolean {
        val h = normalizeHost(host)
        // Strip whitespace and accidental "Bearer "/"token " prefixes from paste.
        val clean = token.trim()
            .removePrefix("Bearer ").removePrefix("bearer ")
            .removePrefix("token ").removePrefix("Token ")
            .trim()
        if (h.isBlank() || clean.isBlank()) {
            Log.w(TAG, "saveHttpsToken ignored: blank host or token")
            return false
        }
        val editor = prefs.edit()
            .putString(tokenKey(h), clean)
        if (!username.isNullOrBlank()) {
            editor.putString(usernameKey(h), username.trim())
        }
        val ok = editor.commit() // synchronous — critical for auth UI flow
        if (!ok) Log.e(TAG, "saveHttpsToken commit failed for host=$h")
        else Log.i(TAG, "saveHttpsToken ok host=$h len=${clean.length}")
        return ok
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
        val h = normalizeHost(host)
        prefs.edit()
            .remove(tokenKey(h))
            .remove(usernameKey(h))
            .commit()
    }

    // ── SSH key + passphrase ────────────────────────────────────────────────

    fun hasSshKey(): Boolean =
        !prefs.getString(KEY_SSH_PRIVATE, null).isNullOrBlank()

    fun getSshPrivateKey(): String? =
        prefs.getString(KEY_SSH_PRIVATE, null)?.takeIf { it.isNotBlank() }

    fun getSshPassphrase(): String? =
        prefs.getString(KEY_SSH_PASSPHRASE, null)?.takeIf { it.isNotBlank() }

    fun saveSshKey(privateKey: String, passphrase: String? = null): Boolean {
        if (privateKey.isBlank()) return false
        val editor = prefs.edit()
            .putString(KEY_SSH_PRIVATE, privateKey.trim())
        if (passphrase != null) {
            editor.putString(KEY_SSH_PASSPHRASE, passphrase)
        } else {
            editor.remove(KEY_SSH_PASSPHRASE)
        }
        return editor.commit()
    }

    fun saveSshPassphrase(passphrase: String?) {
        if (passphrase.isNullOrBlank()) {
            prefs.edit().remove(KEY_SSH_PASSPHRASE).commit()
        } else {
            prefs.edit().putString(KEY_SSH_PASSPHRASE, passphrase).commit()
        }
    }

    fun clearSshKey() {
        prefs.edit()
            .remove(KEY_SSH_PRIVATE)
            .remove(KEY_SSH_PASSPHRASE)
            .commit()
    }

    // ── GPG key + passphrase ────────────────────────────────────────────────

    fun hasGpgKey(): Boolean =
        !prefs.getString(KEY_GPG_PRIVATE, null).isNullOrBlank()

    fun getGpgPrivateKey(): String? =
        prefs.getString(KEY_GPG_PRIVATE, null)?.takeIf { it.isNotBlank() }

    fun getGpgPassphrase(): String? =
        prefs.getString(KEY_GPG_PASSPHRASE, null)?.takeIf { it.isNotBlank() }

    fun saveGpgKey(privateKey: String, passphrase: String? = null): Boolean {
        if (privateKey.isBlank()) return false
        val editor = prefs.edit()
            .putString(KEY_GPG_PRIVATE, privateKey.trim())
        if (passphrase != null) {
            editor.putString(KEY_GPG_PASSPHRASE, passphrase)
        } else {
            editor.remove(KEY_GPG_PASSPHRASE)
        }
        return editor.commit()
    }

    fun saveGpgPassphrase(passphrase: String?) {
        if (passphrase.isNullOrBlank()) {
            prefs.edit().remove(KEY_GPG_PASSPHRASE).commit()
        } else {
            prefs.edit().putString(KEY_GPG_PASSPHRASE, passphrase).commit()
        }
    }

    fun clearGpgKey() {
        prefs.edit()
            .remove(KEY_GPG_PRIVATE)
            .remove(KEY_GPG_PASSPHRASE)
            .commit()
    }

    // ── Global wipe ─────────────────────────────────────────────────────────

    /** Removes every stored secret. Call on logout. */
    fun clearAll() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val TAG = "CredentialStore"
        private const val PREFS_NAME = "quickgit_secure_creds"
        private const val PREFS_FALLBACK = "quickgit_creds_fallback"

        private const val KEY_PREFIX_TOKEN = "https_token_"
        private const val KEY_PREFIX_USER = "https_user_"

        private const val KEY_SSH_PRIVATE = "ssh_private_key"
        private const val KEY_SSH_PASSPHRASE = "ssh_passphrase"

        private const val KEY_GPG_PRIVATE = "gpg_private_key"
        private const val KEY_GPG_PASSPHRASE = "gpg_passphrase"

        private fun tokenKey(host: String) = KEY_PREFIX_TOKEN + normalizeHost(host)
        private fun usernameKey(host: String) = KEY_PREFIX_USER + normalizeHost(host)

        /**
         * Prefer EncryptedSharedPreferences. On failure (Keystore bugs, missing
         * dependency, etc.) fall back to ordinary SharedPreferences so the
         * credentials screen never hangs.
         */
        private fun createPrefs(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (t: Throwable) {
                Log.e(TAG, "EncryptedSharedPreferences unavailable, using fallback", t)
                // Wipe a half-written encrypted file so a later retry can succeed
                try {
                    context.deleteSharedPreferences(PREFS_NAME)
                } catch (_: Throwable) { /* ignore */ }
                context.getSharedPreferences(PREFS_FALLBACK, Context.MODE_PRIVATE)
            }
        }

        /** Extracts and normalizes the host from a remote URL or bare host string. */
        fun hostOf(remoteUrlOrHost: String?): String {
            if (remoteUrlOrHost.isNullOrBlank()) return ""
            val raw = remoteUrlOrHost.trim()
            return try {
                val uri = if ("://" in raw) URI(raw) else URI("https://$raw")
                (uri.host ?: raw)
                    .lowercase()
                    .removePrefix("www.")
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
