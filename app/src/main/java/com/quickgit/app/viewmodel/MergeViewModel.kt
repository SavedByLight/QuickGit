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
    val busy: Boolean = false,
    val lastResult: GitOpResult? = null
)

class MergeViewModel(private val repoManager: RepoManager) : ViewModel() {
    private val _state = MutableStateFlow(MergeUiState())
    val state: StateFlow<MergeUiState> = _state.asStateFlow()
    private lateinit var repoPath: String

    fun init(repoPath: String) {
        this.repoPath = repoPath
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val status = withContext(Dispatchers.IO) { repoManager.getStatus(repoPath) }
            _state.value = _state.value.copy(conflicts = status.conflicting, busy = false)
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
            withContext(Dispatchers.Main) { refresh() }
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
            refresh()
        }
    }

    fun consumeResult() { _state.value = _state.value.copy(lastResult = null) }
}
