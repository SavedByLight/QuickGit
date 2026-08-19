package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.RepoEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CommitTreeUiState(
    val entries: List<RepoEntry> = emptyList(),
    val currentPath: String = "",
    val loading: Boolean = false,
    val error: String? = null
)

class CommitTreeViewModel(private val repoManager: RepoManager) : ViewModel() {

    private val _state = MutableStateFlow(CommitTreeUiState())
    val state: StateFlow<CommitTreeUiState> = _state.asStateFlow()

    private var repoPath: String = ""
    private var commitId: String = ""

    fun init(repoPath: String, commitId: String, path: String = "") {
        this.repoPath = repoPath
        this.commitId = commitId
        load(path)
    }

    fun refresh() {
        load(_state.value.currentPath)
    }

    private fun load(path: String) {
        if (repoPath.isBlank() || commitId.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, currentPath = path)
            try {
                val entries = withContext(Dispatchers.IO) {
                    repoManager.listTreeAtCommit(repoPath, commitId, path)
                }
                _state.value = _state.value.copy(entries = entries, loading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Failed to list tree"
                )
            }
        }
    }
}
