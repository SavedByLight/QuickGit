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
    /** null = current HEAD / default log; otherwise a ref like "fork/main". */
    val logRef: String? = null,
    /** Remote-tracking branches available for viewing (for cherry-pick from forks). */
    val remoteBranches: List<String> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val lastResult: GitOpResult? = null,
    /** Currently expanded commit for viewing its changes / tree. */
    val selectedCommitId: String? = null,
    val selectedChanges: List<com.quickgit.app.data.models.CommitChange> = emptyList(),
    val changesLoading: Boolean = false,
    val parentCommitId: String? = null
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
                val ref = _state.value.logRef
                val commits = withContext(Dispatchers.IO) {
                    repoManager.getLog(repoPath, startRef = ref)
                }
                val remoteBranches = withContext(Dispatchers.IO) {
                    repoManager.listBranches(repoPath)
                        .filter { it.isRemote }
                        .map { it.name }
                        .sorted()
                }
                _state.value = _state.value.copy(
                    commits = commits,
                    remoteBranches = remoteBranches,
                    loading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = e.message ?: "Failed to load history"
                )
            }
        }
    }

    /** Show commits reachable from [ref] (e.g. "fork/feature") or HEAD if null. */
    fun setLogRef(ref: String?) {
        _state.value = _state.value.copy(logRef = ref?.takeIf { it.isNotBlank() })
        refreshHistory()
    }

    fun cherryPick(commitHash: String) {
        if (!::repoPath.isInitialized) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null, statusMessage = null)
            val result = withContext(Dispatchers.IO) { repoManager.cherryPick(repoPath, commitHash) }
            when (result) {
                is GitOpResult.Success -> {
                    // After cherry-pick, show current branch history so the new commit is visible
                    val commits = withContext(Dispatchers.IO) {
                        repoManager.getLog(repoPath, startRef = null)
                    }
                    _state.value = _state.value.copy(
                        commits = commits,
                        logRef = null,
                        loading = false,
                        statusMessage = "Cherry-picked ${commitHash.take(7)} onto current branch",
                        lastResult = result
                    )
                }
                is GitOpResult.Conflict -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        errorMessage = "Cherry-pick conflict in: ${result.paths.joinToString()}. Resolve in the repo screen.",
                        lastResult = result
                    )
                }
                is GitOpResult.Error -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        errorMessage = result.message,
                        lastResult = result
                    )
                }
                else -> {
                    _state.value = _state.value.copy(
                        loading = false,
                        lastResult = result
                    )
                }
            }
        }
    }

    fun revertCommit(commitHash: String, message: String? = null) {
        if (!::repoPath.isInitialized) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null, statusMessage = null)
            val result = withContext(Dispatchers.IO) { repoManager.revertCommit(repoPath, commitHash, message) }
            if (result is GitOpResult.Success) {
                val commits = withContext(Dispatchers.IO) { repoManager.getLog(repoPath) }
                _state.value = _state.value.copy(commits = commits)
            }
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

    /** Expand a commit to show its changed files (and enable tree browse). Toggle off if already selected. */
    fun selectCommit(commitId: String?) {
        if (commitId == null || commitId == _state.value.selectedCommitId) {
            _state.value = _state.value.copy(
                selectedCommitId = null,
                selectedChanges = emptyList(),
                parentCommitId = null,
                changesLoading = false
            )
            return
        }
        if (!::repoPath.isInitialized) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                selectedCommitId = commitId,
                selectedChanges = emptyList(),
                changesLoading = true,
                parentCommitId = null
            )
            try {
                val changes = withContext(Dispatchers.IO) {
                    repoManager.listCommitChanges(repoPath, commitId)
                }
                val parent = withContext(Dispatchers.IO) {
                    repoManager.getParentCommitId(repoPath, commitId)
                }
                _state.value = _state.value.copy(
                    selectedChanges = changes,
                    parentCommitId = parent,
                    changesLoading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    changesLoading = false,
                    errorMessage = e.message ?: "Failed to load commit changes"
                )
            }
        }
    }
}
