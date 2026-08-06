package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GitHubAccountManager
import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.models.GitHubRemoteRepo
import com.quickgit.app.data.models.PrOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProfileUiState(
    val loading: Boolean = false,
    val user: GitHubApi.GitHubUser? = null,
    val repos: List<GitHubRemoteRepo> = emptyList(),
    val isSelf: Boolean = false,
    val errorMessage: String? = null,
    val authRequired: Boolean = false
)

class ProfileViewModel(
    private val accountManager: GitHubAccountManager
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    fun load(login: String? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null, authRequired = false)
            if (!accountManager.isConnected()) {
                _state.value = _state.value.copy(loading = false, authRequired = true)
                return@launch
            }
            val (user, userResult) = withContext(Dispatchers.IO) {
                accountManager.getUserProfile(login)
            }
            if (userResult is PrOpResult.AuthRequired) {
                _state.value = _state.value.copy(loading = false, authRequired = true)
                return@launch
            }
            if (user == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = (userResult as? PrOpResult.Error)?.message ?: "User not found"
                )
                return@launch
            }
            val selfLogin = accountManager.storedUsername()
            val (repos, _) = withContext(Dispatchers.IO) {
                accountManager.listPublicRepos(user.login)
            }
            _state.value = _state.value.copy(
                loading = false,
                user = user,
                repos = repos,
                isSelf = selfLogin != null && selfLogin.equals(user.login, ignoreCase = true)
            )
        }
    }

    fun consumeError() {
        _state.value = _state.value.copy(errorMessage = null)
    }
}
