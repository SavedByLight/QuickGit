package com.quickgit.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GitProgressNotifier
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.data.models.RepoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RepoDetailUiState(
    val status: RepoStatus? = null,
    val branch: String = "",
    /** True while a git operation (stage, commit, push, pull, discard...) is running — used to disable buttons. */
    val busy: Boolean = false,
    /** True only while a pull-to-refresh (or the initial load) is in flight — drives the refresh indicator. */
    val refreshing: Boolean = false,
    val commitMessage: String = "",
    val lastResult: GitOpResult? = null,
    val statusMessage: String? = null,
    val authorName: String = "",
    val authorEmail: String = "",
    /** Append Signed-off-by trailer on commit (git commit -s). */
    val signOff: Boolean = false,
    val remoteUrl: String? = null,
    /** True when origin looks like a Gerrit host — hides GH Actions/PRs/etc., shows push-for-review. */
    val isGerritRemote: Boolean = false,
    /** True when origin is GitLab (gitlab.com or self-hosted) — use MR/CI/Issues labels. */
    val isGitLabRemote: Boolean = false
)

class RepoDetailViewModel(
    private val repoManager: RepoManager,
    private val app: Application
) : ViewModel() {
    private val _state = MutableStateFlow(RepoDetailUiState())
    val state: StateFlow<RepoDetailUiState> = _state.asStateFlow()

    private lateinit var repoPath: String
    private val notifier = GitProgressNotifier(app)

    fun init(repoPath: String) {
        this.repoPath = repoPath
        _state.value = _state.value.copy(
            authorName = repoManager.getCommitAuthorName(),
            authorEmail = repoManager.getCommitAuthorEmail(),
            signOff = repoManager.isSignOffEnabled()
        )
        loadStatus(showRefreshing = true)
    }

    /** Pull-to-refresh entry point — reloads status and shows the refresh indicator while doing so. */
    fun refresh() = loadStatus(showRefreshing = true)

    private fun loadStatus(showRefreshing: Boolean) {
        viewModelScope.launch {
            if (showRefreshing) _state.value = _state.value.copy(refreshing = true)
            val status = withContext(Dispatchers.IO) { repoManager.getStatus(repoPath) }
            val (branch, remoteUrl) = withContext(Dispatchers.IO) {
                repoManager.openGit(repoPath).use { git ->
                    val b = git.repository.branch
                    val url = git.repository.config.getString("remote", "origin", "url")
                    b to url
                }
            }
            val isGerrit = isGerritRemoteUrl(remoteUrl)
            val isGitLab = isGitLabRemoteUrl(remoteUrl)
            _state.value = _state.value.copy(
                status = status,
                branch = branch ?: "",
                remoteUrl = remoteUrl,
                isGerritRemote = isGerrit,
                isGitLabRemote = isGitLab,
                refreshing = if (showRefreshing) false else _state.value.refreshing
            )
        }
    }

    fun toggleStage(filePath: String, currentlyStaged: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                if (currentlyStaged) repoManager.unstage(repoPath, listOf(filePath))
                else repoManager.stage(repoPath, listOf(filePath))
            }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    fun stageAll() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.stageAll(repoPath) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    fun unstageAll() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.unstageAll(repoPath) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    fun discard(filePath: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                repoManager.discardChanges(repoPath, listOf(filePath))
            }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    /** "Revert all" — discards every unstaged/untracked change in one go. */
    fun discardAll() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.discardAll(repoPath) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    fun setCommitMessage(msg: String) { _state.value = _state.value.copy(commitMessage = msg) }

    fun setSignOff(enabled: Boolean) {
        repoManager.setSignOffEnabled(enabled)
        _state.value = _state.value.copy(signOff = enabled)
    }

    fun commit() {
        val msg = _state.value.commitMessage
        if (msg.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                repoManager.commit(
                    repoPath,
                    msg,
                    _state.value.authorName,
                    _state.value.authorEmail,
                    signOff = _state.value.signOff
                )
            }
            _state.value = _state.value.copy(busy = false, lastResult = result, commitMessage = "")
            loadStatus(showRefreshing = false)
        }
    }

    fun push(force: Boolean = false, forceWithLease: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            notifier.start("Pushing…", "Starting…")
            val result = withContext(Dispatchers.IO) {
                repoManager.push(repoPath, force = force, forceWithLease = forceWithLease) { progress ->
                    val percent = parsePercent(progress)
                    notifier.update(progress, percent)
                }
            }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            when (result) {
                is GitOpResult.Success -> notifier.finish("Push finished")
                else -> notifier.cancel()
            }
        }
    }

    /**
     * Push current HEAD to Gerrit for code review (`refs/for/<branch>`).
     * Optional [topic] becomes `refs/for/<branch>%topic=<topic>`.
     */
    fun pushForReview(topic: String? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                repoManager.pushForReview(repoPath, topic = topic)
            }
            _state.value = _state.value.copy(busy = false, lastResult = result)
        }
    }

    fun pull() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            notifier.start("Pulling…", "Starting…")
            val result = withContext(Dispatchers.IO) {
                repoManager.pull(repoPath) { progress ->
                    val percent = parsePercent(progress)
                    notifier.update(progress, percent)
                }
            }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            when (result) {
                is GitOpResult.Success, is GitOpResult.UpToDate -> notifier.finish("Pull finished")
                else -> notifier.cancel()
            }
            loadStatus(showRefreshing = false)
        }
    }

    /** Git pull then LFS pull (when supported). */
    fun pullWithLfs() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            notifier.start("Pulling…", "Starting…")
            val pullResult = withContext(Dispatchers.IO) {
                repoManager.pull(repoPath) { progress ->
                    notifier.update(progress, parsePercent(progress))
                }
            }
            if (pullResult is GitOpResult.Error || pullResult is GitOpResult.AuthRequired || pullResult is GitOpResult.Conflict) {
                _state.value = _state.value.copy(busy = false, lastResult = pullResult)
                notifier.cancel()
                loadStatus(showRefreshing = false)
                return@launch
            }
            notifier.update("Fetching LFS…")
            val lfsResult = withContext(Dispatchers.IO) { repoManager.fetchLfs(repoPath) }
            _state.value = _state.value.copy(
                busy = false,
                lastResult = lfsResult,
                statusMessage = when {
                    lfsResult is GitOpResult.Success -> "Pull done · LFS pulled"
                    lfsResult is GitOpResult.Error -> "Pull done · LFS: ${lfsResult.message}"
                    else -> null
                }
            )
            if (lfsResult is GitOpResult.Error) notifier.cancel() else notifier.finish("Pull finished")
            loadStatus(showRefreshing = false)
        }
    }

    /** Git push then LFS push (LFS upload already runs inside push; this also covers LFS-only leftovers). */
    fun pushWithLfs(force: Boolean = false, forceWithLease: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            notifier.start("Pushing…", "Starting…")
            val pushResult = withContext(Dispatchers.IO) {
                repoManager.push(repoPath, force = force, forceWithLease = forceWithLease) { progress ->
                    notifier.update(progress, parsePercent(progress))
                }
            }
            if (pushResult is GitOpResult.Error || pushResult is GitOpResult.AuthRequired) {
                _state.value = _state.value.copy(busy = false, lastResult = pushResult)
                notifier.cancel()
                return@launch
            }
            notifier.update("Uploading LFS…")
            val lfsResult = withContext(Dispatchers.IO) { repoManager.pushLfs(repoPath) }
            _state.value = _state.value.copy(
                busy = false,
                lastResult = if (lfsResult is GitOpResult.Error) lfsResult else pushResult,
                statusMessage = when {
                    lfsResult is GitOpResult.Success -> "Push done · LFS pushed"
                    lfsResult is GitOpResult.Error -> "Push done · LFS: ${lfsResult.message}"
                    else -> null
                }
            )
            if (lfsResult is GitOpResult.Error) notifier.cancel() else notifier.finish("Push finished")
        }
    }

    fun gitStatus() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val msg = withContext(Dispatchers.IO) {
                try {
                    repoManager.gitStatusSummary(repoPath)
                } catch (e: Exception) {
                    e.message ?: "Status failed"
                }
            }
            _state.value = _state.value.copy(busy = false, statusMessage = msg, lastResult = null)
        }
    }

    /** Combined git + LFS status text. */
    fun fullStatus() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val msg = withContext(Dispatchers.IO) {
                try {
                    val git = repoManager.gitStatusSummary(repoPath)
                    val (lfs, _) = repoManager.lfsStatus(repoPath)
                    buildString {
                        append(git)
                        append("\n\n— LFS —\n")
                        append(lfs?.message ?: "LFS status unavailable")
                        lfs?.trackedPatterns?.takeIf { it.isNotEmpty() }?.let {
                            append("\nPatterns: ${it.joinToString()}")
                        }
                    }
                } catch (e: Exception) {
                    e.message ?: "Status failed"
                }
            }
            _state.value = _state.value.copy(busy = false, statusMessage = msg, lastResult = null)
        }
    }

    fun fetchLfs() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.fetchLfs(repoPath) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    fun pushLfs() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.pushLfs(repoPath) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
        }
    }

    fun lfsInstall() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.lfsInstall(repoPath) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    fun lfsTrack(pattern: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.lfsTrack(repoPath, pattern) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    fun lfsUntrack(pattern: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.lfsUntrack(repoPath, pattern) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    fun lfsStatus() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val (st, result) = withContext(Dispatchers.IO) { repoManager.lfsStatus(repoPath) }
            val msg = st?.message
            _state.value = _state.value.copy(
                busy = false,
                lastResult = if (msg != null && result is GitOpResult.Success) {
                    // Surface summary via Success path — UI shows lastResult
                    result
                } else result,
                statusMessage = msg
            )
        }
    }

    fun consumeResult() { _state.value = _state.value.copy(lastResult = null, statusMessage = null) }

    private fun isGerritRemoteUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        if (u.contains("github.com") || u.contains("gitlab.com") || u.contains("gitlab.")) return false
        if (u.contains("gerrit")) return true
        // Authenticated Gerrit HTTP clone path
        if ("/a/" in u) return true
        return false
    }

    private fun isGitLabRemoteUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase()
        // gitlab.com and typical self-hosted hosts (gitlab.example.com)
        return u.contains("gitlab.com") || u.contains("gitlab.")
    }

    private fun parsePercent(text: String): Int? {
        val match = Regex("""(\d{1,3})\s*%""").find(text) ?: return null
        return match.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
    }
}
