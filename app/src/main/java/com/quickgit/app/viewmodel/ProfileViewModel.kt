package com.quickgit.app.viewmodel

import android.app.Application
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ProfileProvider {
    GITHUB,
    GITLAB
}

data class ConnectedProviderSummary(
    val provider: String,
    val username: String,
    val detail: String?
)

/** Provider-agnostic profile fields for the Profile screen. */
data class ProfileUser(
    val login: String,
    val name: String?,
    val email: String? = null,
    val avatarUrl: String?,
    val htmlUrl: String,
    val bio: String? = null,
    val company: String? = null,
    val location: String? = null,
    val blog: String? = null,
    val publicRepos: Int = 0,
    val followers: Int = 0,
    val following: Int = 0
)

data class ProfileUiState(
    val loading: Boolean = false,
    val user: ProfileUser? = null,
    val repos: List<GitHubRemoteRepo> = emptyList(),
    val isSelf: Boolean = false,
    val errorMessage: String? = null,
    val authRequired: Boolean = false,
    val forkingRepoId: Long? = null,
    val statusMessage: String? = null,
    val forkedRepo: GitHubRemoteRepo? = null,
    val forkError: String? = null,
    val connectedProviders: List<ConnectedProviderSummary> = emptyList(),
    /** Which host profile is shown (self only switches between connected accounts). */
    val selectedProvider: ProfileProvider = ProfileProvider.GITHUB,
    /** Providers the user can switch between on self profile. */
    val availableProviders: List<ProfileProvider> = emptyList(),
    /** Repo currently being cloned from the profile download action. */
    val cloningRepoId: Long? = null,
    val cloneProgress: String? = null,
    /** More repo pages available (100 per page). */
    val hasMoreRepos: Boolean = false,
    val loadingMoreRepos: Boolean = false
)

class ProfileViewModel(
    private val accountManager: GitHubAccountManager,
    private val gitLabAccountManager: GitLabAccountManager,
    private val repoManager: RepoManager,
    app: Application
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private var currentLogin: String? = null
    private var viewingOtherUser = false
    private var reposPage = 1
    private var profileLoginForRepos: String? = null
    private val pageSize = 100
    private val notifier = GitProgressNotifier(app)

    fun load(login: String?) {
        currentLogin = login
        viewingOtherUser = !login.isNullOrBlank()
        viewModelScope.launch {
            _state.value = ProfileUiState(loading = true)
            val providers = loadConnectedProviders()
            val available = buildList {
                if (providers.any { it.provider == "GitHub" }) add(ProfileProvider.GITHUB)
                if (providers.any { it.provider == "GitLab" }) add(ProfileProvider.GITLAB)
            }

            // Other-user profiles are still GitHub-only (search/navigation is GitHub-centric).
            if (viewingOtherUser) {
                loadGitHubProfile(login!!.trim(), isSelf = false, providers, available)
                return@launch
            }

            if (available.isEmpty()) {
                _state.value = ProfileUiState(
                    loading = false,
                    authRequired = true,
                    isSelf = true,
                    errorMessage = "Connect GitHub or GitLab in Settings."
                )
                return@launch
            }

            val preferred = when {
                available.contains(ProfileProvider.GITHUB) -> ProfileProvider.GITHUB
                else -> ProfileProvider.GITLAB
            }
            selectProvider(preferred, providers, available)
        }
    }

    fun selectProvider(provider: ProfileProvider) {
        viewModelScope.launch {
            val providers = _state.value.connectedProviders.ifEmpty { loadConnectedProviders() }
            val available = _state.value.availableProviders.ifEmpty {
                buildList {
                    if (providers.any { it.provider == "GitHub" }) add(ProfileProvider.GITHUB)
                    if (providers.any { it.provider == "GitLab" }) add(ProfileProvider.GITLAB)
                }
            }
            selectProvider(provider, providers, available)
        }
    }

    private suspend fun selectProvider(
        provider: ProfileProvider,
        providers: List<ConnectedProviderSummary>,
        available: List<ProfileProvider>
    ) {
        reposPage = 1
        profileLoginForRepos = null
        _state.value = _state.value.copy(
            loading = true,
            errorMessage = null,
            selectedProvider = provider,
            availableProviders = available,
            connectedProviders = providers,
            isSelf = true,
            repos = emptyList(),
            user = null,
            hasMoreRepos = false,
            loadingMoreRepos = false
        )
        when (provider) {
            ProfileProvider.GITHUB -> loadGitHubProfile(null, isSelf = true, providers, available)
            ProfileProvider.GITLAB -> loadGitLabProfile(providers, available)
        }
    }

    private suspend fun loadGitHubProfile(
        login: String?,
        isSelf: Boolean,
        providers: List<ConnectedProviderSummary>,
        available: List<ProfileProvider>
    ) {
        if (!accountManager.isConnected()) {
            _state.value = ProfileUiState(
                loading = false,
                isSelf = isSelf,
                connectedProviders = providers,
                availableProviders = available,
                selectedProvider = ProfileProvider.GITHUB,
                errorMessage = if (isSelf) "Connect GitHub in Settings." else "GitHub auth required",
                authRequired = true
            )
            return
        }
        val (user, userResult) = withContext(Dispatchers.IO) {
            accountManager.getUserProfile(login)
        }
        when {
            user != null -> {
                reposPage = 1
                profileLoginForRepos = if (isSelf && login == null) null else user.login
                val (repos, hasMore, _) = withContext(Dispatchers.IO) {
                    if (isSelf && login == null) {
                        accountManager.listReposPage(page = 1, perPage = pageSize)
                    } else {
                        accountManager.listPublicReposPage(user.login, page = 1, perPage = pageSize)
                    }
                }
                _state.value = ProfileUiState(
                    loading = false,
                    user = ProfileUser(
                        login = user.login,
                        name = user.name,
                        email = user.email,
                        avatarUrl = user.avatarUrl,
                        htmlUrl = user.htmlUrl,
                        bio = user.bio,
                        company = user.company,
                        location = user.location,
                        blog = user.blog,
                        publicRepos = user.publicRepos,
                        followers = user.followers,
                        following = user.following
                    ),
                    repos = repos,
                    isSelf = isSelf,
                    connectedProviders = providers,
                    availableProviders = available,
                    selectedProvider = ProfileProvider.GITHUB,
                    hasMoreRepos = hasMore
                )
                // Self profile: paint personal repos first; merge org repos in background.
                if (isSelf && login == null) {
                    mergeOrgReposInBackground()
                }
            }
            userResult is PrOpResult.AuthRequired -> {
                _state.value = ProfileUiState(
                    loading = false,
                    authRequired = true,
                    isSelf = isSelf,
                    connectedProviders = providers,
                    availableProviders = available,
                    selectedProvider = ProfileProvider.GITHUB,
                    errorMessage = "GitHub auth required"
                )
            }
            else -> {
                _state.value = ProfileUiState(
                    loading = false,
                    isSelf = isSelf,
                    connectedProviders = providers,
                    availableProviders = available,
                    selectedProvider = ProfileProvider.GITHUB,
                    errorMessage = (userResult as? PrOpResult.Error)?.message
                        ?: "User not found"
                )
            }
        }
    }

    private suspend fun loadGitLabProfile(
        providers: List<ConnectedProviderSummary>,
        available: List<ProfileProvider>
    ) {
        val glHost = when {
            gitLabAccountManager.isConnected(gitLabAccountManager.host) -> gitLabAccountManager.host
            gitLabAccountManager.isConnected("gitlab.com") -> "gitlab.com"
            else -> null
        }
        if (glHost == null) {
            _state.value = ProfileUiState(
                loading = false,
                isSelf = true,
                connectedProviders = providers,
                availableProviders = available,
                selectedProvider = ProfileProvider.GITLAB,
                errorMessage = "Connect GitLab in Settings."
            )
            return
        }
        val (account, accountResult) = withContext(Dispatchers.IO) {
            gitLabAccountManager.refreshAccount(glHost)
        }
        if (account == null) {
            _state.value = ProfileUiState(
                loading = false,
                isSelf = true,
                connectedProviders = providers,
                availableProviders = available,
                selectedProvider = ProfileProvider.GITLAB,
                authRequired = accountResult is PrOpResult.AuthRequired,
                errorMessage = (accountResult as? PrOpResult.Error)?.message
                    ?: "Could not load GitLab profile"
            )
            return
        }
        reposPage = 1
        profileLoginForRepos = null
        val (projects, hasMore, _) = withContext(Dispatchers.IO) {
            gitLabAccountManager.listProjectsPage(page = 1, perPage = pageSize, h = glHost)
        }
        val repos = projects.map { it.toRemoteRepo() }
        _state.value = ProfileUiState(
            loading = false,
            user = ProfileUser(
                login = account.username,
                name = account.name,
                email = account.email,
                avatarUrl = account.avatarUrl,
                htmlUrl = account.webUrl,
                publicRepos = projects.size
            ),
            repos = repos,
            isSelf = true,
            connectedProviders = providers,
            availableProviders = available,
            selectedProvider = ProfileProvider.GITLAB,
            hasMoreRepos = hasMore
        )
    }

    private fun mergeOrgReposInBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            val (orgRepos, result) = accountManager.listOrganizationReposExtra()
            if (result !is PrOpResult.Success || orgRepos.isEmpty()) return@launch
            val current = _state.value.repos
            val seen = current.map { it.id }.toHashSet()
            val extra = orgRepos.filter { it.id !in seen }
            if (extra.isEmpty()) return@launch
            val combined = (current + extra).sortedByDescending { it.updatedAt }
            _state.value = _state.value.copy(repos = combined)
        }
    }

    /** Append the next page of repositories (100 at a time). */
    fun loadMoreRepos() {
        val s = _state.value
        if (!s.hasMoreRepos || s.loadingMoreRepos || s.loading) return
        viewModelScope.launch {
            _state.value = s.copy(loadingMoreRepos = true)
            val next = reposPage + 1
            val (batch, hasMore, result) = withContext(Dispatchers.IO) {
                when (s.selectedProvider) {
                    ProfileProvider.GITHUB -> {
                        val login = profileLoginForRepos
                        if (login == null && s.isSelf) {
                            accountManager.listReposPage(page = next, perPage = pageSize)
                        } else if (login != null) {
                            accountManager.listPublicReposPage(login, page = next, perPage = pageSize)
                        } else {
                            Triple(emptyList(), false, PrOpResult.Success)
                        }
                    }
                    ProfileProvider.GITLAB -> {
                        val glHost = when {
                            gitLabAccountManager.isConnected(gitLabAccountManager.host) ->
                                gitLabAccountManager.host
                            gitLabAccountManager.isConnected("gitlab.com") -> "gitlab.com"
                            else -> null
                        }
                        if (glHost == null) Triple(emptyList(), false, PrOpResult.Error("Not connected"))
                        else {
                            val (projects, more, op) =
                                gitLabAccountManager.listProjectsPage(page = next, perPage = pageSize, h = glHost)
                            Triple(projects.map { it.toRemoteRepo() }, more, op)
                        }
                    }
                }
            }
            if (result is PrOpResult.Error) {
                _state.value = _state.value.copy(
                    loadingMoreRepos = false,
                    forkError = result.message
                )
                return@launch
            }
            reposPage = next
            val existing = _state.value.repos
            val seen = existing.map { it.id }.toHashSet()
            val merged = existing + batch.filter { it.id !in seen }
            _state.value = _state.value.copy(
                repos = merged,
                hasMoreRepos = hasMore,
                loadingMoreRepos = false
            )
        }
    }

    private fun GitLabProject.toRemoteRepo() = GitHubRemoteRepo(
        id = id,
        name = name,
        fullName = pathWithNamespace,
        description = description,
        htmlUrl = webUrl,
        cloneUrl = httpUrlToRepo,
        sshUrl = sshUrlToRepo,
        isPrivate = isPrivate,
        isFork = isFork,
        ownerLogin = pathWithNamespace.substringBeforeLast('/', pathWithNamespace),
        defaultBranch = defaultBranch,
        updatedAt = updatedAt,
        language = null
    )

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
                list += ConnectedProviderSummary(
                    "GitLab",
                    account.username,
                    "$glHost · ${account.name ?: ""}".trimEnd(' ', '·')
                )
            }
        }
        return list
    }

    fun fork(repo: GitHubRemoteRepo) {
        if (_state.value.selectedProvider != ProfileProvider.GITHUB) {
            _state.value = _state.value.copy(forkError = "Fork is only available for GitHub")
            return
        }
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

    /**
     * Clone the selected profile repo into the default repos root (same as Browse → download).
     * HTTPS by default; uses stored credentials via RepoManager transport config.
     */
    fun cloneRepo(repo: GitHubRemoteRepo, useSsh: Boolean = false) {
        val url = if (useSsh) repo.sshUrl else repo.cloneUrl
        if (url.isBlank()) {
            _state.value = _state.value.copy(forkError = "No clone URL for ${repo.name}")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                cloningRepoId = repo.id,
                cloneProgress = "Cloning…",
                statusMessage = null,
                forkError = null
            )
            notifier.start(GitProgressNotifier.Kind.CLONE, "Cloning ${repo.name}…", "Starting…")
            val destination = File(repoManager.reposRoot, repo.name)
            val result = withContext(Dispatchers.IO) {
                repoManager.cloneRepo(url, destination) { progress ->
                    _state.value = _state.value.copy(cloneProgress = progress)
                    val percent = Regex("""(\d{1,3})\s*%""").find(progress)
                        ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
                    notifier.update(GitProgressNotifier.Kind.CLONE, progress, percent)
                }
            }
            when (result) {
                is GitOpResult.Success, is GitOpResult.UpToDate ->
                    notifier.finish(GitProgressNotifier.Kind.CLONE, "Cloned ${repo.name}")
                else -> notifier.cancel(GitProgressNotifier.Kind.CLONE)
            }
            _state.value = when (result) {
                is GitOpResult.Success -> _state.value.copy(
                    cloningRepoId = null,
                    cloneProgress = null,
                    statusMessage = "Cloned ${repo.name}"
                )
                is GitOpResult.AuthRequired -> _state.value.copy(
                    cloningRepoId = null,
                    cloneProgress = null,
                    authRequired = true,
                    forkError = "Authentication required to clone"
                )
                is GitOpResult.Error -> _state.value.copy(
                    cloningRepoId = null,
                    cloneProgress = null,
                    forkError = result.message
                )
                else -> _state.value.copy(
                    cloningRepoId = null,
                    cloneProgress = null,
                    statusMessage = "Cloned ${repo.name}"
                )
            }
        }
    }

    fun consumeMessages() {
        _state.value = _state.value.copy(
            statusMessage = null,
            forkError = null,
            forkedRepo = null,
            authRequired = false
        )
    }
}
