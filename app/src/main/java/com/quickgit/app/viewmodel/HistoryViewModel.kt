package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.CommitInfo
import com.quickgit.app.data.models.GitOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HistoryUiState(
    val commits: List<CommitInfo> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val lastResult: GitOpResult? = null
)

class HistoryViewModel(private val repoManager: RepoManager) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    private lateinit var repoPath: String

    fun init(repoPath: String) {
        this.repoPath = repoPath
        refreshHistory()
    }

    fun refreshHistory() {
        if (!::repoPath.isInitialized) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null, statusMessage = null)
            try {
                val commits = withContext(Dispatchers.IO) { repoManager.getLog(repoPath) }
                _state.value = _state.value.copy(commits = commits, loading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = e.message ?: "Failed to load history"
                )
            }
        }
    }

    fun revertCommit(commitHash: String) {
        if (!::repoPath.isInitialized) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null, statusMessage = null)
            val result = withContext(Dispatchers.IO) { repoManager.revertCommit(repoPath, commitHash) }
            _state.value = when (result) {
                is GitOpResult.Success -> _state.value.copy(
                    loading = false,
                    statusMessage = "Reverted $commitHash",
                    lastResult = result
                )
                is GitOpResult.UpToDate -> _state.value.copy(
                    loading = false,
                    statusMessage = result.message,
                    lastResult = result
                )
                is GitOpResult.Error -> _state.value.copy(
                    loading = false,
                    errorMessage = result.message,
                    lastResult = result
                )
                else -> _state.value.copy(
                    loading = false,
                    statusMessage = "Operation completed",
                    lastResult = result
                )
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(errorMessage = null, statusMessage = null)
    }

    fun consumeResult() {
        _state.value = _state.value.copy(lastResult = null)
    }
}
