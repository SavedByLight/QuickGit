package com.quickgit.app.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GerritAccountManager
import com.quickgit.app.data.GitHubAccountManager
import com.quickgit.app.data.GitLabAccountManager
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.GerritProject
import com.quickgit.app.data.models.GitHubRemoteRepo
import com.quickgit.app.data.models.GitLabProject
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.data.models.PrOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class BrowseGitHubUiState(
    val githubConnected: Boolean = false,
    val gitlabConnected: Boolean = false,
    val gerritConnected: Boolean = false,
    val githubLogin: String? = null,
    val gitlabUsername: String? = null,
    val gerritUsername: String? = null,
    val gerritHost: String? = null,
    val loading: Boolean = false,
    val cloning: Boolean = false,
    val creating: Boolean = false,
    val progressText: String = "",
    val githubRepos: List<GitHubRemoteRepo> = emptyList(),
    val gitlabProjects: List<GitLabProject> = emptyList(),
    val gerritProjects: List<GerritProject> = emptyList(),
    val query: String = "",
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val authRequired: Boolean = false,
    val cloningKey: String? = null,
    val cloneResult: GitOpResult? = null,
    val destinationPath: String? = null,
    val destinationError: String? = null
)

class BrowseGitHubViewModel(
    private val accountManager: GitHubAccountManager,
    private val gitLabAccountManager: GitLabAccountManager,
    private val gerritAccountManager: GerritAccountManager,
    private val repoManager: RepoManager
) : ViewModel() {

    private val _state = MutableStateFlow(BrowseGitHubUiState())
    val state: StateFlow<BrowseGitHubUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var pendingCloneUrl: String? = null
    private var pendingCloneName: String? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val ghConnected = accountManager.isConnected()
            val glHost = gitLabAccountManager.host
            val glConnected = gitLabAccountManager.isConnected(glHost) ||
                listOf("gitlab.com").any { gitLabAccountManager.isConnected(it) }
            val gerritHost = gerritAccountManager.primaryHost()
                ?: gerritAccountManager.host.takeIf { gerritAccountManager.isConnected(it) }
            val gerritConnected = gerritHost != null && gerritAccountManager.isConnected(gerritHost)

            if (!ghConnected && !glConnected && !gerritConnected) {
                _state.value = BrowseGitHubUiState(authRequired = true)
                return@launch
            }

            _state.value = _state.value.copy(
                loading = true,
                errorMessage = null,
                authRequired = false,
                githubConnected = ghConnected,
                gitlabConnected = glConnected,
                gerritConnected = gerritConnected,
                gerritHost = gerritHost
            )

            if (ghConnected) {
                val (account, _) = withContext(Dispatchers.IO) { accountManager.refreshAccount() }
                if (account != null) {
                    _state.value = _state.value.copy(
                        githubLogin = account.login,
                        githubConnected = true
                    )
                }
            }
            if (glConnected) {
                val host = if (gitLabAccountManager.isConnected(glHost)) glHost else "gitlab.com"
                val (account, _) = withContext(Dispatchers.IO) { gitLabAccountManager.refreshAccount(host) }
                if (account != null) {
                    _state.value = _state.value.copy(
                        gitlabUsername = account.username,
                        gitlabConnected = true
                    )
                }
            }
            if (gerritConnected && gerritHost != null) {
                val (account, _) = withContext(Dispatchers.IO) { gerritAccountManager.refreshAccount(gerritHost) }
                if (account != null) {
                    _state.value = _state.value.copy(
                        gerritUsername = account.username,
                        gerritHost = account.host,
                        gerritConnected = true
                    )
                }
            }

            loadAll()
        }
    }

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            loadAll()
        }
    }

    private fun loadAll() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
            val q = _state.value.query.trim()
            val errors = mutableListOf<String>()

            val ghDeferred = async(Dispatchers.IO) {
                if (!_state.value.githubConnected) return@async
                val (repos, result) = if (q.isBlank()) accountManager.listRepos()
                else accountManager.searchRepos(q)
                when (result) {
                    is PrOpResult.Success -> {
                        _state.value = _state.value.copy(githubRepos = repos)
                    }
                    is PrOpResult.AuthRequired -> {
                        _state.value = _state.value.copy(githubConnected = false, githubRepos = emptyList())
                    }
                    is PrOpResult.Error -> {
                        errors += "GitHub: ${result.message}"
                        _state.value = _state.value.copy(githubRepos = emptyList())
                    }
                }
            }

            val glDeferred = async(Dispatchers.IO) {
                if (!_state.value.gitlabConnected) return@async
                val host = gitLabAccountManager.host.takeIf { gitLabAccountManager.isConnected(it) }
                    ?: "gitlab.com".takeIf { gitLabAccountManager.isConnected(it) }
                    ?: return@async
                val (projects, result) = if (q.isBlank()) gitLabAccountManager.listProjects(host)
                else gitLabAccountManager.searchProjects(q, host)
                when (result) {
                    is PrOpResult.Success -> {
                        _state.value = _state.value.copy(gitlabProjects = projects)
                    }
                    is PrOpResult.AuthRequired -> {
                        _state.value = _state.value.copy(gitlabConnected = false, gitlabProjects = emptyList())
                    }
                    is PrOpResult.Error -> {
                        errors += "GitLab: ${result.message}"
                        _state.value = _state.value.copy(gitlabProjects = emptyList())
                    }
                }
            }

            val geDeferred = async(Dispatchers.IO) {
                if (!_state.value.gerritConnected) return@async
                val host = _state.value.gerritHost
                    ?: gerritAccountManager.primaryHost()
                    ?: return@async
                val (projects, result) = if (q.isBlank()) gerritAccountManager.listProjects(host)
                else gerritAccountManager.searchProjects(q, host)
                when (result) {
                    is PrOpResult.Success -> {
                        _state.value = _state.value.copy(gerritProjects = projects)
                    }
                    is PrOpResult.AuthRequired -> {
                        _state.value = _state.value.copy(gerritConnected = false, gerritProjects = emptyList())
                    }
                    is PrOpResult.Error -> {
                        errors += "Gerrit: ${result.message}"
                        _state.value = _state.value.copy(gerritProjects = emptyList())
                    }
                }
            }

            ghDeferred.await()
            glDeferred.await()
            geDeferred.await()

            _state.value = _state.value.copy(
                loading = false,
                errorMessage = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
            )
        }
    }

    fun onDestinationPicked(treeUri: Uri) {
        viewModelScope.launch {
            when (val result = repoManager.resolveCloneDestination(treeUri)) {
                is RepoManager.ResolveCloneDestinationResult.Success -> {
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
                    _state.value = _state.value.copy(destinationError = result.message)
                }
            }
        }
    }

    fun cloneGitHubRepo(repo: GitHubRemoteRepo, useSsh: Boolean = false) {
        val url = if (useSsh) repo.sshUrl else repo.cloneUrl
        val target = File(repoManager.reposRoot, repo.name)
        if (repoManager.reposRoot.canWrite()) {
            doClone(url, repo.name, target, "gh:${repo.id}")
        } else {
            doClone(url, repo.name, File(repoManager.reposRoot, repo.name), "gh:${repo.id}")
        }
    }

    fun cloneGitHubRepoAfterPickingFolder(repo: GitHubRemoteRepo, useSsh: Boolean = false) {
        pendingCloneUrl = if (useSsh) repo.sshUrl else repo.cloneUrl
        pendingCloneName = repo.name
    }

    fun cloneGitLabProject(project: GitLabProject, useSsh: Boolean = false) {
        val url = if (useSsh) project.sshUrlToRepo else project.httpUrlToRepo
        val folder = project.pathWithNamespace.substringAfterLast('/')
        doClone(url, folder, File(repoManager.reposRoot, folder), "gl:${project.id}")
    }

    fun cloneGitLabProjectAfterPickingFolder(project: GitLabProject, useSsh: Boolean = false) {
        pendingCloneUrl = if (useSsh) project.sshUrlToRepo else project.httpUrlToRepo
        pendingCloneName = project.pathWithNamespace.substringAfterLast('/')
    }

    fun cloneGerritProject(project: GerritProject, useSsh: Boolean = false) {
        val url = if (useSsh) project.sshUrl else project.cloneUrl
        val folder = project.name.substringAfterLast('/')
        doClone(url, folder, File(repoManager.reposRoot, folder), "ge:${project.id}")
    }

    fun cloneGerritProjectAfterPickingFolder(project: GerritProject, useSsh: Boolean = false) {
        pendingCloneUrl = if (useSsh) project.sshUrl else project.cloneUrl
        pendingCloneName = project.name.substringAfterLast('/')
    }

    private fun doClone(url: String, folderName: String, destination: File, key: String? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                cloning = true,
                cloningKey = key,
                progressText = "Cloning…",
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
                cloningKey = null,
                cloneResult = result,
                statusMessage = if (result is GitOpResult.Success) "Cloned $folderName" else null,
                errorMessage = (result as? GitOpResult.Error)?.message
            )
        }
    }

    fun createRepo(name: String, description: String?, isPrivate: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(creating = true, errorMessage = null)
            val (repo, result) = withContext(Dispatchers.IO) {
                accountManager.createRepo(name, description, isPrivate)
            }
            _state.value = when (result) {
                is PrOpResult.Success -> _state.value.copy(
                    creating = false,
                    githubRepos = if (repo != null) listOf(repo) + _state.value.githubRepos else _state.value.githubRepos,
                    statusMessage = "Created ${repo?.name ?: name.trim()}"
                )
                is PrOpResult.AuthRequired -> _state.value.copy(
                    creating = false, authRequired = true, githubConnected = false
                )
                is PrOpResult.Error -> _state.value.copy(creating = false, errorMessage = result.message)
            }
        }
    }

    fun consumeMessages() {
        _state.value = _state.value.copy(
            errorMessage = null,
            statusMessage = null,
            cloneResult = null
        )
    }
}
