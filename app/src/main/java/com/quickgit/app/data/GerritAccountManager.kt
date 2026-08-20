package com.quickgit.app.data

import com.quickgit.app.data.gerrit.GerritApi
import com.quickgit.app.data.gerrit.toPrOpResult
import com.quickgit.app.data.models.GerritProject
import com.quickgit.app.data.models.PrOpResult

/**
 * Connects a Gerrit host via username + HTTP password (Gerrit Settings → HTTP Credentials).
 * Credentials are stored under the host in [CredentialStore] so JGit clone/fetch/push work.
 */
class GerritAccountManager(private val credentialStore: CredentialStore) {

    private val TAG = "GerritAccountManager"

    var host: String = ""
        private set

    data class ConnectedAccount(
        val username: String,
        val name: String?,
        val email: String?,
        val host: String
    )

    fun isConnected(h: String = host): Boolean =
        h.isNotBlank() && credentialStore.hasHttpsCredential(h)

    fun storedUsername(h: String = host): String? =
        if (h.isBlank()) null else credentialStore.getHttpsUsername(h)

    /**
     * @param customHost e.g. "gerrit.example.com" (no scheme required)
     * @param username Gerrit account username
     * @param httpPassword HTTP password from Gerrit settings (not the web login password unless configured that way)
     */
    fun connect(
        customHost: String,
        username: String,
        httpPassword: String
    ): Pair<ConnectedAccount?, PrOpResult> {
        val h = customHost.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        if (h.isBlank()) return null to PrOpResult.Error("Gerrit host is required")
        val user = username.trim()
        val pass = httpPassword.trim()
        if (user.isBlank()) return null to PrOpResult.Error("Username is required")
        if (pass.isBlank()) return null to PrOpResult.Error("HTTP password is required")

        host = h
        credentialStore.setPreferredGerritHost(h)
        AppLog.i(TAG, "connect: verifying $user@$h")
        try {
            credentialStore.saveHttpsToken(h, user, pass)
        } catch (e: Exception) {
            return null to PrOpResult.Error(e.message ?: "Failed to save credentials", e)
        }

        val result = GerritApi(h, user, pass).getAuthenticatedUser()
        val account = result.getOrNull()
        return if (account != null) {
            try {
                credentialStore.saveHttpsToken(h, account.username.ifBlank { user }, pass)
            } catch (_: Exception) { /* already saved */ }
            ConnectedAccount(
                username = account.username.ifBlank { user },
                name = account.name,
                email = account.email,
                host = h
            ) to PrOpResult.Success
        } else {
            val err = result.toPrOpResult(h)
            // Leave credentials stored so user can still try clone; report verify failure
            null to err
        }
    }

    fun disconnect(h: String = host) {
        if (h.isBlank()) return
        AppLog.i(TAG, "disconnect $h")
        credentialStore.clearHttpsToken(h)
        if (host == h || credentialStore.getPreferredGerritHost() == h) {
            credentialStore.clearPreferredGerritHost()
            host = ""
        }
    }

    fun restoreHost() {
        val h = credentialStore.getPreferredGerritHost().orEmpty()
        if (h.isNotBlank() && credentialStore.hasHttpsCredential(h)) {
            host = h
        }
    }

    fun listProjects(h: String = host, query: String? = "state:ACTIVE"): Pair<List<GerritProject>, PrOpResult> {
        if (h.isBlank()) return emptyList<GerritProject>() to PrOpResult.Error("No Gerrit host connected")
        val user = credentialStore.getHttpsUsername(h)
        val pass = credentialStore.getHttpsToken(h)
        val result = GerritApi(h, user, pass).listProjects(query)
        return result.getOrElse { emptyList() } to result.toPrOpResult(h)
    }

    fun cloneUrl(projectName: String, h: String = host): String? {
        if (h.isBlank()) return null
        val user = credentialStore.getHttpsUsername(h)
        val pass = credentialStore.getHttpsToken(h)
        return GerritApi(h, user, pass).cloneUrl(projectName)
    }

    /** Restore last-known host from a non-empty credential host list if needed. */
    fun preferHost(h: String) {
        if (h.isNotBlank()) host = h.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    }
}
