package com.quickgit.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.aead.AeadKeyTemplates
import java.io.File
import java.security.GeneralSecurityException

/**
 * Stores all sensitive data (GPG secret key, GitHub token, commit draft, etc.)
 * using Google Tink + Android Keystore (no plaintext backup).
 *
 * On first run it creates a master key in the Android Keystore and an encrypted
 * keyset. The master key is NEVER backed up to SharedPreferences.
 */
class CredentialStore private constructor(private val context: Context) {

    companion object {
        private const val MASTER_KEY_ALIAS = "QuickGitMasterKeyV2"

        // Tink master key (we use it only for AndroidKeysetManager)
        private val masterKey = MasterKeys.getOrCreate(
            MasterKeys.AES256_GCM_SPEC
        )

        /** Singleton instance (per application context) */
        @Volatile
        private var INSTANCE: CredentialStore? = null

        fun getInstance(context: Context): CredentialStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CredentialStore(context.applicationContext).also { INSTANCE = it }
            }
        }

        /** Delete everything (including the Tink keyset) – use only for debug / data wipe */
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

    // ------------------------------------------------------------------
    // Public API (same as before, now backed by Android Keystore)
    // ------------------------------------------------------------------

    /** Read the GPG secret key (armored) from disk */
    fun getGpgSecretKey(): String? {
        val keysetHandle = AndroidKeysetManager
            .withKeysetUri("android-keystore://$MASTER_KEY_ALIAS")
            .build()
            .keysetHandle

        val aead = keysetHandle.getPrimitive(Aead::class.java)
        return aead.decrypt("gpg_secret_key".toByteArray(), null)
            ?.decodeToString()
    }

    /** Save a new GPG secret key (armored) */
    fun saveGpgSecretKey(key: String) {
        val keysetHandle = AndroidKeysetManager
            .withKeysetUri("android-keystore://$MASTER_KEY_ALIAS")
            .build()
            .keysetHandle

        val aead = keysetHandle.getPrimitive(Aead::class.java)
        aead.encrypt(key.toByteArray(), null)

        // Persist the updated keyset to SharedPreferences (required for Tink)
        val prefs = EncryptedSharedPreferences.create(
            "tink_keyset_pref",
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.edit().putString("gpg_secret_key", key).apply()
    }

    // ------------------------------------------------------------------
    // GitHub token (same pattern)
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Commit draft (per-repo)
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Clear (for testing / debug)
    // ------------------------------------------------------------------

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
