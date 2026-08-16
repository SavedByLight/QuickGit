package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GitHubAccountManager
import com.quickgit.app.data.models.PrOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RemoteFileUiState(
    val path: String = "",
    val content: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val authRequired: Boolean = false
)

/** Read-only view of a single file's content in someone else's (or your own) repo — no clone. */
class RemoteFileViewModel(
    private val accountManager: GitHubAccountManager
) : ViewModel() {

    private val _state = MutableStateFlow(RemoteFileUiState())
    val state: StateFlow<RemoteFileUiState> = _state.asStateFlow()

    fun load(owner: String, repo: String, ref: String, path: String) {
        viewModelScope.launch {
            _state.value = RemoteFileUiState(path = path, loading = true)
            val (content, result) = withContext(Dispatchers.IO) {
                accountManager.getFileContent(owner, repo, path, ref)
            }
            _state.value = when (result) {
                is PrOpResult.Success -> _state.value.copy(loading = false, content = content ?: "")
                is PrOpResult.AuthRequired -> _state.value.copy(loading = false, authRequired = true)
                is PrOpResult.Error -> _state.value.copy(loading = false, error = result.message)
            }
        }
    }

    /** Clear the one-shot auth signal so returning from Settings does not re-navigate. */
    fun consumeAuthRequired() {
        _state.value = _state.value.copy(authRequired = false)
    }
}
