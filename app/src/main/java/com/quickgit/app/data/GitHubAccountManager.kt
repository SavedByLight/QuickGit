package com.quickgit.app.data

import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.github.toPrOpResult
import com.quickgit.app.data.models.GitHubRemoteRepo
import com.quickgit.app.data.models.PrOpResult

/**
 * Connects a GitHub account via personal access token (already stored in CredentialStore)
 * and lists repositories the user can clone.
 *
 * True OAuth would need a registered OAuth App + redirect URI; this app uses the same
 * encrypted PAT flow as git push/pull, then verifies the token with GET /user.
 */
class GitHubAccountManager(private val credentialStore: CredentialStore) {

    private val TAG = "GitHubAccountManager"
    private val host = "github.com"

    private val api: GitHubApi get() = GitHubApi(credentialStore.getHttpsToken(host))

    data class ConnectedAccount(
        val login: String,
        val name: String?,
        val email: String?,
        val avatarUrl: String?,
        val htmlUrl: String
    )

    fun isConnected(): Boolean = credentialStore.hasHttpsCredential(host)

    fun storedUsername(): String? = credentialStore.getHttpsUsername(host)

    /**
     * Saves the token, then calls GitHub to verify it and return the authenticated user.
     * On success, updates the stored username to the real GitHub login.
     */
    fun connect(token: String, preferredUsername: String? = null): Pair<ConnectedAccount?, PrOpResult> {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return null to PrOpResult.Error("Token is required")
        AppLog.i(TAG, "connect: verifying token with GitHub")
        val provisionalUser = preferredUsername?.trim()?.ifBlank { null } ?: "x-access-token"
        try {
            credentialStore.saveHttpsToken(host, provisionalUser, trimmed)
        } catch (e: Exception) {
            return null to PrOpResult.Error(e.message ?: "Failed to save token", e)
        }
        val result = api.getAuthenticatedUser()
        val user = result.getOrNull()
        return if (user != null) {
            try {
                credentialStore.saveHttpsToken(host, user.login, trimmed)
            } catch (_: Exception) { /* already saved */ }
            AppLog.i(TAG, "connect succeeded: ${user.login}")
            ConnectedAccount(user.login, user.name, user.email, user.avatarUrl, user.htmlUrl) to PrOpResult.Success
        } else {
            val op = result.toPrOpResult(host)
            if (op is PrOpResult.AuthRequired) {
                credentialStore.clearHttpsToken(host)
            }
            null to op
        }
    }

    fun disconnect() {
        AppLog.i(TAG, "disconnect")
        credentialStore.clearHttpsToken(host)
    }

    /** Re-verify the stored token and return the current user, or null if not connected / invalid. */
    fun refreshAccount(): Pair<ConnectedAccount?, PrOpResult> {
        if (!isConnected()) return null to PrOpResult.Error("Not connected")
        val result = api.getAuthenticatedUser()
        val user = result.getOrNull()
        return if (user != null) {
            ConnectedAccount(user.login, user.name, user.email, user.avatarUrl, user.htmlUrl) to PrOpResult.Success
        } else {
            null to result.toPrOpResult(host)
        }
    }

    /** Fetches every page of repos the authenticated user can access (GitHub paginates at 100/page). */
    fun listRepos(
        affiliation: String = "owner,collaborator,organization_member"
    ): Pair<List<GitHubRemoteRepo>, PrOpResult> {
        if (!isConnected()) return emptyList<GitHubRemoteRepo>() to PrOpResult.AuthRequired(host)
        val perPage = 100
        val all = mutableListOf<GitHubRemoteRepo>()
        var page = 1
        while (true) {
            val result = api.listUserRepos(affiliation = affiliation, perPage = perPage, page = page)
            val batch = result.getOrNull()
            if (batch == null) {
                return if (page == 1) emptyList<GitHubRemoteRepo>() to result.toPrOpResult(host)
                else all to PrOpResult.Success
            }
            all += batch
            if (batch.size < perPage || page >= 10) break // 10-page safety cap (1000 repos)
            page++
        }
        return all to PrOpResult.Success
    }

    fun searchRepos(query: String): Pair<List<GitHubRemoteRepo>, PrOpResult> {
        if (!isConnected()) return emptyList<GitHubRemoteRepo>() to PrOpResult.AuthRequired(host)
        val login = storedUsername()
        val result = if (!login.isNullOrBlank() && login != "x-access-token" && query.isNotBlank()) {
            api.searchUserRepos(login, query, perPage = 100)
        } else {
            return if (query.isBlank()) listRepos() else {
                val (all, opResult) = listRepos()
                val q = query.trim().lowercase()
                all.filter {
                    it.name.lowercase().contains(q) ||
                        it.fullName.lowercase().contains(q) ||
                        (it.description?.lowercase()?.contains(q) == true)
                } to opResult
            }
        }
        return (result.getOrNull() ?: emptyList()) to result.toPrOpResult(host)
    }

    fun getUserProfile(login: String? = null): Pair<GitHubApi.GitHubUser?, PrOpResult> {
        if (!isConnected()) return null to PrOpResult.AuthRequired(host)
        val result = if (login.isNullOrBlank()) {
            api.getAuthenticatedUser()
        } else {
            api.getUser(login)
        }
        return result.getOrNull() to result.toPrOpResult(host)
    }

    fun searchUsers(query: String): Pair<List<GitHubApi.GitHubUserSummary>, PrOpResult> {
        if (!isConnected()) return emptyList<GitHubApi.GitHubUserSummary>() to PrOpResult.AuthRequired(host)
        val result = api.searchUsers(query)
        return (result.getOrNull() ?: emptyList()) to result.toPrOpResult(host)
    }

    /** Fetches every page of the user's public repos (GitHub paginates at 100/page). */
    fun listPublicRepos(login: String): Pair<List<GitHubRemoteRepo>, PrOpResult> {
        if (!isConnected()) return emptyList<GitHubRemoteRepo>() to PrOpResult.AuthRequired(host)
        val perPage = 100
        val all = mutableListOf<GitHubRemoteRepo>()
        var page = 1
        while (true) {
            val result = api.listPublicRepos(login, perPage = perPage, page = page)
            val batch = result.getOrNull()
            if (batch == null) {
                // First page failing is a real error; a later page failing just stops pagination.
                return if (page == 1) emptyList<GitHubRemoteRepo>() to result.toPrOpResult(host)
                else all to PrOpResult.Success
            }
            all += batch
            if (batch.size < perPage || page >= 10) break // 10-page safety cap (1000 repos)
            page++
        }
        return all to PrOpResult.Success
    }

    /** Forks someone else's repo into the authenticated user's own account. */
    fun forkRepo(owner: String, repo: String): Pair<GitHubRemoteRepo?, PrOpResult> {
        if (!isConnected()) return null to PrOpResult.AuthRequired(host)
        val result = api.forkRepo(owner, repo)
        return result.getOrNull() to result.toPrOpResult(host)
    }

    /** Creates a new repo owned by the authenticated user. */
    fun createRepo(name: String, description: String?, isPrivate: Boolean): Pair<GitHubRemoteRepo?, PrOpResult> {
        if (!isConnected()) return null to PrOpResult.AuthRequired(host)
        if (name.isBlank()) return null to PrOpResult.Error("Repo name is required")
        val result = api.createRepo(name.trim(), description, isPrivate)
        return result.getOrNull() to result.toPrOpResult(host)
    }
}
