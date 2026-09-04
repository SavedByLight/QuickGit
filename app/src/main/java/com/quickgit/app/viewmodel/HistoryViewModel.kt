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
    /** null = current HEAD / default log; otherwise a local branch or remote-tracking ref. */
    val logRef: String? = null,
    /** Other local branches (not current) for cherry-pick browsing. */
    val localBranches: List<String> = emptyList(),
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
    val parentCommitId: String? = null,
    /** Commit ids marked for multi cherry-pick (from another branch/remote). */
    val cherryPickSelection: Set<String> = emptySet()
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
                val allBranches = withContext(Dispatchers.IO) {
                    repoManager.listBranches(repoPath)
                }
                val localBranches = allBranches
                    .filter { !it.isRemote && !it.isCurrent }
                    .map { it.name }
                    .sorted()
                val remoteBranches = allBranches
                    .filter { it.isRemote }
                    .map { it.name }
                    .sorted()
                _state.value = _state.value.copy(
                    commits = commits,
                    localBranches = localBranches,
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
        _state.value = _state.value.copy(
            logRef = ref?.takeIf { it.isNotBlank() },
            cherryPickSelection = emptySet()
        )
        refreshHistory()
    }

    fun toggleCherryPickSelection(commitId: String) {
        val cur = _state.value.cherryPickSelection
        _state.value = _state.value.copy(
            cherryPickSelection = if (commitId in cur) cur - commitId else cur + commitId
        )
    }

    fun clearCherryPickSelection() {
        _state.value = _state.value.copy(cherryPickSelection = emptySet())
    }

    fun cherryPick(commitHash: String) {
        cherryPick(listOf(commitHash))
    }

    fun cherryPickSelected() {
        val ids = _state.value.cherryPickSelection.toList()
        if (ids.isEmpty()) return
        cherryPick(ids)
    }

    fun cherryPick(commitHashes: List<String>) {
        if (!::repoPath.isInitialized) return
        if (commitHashes.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null, statusMessage = null)
            val result = withContext(Dispatchers.IO) {
                repoManager.cherryPick(repoPath, commitHashes)
            }
            when (result) {
                is GitOpResult.Success -> {
                    // After cherry-pick, show current branch history so the new commits are visible
                    val commits = withContext(Dispatchers.IO) {
                        repoManager.getLog(repoPath, startRef = null)
                    }
                    val label = if (commitHashes.size == 1) {
                        "Cherry-picked ${commitHashes.first().take(7)} onto current branch"
                    } else {
                        "Cherry-picked ${commitHashes.size} commits onto current branch"
                    }
                    _state.value = _state.value.copy(
                        commits = commits,
                        logRef = null,
                        cherryPickSelection = emptySet(),
                        loading = false,
                        statusMessage = label,
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

    /**
     * Hard-reset current branch to [commitHash] (`git reset --hard`).
     * Discards all uncommitted changes and moves the branch tip to that commit.
     */
    fun hardReset(commitHash: String) {
        if (!::repoPath.isInitialized) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null, statusMessage = null)
            val result = withContext(Dispatchers.IO) { repoManager.hardReset(repoPath, commitHash) }
            when (result) {
                is GitOpResult.Success -> {
                    // After hard reset, always show current branch history at the new tip
                    val commits = withContext(Dispatchers.IO) {
                        repoManager.getLog(repoPath, startRef = null)
                    }
                    _state.value = _state.value.copy(
                        commits = commits,
                        logRef = null,
                        selectedCommitId = null,
                        selectedChanges = emptyList(),
                        parentCommitId = null,
                        loading = false,
                        statusMessage = "Hard reset to ${commitHash.take(7)}",
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
