package com.quickgit.app.data

import com.quickgit.app.data.gerrit.GerritApi
import com.quickgit.app.data.gerrit.toGerritOpResult
import com.quickgit.app.data.models.GerritChange
import com.quickgit.app.data.models.GerritProject
import com.quickgit.app.data.models.GerritReviewInput
import com.quickgit.app.data.models.PrOpResult

/**
 * Connects a Gerrit account (any host) via username + HTTP password / access token.
 * Credentials stored under the configured host in CredentialStore.
 */
class GerritAccountManager(private val credentialStore: CredentialStore) {

    private val TAG = "GerritAccountManager"

    var host: String = "gerrit.googlesource.com"
        private set

    private fun api(h: String = host): GerritApi {
        val user = credentialStore.getHttpsUsername(h)
        val pass = credentialStore.getHttpsToken(h)
        return GerritApi(h, user, pass)
    }

    data class ConnectedAccount(
        val username: String,
        val name: String?,
        val email: String?,
        val host: String
    )

    fun isConnected(h: String = host): Boolean = credentialStore.hasHttpsCredential(h)

    fun storedUsername(h: String = host): String? = credentialStore.getHttpsUsername(h)

    /**
     * @param customHost e.g. "gerrit.example.com" — required for most deployments
     */
    fun connect(
        username: String,
        passwordOrToken: String,
        customHost: String
    ): Pair<ConnectedAccount?, PrOpResult> {
        val h = customHost.trim()
            .removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (h.isBlank()) return null to PrOpResult.Error("Host is required")
        host = h
        val user = username.trim()
        val pass = passwordOrToken.trim()
        if (user.isBlank() || pass.isBlank()) {
            return null to PrOpResult.Error("Username and HTTP password/token are required")
        }
        AppLog.i(TAG, "connect: verifying credentials with $h")
        try {
            credentialStore.saveHttpsToken(h, user, pass)
        } catch (e: Exception) {
            return null to PrOpResult.Error(e.message ?: "Failed to save credentials", e)
        }
        val result = api(h).getAuthenticatedUser()
        val account = result.getOrNull()
        return if (account != null) {
            try {
                credentialStore.savePrimaryGerritHost(h)
            } catch (e: Exception) {
                AppLog.w(TAG, "failed to persist primary Gerrit host: ${e.message}")
            }
            AppLog.i(TAG, "connect succeeded: ${account.username}@$h")
            ConnectedAccount(account.username, account.name, account.email, h) to PrOpResult.Success
        } else {
            val op = result.toGerritOpResult(h)
            if (op is PrOpResult.AuthRequired) {
                credentialStore.clearHttpsToken(h)
            }
            null to op
        }
    }

    fun disconnect(h: String = host) {
        AppLog.i(TAG, "disconnect $h")
        credentialStore.clearHttpsToken(h)
        if (credentialStore.getPrimaryGerritHost() == h) {
            credentialStore.clearPrimaryGerritHost()
        }
        if (h == host) host = "gerrit.googlesource.com"
    }

    /** Last successfully connected Gerrit host, if any. */
    fun primaryHost(): String? = credentialStore.getPrimaryGerritHost()

    fun refreshAccount(h: String = host): Pair<ConnectedAccount?, PrOpResult> {
        if (!isConnected(h)) return null to PrOpResult.Error("Not connected")
        val result = api(h).getAuthenticatedUser()
        val account = result.getOrNull()
        return if (account != null) {
            ConnectedAccount(account.username, account.name, account.email, h) to PrOpResult.Success
        } else {
            null to result.toGerritOpResult(h)
        }
    }

    fun listChanges(
        query: String = "status:open",
        limit: Int = 50,
        h: String = host
    ): Pair<List<GerritChange>, PrOpResult> {
        if (!isConnected(h)) return emptyList<GerritChange>() to PrOpResult.AuthRequired(h)
        val result = api(h).listChanges(query = query, limit = limit)
        return (result.getOrNull() ?: emptyList()) to result.toGerritOpResult(h)
    }

    fun listProjectsPage(
        page: Int = 1,
        perPage: Int = 100,
        h: String = host
    ): Triple<List<GerritProject>, Boolean, PrOpResult> {
        val hostKey = h.ifBlank { primaryHost() ?: host }
        if (!isConnected(hostKey)) return Triple(emptyList(), false, PrOpResult.AuthRequired(hostKey))
        val start = ((page - 1) * perPage).coerceAtLeast(0)
        val result = api(hostKey).listProjects(limit = perPage, start = start)
        val batch = result.getOrNull()
        return if (batch == null) {
            Triple(emptyList(), false, result.toGerritOpResult(hostKey))
        } else {
            Triple(batch, batch.size >= perPage, PrOpResult.Success)
        }
    }

    fun searchProjectsPage(
        query: String,
        page: Int = 1,
        perPage: Int = 100,
        h: String = host
    ): Triple<List<GerritProject>, Boolean, PrOpResult> {
        val hostKey = h.ifBlank { primaryHost() ?: host }
        if (!isConnected(hostKey)) return Triple(emptyList(), false, PrOpResult.AuthRequired(hostKey))
        val start = ((page - 1) * perPage).coerceAtLeast(0)
        val result = api(hostKey).searchProjects(query, limit = perPage, start = start)
        val batch = result.getOrNull()
        return if (batch == null) {
            Triple(emptyList(), false, result.toGerritOpResult(hostKey))
        } else {
            Triple(batch, batch.size >= perPage, PrOpResult.Success)
        }
    }

    fun searchAccounts(query: String, h: String = host): Pair<List<com.quickgit.app.data.gerrit.GerritApi.GerritAccountSummary>, PrOpResult> {
        val hostKey = h.ifBlank { primaryHost() ?: host }
        if (!isConnected(hostKey)) return emptyList<com.quickgit.app.data.gerrit.GerritApi.GerritAccountSummary>() to PrOpResult.AuthRequired(hostKey)
        val result = api(hostKey).searchAccounts(query)
        return (result.getOrNull() ?: emptyList()) to result.toGerritOpResult(hostKey)
    }

    fun listProjects(h: String = host, limit: Int = 50): Pair<List<GerritProject>, PrOpResult> {
        val hostKey = h.ifBlank { primaryHost() ?: host }
        if (!isConnected(hostKey)) return emptyList<GerritProject>() to PrOpResult.AuthRequired(hostKey)
        val result = api(hostKey).listProjects(limit = limit)
        return (result.getOrNull() ?: emptyList()) to result.toGerritOpResult(hostKey)
    }

    fun searchProjects(query: String, h: String = host, limit: Int = 50): Pair<List<GerritProject>, PrOpResult> {
        val hostKey = h.ifBlank { primaryHost() ?: host }
        if (!isConnected(hostKey)) return emptyList<GerritProject>() to PrOpResult.AuthRequired(hostKey)
        val result = api(hostKey).searchProjects(query, limit = limit)
        return (result.getOrNull() ?: emptyList()) to result.toGerritOpResult(hostKey)
    }

    fun getChange(changeId: String, h: String = host): Pair<GerritChange?, PrOpResult> {
        if (!isConnected(h)) return null to PrOpResult.AuthRequired(h)
        val result = api(h).getChange(changeId)
        return result.getOrNull() to result.toGerritOpResult(h)
    }

    fun postReview(
        changeId: String,
        input: GerritReviewInput,
        revision: String = "current",
        h: String = host
    ): PrOpResult {
        if (!isConnected(h)) return PrOpResult.AuthRequired(h)
        return api(h).postReview(changeId, revision, input).toGerritOpResult(h)
    }

    fun voteCodeReview(
        changeId: String,
        value: Int,
        message: String? = null,
        h: String = host
    ): PrOpResult {
        if (!isConnected(h)) return PrOpResult.AuthRequired(h)
        return api(h).voteCodeReview(changeId, value, message).toGerritOpResult(h)
    }

    fun listChangeFiles(
        changeId: String,
        revision: String = "current",
        h: String = host
    ): Pair<List<com.quickgit.app.data.models.GerritFileChange>, PrOpResult> {
        val hostKey = h.ifBlank { primaryHost() ?: host }
        if (!isConnected(hostKey)) {
            return emptyList<com.quickgit.app.data.models.GerritFileChange>() to PrOpResult.AuthRequired(hostKey)
        }
        val result = api(hostKey).listFiles(changeId, revision)
        return (result.getOrNull() ?: emptyList()) to result.toGerritOpResult(hostKey)
    }

    fun getChangeFileDiff(
        changeId: String,
        filePath: String,
        revision: String = "current",
        h: String = host
    ): Pair<com.quickgit.app.data.models.FileDiff?, PrOpResult> {
        val hostKey = h.ifBlank { primaryHost() ?: host }
        if (!isConnected(hostKey)) return null to PrOpResult.AuthRequired(hostKey)
        val result = api(hostKey).getFileDiff(changeId, filePath, revision)
        return result.getOrNull() to result.toGerritOpResult(hostKey)
    }
}
