package com.quickgit.app.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.AppLog
import com.quickgit.app.data.AppPreferences
import com.quickgit.app.data.DesktopLayoutMode
import com.quickgit.app.data.CredentialStore
import com.quickgit.app.data.GitHubAccountManager
import com.quickgit.app.data.GitLabAccountManager
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.PrOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val host: String = "github.com",
    val username: String = "",
    val token: String = "",
    val hasStoredToken: Boolean = false,
    /** GitHub login verified via API when host is github.com. */
    val githubLogin: String? = null,
    val githubName: String? = null,
    /** GitLab account (any host). */
    val gitlabHost: String = "gitlab.com",
    val gitlabUsername: String? = null,
    val gitlabConnected: Boolean = false,
    val gerritHost: String = "",
    val gerritUsername: String? = null,
    val gerritConnected: Boolean = false,
    val connecting: Boolean = false,
    val authorName: String = "",
    val authorEmail: String = "",
    val sshKey: String = "",
    val sshPassphrase: String = "",
    val hasStoredSshKey: Boolean = false,
    val gpgKey: String = "",
    val gpgPassphrase: String = "",
    val hasStoredGpgKey: Boolean = false,
    val gpgSignEnabled: Boolean = false,
    val reposRootPath: String = "",
    val reposRootIsUserChosen: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false,
    // App update (GitHub Releases)
    val appVersionName: String = "",
    val appVersionCode: Long = 0L,
    val updateChecking: Boolean = false,
    val updateAvailable: Boolean = false,
    val updateLatestName: String? = null,
    val updateNotes: String? = null,
    val updateReleasesUrl: String? = null,
    /** Left NavigationRail layout (matches Linux desktop). Auto / Always / Never. */
    val desktopLayoutMode: DesktopLayoutMode = DesktopLayoutMode.AUTO
)

class SettingsViewModel(
    private val credentialStore: CredentialStore,
    private val repoManager: RepoManager,
    private val gitHubAccountManager: GitHubAccountManager,
    private val gitLabAccountManager: GitLabAccountManager,
    private val gerritAccountManager: com.quickgit.app.data.GerritAccountManager,
    private val appUpdateManager: com.quickgit.app.data.AppUpdateManager,
    private val appPreferences: AppPreferences
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        loadForHost("github.com")
        refreshSsh()
        refreshGpg()
        refreshReposRoot()
        refreshAuthor()
        verifyGitHubIfConnected()
        verifyGitLabIfConnected()
        refreshGerrit()
        refreshAppVersion()
        refreshDesktopLayout()
    }

    private fun refreshDesktopLayout() {
        _state.value = _state.value.copy(
            desktopLayoutMode = appPreferences.desktopLayoutMode.value
        )
    }

    fun setDesktopLayoutMode(mode: DesktopLayoutMode) {
        appPreferences.setDesktopLayoutMode(mode)
        _state.value = _state.value.copy(desktopLayoutMode = mode)
    }

    private fun refreshAppVersion() {
        val v = appUpdateManager.currentVersion()
        _state.value = _state.value.copy(
            appVersionName = v.versionName,
            appVersionCode = v.versionCode
        )
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            appUpdateManager.clearSnooze()
            _state.value = _state.value.copy(
                updateChecking = true,
                updateAvailable = false,
                updateLatestName = null,
                updateNotes = null,
                updateReleasesUrl = null,
                statusMessage = null
            )
            val result = withContext(Dispatchers.IO) { appUpdateManager.checkForUpdate() }
            when (result) {
                is com.quickgit.app.data.UpdateCheckResult.UpToDate -> {
                    _state.value = _state.value.copy(
                        updateChecking = false,
                        updateAvailable = false,
                        statusMessage = "You're on the latest version (${result.current.versionName})",
                        isError = false
                    )
                }
                is com.quickgit.app.data.UpdateCheckResult.Available -> {
                    _state.value = _state.value.copy(
                        updateChecking = false,
                        updateAvailable = true,
                        updateLatestName = result.latest.versionName,
                        updateNotes = result.release.body,
                        updateReleasesUrl = appUpdateManager.downloadOrReleasesUrl(result),
                        statusMessage = "Update available: ${result.latest.versionName}",
                        isError = false
                    )
                }
                is com.quickgit.app.data.UpdateCheckResult.Error -> {
                    _state.value = _state.value.copy(
                        updateChecking = false,
                        updateAvailable = false,
                        statusMessage = result.message,
                        isError = true
                    )
                }
            }
        }
    }

    fun openReleasesPageUrl(): String {
        return _state.value.updateReleasesUrl
            ?: appUpdateManager.releasesPageUrl(null)
    }

    fun dismissUpdatePrompt() {
        _state.value = _state.value.copy(
            updateAvailable = false,
            updateReleasesUrl = null
        )
    }





    private fun refreshAuthor() {
        _state.value = _state.value.copy(
            authorName = repoManager.getCommitAuthorName(),
            authorEmail = repoManager.getCommitAuthorEmail()
        )
    }

    fun setAuthorName(v: String) { _state.value = _state.value.copy(authorName = v) }
    fun setAuthorEmail(v: String) { _state.value = _state.value.copy(authorEmail = v) }

    fun saveAuthor() {
        val name = _state.value.authorName.trim()
        val email = _state.value.authorEmail.trim()
        if (name.isBlank() || email.isBlank()) {
            _state.value = _state.value.copy(
                statusMessage = "Name and email are required for commits",
                isError = true
            )
            return
        }
        if (!email.contains("@")) {
            _state.value = _state.value.copy(
                statusMessage = "Email looks invalid",
                isError = true
            )
            return
        }
        repoManager.setCommitAuthor(name, email)
        refreshAuthor()
        _state.value = _state.value.copy(
            statusMessage = "Commit identity saved — new commits will use $name <$email>",
            isError = false
        )
    }

    private fun verifyGitHubIfConnected() {
        if (!gitHubAccountManager.isConnected()) return
        viewModelScope.launch {
            val (account, result) = withContext(Dispatchers.IO) { gitHubAccountManager.refreshAccount() }
            if (account != null) {
                repoManager.seedCommitAuthorFromGitHub(
                    login = account.login,
                    displayName = account.name,
                    emailFromApi = account.email,
                    force = false
                )
                refreshAuthor()
                _state.value = _state.value.copy(
                    githubLogin = account.login,
                    githubName = account.name,
                    username = account.login,
                    hasStoredToken = true
                )
            } else if (result is PrOpResult.AuthRequired) {
                _state.value = _state.value.copy(
                    githubLogin = null,
                    githubName = null,
                    hasStoredToken = false
                )
            }
        }
    }

    private fun refreshReposRoot() {
        _state.value = _state.value.copy(
            reposRootPath = repoManager.reposRoot.absolutePath,
            reposRootIsUserChosen = repoManager.reposRootIsUserChosen
        )
    }

    /** Called with the tree the user picked from `ActivityResultContracts.OpenDocumentTree()`. */
    fun setReposRoot(treeUri: Uri) {
        when (val result = repoManager.setReposRootFromTree(treeUri)) {
            is RepoManager.SetReposRootResult.Success -> {
                refreshReposRoot()
                _state.value = _state.value.copy(
                    statusMessage = "Repos will now be stored in ${result.path.absolutePath}",
                    isError = false
                )
            }
            is RepoManager.SetReposRootResult.Error -> {
                _state.value = _state.value.copy(statusMessage = result.message, isError = true)
            }
        }
    }

    fun resetReposRoot() {
        repoManager.resetReposRootToDefault()
        refreshReposRoot()
        _state.value = _state.value.copy(
            statusMessage = "Reverted to the default storage location",
            isError = false
        )
    }

    fun setHost(host: String) {
        _state.value = _state.value.copy(host = host)
    }

    fun loadForHost(host: String) {
        val h = host.trim().ifBlank { "github.com" }
        val user = credentialStore.getHttpsUsername(h).orEmpty()
        val token = credentialStore.getHttpsToken(h).orEmpty()
        _state.value = _state.value.copy(
            host = h,
            username = user,
            // Don't put the real token back into the field (security); show placeholder state instead
            token = "",
            hasStoredToken = token.isNotEmpty(),
            statusMessage = null
        )
    }

    fun setUsername(v: String) { _state.value = _state.value.copy(username = v) }
    fun setToken(v: String) { _state.value = _state.value.copy(token = v) }
    fun setSshKey(v: String) { _state.value = _state.value.copy(sshKey = v) }
    fun setSshPassphrase(v: String) { _state.value = _state.value.copy(sshPassphrase = v) }

    fun saveHttpsToken() {
        val s = _state.value
        val host = s.host.trim()
        if (host.isBlank()) {
            _state.value = s.copy(statusMessage = "Host is required", isError = true)
            return
        }
        val token = s.token.trim()
        if (token.isBlank()) {
            _state.value = s.copy(
                statusMessage = if (s.hasStoredToken) "Enter a new token to replace the saved one"
                else "Token is required",
                isError = true
            )
            return
        }
        // For github.com, verify the token against the API and store the real login
        if (host.equals("github.com", ignoreCase = true)) {
            _state.value = s.copy(connecting = true, statusMessage = null)
            viewModelScope.launch {
                val (account, result) = withContext(Dispatchers.IO) {
                    gitHubAccountManager.connect(token, s.username.ifBlank { null })
                }
                when {
                    account != null -> {
                        repoManager.seedCommitAuthorFromGitHub(
                            login = account.login,
                            displayName = account.name,
                            emailFromApi = account.email,
                            force = false
                        )
                        refreshAuthor()
                        _state.value = _state.value.copy(
                            connecting = false,
                            token = "",
                            hasStoredToken = true,
                            username = account.login,
                            githubLogin = account.login,
                            githubName = account.name,
                            statusMessage = "Connected as @${account.login}",
                            isError = false
                        )
                    }
                    result is PrOpResult.AuthRequired -> {
                        _state.value = _state.value.copy(
                            connecting = false,
                            hasStoredToken = false,
                            githubLogin = null,
                            statusMessage = "Invalid token — check scopes include repo access",
                            isError = true
                        )
                    }
                    result is PrOpResult.Error -> {
                        _state.value = _state.value.copy(
                            connecting = false,
                            statusMessage = result.message,
                            isError = true
                        )
                    }
                    else -> {
                        _state.value = _state.value.copy(
                            connecting = false,
                            statusMessage = "Could not verify token",
                            isError = true
                        )
                    }
                }
            }
            return
        }
        // For hosts that look like GitLab, verify via GitLabAccountManager
        if (host.contains("gitlab", ignoreCase = true)) {
            connectGitLab(host, token, s.username.ifBlank { null })
            return
        }
        try {
            val user = s.username.trim().ifBlank { "x-access-token" }
            credentialStore.saveHttpsToken(host, user, token)
            _state.value = s.copy(
                token = "",
                hasStoredToken = true,
                statusMessage = "Saved token for $host",
                isError = false
            )
        } catch (e: Exception) {
            _state.value = s.copy(
                statusMessage = "Save failed: ${e.message ?: e.javaClass.simpleName}",
                isError = true
            )
        }
    }

    fun clearHttpsToken() {
        val host = _state.value.host.trim()
        try {
            when {
                host.equals("github.com", ignoreCase = true) -> gitHubAccountManager.disconnect()
                host.contains("gitlab", ignoreCase = true) -> gitLabAccountManager.disconnect(host)
                else -> credentialStore.clearHttpsToken(host)
            }
            _state.value = _state.value.copy(
                username = "",
                token = "",
                hasStoredToken = false,
                githubLogin = null,
                githubName = null,
                gitlabConnected = if (host.contains("gitlab", ignoreCase = true)) false else _state.value.gitlabConnected,
                gitlabUsername = if (host.contains("gitlab", ignoreCase = true)) null else _state.value.gitlabUsername,
                statusMessage = "Disconnected from $host",
                isError = false
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                statusMessage = "Clear failed: ${e.message}",
                isError = true
            )
        }
    }

    fun saveSshKey() {
        val s = _state.value
        val key = s.sshKey.trim()
        val pass = s.sshPassphrase.trim().ifBlank { null }
        try {
            if (key.isNotBlank()) {
                credentialStore.saveSshKey(key, pass)
            } else if (s.hasStoredSshKey) {
                // Allow updating passphrase alone when a key is already stored
                credentialStore.saveSshPassphrase(pass)
            } else {
                _state.value = s.copy(
                    statusMessage = "Paste a PEM private key first",
                    isError = true
                )
                return
            }
            refreshSsh()
            _state.value = _state.value.copy(
                sshKey = "",
                sshPassphrase = "",
                statusMessage = "SSH credentials saved",
                isError = false
            )
        } catch (e: Exception) {
            _state.value = s.copy(
                statusMessage = "Save failed: ${e.message ?: e.javaClass.simpleName}",
                isError = true
            )
        }
    }

    fun clearSshKey() {
        try {
            credentialStore.clearSshKey()
            _state.value = _state.value.copy(
                sshKey = "",
                sshPassphrase = "",
                hasStoredSshKey = false,
                statusMessage = "SSH key cleared",
                isError = false
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                statusMessage = "Clear failed: ${e.message}",
                isError = true
            )
        }
    }

    private fun refreshSsh() {
        _state.value = _state.value.copy(hasStoredSshKey = credentialStore.hasSshKey())
    }

    private fun refreshGpg() {
        _state.value = _state.value.copy(
            hasStoredGpgKey = credentialStore.hasGpgKey(),
            gpgSignEnabled = repoManager.isGpgSigningEnabled()
        )
    }

    fun setGpgKey(v: String) { _state.value = _state.value.copy(gpgKey = v) }
    fun setGpgPassphrase(v: String) { _state.value = _state.value.copy(gpgPassphrase = v) }

    fun reportGpgPasteEmpty() {
        _state.value = _state.value.copy(
            statusMessage = "Clipboard is empty — copy the armored key first, then tap Paste key from clipboard",
            isError = true
        )
    }

    fun setGpgSignEnabled(enabled: Boolean) {
        if (enabled && !credentialStore.hasGpgKey()) {
            _state.value = _state.value.copy(
                statusMessage = "Import a GPG secret key before enabling signing",
                isError = true
            )
            return
        }
        repoManager.setGpgSigningEnabled(enabled)
        _state.value = _state.value.copy(
            gpgSignEnabled = enabled,
            statusMessage = if (enabled) "Commit signing enabled" else "Commit signing disabled",
            isError = false
        )
    }

    fun saveGpgKey() {
        val s = _state.value
        val key = s.gpgKey.trim()
        val pass = s.gpgPassphrase.trim().ifBlank { null }
        try {
            if (key.isNotBlank()) {
                // Validate before persisting so we never store a key we cannot unlock
                val keyId = com.quickgit.app.data.GpgSupport.validateArmoredSecretKey(key, pass)
                credentialStore.saveGpgKey(key, pass)
                // Confirm it is readable from storage
                val stored = credentialStore.getGpgPrivateKey()
                if (stored.isNullOrBlank()) {
                    throw IllegalStateException("Key write reported success but nothing is stored — try again")
                }
                _state.value = s.copy(
                    gpgKey = "",
                    gpgPassphrase = "",
                    hasStoredGpgKey = true,
                    statusMessage = "GPG key saved (id …${keyId.takeLast(8)}). Enable signing below.",
                    isError = false
                )
                refreshGpg()
            } else if (s.hasStoredGpgKey) {
                credentialStore.saveGpgPassphrase(pass)
                val stored = credentialStore.getGpgPrivateKey()
                    ?: throw IllegalStateException("Stored GPG key is missing — paste the secret key again")
                com.quickgit.app.data.GpgSupport.validateArmoredSecretKey(stored, pass)
                _state.value = s.copy(
                    gpgPassphrase = "",
                    statusMessage = "GPG passphrase updated",
                    isError = false
                )
            } else {
                _state.value = s.copy(
                    statusMessage = "Paste an armored GPG secret key first (BEGIN PGP PRIVATE/SECRET KEY BLOCK)",
                    isError = true
                )
            }
        } catch (e: Exception) {
            val msg = deepestMessage(e)
            AppLog.e("SettingsViewModel", "saveGpgKey failed: $msg", e)
            _state.value = s.copy(
                // Keep gpgKey / passphrase in the fields so the user can fix and retry
                statusMessage = "GPG key error: $msg",
                isError = true
            )
        }
    }

    private fun deepestMessage(e: Throwable): String {
        var cur: Throwable? = e
        var last = e.message?.takeIf { it.isNotBlank() }
        while (cur != null) {
            cur.message?.takeIf { it.isNotBlank() }?.let { last = it }
            cur = cur.cause
        }
        return last ?: e.javaClass.simpleName
    }

    fun clearGpgKey() {
        try {
            credentialStore.clearGpgKey()
            repoManager.setGpgSigningEnabled(false)
            _state.value = _state.value.copy(
                gpgKey = "",
                gpgPassphrase = "",
                hasStoredGpgKey = false,
                gpgSignEnabled = false,
                statusMessage = "GPG key cleared",
                isError = false
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                statusMessage = "Clear failed: ${e.message}",
                isError = true
            )
        }
    }

    fun consumeStatus() {
        _state.value = _state.value.copy(statusMessage = null, isError = false)
    }

    // ---- GitLab ----

    private fun verifyGitLabIfConnected() {
        // Check common hosts; primary is gitlab.com or whatever is stored
        val candidates = listOf("gitlab.com") + listOfNotNull(
            _state.value.gitlabHost.takeIf { it.isNotBlank() && it != "gitlab.com" }
        )
        for (h in candidates) {
            if (!gitLabAccountManager.isConnected(h)) continue
            viewModelScope.launch {
                val (account, result) = withContext(Dispatchers.IO) {
                    gitLabAccountManager.refreshAccount(h)
                }
                if (account != null) {
                    _state.value = _state.value.copy(
                        gitlabHost = account.host,
                        gitlabUsername = account.username,
                        gitlabConnected = true
                    )
                } else if (result is PrOpResult.AuthRequired) {
                    _state.value = _state.value.copy(
                        gitlabUsername = null,
                        gitlabConnected = false
                    )
                }
            }
            break
        }
    }

    fun connectGitLab(host: String, token: String, username: String? = null) {
        val h = host.trim().ifBlank { "gitlab.com" }
        val t = token.trim()
        if (t.isBlank()) {
            _state.value = _state.value.copy(
                statusMessage = "GitLab token is required",
                isError = true
            )
            return
        }
        _state.value = _state.value.copy(connecting = true, statusMessage = null)
        viewModelScope.launch {
            val (account, result) = withContext(Dispatchers.IO) {
                gitLabAccountManager.connect(t, username, h)
            }
            when {
                account != null -> {
                    _state.value = _state.value.copy(
                        connecting = false,
                        gitlabHost = account.host,
                        gitlabUsername = account.username,
                        gitlabConnected = true,
                        statusMessage = "GitLab connected as @${account.username} on ${account.host}",
                        isError = false
                    )
                    // Also ensure the general HTTPS credential slot for this host is consistent
                    // (username + token already written by GitLabAccountManager.connect)
                }
                result is PrOpResult.AuthRequired -> {
                    _state.value = _state.value.copy(
                        connecting = false,
                        gitlabConnected = false,
                        statusMessage = "Invalid GitLab token for $h",
                        isError = true
                    )
                }
                result is PrOpResult.Error -> {
                    _state.value = _state.value.copy(
                        connecting = false,
                        statusMessage = result.message,
                        isError = true
                    )
                }
                else -> {
                    _state.value = _state.value.copy(
                        connecting = false,
                        statusMessage = "Could not verify GitLab token",
                        isError = true
                    )
                }
            }
        }
    }

    fun disconnectGitLab() {
        val h = _state.value.gitlabHost.ifBlank { "gitlab.com" }
        gitLabAccountManager.disconnect(h)
        _state.value = _state.value.copy(
            gitlabUsername = null,
            gitlabConnected = false,
            statusMessage = "Disconnected from GitLab ($h)",
            isError = false
        )
    }

    private fun refreshGerrit() {
        val h = gerritAccountManager.host.ifBlank {
            // Prefer a stored non-github/gitlab host if any — leave blank otherwise
            ""
        }
        val connected = h.isNotBlank() && gerritAccountManager.isConnected(h)
        _state.value = _state.value.copy(
            gerritHost = h,
            gerritUsername = if (connected) gerritAccountManager.storedUsername(h) else null,
            gerritConnected = connected
        )
    }

    fun connectGerrit(host: String, username: String, httpPassword: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(connecting = true, statusMessage = null)
            val (account, result) = withContext(Dispatchers.IO) {
                gerritAccountManager.connect(host, username, httpPassword)
            }
            when {
                account != null -> {
                    _state.value = _state.value.copy(
                        connecting = false,
                        gerritHost = account.host,
                        gerritUsername = account.username,
                        gerritConnected = true,
                        statusMessage = "Connected to Gerrit as ${account.username} on ${account.host}",
                        isError = false
                    )
                }
                result is PrOpResult.Error -> {
                    _state.value = _state.value.copy(
                        connecting = false,
                        statusMessage = result.message,
                        isError = true
                    )
                }
                result is PrOpResult.AuthRequired -> {
                    _state.value = _state.value.copy(
                        connecting = false,
                        statusMessage = "Gerrit authentication failed for ${result.host}",
                        isError = true
                    )
                }
                else -> {
                    _state.value = _state.value.copy(
                        connecting = false,
                        statusMessage = "Could not verify Gerrit credentials",
                        isError = true
                    )
                }
            }
        }
    }

    fun disconnectGerrit() {
        val h = _state.value.gerritHost
        gerritAccountManager.disconnect(h)
        _state.value = _state.value.copy(
            gerritHost = "",
            gerritUsername = null,
            gerritConnected = false,
            statusMessage = "Disconnected Gerrit",
            isError = false
        )
    }

}
