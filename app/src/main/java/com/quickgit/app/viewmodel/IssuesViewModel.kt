package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.IssueManager
import com.quickgit.app.data.models.Issue
import com.quickgit.app.data.models.IssueStateFilter
import com.quickgit.app.data.models.PrComment
import com.quickgit.app.data.models.PrOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IssuesUiState(
    val supported: Boolean = true,
    val filter: IssueStateFilter = IssueStateFilter.OPEN,
    val issues: List<Issue> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val authRequiredHost: String? = null,
    val statusMessage: String? = null,
    val selected: Issue? = null,
    val comments: List<PrComment> = emptyList(),
    val detailLoading: Boolean = false
)

class IssuesViewModel(private val issueManager: IssueManager) : ViewModel() {

    private val _state = MutableStateFlow(IssuesUiState())
    val state: StateFlow<IssuesUiState> = _state.asStateFlow()

    private lateinit var repoPath: String
    private var owner: String? = null
    private var repo: String? = null

    fun init(repoPath: String) {
        this.repoPath = repoPath
        viewModelScope.launch {
            val ownerRepo = withContext(Dispatchers.IO) { issueManager.ownerRepoFor(repoPath) }
            if (ownerRepo == null) {
                _state.value = _state.value.copy(supported = false)
                return@launch
            }
            owner = ownerRepo.owner
            repo = ownerRepo.repo
            refresh()
        }
    }

    fun setFilter(filter: IssueStateFilter) {
        _state.value = _state.value.copy(filter = filter)
        refresh()
    }

    fun refresh() {
        val o = owner ?: return
        val r = repo ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val (issues, result) = withContext(Dispatchers.IO) {
                issueManager.listIssues(o, r, _state.value.filter.apiValue)
            }
            _state.value = applyResult(_state.value.copy(loading = false, issues = issues), result)
        }
    }

    fun openDetail(number: Int) {
        val o = owner ?: return
        val r = repo ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(detailLoading = true, selected = null, comments = emptyList())
            val (issue, result) = withContext(Dispatchers.IO) { issueManager.getIssue(o, r, number) }
            val (comments, commentsResult) = withContext(Dispatchers.IO) { issueManager.listComments(o, r, number) }
            _state.value = applyResult(
                _state.value.copy(detailLoading = false, selected = issue, comments = comments),
                result.takeIf { it !is PrOpResult.Success } ?: commentsResult
            )
        }
    }

    fun closeDetail() {
        _state.value = _state.value.copy(selected = null, comments = emptyList())
    }

    fun createIssue(title: String, body: String) {
        val o = owner ?: return
        val r = repo ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val (_, result) = withContext(Dispatchers.IO) { issueManager.createIssue(o, r, title, body) }
            _state.value = applyResult(_state.value.copy(busy = false), result, successMessage = "Issue created")
            if (result is PrOpResult.Success) refresh()
        }
    }

    fun setOpen(open: Boolean) {
        val o = owner ?: return
        val r = repo ?: return
        val number = _state.value.selected?.number ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { issueManager.setIssueState(o, r, number, open) }
            _state.value = applyResult(
                _state.value.copy(busy = false),
                result,
                successMessage = if (open) "Reopened" else "Closed"
            )
            if (result is PrOpResult.Success) openDetail(number)
        }
    }

    fun addComment(body: String) {
        val o = owner ?: return
        val r = repo ?: return
        val number = _state.value.selected?.number ?: return
        if (body.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { issueManager.addComment(o, r, number, body) }
            _state.value = applyResult(_state.value.copy(busy = false), result)
            if (result is PrOpResult.Success) openDetail(number)
        }
    }

    fun consumeMessages() {
        _state.value = _state.value.copy(errorMessage = null, statusMessage = null, authRequiredHost = null)
    }

    private fun applyResult(
        base: IssuesUiState,
        result: PrOpResult,
        successMessage: String? = null
    ): IssuesUiState = when (result) {
        is PrOpResult.Success -> base.copy(statusMessage = successMessage)
        is PrOpResult.Error -> base.copy(errorMessage = result.message)
        is PrOpResult.AuthRequired -> base.copy(authRequiredHost = result.host)
    }
}
