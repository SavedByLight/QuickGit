package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GitHubAccountManager
import com.quickgit.app.data.GitLabAccountManager
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.PrOpResult
import com.quickgit.app.data.models.RepoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CreateRepoProvider {
    GITHUB,
    GITLAB
}

class RepoListViewModel(
    private val repoManager: RepoManager,
    private val accountManager: GitHubAccountManager,
    private val gitLabAccountManager: GitLabAccountManager
) : ViewModel() {

    private val _repos = MutableStateFlow<List<RepoInfo>>(emptyList())
    val repos: StateFlow<List<RepoInfo>> = _repos.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _account = MutableStateFlow<GitHubAccountManager.ConnectedAccount?>(null)
    val account: StateFlow<GitHubAccountManager.ConnectedAccount?> = _account.asStateFlow()

    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _authRequired = MutableStateFlow(false)
    val authRequired: StateFlow<Boolean> = _authRequired.asStateFlow()

    init {
        refresh()
        loadAccount()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _repos.value = withContext(Dispatchers.IO) { repoManager.listLocalRepos() }
            _loading.value = false
            loadAccount()
        }
    }

    private fun loadAccount() {
        if (!accountManager.isConnected()) {
            _account.value = null
            return
        }
        viewModelScope.launch {
            val (account, _) = withContext(Dispatchers.IO) { accountManager.refreshAccount() }
            _account.value = account
        }
    }

    fun isGitHubConnected(): Boolean = accountManager.isConnected()

    fun isGitLabConnected(): Boolean {
        val h = gitLabAccountManager.host
        return gitLabAccountManager.isConnected(h) || gitLabAccountManager.isConnected("gitlab.com")
    }

    fun availableCreateProviders(): List<CreateRepoProvider> = buildList {
        if (isGitHubConnected()) add(CreateRepoProvider.GITHUB)
        if (isGitLabConnected()) add(CreateRepoProvider.GITLAB)
    }

    fun canCreateRemoteRepo(): Boolean = availableCreateProviders().isNotEmpty()

    fun createRepo(
        name: String,
        description: String?,
        isPrivate: Boolean,
        provider: CreateRepoProvider
    ) {
        when (provider) {
            CreateRepoProvider.GITHUB -> createOnGitHub(name, description, isPrivate)
            CreateRepoProvider.GITLAB -> createOnGitLab(name, description, isPrivate)
        }
    }

    private fun createOnGitHub(name: String, description: String?, isPrivate: Boolean) {
        if (!accountManager.isConnected()) {
            _authRequired.value = true
            _errorMessage.value = "Connect a GitHub account in Settings to create a repository"
            return
        }
        viewModelScope.launch {
            _creating.value = true
            _errorMessage.value = null
            val (repo, result) = withContext(Dispatchers.IO) {
                accountManager.createRepo(name, description, isPrivate)
            }
            _creating.value = false
            when (result) {
                is PrOpResult.Success -> {
                    _statusMessage.value = "Created ${repo?.fullName ?: name.trim()} on GitHub"
                }
                is PrOpResult.AuthRequired -> {
                    _authRequired.value = true
                    _errorMessage.value = "GitHub authentication required"
                }
                is PrOpResult.Error -> {
                    _errorMessage.value = result.message
                }
            }
        }
    }

    private fun createOnGitLab(name: String, description: String?, isPrivate: Boolean) {
        val h = when {
            gitLabAccountManager.isConnected(gitLabAccountManager.host) -> gitLabAccountManager.host
            gitLabAccountManager.isConnected("gitlab.com") -> "gitlab.com"
            else -> null
        }
        if (h == null) {
            _authRequired.value = true
            _errorMessage.value = "Connect a GitLab account in Settings to create a project"
            return
        }
        viewModelScope.launch {
            _creating.value = true
            _errorMessage.value = null
            val (project, result) = withContext(Dispatchers.IO) {
                gitLabAccountManager.createProject(name, description, isPrivate, h)
            }
            _creating.value = false
            when (result) {
                is PrOpResult.Success -> {
                    _statusMessage.value =
                        "Created ${project?.pathWithNamespace ?: name.trim()} on GitLab"
                }
                is PrOpResult.AuthRequired -> {
                    _authRequired.value = true
                    _errorMessage.value = "GitLab authentication required"
                }
                is PrOpResult.Error -> {
                    _errorMessage.value = result.message
                }
            }
        }
    }

    fun consumeMessages() {
        _statusMessage.value = null
        _errorMessage.value = null
        _authRequired.value = false
    }

    fun deleteRepo(repo: RepoInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.dir.deleteRecursively()
            withContext(Dispatchers.Main) { refresh() }
        }
    }
}
