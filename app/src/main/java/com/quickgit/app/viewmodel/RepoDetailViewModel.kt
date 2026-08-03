package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.data.models.RepoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RepoDetailUiState(
    val status: RepoStatus? = null,
    val branch: String = "",
    val busy: Boolean = false,
    val commitMessage: String = "",
    val lastResult: GitOpResult? = null,
    val authorName: String = "Mobile User",
    val authorEmail: String = "mobile@example.com"
)

class RepoDetailViewModel(private val repoManager: RepoManager) : ViewModel() {
    private val _state = MutableStateFlow(RepoDetailUiState())
    val state: StateFlow<RepoDetailUiState> = _state.asStateFlow()

    private lateinit var repoPath: String

    fun init(repoPath: String) {
        this.repoPath = repoPath
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val status = withContext(Dispatchers.IO) { repoManager.getStatus(repoPath) }
            val branch = withContext(Dispatchers.IO) {
                repoManager.openGit(repoPath).use { it.repository.branch }
            }
            _state.value = _state.value.copy(status = status, branch = branch ?: "", busy = false)
        }
    }

    fun toggleStage(filePath: String, currentlyStaged: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (currentlyStaged) repoManager.unstage(repoPath, listOf(filePath))
            else repoManager.stage(repoPath, listOf(filePath))
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun stageAll() {
        viewModelScope.launch(Dispatchers.IO) {
            repoManager.stageAll(repoPath)
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    fun discard(filePath: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                repoManager.discardChanges(repoPath, listOf(filePath))
            }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) refresh()
        }
    }

    fun setCommitMessage(msg: String) { _state.value = _state.value.copy(commitMessage = msg) }

    fun commit() {
        val msg = _state.value.commitMessage
        if (msg.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                repoManager.commit(repoPath, msg, _state.value.authorName, _state.value.authorEmail)
            }
            _state.value = _state.value.copy(busy = false, lastResult = result, commitMessage = "")
            refresh()
        }
    }

    fun push() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.push(repoPath) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
        }
    }

    fun pull() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.pull(repoPath) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            refresh()
        }
    }

    fun consumeResult() { _state.value = _state.value.copy(lastResult = null) }
}
