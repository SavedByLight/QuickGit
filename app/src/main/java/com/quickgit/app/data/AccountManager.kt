package com.quickgit.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Unified multi-account manager for GitHub, GitLab, and Gerrit.
 *
 * Stores an arbitrary number of accounts per provider. Tokens stay in the
 * encrypted [CredentialStore]. The "active" account per provider is the one
 * used for API calls and for syncing the host-level HTTPS credential that
 * JGit uses for push/pull.
 */
class AccountManager(private val credentialStore: CredentialStore) {

    enum class Provider { GITHUB, GITLAB, GERRIT }

    data class Account(
        val id: String,
        val provider: Provider,
        val host: String,
        val username: String,
        val displayName: String? = null,
        val email: String? = null,
        val avatarUrl: String? = null,
        val profileUrl: String? = null
    ) {
        val label: String
            get() = when {
                displayName != null && displayName.isNotBlank() -> "$displayName (@$username)"
                else -> "@$username"
            }

        val shortLabel: String get() = username

        fun providerLabel(): String = when (provider) {
            Provider.GITHUB -> "GitHub"
            Provider.GITLAB -> if (host.equals("gitlab.com", true)) "GitLab" else "GitLab ($host)"
            Provider.GERRIT -> "Gerrit ($host)"
        }
    }

    companion object {
        private const val KEY_ACCOUNTS = "managed_accounts_v1"
        private const val KEY_ACTIVE_PREFIX = "active_account_"
        private const val TAG = "AccountManager"
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    fun listAccounts(provider: Provider? = null): List<Account> {
        val all = loadAll()
        return if (provider == null) all else all.filter { it.provider == provider }
    }

    fun getAccount(id: String): Account? = loadAll().find { it.id == id }

    fun getActiveAccount(provider: Provider): Account? {
        val activeId = credentialStore.getString("${KEY_ACTIVE_PREFIX}${provider.name}")
        val all = listAccounts(provider)
        if (activeId != null) {
            all.find { it.id == activeId }?.let { return it }
        }
        return all.firstOrNull()
    }

    fun setActiveAccount(accountId: String) {
        val account = getAccount(accountId) ?: return
        credentialStore.putString("${KEY_ACTIVE_PREFIX}${account.provider.name}", accountId)
        // Sync host-level token so JGit / existing single-host APIs use this account
        syncHostCredential(account)
        AppLog.i(TAG, "active account set: ${account.provider} ${account.username}@${account.host}")
    }

    fun addOrUpdateAccount(
        provider: Provider,
        host: String,
        username: String,
        token: String,
        displayName: String? = null,
        email: String? = null,
        avatarUrl: String? = null,
        profileUrl: String? = null,
        existingId: String? = null
    ): Account {
        val normalizedHost = host.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
            .lowercase()

        val accounts = loadAll().toMutableList()
        val id = existingId ?: accounts.find {
            it.provider == provider &&
                it.host.equals(normalizedHost, true) &&
                it.username.equals(username, true)
        }?.id ?: UUID.randomUUID().toString()

        // Store token under a per-account key AND under the host key for the active one
        credentialStore.saveAccountToken(id, username, token)

        val account = Account(
            id = id,
            provider = provider,
            host = normalizedHost,
            username = username,
            displayName = displayName,
            email = email,
            avatarUrl = avatarUrl,
            profileUrl = profileUrl
        )

        val idx = accounts.indexOfFirst { it.id == id }
        if (idx >= 0) accounts[idx] = account else accounts.add(account)
        saveAll(accounts)

        // If this is the first/only account for the provider, make it active
        if (getActiveAccount(provider)?.id == null || getActiveAccount(provider)?.id == id) {
            setActiveAccount(id)
        } else {
            // Still sync if this is already active
            if (getActiveAccount(provider)?.id == id) syncHostCredential(account)
        }

        AppLog.i(TAG, "saved account: ${provider} ${username}@${normalizedHost} id=$id")
        return account
    }

    fun removeAccount(accountId: String) {
        val accounts = loadAll().toMutableList()
        val removed = accounts.find { it.id == accountId } ?: return
        accounts.removeAll { it.id == accountId }
        credentialStore.clearAccountToken(accountId)
        saveAll(accounts)

        val activeId = credentialStore.getString("${KEY_ACTIVE_PREFIX}${removed.provider.name}")
        if (activeId == accountId) {
            val next = accounts.firstOrNull { it.provider == removed.provider }
            if (next != null) {
                setActiveAccount(next.id)
            } else {
                credentialStore.removeString("${KEY_ACTIVE_PREFIX}${removed.provider.name}")
                // Clear host-level credential if no more accounts for this host
                val stillHasHost = accounts.any { it.host.equals(removed.host, true) }
                if (!stillHasHost) {
                    credentialStore.clearHttpsToken(removed.host)
                }
            }
        }
        AppLog.i(TAG, "removed account $accountId (${removed.username})")
    }

    fun getToken(accountId: String): String? = credentialStore.getAccountToken(accountId)

    fun getTokenForActive(provider: Provider): String? {
        val active = getActiveAccount(provider) ?: return null
        return getToken(active.id) ?: credentialStore.getHttpsToken(active.host)
    }

    /**
     * One-time migration: import existing host-based credentials into managed accounts
     * so users who already connected GitHub/GitLab/Gerrit keep their sessions.
     */
    fun migrateFromLegacyCredentials() {
        if (loadAll().isNotEmpty()) return // already migrated / has accounts

        val legacy = mutableListOf<Account>()

        // GitHub
        if (credentialStore.hasHttpsCredential("github.com")) {
            val user = credentialStore.getHttpsUsername("github.com") ?: "github-user"
            val token = credentialStore.getHttpsToken("github.com")
            if (!token.isNullOrBlank()) {
                val id = UUID.randomUUID().toString()
                credentialStore.saveAccountToken(id, user, token)
                legacy.add(
                    Account(
                        id = id,
                        provider = Provider.GITHUB,
                        host = "github.com",
                        username = user
                    )
                )
            }
        }

        // GitLab — primary host + any host that looks like gitlab
        val gitlabHosts = credentialStore.listHttpsHosts().filter {
            it.contains("gitlab", ignoreCase = true) || it == "gitlab.com"
        }.distinct()
        for (h in gitlabHosts) {
            val user = credentialStore.getHttpsUsername(h) ?: continue
            val token = credentialStore.getHttpsToken(h) ?: continue
            if (token.isBlank()) continue
            val id = UUID.randomUUID().toString()
            credentialStore.saveAccountToken(id, user, token)
            legacy.add(
                Account(
                    id = id,
                    provider = Provider.GITLAB,
                    host = h,
                    username = user
                )
            )
        }

        // Gerrit preferred host
        val gerritHost = credentialStore.getPreferredGerritHost()
        if (!gerritHost.isNullOrBlank() && credentialStore.hasHttpsCredential(gerritHost)) {
            val user = credentialStore.getHttpsUsername(gerritHost) ?: "gerrit-user"
            val token = credentialStore.getHttpsToken(gerritHost)
            if (!token.isNullOrBlank()) {
                val id = UUID.randomUUID().toString()
                credentialStore.saveAccountToken(id, user, token)
                legacy.add(
                    Account(
                        id = id,
                        provider = Provider.GERRIT,
                        host = gerritHost,
                        username = user
                    )
                )
            }
        }

        if (legacy.isNotEmpty()) {
            saveAll(legacy)
            legacy.groupBy { it.provider }.forEach { (provider, list) ->
                setActiveAccount(list.first().id)
            }
            AppLog.i(TAG, "migrated ${legacy.size} legacy account(s)")
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun syncHostCredential(account: Account) {
        val token = credentialStore.getAccountToken(account.id) ?: return
        credentialStore.saveHttpsToken(account.host, account.username, token)
        if (account.provider == Provider.GERRIT) {
            credentialStore.setPreferredGerritHost(account.host)
        }
        if (account.provider == Provider.GITLAB) {
            // Keep GitLabAccountManager.host in sync via preferred key if used
            credentialStore.putString("gitlab_primary_host", account.host)
        }
    }

    private fun loadAll(): List<Account> {
        val raw = credentialStore.getString(KEY_ACCOUNTS) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Account(
                            id = o.getString("id"),
                            provider = Provider.valueOf(o.getString("provider")),
                            host = o.getString("host"),
                            username = o.getString("username"),
                            displayName = o.optString("displayName", null)?.takeIf { it.isNotBlank() && it != "null" },
                            email = o.optString("email", null)?.takeIf { it.isNotBlank() && it != "null" },
                            avatarUrl = o.optString("avatarUrl", null)?.takeIf { it.isNotBlank() && it != "null" },
                            profileUrl = o.optString("profileUrl", null)?.takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "failed to parse accounts: ${e.message}")
            emptyList()
        }
    }

    private fun saveAll(accounts: List<Account>) {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("provider", a.provider.name)
                    put("host", a.host)
                    put("username", a.username)
                    put("displayName", a.displayName)
                    put("email", a.email)
                    put("avatarUrl", a.avatarUrl)
                    put("profileUrl", a.profileUrl)
                }
            )
        }
        credentialStore.putString(KEY_ACCOUNTS, arr.toString())
    }
}
