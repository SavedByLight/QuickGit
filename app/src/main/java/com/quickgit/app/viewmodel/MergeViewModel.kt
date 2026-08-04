package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.FileChange
import com.quickgit.app.data.models.GitOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MergeUiState(
    val conflicts: List<FileChange> = emptyList(),
    /** True while resolving/aborting/finishing is running — used to disable buttons. */
    val busy: Boolean = false,
    /** True only while a pull-to-refresh (or the initial load) is in flight — drives the refresh indicator. */
    val refreshing: Boolean = false,
    val lastResult: GitOpResult? = null
)

class MergeViewModel(private val repoManager: RepoManager) : ViewModel() {
    private val _state = MutableStateFlow(MergeUiState())
    val state: StateFlow<MergeUiState> = _state.asStateFlow()
    private lateinit var repoPath: String

    fun init(repoPath: String) {
        this.repoPath = repoPath
        loadConflicts(showRefreshing = true)
    }

    /** Pull-to-refresh entry point — reloads conflicts and shows the refresh indicator while doing so. */
    fun refresh() = loadConflicts(showRefreshing = true)

    private fun loadConflicts(showRefreshing: Boolean) {
        viewModelScope.launch {
            if (showRefreshing) _state.value = _state.value.copy(refreshing = true)
            val status = withContext(Dispatchers.IO) { repoManager.getStatus(repoPath) }
            _state.value = _state.value.copy(
                conflicts = status.conflicting,
                refreshing = if (showRefreshing) false else _state.value.refreshing
            )
        }
    }

    fun conflictSides(filePath: String, onResult: (Triple<String?, String?, String?>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val sides = repoManager.getConflictSides(repoPath, filePath)
            withContext(Dispatchers.Main) { onResult(sides) }
        }
    }

    fun resolveWithContent(filePath: String, content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repoManager.writeResolvedContent(repoPath, filePath, content)
            repoManager.markResolved(repoPath, filePath)
            withContext(Dispatchers.Main) { loadConflicts(showRefreshing = false) }
        }
    }

    fun keepOursOrTheirs(filePath: String, keepOurs: Boolean, ours: String?, theirs: String?) {
        val content = if (keepOurs) ours else theirs
        if (content != null) resolveWithContent(filePath, content)
    }

    fun finishMerge(message: String, authorName: String, authorEmail: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                repoManager.continueMergeAsCommit(repoPath, message, authorName, authorEmail)
            }
            _state.value = _state.value.copy(busy = false, lastResult = result)
        }
    }

    fun abort() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.abortMerge(repoPath) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            loadConflicts(showRefreshing = false)
        }
    }

    fun consumeResult() { _state.value = _state.value.copy(lastResult = null) }
}
