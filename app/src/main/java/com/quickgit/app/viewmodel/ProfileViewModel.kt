package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GerritAccountManager
import com.quickgit.app.data.GitHubAccountManager
import com.quickgit.app.data.GitLabAccountManager
import com.quickgit.app.data.models.GitHubRemoteRepo
import com.quickgit.app.data.models.GitLabProject
import com.quickgit.app.data.models.PrOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val availableProviders: List<ProfileProvider> = emptyList()
)

class ProfileViewModel(
    private val accountManager: GitHubAccountManager,
    private val gitLabAccountManager: GitLabAccountManager,
    private val gerritAccountManager: GerritAccountManager
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private var currentLogin: String? = null
    private var viewingOtherUser = false

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
        _state.value = _state.value.copy(
            loading = true,
            errorMessage = null,
            selectedProvider = provider,
            availableProviders = available,
            connectedProviders = providers,
            isSelf = true,
            repos = emptyList(),
            user = null
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
                val (repos, _) = withContext(Dispatchers.IO) {
                    if (isSelf && login == null) {
                        accountManager.listRepos()
                    } else {
                        accountManager.listPublicRepos(user.login)
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
                    selectedProvider = ProfileProvider.GITHUB
                )
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
        val (projects, _) = withContext(Dispatchers.IO) {
            gitLabAccountManager.listProjects(glHost)
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
            selectedProvider = ProfileProvider.GITLAB
        )
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

    fun consumeMessages() {
        _state.value = _state.value.copy(statusMessage = null, forkError = null, forkedRepo = null)
    }
}
