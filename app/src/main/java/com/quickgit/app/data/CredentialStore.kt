package com.quickgit.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.quickgit.app.data.models.AuthType

/**
 * Stores per-host HTTPS tokens and a single SSH identity (private key + optional passphrase),
 * encrypted at rest via Android Keystore-backed EncryptedSharedPreferences.
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "quickgit_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ---- HTTPS personal access tokens, keyed by host (e.g. "github.com") ----

    fun saveHttpsToken(host: String, username: String, token: String) {
        prefs.edit()
            .putString("https_user_$host", username)
            .putString("https_token_$host", token)
            .apply()
    }

    fun getHttpsUsername(host: String): String? = prefs.getString("https_user_$host", null)
    fun getHttpsToken(host: String): String? = prefs.getString("https_token_$host", null)

    fun hasHttpsCredential(host: String): Boolean = getHttpsToken(host) != null

    fun clearHttpsToken(host: String) {
        prefs.edit().remove("https_user_$host").remove("https_token_$host").apply()
    }

    // ---- SSH identity (single key pair imported by the user) ----

    fun saveSshKey(privateKeyPem: String, passphrase: String?) {
        prefs.edit()
            .putString("ssh_private_key", privateKeyPem)
            .putString("ssh_passphrase", passphrase ?: "")
            .apply()
    }

    fun getSshPrivateKey(): String? = prefs.getString("ssh_private_key", null)
    fun getSshPassphrase(): String? = prefs.getString("ssh_passphrase", null)
    fun hasSshKey(): Boolean = getSshPrivateKey() != null

    fun clearSshKey() {
        prefs.edit().remove("ssh_private_key").remove("ssh_passphrase").apply()
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
