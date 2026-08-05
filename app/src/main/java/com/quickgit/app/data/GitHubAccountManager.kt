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
            ConnectedAccount(user.login, user.name, user.avatarUrl, user.htmlUrl) to PrOpResult.Success
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
            ConnectedAccount(user.login, user.name, user.avatarUrl, user.htmlUrl) to PrOpResult.Success
        } else {
            null to result.toPrOpResult(host)
        }
    }

    fun listRepos(
        affiliation: String = "owner,collaborator,organization_member",
        page: Int = 1
    ): Pair<List<GitHubRemoteRepo>, PrOpResult> {
        if (!isConnected()) return emptyList<GitHubRemoteRepo>() to PrOpResult.AuthRequired(host)
        val result = api.listUserRepos(affiliation = affiliation, page = page)
        return (result.getOrNull() ?: emptyList()) to result.toPrOpResult(host)
    }

    fun searchRepos(query: String): Pair<List<GitHubRemoteRepo>, PrOpResult> {
        if (!isConnected()) return emptyList<GitHubRemoteRepo>() to PrOpResult.AuthRequired(host)
        val login = storedUsername()
        val result = if (!login.isNullOrBlank() && login != "x-access-token" && query.isNotBlank()) {
            api.searchUserRepos(login, query)
        } else {
            api.listUserRepos().map { list ->
                if (query.isBlank()) list
                else {
                    val q = query.trim().lowercase()
                    list.filter {
                        it.name.lowercase().contains(q) ||
                            it.fullName.lowercase().contains(q) ||
                            (it.description?.lowercase()?.contains(q) == true)
                    }
                }
            }
        }
        return (result.getOrNull() ?: emptyList()) to result.toPrOpResult(host)
    }
}
