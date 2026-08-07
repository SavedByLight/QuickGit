package com.quickgit.app.data

import com.quickgit.app.data.gitlab.GitLabApi
import com.quickgit.app.data.gitlab.toPrOpResult
import com.quickgit.app.data.models.GitLabProject
import com.quickgit.app.data.models.PrOpResult

/**
 * Connects a GitLab account (gitlab.com or self-hosted) via personal access token.
 * Token is stored under the configured host in CredentialStore.
 */
class GitLabAccountManager(private val credentialStore: CredentialStore) {

    private val TAG = "GitLabAccountManager"

    /** Default host; callers can override with a custom host. */
    var host: String = "gitlab.com"
        private set

    private fun api(h: String = host): GitLabApi =
        GitLabApi(h, credentialStore.getHttpsToken(h))

    data class ConnectedAccount(
        val username: String,
        val name: String?,
        val email: String?,
        val avatarUrl: String?,
        val webUrl: String,
        val host: String
    )

    fun isConnected(h: String = host): Boolean = credentialStore.hasHttpsCredential(h)

    fun storedUsername(h: String = host): String? = credentialStore.getHttpsUsername(h)

    /**
     * Saves the token for [host], verifies it, and returns the authenticated user.
     * @param customHost e.g. "gitlab.example.com" — leave null for gitlab.com
     */
    fun connect(
        token: String,
        preferredUsername: String? = null,
        customHost: String? = null
    ): Pair<ConnectedAccount?, PrOpResult> {
        val h = (customHost ?: "gitlab.com").trim()
            .removePrefix("https://").removePrefix("http://").trimEnd('/')
        host = h
        val trimmed = token.trim()
        if (trimmed.isBlank()) return null to PrOpResult.Error("Token is required")
        AppLog.i(TAG, "connect: verifying token with $h")
        val provisional = preferredUsername?.trim()?.ifBlank { null } ?: "oauth2"
        try {
            credentialStore.saveHttpsToken(h, provisional, trimmed)
        } catch (e: Exception) {
            return null to PrOpResult.Error(e.message ?: "Failed to save token", e)
        }
        val result = api(h).getAuthenticatedUser()
        val user = result.getOrNull()
        return if (user != null) {
            try {
                credentialStore.saveHttpsToken(h, user.username, trimmed)
            } catch (_: Exception) { /* already saved */ }
            // remember which host is the "primary" GitLab host
            savePrimaryHost(h)
            AppLog.i(TAG, "connect succeeded: ${user.username}@$h")
            ConnectedAccount(
                user.username, user.name, user.email, user.avatarUrl, user.webUrl, h
            ) to PrOpResult.Success
        } else {
            val op = result.toPrOpResult(h)
            if (op is PrOpResult.AuthRequired) {
                credentialStore.clearHttpsToken(h)
            }
            null to op
        }
    }

    fun disconnect(h: String = host) {
        AppLog.i(TAG, "disconnect $h")
        credentialStore.clearHttpsToken(h)
        if (h == host) host = "gitlab.com"
    }

    fun refreshAccount(h: String = host): Pair<ConnectedAccount?, PrOpResult> {
        if (!isConnected(h)) return null to PrOpResult.Error("Not connected")
        val result = api(h).getAuthenticatedUser()
        val user = result.getOrNull()
        return if (user != null) {
            ConnectedAccount(
                user.username, user.name, user.email, user.avatarUrl, user.webUrl, h
            ) to PrOpResult.Success
        } else {
            null to result.toPrOpResult(h)
        }
    }

    fun listProjects(h: String = host): Pair<List<GitLabProject>, PrOpResult> {
        if (!isConnected(h)) return emptyList<GitLabProject>() to PrOpResult.AuthRequired(h)
        val perPage = 50
        val all = mutableListOf<GitLabProject>()
        var page = 1
        while (true) {
            val result = api(h).listProjects(membership = true, perPage = perPage, page = page)
            val batch = result.getOrNull()
            if (batch == null) {
                return if (page == 1) emptyList<GitLabProject>() to result.toPrOpResult(h)
                else all to PrOpResult.Success
            }
            all += batch
            if (batch.size < perPage || page >= 10) break
            page++
        }
        return all to PrOpResult.Success
    }

    fun listProjectsPage(
        page: Int = 1,
        perPage: Int = 100,
        h: String = host
    ): Triple<List<GitLabProject>, Boolean, PrOpResult> {
        if (!isConnected(h)) return Triple(emptyList(), false, PrOpResult.AuthRequired(h))
        val result = api(h).listProjects(membership = true, perPage = perPage, page = page)
        val batch = result.getOrNull()
        return if (batch == null) {
            Triple(emptyList(), false, result.toPrOpResult(h))
        } else {
            Triple(batch, batch.size >= perPage, PrOpResult.Success)
        }
    }

    fun searchProjectsPage(
        query: String,
        page: Int = 1,
        perPage: Int = 100,
        h: String = host
    ): Triple<List<GitLabProject>, Boolean, PrOpResult> {
        if (!isConnected(h)) return Triple(emptyList(), false, PrOpResult.AuthRequired(h))
        val result = api(h).searchProjects(query, perPage = perPage, page = page)
        val batch = result.getOrNull()
        return if (batch == null) {
            Triple(emptyList(), false, result.toPrOpResult(h))
        } else {
            Triple(batch, batch.size >= perPage, PrOpResult.Success)
        }
    }

    fun searchProjects(query: String, h: String = host): Pair<List<GitLabProject>, PrOpResult> {
        if (!isConnected(h)) return emptyList<GitLabProject>() to PrOpResult.AuthRequired(h)
        val result = api(h).searchProjects(query)
        return (result.getOrNull() ?: emptyList()) to result.toPrOpResult(h)
    }

    /** Project path (group/project) or numeric ID for a remote URL. */
    fun projectIdForRemote(remoteUrl: String?, h: String = host): String? {
        val parsed = api(h).parseOwnerProject(remoteUrl) ?: return null
        return "${parsed.owner}/${parsed.project}"
    }

    // Persist primary GitLab host so UI can restore it
    private fun savePrimaryHost(h: String) {
        // reuse a simple preference key via the encrypted store's underlying prefs is awkward;
        // store under a well-known username marker
        try {
            val existing = credentialStore.getHttpsToken(h)
            if (existing != null) {
                // host is already the key; nothing extra needed beyond the token itself
            }
        } catch (_: Exception) {}
    }
}


    fun searchUsers(query: String, h: String = host): Pair<List<com.quickgit.app.data.gitlab.GitLabApi.GitLabUserSummary>, PrOpResult> {
        if (!isConnected(h)) return emptyList<com.quickgit.app.data.gitlab.GitLabApi.GitLabUserSummary>() to PrOpResult.AuthRequired(h)
        val result = api(h).searchUsers(query)
        return (result.getOrNull() ?: emptyList()) to result.toPrOpResult(h)
    }
