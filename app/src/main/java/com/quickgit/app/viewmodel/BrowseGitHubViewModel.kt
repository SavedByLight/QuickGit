package com.quickgit.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GitHubAccountManager
import com.quickgit.app.data.GitLabAccountManager
import com.quickgit.app.data.GitProgressNotifier
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.GitHubRemoteRepo
import com.quickgit.app.data.models.GitLabProject
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

enum class BrowseProviderTab { GITHUB, GITLAB }

data class BrowseGitHubUiState(
    val selectedTab: BrowseProviderTab = BrowseProviderTab.GITHUB,
    val githubConnected: Boolean = false,
    val gitlabConnected: Boolean = false,
    val githubLogin: String? = null,
    val gitlabUsername: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val cloning: Boolean = false,
    val creating: Boolean = false,
    val progressText: String = "",
    val githubRepos: List<GitHubRemoteRepo> = emptyList(),
    val gitlabProjects: List<GitLabProject> = emptyList(),
    val githubPage: Int = 0,
    val gitlabPage: Int = 0,
    val githubHasMore: Boolean = false,
    val gitlabHasMore: Boolean = false,
    val githubLoaded: Boolean = false,
    val gitlabLoaded: Boolean = false,
    val query: String = "",
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val authRequired: Boolean = false,
    val cloningKey: String? = null,
    val cloneResult: GitOpResult? = null
)

class BrowseGitHubViewModel(
    private val accountManager: GitHubAccountManager,
    private val gitLabAccountManager: GitLabAccountManager,
    private val repoManager: RepoManager,
    app: Application
) : ViewModel() {

    private val _state = MutableStateFlow(BrowseGitHubUiState())
    val state: StateFlow<BrowseGitHubUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var pendingCloneUrl: String? = null
    private var pendingCloneName: String? = null
    private val notifier = GitProgressNotifier(app)

    companion object {
        const val PAGE_SIZE = 100
    }

    init {
        refreshConnections()
    }

    fun refreshConnections() {
        viewModelScope.launch {
            val ghConnected = accountManager.isConnected()
            val glHost = resolveGitLabHost()
            val glConnected = glHost != null

            if (!ghConnected && !glConnected) {
                _state.value = BrowseGitHubUiState(authRequired = true)
                return@launch
            }

            // Default tab: GitHub if connected, else GitLab
            val defaultTab = when {
                ghConnected -> BrowseProviderTab.GITHUB
                else -> BrowseProviderTab.GITLAB
            }

            _state.value = _state.value.copy(
                authRequired = false,
                githubConnected = ghConnected,
                gitlabConnected = glConnected,
                selectedTab = if (_state.value.githubLoaded || _state.value.gitlabLoaded)
                    _state.value.selectedTab
                else defaultTab,
                // reset lists so we reload active tab
                githubLoaded = false,
                gitlabLoaded = false,
                githubRepos = emptyList(),
                gitlabProjects = emptyList(),
                githubPage = 0,
                gitlabPage = 0
            )

            if (ghConnected) {
                val (account, _) = withContext(Dispatchers.IO) { accountManager.refreshAccount() }
                if (account != null) {
                    _state.value = _state.value.copy(githubLogin = account.login, githubConnected = true)
                }
            }
            if (glConnected && glHost != null) {
                val (account, _) = withContext(Dispatchers.IO) { gitLabAccountManager.refreshAccount(glHost) }
                if (account != null) {
                    _state.value = _state.value.copy(gitlabUsername = account.username, gitlabConnected = true)
                }
            }

            ensureTabLoaded(_state.value.selectedTab)
        }
    }

    fun refresh() = refreshConnections()

    fun selectTab(tab: BrowseProviderTab) {
        _state.value = _state.value.copy(selectedTab = tab)
        ensureTabLoaded(tab)
    }

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350)
            // Reset loaded state for all tabs so active tab reloads with query
            _state.value = _state.value.copy(
                githubLoaded = false,
                gitlabLoaded = false,
                githubRepos = emptyList(),
                gitlabProjects = emptyList(),
                githubPage = 0,
                gitlabPage = 0
            )
            ensureTabLoaded(_state.value.selectedTab)
        }
    }

    private fun ensureTabLoaded(tab: BrowseProviderTab) {
        val s = _state.value
        val already = when (tab) {
            BrowseProviderTab.GITHUB -> s.githubLoaded
            BrowseProviderTab.GITLAB -> s.gitlabLoaded
        }
        if (already) return
        loadPage(tab, reset = true)
    }

    fun loadMore() {
        val tab = _state.value.selectedTab
        val hasMore = when (tab) {
            BrowseProviderTab.GITHUB -> _state.value.githubHasMore
            BrowseProviderTab.GITLAB -> _state.value.gitlabHasMore
        }
        if (!hasMore || _state.value.loadingMore || _state.value.loading) return
        loadPage(tab, reset = false)
    }

    private fun loadPage(tab: BrowseProviderTab, reset: Boolean) {
        viewModelScope.launch {
            if (reset) {
                _state.value = _state.value.copy(loading = true, errorMessage = null)
            } else {
                _state.value = _state.value.copy(loadingMore = true, errorMessage = null)
            }
            val q = _state.value.query.trim()
            when (tab) {
                BrowseProviderTab.GITHUB -> loadGitHubPage(q, reset)
                BrowseProviderTab.GITLAB -> loadGitLabPage(q, reset)
            }
        }
    }

    private suspend fun loadGitHubPage(query: String, reset: Boolean) {
        if (!_state.value.githubConnected) {
            _state.value = _state.value.copy(loading = false, loadingMore = false, githubLoaded = true)
            return
        }
        val nextPage = if (reset) 1 else _state.value.githubPage + 1
        val (batch, hasMore, result) = withContext(Dispatchers.IO) {
            if (query.isBlank()) accountManager.listReposPage(page = nextPage, perPage = PAGE_SIZE)
            else accountManager.searchReposPage(query, page = nextPage, perPage = PAGE_SIZE)
        }
        when (result) {
            is PrOpResult.AuthRequired -> _state.value = _state.value.copy(
                loading = false, loadingMore = false, githubConnected = false, githubLoaded = true
            )
            is PrOpResult.Error -> _state.value = _state.value.copy(
                loading = false, loadingMore = false, githubLoaded = true,
                errorMessage = "GitHub: ${result.message}"
            )
            is PrOpResult.Success -> {
                val merged = if (reset) batch else _state.value.githubRepos + batch
                _state.value = _state.value.copy(
                    loading = false,
                    loadingMore = false,
                    githubRepos = merged,
                    githubPage = nextPage,
                    githubHasMore = hasMore,
                    githubLoaded = true
                )
                // After first personal page (no search), merge org repos in the background
                // so the list paints quickly and org coverage arrives without blocking.
                if (reset && query.isBlank()) {
                    mergeOrgReposInBackground()
                }
            }
        }
    }

    private fun mergeOrgReposInBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            val (orgRepos, result) = accountManager.listOrganizationReposExtra()
            if (result !is PrOpResult.Success || orgRepos.isEmpty()) return@launch
            val current = _state.value.githubRepos
            val seen = current.map { it.id }.toHashSet()
            val extra = orgRepos.filter { it.id !in seen }
            if (extra.isEmpty()) return@launch
            val combined = (current + extra).sortedByDescending { it.updatedAt }
            _state.value = _state.value.copy(githubRepos = combined)
        }
    }

    private suspend fun loadGitLabPage(query: String, reset: Boolean) {
        if (!_state.value.gitlabConnected) {
            _state.value = _state.value.copy(loading = false, loadingMore = false, gitlabLoaded = true)
            return
        }
        val host = resolveGitLabHost() ?: run {
            _state.value = _state.value.copy(loading = false, loadingMore = false, gitlabLoaded = true)
            return
        }
        val nextPage = if (reset) 1 else _state.value.gitlabPage + 1
        val (batch, hasMore, result) = withContext(Dispatchers.IO) {
            if (query.isBlank()) gitLabAccountManager.listProjectsPage(page = nextPage, perPage = PAGE_SIZE, h = host)
            else gitLabAccountManager.searchProjectsPage(query, page = nextPage, perPage = PAGE_SIZE, h = host)
        }
        when (result) {
            is PrOpResult.AuthRequired -> _state.value = _state.value.copy(
                loading = false, loadingMore = false, gitlabConnected = false, gitlabLoaded = true
            )
            is PrOpResult.Error -> _state.value = _state.value.copy(
                loading = false, loadingMore = false, gitlabLoaded = true,
                errorMessage = "GitLab: ${result.message}"
            )
            is PrOpResult.Success -> {
                val merged = if (reset) batch else _state.value.gitlabProjects + batch
                _state.value = _state.value.copy(
                    loading = false,
                    loadingMore = false,
                    gitlabProjects = merged,
                    gitlabPage = nextPage,
                    gitlabHasMore = hasMore,
                    gitlabLoaded = true
                )
            }
        }
    }

    private fun resolveGitLabHost(): String? {
        val h = gitLabAccountManager.host
        if (gitLabAccountManager.isConnected(h)) return h
        if (gitLabAccountManager.isConnected("gitlab.com")) return "gitlab.com"
        return null
    }

    fun onDestinationPicked(treeUri: Uri) {
        viewModelScope.launch {
            when (val result = repoManager.resolveCloneDestination(treeUri)) {
                is RepoManager.ResolveCloneDestinationResult.Success -> {
                    val url = pendingCloneUrl
                    val name = pendingCloneName
                    if (url != null && name != null) {
                        pendingCloneUrl = null
                        pendingCloneName = null
                        doClone(url, name, result.path)
                    }
                }
                is RepoManager.ResolveCloneDestinationResult.Error -> {
                    _state.value = _state.value.copy(errorMessage = result.message)
                }
            }
        }
    }

    fun cloneGitHubRepo(repo: GitHubRemoteRepo, useSsh: Boolean = false) {
        val url = if (useSsh) repo.sshUrl else repo.cloneUrl
        doClone(url, repo.name, File(repoManager.reposRoot, repo.name), "gh:${repo.id}")
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

    private fun doClone(url: String, folderName: String, destination: File, key: String? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                cloning = true,
                cloningKey = key,
                progressText = "Cloning…",
                cloneResult = null,
                errorMessage = null
            )
            notifier.start(GitProgressNotifier.Kind.CLONE, "Cloning $folderName…", "Starting…")
            val result = withContext(Dispatchers.IO) {
                repoManager.cloneRepo(url, destination) { progress ->
                    _state.value = _state.value.copy(progressText = progress)
                    val percent = Regex("""(\d{1,3})\s*%""").find(progress)
                        ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
                    notifier.update(GitProgressNotifier.Kind.CLONE, progress, percent)
                }
            }
            when (result) {
                is GitOpResult.Success ->
                    notifier.finish(GitProgressNotifier.Kind.CLONE, "Cloned $folderName")
                is GitOpResult.UpToDate ->
                    notifier.finish(GitProgressNotifier.Kind.CLONE, result.message)
                else -> notifier.cancel(GitProgressNotifier.Kind.CLONE)
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

    fun createRepo(
        name: String,
        description: String?,
        isPrivate: Boolean,
        onGitLab: Boolean = false
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(creating = true, errorMessage = null)
            if (onGitLab) {
                val h = when {
                    gitLabAccountManager.isConnected(gitLabAccountManager.host) -> gitLabAccountManager.host
                    gitLabAccountManager.isConnected("gitlab.com") -> "gitlab.com"
                    else -> null
                }
                if (h == null) {
                    _state.value = _state.value.copy(
                        creating = false,
                        errorMessage = "Connect GitLab in Settings"
                    )
                    return@launch
                }
                val (project, result) = withContext(Dispatchers.IO) {
                    gitLabAccountManager.createProject(name, description, isPrivate, h)
                }
                _state.value = when (result) {
                    is PrOpResult.Success -> _state.value.copy(
                        creating = false,
                        statusMessage = "Created ${project?.pathWithNamespace ?: name.trim()} on GitLab"
                    )
                    is PrOpResult.AuthRequired -> _state.value.copy(
                        creating = false, authRequired = true, gitlabConnected = false
                    )
                    is PrOpResult.Error -> _state.value.copy(creating = false, errorMessage = result.message)
                }
            } else {
                val (repo, result) = withContext(Dispatchers.IO) {
                    accountManager.createRepo(name, description, isPrivate)
                }
                _state.value = when (result) {
                    is PrOpResult.Success -> _state.value.copy(
                        creating = false,
                        githubRepos = if (repo != null) listOf(repo) + _state.value.githubRepos else _state.value.githubRepos,
                        statusMessage = "Created ${repo?.name ?: name.trim()} on GitHub"
                    )
                    is PrOpResult.AuthRequired -> _state.value.copy(
                        creating = false, authRequired = true, githubConnected = false
                    )
                    is PrOpResult.Error -> _state.value.copy(creating = false, errorMessage = result.message)
                }
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
