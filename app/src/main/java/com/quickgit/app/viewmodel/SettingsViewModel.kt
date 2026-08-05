package com.quickgit.app.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.quickgit.app.data.CredentialStore
import com.quickgit.app.data.RepoManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(
    val host: String = "github.com",
    val username: String = "",
    val token: String = "",
    val hasStoredToken: Boolean = false,
    val sshKey: String = "",
    val sshPassphrase: String = "",
    val hasStoredSshKey: Boolean = false,
    val reposRootPath: String = "",
    val reposRootIsUserChosen: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false
)

class SettingsViewModel(
    private val credentialStore: CredentialStore,
    private val repoManager: RepoManager
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        loadForHost("github.com")
        refreshSsh()
        refreshReposRoot()
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
        // If field is empty but we already have a stored token, keep it unless they're clearing
        val token = s.token.trim()
        if (token.isBlank()) {
            _state.value = s.copy(
                statusMessage = if (s.hasStoredToken) "Enter a new token to replace the saved one"
                else "Token is required",
                isError = true
            )
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
            credentialStore.clearHttpsToken(host)
            _state.value = _state.value.copy(
                username = "",
                token = "",
                hasStoredToken = false,
                statusMessage = "Cleared token for $host",
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

    fun consumeStatus() {
        _state.value = _state.value.copy(statusMessage = null, isError = false)
    }
}
