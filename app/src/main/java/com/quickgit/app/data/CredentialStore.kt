package com.quickgit.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File

/**
 * All sensitive data (GPG secret key, GitHub token, commit draft, etc.)
 * is stored using Tink + Android Keystore (no plaintext backup).
 * The master key lives only inside the Android Keystore.
 */
class CredentialStore private constructor(private val context: Context) {

    companion object {
        private const val MASTER_KEY_ALIAS = "QuickGitMasterKeyV2"

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

        /** Delete everything (for debug / data wipe) */
        fun clear(context: Context) {
            val prefs = EncryptedSharedPreferences.create(
                "tink_keyset_pref",
                masterKey,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            prefs.edit().clear().apply()
            INSTANCE = null
        }
    }

    // ==================== GPG ====================
    fun getGpgSecretKey(): String? {
        val keysetHandle = AndroidKeysetManager
            .withKeysetUri("android-keystore://$MASTER_KEY_ALIAS")
            .build()
            .keysetHandle

        val aead = keysetHandle.getPrimitive(Aead::class.java)
        return aead.decrypt("gpg_secret_key".toByteArray(), null)
            ?.decodeToString()
    }

    fun saveGpgSecretKey(key: String) {
        val keysetHandle = AndroidKeysetManager
            .withKeysetUri("android-keystore://$MASTER_KEY_ALIAS")
            .build()
            .keysetHandle

        val aead = keysetHandle.getPrimitive(Aead::class.java)
        aead.encrypt(key.toByteArray(), null)

        // Persist keyset (required by Tink)
        val prefs = EncryptedSharedPreferences.create(
            "tink_keyset_pref",
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.edit().putString("gpg_secret_key", key).apply()
    }

    // ==================== GitHub token ====================
    fun getGitHubToken(): String? {
        val prefs = EncryptedSharedPreferences.create(
            "tink_keyset_pref",
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        return prefs.getString("github_token", null)
    }

    fun saveGitHubToken(token: String) {
        val prefs = EncryptedSharedPreferences.create(
            "tink_keyset_pref",
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.edit().putString("github_token", token).apply()
    }

    // ==================== Commit draft (per-repo) ====================
    fun getCommitDraft(repoId: String): String? {
        val prefs = EncryptedSharedPreferences.create(
            "tink_keyset_pref",
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        return prefs.getString("draft_$repoId", null)
    }

    fun saveCommitDraft(repoId: String, message: String) {
        val prefs = EncryptedSharedPreferences.create(
            "tink_keyset_pref",
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.edit().putString("draft_$repoId", message).apply()
    }

    // ==================== Clear ====================
    fun clear() {
        val prefs = EncryptedSharedPreferences.create(
            "tink_keyset_pref",
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.edit().clear().apply()
        INSTANCE = null
    }
}