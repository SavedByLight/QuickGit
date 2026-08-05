package com.quickgit.app.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GitHubAccountManager
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.GitHubRemoteRepo
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.data.models.PrOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class BrowseGitHubUiState(
    val connected: Boolean = false,
    val accountLogin: String? = null,
    val accountName: String? = null,
    val loading: Boolean = false,
    val cloning: Boolean = false,
    val progressText: String = "",
    val repos: List<GitHubRemoteRepo> = emptyList(),
    val query: String = "",
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val authRequired: Boolean = false,
    val cloningRepoId: Long? = null,
    val cloneResult: GitOpResult? = null,
    val destinationPath: String? = null,
    val destinationError: String? = null
)

class BrowseGitHubViewModel(
    private val accountManager: GitHubAccountManager,
    private val repoManager: RepoManager
) : ViewModel() {

    private val _state = MutableStateFlow(BrowseGitHubUiState())
    val state: StateFlow<BrowseGitHubUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var pickedDestination: File? = null
    private var pendingCloneUrl: String? = null
    private var pendingCloneName: String? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val connected = accountManager.isConnected()
            if (!connected) {
                _state.value = BrowseGitHubUiState(connected = false, authRequired = true)
                return@launch
            }
            _state.value = _state.value.copy(loading = true, errorMessage = null, authRequired = false)
            val (account, accountResult) = withContext(Dispatchers.IO) { accountManager.refreshAccount() }
            if (accountResult is PrOpResult.AuthRequired || account == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    connected = false,
                    authRequired = true,
                    errorMessage = if (accountResult is PrOpResult.Error) accountResult.message
                    else "Sign in with a GitHub token in Settings"
                )
                return@launch
            }
            val (repos, listResult) = withContext(Dispatchers.IO) {
                if (_state.value.query.isBlank()) accountManager.listRepos()
                else accountManager.searchRepos(_state.value.query)
            }
            _state.value = applyListResult(
                _state.value.copy(
                    loading = false,
                    connected = true,
                    accountLogin = account.login,
                    accountName = account.name,
                    repos = repos
                ),
                listResult
            )
        }
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            loadRepos()
        }
    }

    private fun loadRepos() {
        if (!accountManager.isConnected()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
            val (repos, result) = withContext(Dispatchers.IO) {
                if (_state.value.query.isBlank()) accountManager.listRepos()
                else accountManager.searchRepos(_state.value.query)
            }
            _state.value = applyListResult(
                _state.value.copy(loading = false, repos = repos),
                result
            )
        }
    }

    fun onDestinationPicked(treeUri: Uri) {
        when (val result = repoManager.resolveCloneDestination(treeUri)) {
            is RepoManager.ResolveCloneDestinationResult.Success -> {
                pickedDestination = result.path
                _state.value = _state.value.copy(
                    destinationPath = result.path.absolutePath,
                    destinationError = null
                )
                val url = pendingCloneUrl
                val name = pendingCloneName
                if (url != null && name != null) {
                    pendingCloneUrl = null
                    pendingCloneName = null
                    doClone(url, name, result.path)
                }
            }
            is RepoManager.ResolveCloneDestinationResult.Error -> {
                pickedDestination = null
                _state.value = _state.value.copy(
                    destinationPath = null,
                    destinationError = result.message
                )
            }
        }
    }

    fun cloneRepo(repo: GitHubRemoteRepo, useSsh: Boolean = false) {
        val url = if (useSsh) repo.sshUrl else repo.cloneUrl
        val dest = pickedDestination
        if (dest != null) {
            val target = if (dest.listFiles()?.isEmpty() != false) dest else File(dest, repo.name)
            doClone(url, repo.name, target, repo.id)
        } else {
            doClone(url, repo.name, File(repoManager.reposRoot, repo.name), repo.id)
        }
    }

    fun cloneRepoAfterPickingFolder(repo: GitHubRemoteRepo, useSsh: Boolean = false) {
        pendingCloneUrl = if (useSsh) repo.sshUrl else repo.cloneUrl
        pendingCloneName = repo.name
    }

    private fun doClone(url: String, folderName: String, destination: File, repoId: Long? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                cloning = true,
                cloningRepoId = repoId,
                progressText = "Starting…",
                cloneResult = null,
                errorMessage = null
            )
            val result = withContext(Dispatchers.IO) {
                repoManager.cloneRepo(url, destination) { progress ->
                    _state.value = _state.value.copy(progressText = progress)
                }
            }
            _state.value = _state.value.copy(
                cloning = false,
                cloningRepoId = null,
                cloneResult = result,
                statusMessage = if (result is GitOpResult.Success) "Cloned $folderName" else null,
                errorMessage = (result as? GitOpResult.Error)?.message
            )
        }
    }

    fun consumeMessages() {
        _state.value = _state.value.copy(
            errorMessage = null,
            statusMessage = null,
            cloneResult = null
        )
    }

    private fun applyListResult(base: BrowseGitHubUiState, result: PrOpResult): BrowseGitHubUiState =
        when (result) {
            is PrOpResult.Success -> base
            is PrOpResult.Error -> base.copy(errorMessage = result.message)
            is PrOpResult.AuthRequired -> base.copy(authRequired = true, connected = false)
        }
}
