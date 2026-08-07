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
    /** True while a git operation (stage, commit, push, pull, discard...) is running — used to disable buttons. */
    val busy: Boolean = false,
    /** True only while a pull-to-refresh (or the initial load) is in flight — drives the refresh indicator. */
    val refreshing: Boolean = false,
    val commitMessage: String = "",
    val lastResult: GitOpResult? = null,
    val authorName: String = "",
    val authorEmail: String = "",
    /** Append Signed-off-by trailer on commit (git commit -s). */
    val signOff: Boolean = false
)

class RepoDetailViewModel(private val repoManager: RepoManager) : ViewModel() {
    private val _state = MutableStateFlow(RepoDetailUiState())
    val state: StateFlow<RepoDetailUiState> = _state.asStateFlow()

    private lateinit var repoPath: String

    fun init(repoPath: String) {
        this.repoPath = repoPath
        _state.value = _state.value.copy(
            authorName = repoManager.getCommitAuthorName(),
            authorEmail = repoManager.getCommitAuthorEmail(),
            signOff = repoManager.isSignOffEnabled()
        )
        loadStatus(showRefreshing = true)
    }

    /** Pull-to-refresh entry point — reloads status and shows the refresh indicator while doing so. */
    fun refresh() = loadStatus(showRefreshing = true)

    private fun loadStatus(showRefreshing: Boolean) {
        viewModelScope.launch {
            if (showRefreshing) _state.value = _state.value.copy(refreshing = true)
            val status = withContext(Dispatchers.IO) { repoManager.getStatus(repoPath) }
            val branch = withContext(Dispatchers.IO) {
                repoManager.openGit(repoPath).use { it.repository.branch }
            }
            _state.value = _state.value.copy(
                status = status,
                branch = branch ?: "",
                refreshing = if (showRefreshing) false else _state.value.refreshing
            )
        }
    }

    fun toggleStage(filePath: String, currentlyStaged: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                if (currentlyStaged) repoManager.unstage(repoPath, listOf(filePath))
                else repoManager.stage(repoPath, listOf(filePath))
            }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    fun stageAll() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.stageAll(repoPath) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    fun unstageAll() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.unstageAll(repoPath) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    fun discard(filePath: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                repoManager.discardChanges(repoPath, listOf(filePath))
            }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    /** "Revert all" — discards every unstaged/untracked change in one go. */
    fun discardAll() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.discardAll(repoPath) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    fun setCommitMessage(msg: String) { _state.value = _state.value.copy(commitMessage = msg) }

    fun setSignOff(enabled: Boolean) {
        repoManager.setSignOffEnabled(enabled)
        _state.value = _state.value.copy(signOff = enabled)
    }

    fun commit() {
        val msg = _state.value.commitMessage
        if (msg.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                repoManager.commit(
                    repoPath,
                    msg,
                    _state.value.authorName,
                    _state.value.authorEmail,
                    signOff = _state.value.signOff
                )
            }
            _state.value = _state.value.copy(busy = false, lastResult = result, commitMessage = "")
            loadStatus(showRefreshing = false)
        }
    }

    fun push(force: Boolean = false, forceWithLease: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                repoManager.push(repoPath, force = force, forceWithLease = forceWithLease)
            }
            _state.value = _state.value.copy(busy = false, lastResult = result)
        }
    }

    /**
     * Push current HEAD to Gerrit for code review (`refs/for/<branch>`).
     * Optional [topic] becomes `refs/for/<branch>%topic=<topic>`.
     */
    fun pushForReview(topic: String? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                repoManager.pushForReview(repoPath, topic = topic)
            }
            _state.value = _state.value.copy(busy = false, lastResult = result)
        }
    }

    fun pull() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.pull(repoPath) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            loadStatus(showRefreshing = false)
        }
    }

    fun fetchLfs() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.fetchLfs(repoPath) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    fun consumeResult() { _state.value = _state.value.copy(lastResult = null) }
}
