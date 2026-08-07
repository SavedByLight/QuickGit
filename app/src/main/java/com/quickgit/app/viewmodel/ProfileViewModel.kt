package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GerritAccountManager
import com.quickgit.app.data.GitHubAccountManager
import com.quickgit.app.data.GitLabAccountManager
import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.models.GitHubRemoteRepo
import com.quickgit.app.data.models.PrOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConnectedProviderSummary(
    val provider: String,
    val username: String,
    val detail: String?
)

data class ProfileUiState(
    val loading: Boolean = false,
    val user: GitHubApi.GitHubUser? = null,
    val repos: List<GitHubRemoteRepo> = emptyList(),
    val isSelf: Boolean = false,
    val errorMessage: String? = null,
    val authRequired: Boolean = false,
    val forkingRepoId: Long? = null,
    val statusMessage: String? = null,
    val forkedRepo: GitHubRemoteRepo? = null,
    val forkError: String? = null,
    /** Self-profile only: accounts connected across providers. */
    val connectedProviders: List<ConnectedProviderSummary> = emptyList()
)

class ProfileViewModel(
    private val accountManager: GitHubAccountManager,
    private val gitLabAccountManager: GitLabAccountManager,
    private val gerritAccountManager: GerritAccountManager
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private var currentLogin: String? = null

    fun load(login: String?) {
        currentLogin = login
        viewModelScope.launch {
            _state.value = ProfileUiState(loading = true)
            val selfLogin = if (accountManager.isConnected()) {
                withContext(Dispatchers.IO) { accountManager.refreshAccount() }.first?.login
            } else null

            val target = login?.trim()?.takeIf { it.isNotEmpty() } ?: selfLogin
            val isSelf = target == null || (selfLogin != null && target.equals(selfLogin, ignoreCase = true))

            val providers = if (isSelf) loadConnectedProviders() else emptyList()

            if (target == null && providers.isEmpty()) {
                _state.value = ProfileUiState(
                    loading = false,
                    authRequired = true,
                    isSelf = true,
                    errorMessage = "Connect GitHub, GitLab, or Gerrit in Settings."
                )
                return@launch
            }

            if (target != null && accountManager.isConnected()) {
                val (user, userResult) = withContext(Dispatchers.IO) {
                    accountManager.getUserProfile(target)
                }
                when {
                    user != null -> {
                        val (repos, _) = withContext(Dispatchers.IO) {
                            accountManager.listPublicRepos(user.login)
                        }
                        _state.value = ProfileUiState(
                            loading = false,
                            user = user,
                            repos = repos,
                            isSelf = isSelf,
                            connectedProviders = providers
                        )
                    }
                    userResult is PrOpResult.AuthRequired -> {
                        _state.value = ProfileUiState(
                            loading = false,
                            authRequired = true,
                            isSelf = isSelf,
                            connectedProviders = providers,
                            errorMessage = "GitHub auth required"
                        )
                    }
                    else -> {
                        // Still show connected providers if self
                        _state.value = ProfileUiState(
                            loading = false,
                            isSelf = isSelf,
                            connectedProviders = providers,
                            errorMessage = (userResult as? PrOpResult.Error)?.message
                                ?: if (providers.isEmpty()) "User not found" else null
                        )
                    }
                }
            } else {
                _state.value = ProfileUiState(
                    loading = false,
                    isSelf = isSelf,
                    connectedProviders = providers,
                    errorMessage = if (providers.isEmpty()) "Connect an account in Settings." else null
                )
            }
        }
    }

    private suspend fun loadConnectedProviders(): List<ConnectedProviderSummary> {
        val list = mutableListOf<ConnectedProviderSummary>()
        if (accountManager.isConnected()) {
            val (account, _) = withContext(Dispatchers.IO) { accountManager.refreshAccount() }
            if (account != null) {
                list += ConnectedProviderSummary("GitHub", account.login, account.name)
            }
        }
        val glHost = when {
            gitLabAccountManager.isConnected(gitLabAccountManager.host) -> gitLabAccountManager.host
            gitLabAccountManager.isConnected("gitlab.com") -> "gitlab.com"
            else -> null
        }
        if (glHost != null) {
            val (account, _) = withContext(Dispatchers.IO) { gitLabAccountManager.refreshAccount(glHost) }
            if (account != null) {
                list += ConnectedProviderSummary("GitLab", account.username, "$glHost · ${account.name ?: ""}".trimEnd(' ', '·'))
            }
        }
        val geHost = gerritAccountManager.primaryHost()
            ?: gerritAccountManager.host.takeIf { gerritAccountManager.isConnected(it) }
        if (geHost != null && gerritAccountManager.isConnected(geHost)) {
            val (account, _) = withContext(Dispatchers.IO) { gerritAccountManager.refreshAccount(geHost) }
            if (account != null) {
                list += ConnectedProviderSummary(
                    "Gerrit",
                    account.username,
                    listOfNotNull(account.host, account.email).joinToString(" · ")
                )
            }
        }
        return list
    }

    fun fork(repo: GitHubRemoteRepo) {
        viewModelScope.launch {
            _state.value = _state.value.copy(forkingRepoId = repo.id, forkError = null, statusMessage = null)
            val (forked, result) = withContext(Dispatchers.IO) {
                accountManager.forkRepo(repo.ownerLogin, repo.name)
            }
            when {
                forked != null -> _state.value = _state.value.copy(
                    forkingRepoId = null,
                    forkedRepo = forked,
                    statusMessage = "Forked to ${forked.fullName}"
                )
                result is PrOpResult.Error -> _state.value = _state.value.copy(
                    forkingRepoId = null,
                    forkError = result.message
                )
                result is PrOpResult.AuthRequired -> _state.value = _state.value.copy(
                    forkingRepoId = null,
                    authRequired = true
                )
                else -> _state.value = _state.value.copy(forkingRepoId = null, forkError = "Fork failed")
            }
        }
    }

    fun consumeMessages() {
        _state.value = _state.value.copy(statusMessage = null, forkError = null, forkedRepo = null)
    }
}
