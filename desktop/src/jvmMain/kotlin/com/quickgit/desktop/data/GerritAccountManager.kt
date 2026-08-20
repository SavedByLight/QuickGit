package com.quickgit.desktop.data

import com.quickgit.desktop.data.gerrit.GerritApi
import com.quickgit.desktop.data.models.GerritProject

/**
 * Desktop Gerrit connection: username + HTTP password stored per host.
 */
class GerritAccountManager(private val credentialStore: DesktopCredentialStore) {

    private val TAG = "GerritAccountManager"

    var host: String
        get() = credentialStore.getPreferredGerritHost().orEmpty()
        set(value) = credentialStore.setPreferredGerritHost(value)

    data class ConnectedAccount(
        val username: String,
        val name: String?,
        val email: String?,
        val host: String
    )

    fun isConnected(h: String = host): Boolean {
        if (h.isBlank()) return false
        return !credentialStore.getHttpsToken(h).isNullOrBlank() &&
            !credentialStore.getHttpsUsername(h).isNullOrBlank()
    }

    fun storedUsername(h: String = host): String? = credentialStore.getHttpsUsername(h)

    fun connect(
        customHost: String,
        username: String,
        httpPassword: String
    ): Result<ConnectedAccount> = runCatching {
        val h = customHost.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        require(h.isNotBlank()) { "Gerrit host is required" }
        val user = username.trim()
        val pass = httpPassword.trim()
        require(user.isNotBlank()) { "Username is required" }
        require(pass.isNotBlank()) { "HTTP password is required" }

        AppLog.i(TAG, "connect: verifying $user@$h")
        credentialStore.saveHttpsCredential(h, user, pass)
        credentialStore.setPreferredGerritHost(h)

        val account = GerritApi(h, user, pass).getAuthenticatedUser().getOrThrow()
        val resolvedUser = account.username.ifBlank { user }
        credentialStore.saveHttpsCredential(h, resolvedUser, pass)
        ConnectedAccount(
            username = resolvedUser,
            name = account.name,
            email = account.email,
            host = h
        )
    }

    fun disconnect(h: String = host) {
        if (h.isBlank()) return
        AppLog.i(TAG, "disconnect $h")
        credentialStore.setHttpsToken(h, null)
        credentialStore.setHttpsUsername(h, null)
        if (credentialStore.getPreferredGerritHost() == h.lowercase()) {
            credentialStore.setPreferredGerritHost(null)
        }
    }

    fun listProjects(h: String = host, query: String? = "state:ACTIVE"): Result<List<GerritProject>> {
        if (h.isBlank()) return Result.failure(IllegalStateException("No Gerrit host connected"))
        val user = credentialStore.getHttpsUsername(h)
        val pass = credentialStore.getHttpsToken(h)
        return GerritApi(h, user, pass).listProjects(query)
    }

    fun cloneUrl(projectName: String, h: String = host): String? {
        if (h.isBlank()) return null
        val user = credentialStore.getHttpsUsername(h)
        val pass = credentialStore.getHttpsToken(h)
        return GerritApi(h, user, pass).cloneUrl(projectName)
    }
}
