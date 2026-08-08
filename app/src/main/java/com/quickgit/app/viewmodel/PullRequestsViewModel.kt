package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.PullRequestManager
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.BranchInfo
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.data.models.MergeMethod
import com.quickgit.app.data.models.PrComment
import com.quickgit.app.data.models.PrOpResult
import com.quickgit.app.data.models.PrStateFilter
import com.quickgit.app.data.models.PullRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PullRequestsUiState(
    val supported: Boolean = true,
    val isGitLab: Boolean = false,
    val filter: PrStateFilter = PrStateFilter.OPEN,
    val pullRequests: List<PullRequest> = emptyList(),
    val localBranches: List<BranchInfo> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val authRequiredHost: String? = null,
    val statusMessage: String? = null,
    val selected: PullRequest? = null,
    val comments: List<PrComment> = emptyList(),
    val detailLoading: Boolean = false
)

class PullRequestsViewModel(
    private val repoManager: RepoManager,
    private val prManager: PullRequestManager
) : ViewModel() {

    private val _state = MutableStateFlow(PullRequestsUiState())
    val state: StateFlow<PullRequestsUiState> = _state.asStateFlow()

    private lateinit var repoPath: String
    private var project: PullRequestManager.ProjectRef? = null

    fun init(repoPath: String) {
        this.repoPath = repoPath
        viewModelScope.launch {
            val ref = withContext(Dispatchers.IO) { prManager.projectFor(repoPath) }
            if (ref == null) {
                _state.value = _state.value.copy(supported = false)
                return@launch
            }
            project = ref
            _state.value = _state.value.copy(supported = true, isGitLab = ref.isGitLab)
            refresh()
            val branches = withContext(Dispatchers.IO) { repoManager.listBranches(repoPath) }
            _state.value = _state.value.copy(localBranches = branches)
        }
    }

    fun setFilter(filter: PrStateFilter) {
        _state.value = _state.value.copy(filter = filter)
        refresh()
    }

    fun refresh() {
        val ref = project ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val (prs, result) = withContext(Dispatchers.IO) {
                prManager.listPullRequests(ref, _state.value.filter.apiValue)
            }
            _state.value = applyResult(_state.value.copy(loading = false, pullRequests = prs), result)
        }
    }

    fun openDetail(number: Int) {
        val ref = project ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(detailLoading = true, selected = null, comments = emptyList())
            val (pr, result) = withContext(Dispatchers.IO) { prManager.getPullRequest(ref, number) }
            val (comments, commentsResult) = withContext(Dispatchers.IO) { prManager.listComments(ref, number) }
            _state.value = applyResult(
                _state.value.copy(detailLoading = false, selected = pr, comments = comments),
                result.takeIf { it !is PrOpResult.Success } ?: commentsResult
            )
        }
    }

    fun closeDetail() {
        _state.value = _state.value.copy(selected = null, comments = emptyList())
    }

    fun createPullRequest(title: String, body: String, head: String, base: String, draft: Boolean) {
        val ref = project ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val (_, result) = withContext(Dispatchers.IO) {
                prManager.createPullRequest(ref, title, body, head, base, draft)
            }
            val msg = if (ref.isGitLab) "Merge request created" else "Pull request created"
            _state.value = applyResult(_state.value.copy(busy = false), result, successMessage = msg)
            if (result is PrOpResult.Success) refresh()
        }
    }

    fun merge(method: MergeMethod, commitTitle: String?) {
        val ref = project ?: return
        val number = _state.value.selected?.number ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { prManager.mergePullRequest(ref, number, method, commitTitle) }
            _state.value = applyResult(_state.value.copy(busy = false), result, successMessage = "Merged")
            if (result is PrOpResult.Success) openDetail(number)
        }
    }

    fun setOpen(open: Boolean) {
        val ref = project ?: return
        val number = _state.value.selected?.number ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { prManager.setPullRequestState(ref, number, open) }
            _state.value = applyResult(
                _state.value.copy(busy = false), result,
                successMessage = if (open) "Reopened" else "Closed"
            )
            if (result is PrOpResult.Success) openDetail(number)
        }
    }

    fun addComment(body: String) {
        val ref = project ?: return
        val number = _state.value.selected?.number ?: return
        if (body.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { prManager.addComment(ref, number, body) }
            _state.value = applyResult(_state.value.copy(busy = false), result)
            if (result is PrOpResult.Success) openDetail(number)
        }
    }

    fun checkoutLocally(onDone: (GitOpResult) -> Unit) {
        val ref = project ?: return
        val number = _state.value.selected?.number ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                prManager.checkoutPullRequestLocally(repoPath, number, isGitLab = ref.isGitLab)
            }
            _state.value = _state.value.copy(busy = false)
            onDone(result)
        }
    }

    fun consumeMessages() {
        _state.value = _state.value.copy(errorMessage = null, statusMessage = null, authRequiredHost = null)
    }

    private fun applyResult(
        base: PullRequestsUiState,
        result: PrOpResult,
        successMessage: String? = null
    ): PullRequestsUiState = when (result) {
        is PrOpResult.Success -> base.copy(statusMessage = successMessage)
        is PrOpResult.Error -> base.copy(errorMessage = result.message)
        is PrOpResult.AuthRequired -> base.copy(authRequiredHost = result.host)
    }
}
