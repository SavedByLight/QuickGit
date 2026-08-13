package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GitHubAccountManager
import com.quickgit.app.data.GitLabAccountManager
import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.gitlab.GitLabApi
import com.quickgit.app.data.models.PrOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UserSearchProvider { GITHUB, GITLAB }

data class UserSearchUiState(
    val selectedTab: UserSearchProvider = UserSearchProvider.GITHUB,
    val query: String = "",
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val githubConnected: Boolean = false,
    val gitlabConnected: Boolean = false,
    val githubResults: List<GitHubApi.GitHubUserSummary> = emptyList(),
    val gitlabResults: List<GitLabApi.GitLabUserSummary> = emptyList(),
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
    val authRequired: Boolean = false,
    val searched: Boolean = false
)

class UserSearchViewModel(
    private val accountManager: GitHubAccountManager,
    private val gitLabAccountManager: GitLabAccountManager
) : ViewModel() {

    private val _state = MutableStateFlow(UserSearchUiState())
    val state: StateFlow<UserSearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var page = 1
    private val pageSize = 100
    private var lastQuery: String = ""

    init {
        val gh = accountManager.isConnected()
        val gl = gitLabAccountManager.isConnected(gitLabAccountManager.host) ||
            gitLabAccountManager.isConnected("gitlab.com")
        val tab = when {
            gh -> UserSearchProvider.GITHUB
            gl -> UserSearchProvider.GITLAB
            else -> UserSearchProvider.GITHUB
        }
        _state.value = UserSearchUiState(
            selectedTab = tab,
            githubConnected = gh,
            gitlabConnected = gl
        )
    }

    fun selectTab(tab: UserSearchProvider) {
        _state.value = _state.value.copy(
            selectedTab = tab,
            searched = false,
            hasMore = false,
            githubResults = emptyList(),
            gitlabResults = emptyList()
        )
        page = 1
        val q = _state.value.query.trim()
        if (q.length >= 2) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { search(q, reset = true) }
        }
    }

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
        searchJob?.cancel()
        if (q.trim().length < 2) {
            page = 1
            lastQuery = ""
            _state.value = _state.value.copy(
                githubResults = emptyList(),
                gitlabResults = emptyList(),
                searched = false,
                hasMore = false,
                errorMessage = null
            )
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            search(q.trim(), reset = true)
        }
    }

    fun searchNow() {
        val q = _state.value.query.trim()
        if (q.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(q, reset = true) }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.hasMore || s.loadingMore || s.loading) return
        val q = lastQuery.ifBlank { s.query.trim() }
        if (q.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(q, reset = false) }
    }

    private suspend fun search(q: String, reset: Boolean) {
        val s = _state.value
        if (s.authRequired) {
            _state.value = s.copy(loading = false, loadingMore = false)
            return
        }
        if (reset) {
            page = 1
            lastQuery = q
            _state.value = s.copy(
                loading = true,
                loadingMore = false,
                errorMessage = null,
                hasMore = false
            )
        } else {
            _state.value = s.copy(loadingMore = true, errorMessage = null)
        }
        val requestPage = if (reset) 1 else page + 1
        when (s.selectedTab) {
            UserSearchProvider.GITHUB -> {
                if (!s.githubConnected) {
                    _state.value = _state.value.copy(
                        loading = false,
                        loadingMore = false,
                        errorMessage = "Connect GitHub in Settings"
                    )
                    return
                }
                val (results, hasMore, result) = withContext(Dispatchers.IO) {
                    accountManager.searchUsers(q, page = requestPage, perPage = pageSize)
                }
                applyResult(result, reset) {
                    val merged = if (reset) results else {
                        val seen = githubResults.map { it.login }.toHashSet()
                        githubResults + results.filter { it.login !in seen }
                    }
                    copy(
                        githubResults = merged,
                        hasMore = hasMore,
                        searched = true,
                        loading = false,
                        loadingMore = false
                    )
                }
                if (result is PrOpResult.Success) page = requestPage
            }
            UserSearchProvider.GITLAB -> {
                if (!s.gitlabConnected) {
                    _state.value = _state.value.copy(
                        loading = false,
                        loadingMore = false,
                        errorMessage = "Connect GitLab in Settings"
                    )
                    return
                }
                val host = if (gitLabAccountManager.isConnected(gitLabAccountManager.host))
                    gitLabAccountManager.host else "gitlab.com"
                val (results, hasMore, result) = withContext(Dispatchers.IO) {
                    gitLabAccountManager.searchUsers(q, host, page = requestPage, perPage = pageSize)
                }
                applyResult(result, reset) {
                    val merged = if (reset) results else {
                        val seen = gitlabResults.map { it.id }.toHashSet()
                        gitlabResults + results.filter { it.id !in seen }
                    }
                    copy(
                        gitlabResults = merged,
                        hasMore = hasMore,
                        searched = true,
                        loading = false,
                        loadingMore = false
                    )
                }
                if (result is PrOpResult.Success) page = requestPage
            }
        }
    }

    private fun applyResult(
        result: PrOpResult,
        reset: Boolean,
        ok: UserSearchUiState.() -> UserSearchUiState
    ) {
        when (result) {
            is PrOpResult.Success -> _state.value = _state.value.ok()
            is PrOpResult.AuthRequired ->
                _state.value = _state.value.copy(
                    loading = false,
                    loadingMore = false,
                    authRequired = true,
                    searched = true
                )
            is PrOpResult.Error ->
                _state.value = _state.value.copy(
                    loading = false,
                    loadingMore = false,
                    searched = true,
                    errorMessage = result.message
                )
        }
    }
}
