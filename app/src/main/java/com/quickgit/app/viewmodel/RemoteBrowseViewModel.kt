package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GitHubAccountManager
import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.models.PrOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RemoteBrowseUiState(
    val owner: String = "",
    val repo: String = "",
    val ref: String = "",
    val currentPath: String = "",
    val entries: List<GitHubApi.RemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val authRequired: Boolean = false
)

/** Read-only directory listing for someone else's (or your own) repo, straight from the GitHub API — no clone. */
class RemoteBrowseViewModel(
    private val accountManager: GitHubAccountManager
) : ViewModel() {

    private val _state = MutableStateFlow(RemoteBrowseUiState())
    val state: StateFlow<RemoteBrowseUiState> = _state.asStateFlow()

    fun init(owner: String, repo: String, ref: String, path: String) {
        _state.value = _state.value.copy(owner = owner, repo = repo, ref = ref)
        openDir(path)
    }

    fun openDir(path: String) {
        val owner = _state.value.owner
        val repo = _state.value.repo
        val ref = _state.value.ref
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, currentPath = path, authRequired = false)
            val (entries, result) = withContext(Dispatchers.IO) {
                accountManager.getRepoContents(owner, repo, path, ref)
            }
            _state.value = when (result) {
                is PrOpResult.Success -> _state.value.copy(loading = false, entries = entries)
                is PrOpResult.AuthRequired -> _state.value.copy(loading = false, authRequired = true)
                is PrOpResult.Error -> _state.value.copy(loading = false, error = result.message)
            }
        }
    }

    fun goUp() {
        val current = _state.value.currentPath
        if (current.isBlank()) return
        openDir(current.substringBeforeLast('/', missingDelimiterValue = ""))
    }

    fun consumeError() {
        _state.value = _state.value.copy(error = null)
    }
}
