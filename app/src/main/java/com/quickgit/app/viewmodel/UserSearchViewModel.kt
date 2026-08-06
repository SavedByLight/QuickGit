package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GitHubAccountManager
import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.models.PrOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UserSearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<GitHubApi.GitHubUserSummary> = emptyList(),
    val errorMessage: String? = null,
    val authRequired: Boolean = false,
    val searched: Boolean = false
)

class UserSearchViewModel(
    private val accountManager: GitHubAccountManager
) : ViewModel() {

    private val _state = MutableStateFlow(UserSearchUiState())
    val state: StateFlow<UserSearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        searchJob?.cancel()
        if (q.trim().length < 2) {
            _state.value = _state.value.copy(results = emptyList(), searched = false, loading = false)
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            search(q.trim())
        }
    }

    fun searchNow() {
        searchJob?.cancel()
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        searchJob = viewModelScope.launch { search(q) }
    }

    private suspend fun search(q: String) {
        if (!accountManager.isConnected()) {
            _state.value = _state.value.copy(authRequired = true, loading = false)
            return
        }
        _state.value = _state.value.copy(loading = true, errorMessage = null, authRequired = false)
        val (results, result) = withContext(Dispatchers.IO) { accountManager.searchUsers(q) }
        when (result) {
            is PrOpResult.AuthRequired ->
                _state.value = _state.value.copy(loading = false, authRequired = true, searched = true)
            is PrOpResult.Error ->
                _state.value = _state.value.copy(
                    loading = false,
                    searched = true,
                    results = emptyList(),
                    errorMessage = result.message
                )
            is PrOpResult.Success ->
                _state.value = _state.value.copy(
                    loading = false,
                    searched = true,
                    results = results,
                    errorMessage = null
                )
        }
    }

    fun consumeError() {
        _state.value = _state.value.copy(errorMessage = null)
    }
}
