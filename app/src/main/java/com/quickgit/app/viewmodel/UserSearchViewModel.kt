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
    val githubConnected: Boolean = false,
    val gitlabConnected: Boolean = false,
    val githubResults: List<GitHubApi.GitHubUserSummary> = emptyList(),
    val gitlabResults: List<GitLabApi.GitLabUserSummary> = emptyList(),
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
        _state.value = _state.value.copy(selectedTab = tab, searched = false)
        val q = _state.value.query.trim()
        if (q.length >= 2) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { search(q) }
        }
    }

    fun onQueryChange(q: String) {
        _state.value = _state.value.copy(query = q)
        searchJob?.cancel()
        if (q.trim().length < 2) {
            _state.value = _state.value.copy(
                githubResults = emptyList(),
                gitlabResults = emptyList(),
                searched = false,
                errorMessage = null
            )
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            search(q.trim())
        }
    }

    fun searchNow() {
        val q = _state.value.query.trim()
        if (q.length < 2) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(q) }
    }

    private suspend fun search(q: String) {
        val s = _state.value
        if (s.authRequired) {
            _state.value = s.copy(loading = false)
            return
        }
        _state.value = s.copy(loading = true, errorMessage = null)
        when (s.selectedTab) {
            UserSearchProvider.GITHUB -> {
                if (!s.githubConnected) {
                    _state.value = s.copy(loading = false, errorMessage = "Connect GitHub in Settings")
                    return
                }
                val (results, result) = withContext(Dispatchers.IO) { accountManager.searchUsers(q) }
                applyResult(result) {
                    copy(githubResults = results, searched = true, loading = false)
                }
            }
            UserSearchProvider.GITLAB -> {
                if (!s.gitlabConnected) {
                    _state.value = s.copy(loading = false, errorMessage = "Connect GitLab in Settings")
                    return
                }
                val host = if (gitLabAccountManager.isConnected(gitLabAccountManager.host))
                    gitLabAccountManager.host else "gitlab.com"
                val (results, result) = withContext(Dispatchers.IO) {
                    gitLabAccountManager.searchUsers(q, host)
                }
                applyResult(result) {
                    copy(gitlabResults = results, searched = true, loading = false)
                }
            }
        }
    }

    private fun applyResult(result: PrOpResult, ok: UserSearchUiState.() -> UserSearchUiState) {
        when (result) {
            is PrOpResult.Success -> _state.value = _state.value.ok()
            is PrOpResult.AuthRequired ->
                _state.value = _state.value.copy(loading = false, authRequired = true, searched = true)
            is PrOpResult.Error ->
                _state.value = _state.value.copy(
                    loading = false, searched = true, errorMessage = result.message
                )
        }
    }
}
