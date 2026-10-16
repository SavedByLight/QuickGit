package com.quickgit.desktop.data

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop equivalent of the Android CredentialStore.
 * Stores sensitive data (PATs, SSH keys, GPG keys) encrypted at rest using
 * AES-GCM with a key derived from a machine-local master password file.
 *
 * Location: ~/.config/quickgit/credentials.enc
 */
class DesktopCredentialStore {

    companion object {
        private const val CONFIG_DIR = ".config/quickgit"
        private const val CREDS_FILE = "credentials.enc"
        private const val MASTER_FILE = "master.key"
        private const val GCM_TAG_LENGTH = 128
        private const val IV_LENGTH = 12
        private const val ITERATIONS = 100_000
        private const val KEY_LENGTH = 256
    }

    private val configDir: File
    private val credsFile: File
    private val masterFile: File
    private val cache = ConcurrentHashMap<String, String>()
    private val lock = Any()

    init {
        val home = System.getProperty("user.home")
        configDir = File(home, CONFIG_DIR).also { it.mkdirs() }
        credsFile = File(configDir, CREDS_FILE)
        masterFile = File(configDir, MASTER_FILE)
        load()
    }

    // ---------- Public API (mirrors Android CredentialStore) ----------

    fun getHttpsToken(host: String): String? = get("https_token_${host.lowercase()}")

    fun setHttpsToken(host: String, token: String?) {
        val h = host.lowercase()
        if (token.isNullOrBlank()) remove("https_token_$h")
        else put("https_token_$h", token)
    }

    fun getHttpsUsername(host: String): String? = get("https_user_${host.lowercase()}")

    fun setHttpsUsername(host: String, username: String?) {
        val h = host.lowercase()
        if (username.isNullOrBlank()) remove("https_user_$h")
        else put("https_user_$h", username)
    }

    /** Save host + username + token together (matches Android CredentialStore). */
    fun saveHttpsCredential(host: String, username: String, token: String) {
        setHttpsUsername(host, username)
        setHttpsToken(host, token)
    }

    fun getPreferredGerritHost(): String? = get("gerrit_preferred_host")
    fun setPreferredGerritHost(host: String?) {
        if (host.isNullOrBlank()) remove("gerrit_preferred_host")
        else put("gerrit_preferred_host", host.trim().lowercase())
    }

    fun getSshPrivateKey(): String? = get("ssh_private_key")
    fun setSshPrivateKey(key: String?) {
        if (key.isNullOrBlank()) remove("ssh_private_key")
        else put("ssh_private_key", key)
    }

    fun getSshPassphrase(): String? = get("ssh_passphrase")
    fun setSshPassphrase(pass: String?) {
        if (pass.isNullOrBlank()) remove("ssh_passphrase")
        else put("ssh_passphrase", pass)
    }

    fun getGpgSecretKey(): String? = get("gpg_secret_key")
    fun setGpgSecretKey(key: String?) {
        if (key.isNullOrBlank()) remove("gpg_secret_key")
        else put("gpg_secret_key", key)
    }

    fun getGpgPassphrase(): String? = get("gpg_passphrase")
    fun setGpgPassphrase(pass: String?) {
        if (pass.isNullOrBlank()) remove("gpg_passphrase")
        else put("gpg_passphrase", pass)
    }

    fun getGithubToken(): String? = get("github_token")
    fun setGithubToken(token: String?) {
        if (token.isNullOrBlank()) remove("github_token")
        else put("github_token", token)
    }

    fun getGitlabToken(): String? = get("gitlab_token")
    fun setGitlabToken(token: String?) {
        if (token.isNullOrBlank()) remove("gitlab_token")
        else put("gitlab_token", token)
    }

    /** Preferred GitLab host for browse/API (gitlab.com or self-hosted). */
    fun getPreferredGitlabHost(): String? = get("gitlab_preferred_host")?.takeIf { it.isNotBlank() }
    fun setPreferredGitlabHost(host: String?) {
        if (host.isNullOrBlank()) remove("gitlab_preferred_host")
        else put("gitlab_preferred_host", host.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/'))
    }

    fun getAuthorName(): String? = get("author_name")
    fun setAuthorName(name: String?) {
        if (name.isNullOrBlank()) remove("author_name")
        else put("author_name", name)
    }

    fun getAuthorEmail(): String? = get("author_email")
    fun setAuthorEmail(email: String?) {
        if (email.isNullOrBlank()) remove("author_email")
        else put("author_email", email)
    }

    fun isGpgSignEnabled(): Boolean = get("gpg_sign") == "true"
    fun setGpgSignEnabled(enabled: Boolean) = put("gpg_sign", enabled.toString())

    // ---------- Multi-account (parity with Android AccountManager) ----------

    enum class AccountProvider { GITHUB, GITLAB, GERRIT }

    data class ManagedAccount(
        val id: String,
        val provider: AccountProvider,
        val host: String,
        val username: String,
        val displayName: String? = null
    ) {
        val label: String
            get() = when {
                !displayName.isNullOrBlank() -> "$displayName (@$username)"
                else -> "@$username"
            }

        fun providerLabel(): String = when (provider) {
            AccountProvider.GITHUB -> "GitHub"
            AccountProvider.GITLAB -> if (host.equals("gitlab.com", true)) "GitLab" else "GitLab ($host)"
            AccountProvider.GERRIT -> "Gerrit ($host)"
        }
    }

    fun listManagedAccounts(provider: AccountProvider? = null): List<ManagedAccount> {
        val raw = get("managed_accounts_v1") ?: return emptyList()
        val all = raw.split("\n").mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4) return@mapNotNull null
            val prov = runCatching { AccountProvider.valueOf(p[1]) }.getOrNull() ?: return@mapNotNull null
            ManagedAccount(
                id = p[0],
                provider = prov,
                host = p[2],
                username = p[3],
                displayName = p.getOrNull(4)?.takeIf { it.isNotBlank() }
            )
        }
        // Dedup by provider+host+username
        val seen = linkedSetOf<String>()
        val deduped = all.filter {
            val key = "${it.provider}|${it.host.lowercase()}|${it.username.lowercase()}"
            seen.add(key)
        }
        return if (provider == null) deduped else deduped.filter { it.provider == provider }
    }

    fun getAccountToken(accountId: String): String? = get("acct_token_$accountId")

    fun addOrUpdateManagedAccount(
        provider: AccountProvider,
        host: String,
        username: String,
        token: String,
        displayName: String? = null,
        existingId: String? = null
    ): ManagedAccount {
        val normalizedHost = host.trim()
            .removePrefix("https://").removePrefix("http://").trimEnd('/').lowercase()
        val accounts = listManagedAccounts().toMutableList()
        val id = existingId
            ?: accounts.find {
                it.provider == provider &&
                    it.host.equals(normalizedHost, true) &&
                    it.username.equals(username, true)
            }?.id
            ?: UUID.randomUUID().toString()

        put("acct_token_$id", token)
        val account = ManagedAccount(id, provider, normalizedHost, username, displayName)
        val idx = accounts.indexOfFirst { it.id == id }
        if (idx >= 0) accounts[idx] = account else accounts.add(account)
        saveManagedAccountsList(accounts)
        // If first for provider or same as active, make active
        val activeId = getActiveAccountId(provider)
        if (activeId == null || activeId == id) {
            setActiveAccount(id)
        }
        return account
    }

    fun removeManagedAccount(accountId: String) {
        val accounts = listManagedAccounts().toMutableList()
        val removed = accounts.find { it.id == accountId } ?: return
        accounts.removeAll { it.id == accountId }
        remove("acct_token_$accountId")
        saveManagedAccountsList(accounts)
        if (getActiveAccountId(removed.provider) == accountId) {
            val next = accounts.firstOrNull { it.provider == removed.provider }
            if (next != null) setActiveAccount(next.id)
            else {
                remove("active_account_${removed.provider.name}")
                // Clear host-level token for that provider's common host
                when (removed.provider) {
                    AccountProvider.GITHUB -> {
                        setGithubToken(null)
                        setHttpsToken("github.com", null)
                    }
                    AccountProvider.GITLAB -> {
                        setGitlabToken(null)
                        setHttpsToken(removed.host, null)
                    }
                    AccountProvider.GERRIT -> setHttpsToken(removed.host, null)
                }
            }
        }
    }

    fun getActiveAccountId(provider: AccountProvider): String? =
        get("active_account_${provider.name}")

    fun getActiveAccount(provider: AccountProvider): ManagedAccount? {
        val id = getActiveAccountId(provider)
        val all = listManagedAccounts(provider)
        if (id != null) all.find { it.id == id }?.let { return it }
        return all.firstOrNull()
    }

    fun setActiveAccount(accountId: String) {
        val account = listManagedAccounts().find { it.id == accountId } ?: return
        put("active_account_${account.provider.name}", accountId)
        val token = getAccountToken(accountId) ?: return
        // Sync host-level + legacy single-token keys used by the rest of the desktop app
        when (account.provider) {
            AccountProvider.GITHUB -> {
                setGithubToken(token)
                saveHttpsCredential(account.host.ifBlank { "github.com" }, account.username, token)
            }
            AccountProvider.GITLAB -> {
                setGitlabToken(token)
                setPreferredGitlabHost(account.host)
                saveHttpsCredential(account.host, account.username, token)
            }
            AccountProvider.GERRIT -> {
                setPreferredGerritHost(account.host)
                saveHttpsCredential(account.host, account.username, token)
            }
        }
    }

    private fun saveManagedAccountsList(accounts: List<ManagedAccount>) {
        if (accounts.isEmpty()) {
            remove("managed_accounts_v1")
            return
        }
        val encoded = accounts.joinToString("\n") { a ->
            listOf(a.id, a.provider.name, a.host, a.username, a.displayName.orEmpty()).joinToString("\t")
        }
        put("managed_accounts_v1", encoded)
    }

    fun clearAll() {
        synchronized(lock) {
            cache.clear()
            if (credsFile.exists()) credsFile.delete()
        }
    }

    // ---------- Host helpers ----------

    fun hostFromRemoteUrl(url: String): String? {
        return try {
            when {
                url.startsWith("https://") || url.startsWith("http://") -> {
                    val withoutScheme = url.substringAfter("://")
                    withoutScheme.substringBefore("/").substringBefore(":")
                }
                url.startsWith("git@") -> {
                    url.substringAfter("git@").substringBefore(":")
                }
                url.startsWith("ssh://") -> {
                    val withoutScheme = url.substringAfter("://")
                    withoutScheme.substringBefore("/").substringBefore(":")
                }
                else -> null
            }?.lowercase()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- Internal encrypted storage ----------

    private fun put(key: String, value: String) {
        synchronized(lock) {
            cache[key] = value
            save()
        }
    }

    private fun get(key: String): String? = synchronized(lock) { cache[key] }

    private fun remove(key: String) {
        synchronized(lock) {
            cache.remove(key)
            save()
        }
    }

    private fun load() {
        if (!credsFile.exists()) return
        try {
            val encrypted = credsFile.readBytes()
            val decrypted = decrypt(encrypted)
            val props = Properties()
            props.load(decrypted.inputStream())
            props.forEach { k, v ->
                cache[k.toString()] = v.toString()
            }
        } catch (e: Exception) {
            System.err.println("Failed to load credentials (will start empty): ${e.message}")
            cache.clear()
        }
    }

    private fun save() {
        try {
            val props = Properties()
            cache.forEach { (k, v) -> props[k] = v }
            val baos = java.io.ByteArrayOutputStream()
            props.store(baos, "QuickGit credentials – do not edit")
            val encrypted = encrypt(baos.toByteArray())
            credsFile.writeBytes(encrypted)
        } catch (e: Exception) {
            System.err.println("Failed to save credentials: ${e.message}")
        }
    }

    private fun masterKey(): ByteArray {
        if (!masterFile.exists()) {
            val key = ByteArray(32)
            SecureRandom().nextBytes(key)
            masterFile.writeBytes(key)
            // Restrict permissions on Unix
            try {
                masterFile.setReadable(false, false)
                masterFile.setReadable(true, true)
                masterFile.setWritable(false, false)
                masterFile.setWritable(true, true)
            } catch (_: Exception) { }
        }
        return masterFile.readBytes()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val key = deriveKey(masterKey())
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val ciphertext = cipher.doFinal(plain)
        return iv + ciphertext
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val key = deriveKey(masterKey())
        val iv = data.copyOfRange(0, IV_LENGTH)
        val ciphertext = data.copyOfRange(IV_LENGTH, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(master: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(
            Base64.getEncoder().encodeToString(master).toCharArray(),
            "QuickGitSalt".toByteArray(StandardCharsets.UTF_8),
            ITERATIONS,
            KEY_LENGTH
        )
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }
}
