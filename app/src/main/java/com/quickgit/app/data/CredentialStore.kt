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
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            appContext,
            "quickgit_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

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
        val ok = prefs.edit()
            .putString("gpg_private_key", armoredPrivateKey)
            .putString("gpg_passphrase", passphrase ?: "")
            .commit()
        if (!ok) throw IllegalStateException("Failed to persist GPG key")
    }

    fun saveGpgPassphrase(passphrase: String?) {
        if (!hasGpgKey()) throw IllegalStateException("No GPG key stored — paste an armored secret key first")
        val ok = prefs.edit()
            .putString("gpg_passphrase", passphrase ?: "")
            .commit()
        if (!ok) throw IllegalStateException("Failed to persist GPG passphrase")
    }

    fun getGpgPrivateKey(): String? = prefs.getString("gpg_private_key", null)
    fun getGpgPassphrase(): String? = prefs.getString("gpg_passphrase", null)?.takeIf { it.isNotEmpty() }
    fun hasGpgKey(): Boolean = getGpgPrivateKey() != null

    fun clearGpgKey() {
        prefs.edit().remove("gpg_private_key").remove("gpg_passphrase").commit()
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
