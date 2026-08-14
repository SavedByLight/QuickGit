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
    /** Suggested GitHub-style message from staged/unstaged changes. */
    val suggestedCommitMessage: String = "",
    val lastResult: GitOpResult? = null,
    val statusMessage: String? = null,
    /** Full multi-line status text shown on a dedicated status sheet (not a snackbar). */
    val statusDetail: String? = null,
    val authorName: String = "",
    val authorEmail: String = "",
    /** Append Signed-off-by trailer on commit (git commit -s). */
    val signOff: Boolean = false,
    /** Amend the previous commit (git commit --amend). */
    val amend: Boolean = false,
    val remoteUrl: String? = null,
    /** True when origin is GitLab (gitlab.com or self-hosted) — use MR/CI/Issues labels. */
    val isGitLabRemote: Boolean = false,
    val remotes: Map<String, String> = emptyMap(),
    val pushRemote: String = "origin",
    val pushLocalBranch: String = "",
    val pushRemoteBranch: String = ""
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
        val draft = loadCommitDraft(repoPath)
        _state.value = _state.value.copy(
            authorName = repoManager.getCommitAuthorName(),
            authorEmail = repoManager.getCommitAuthorEmail(),
            signOff = repoManager.isSignOffEnabled(),
            commitMessage = draft
        )
        loadStatus(showRefreshing = true)
    }

    private fun draftPrefs() =
        app.getSharedPreferences("quickgit_commit_drafts", android.content.Context.MODE_PRIVATE)

    private fun draftKey(path: String) = "draft:" + path.hashCode()

    private fun loadCommitDraft(path: String): String =
        draftPrefs().getString(draftKey(path), "") ?: ""

    private fun saveCommitDraft(path: String, message: String) {
        draftPrefs().edit().putString(draftKey(path), message).apply()
    }

    private fun clearCommitDraft(path: String) {
        draftPrefs().edit().remove(draftKey(path)).apply()
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
            val isGitLab = isGitLabRemoteUrl(remoteUrl)
            val remotes = withContext(Dispatchers.IO) { repoManager.listRemotes(repoPath) }
            val suggestion = withContext(Dispatchers.IO) { repoManager.suggestCommitMessage(repoPath) }
            val branchName = branch ?: ""
            _state.value = _state.value.copy(
                status = status,
                branch = branchName,
                remoteUrl = remoteUrl,
                isGitLabRemote = isGitLab,
                remotes = remotes,
                suggestedCommitMessage = suggestion,
                pushLocalBranch = _state.value.pushLocalBranch.ifBlank { branchName },
                pushRemoteBranch = _state.value.pushRemoteBranch.ifBlank { branchName },
                pushRemote = _state.value.pushRemote.ifBlank {
                    if (remotes.containsKey("origin")) "origin" else remotes.keys.firstOrNull() ?: "origin"
                },
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

    fun setCommitMessage(msg: String) {
        _state.value = _state.value.copy(commitMessage = msg)
        if (::repoPath.isInitialized) saveCommitDraft(repoPath, msg)
    }

    fun applySuggestedCommitMessage() {
        val s = _state.value.suggestedCommitMessage
        if (s.isNotBlank()) setCommitMessage(s)
    }

    fun setAmend(enabled: Boolean) {
        _state.value = _state.value.copy(amend = enabled)
    }

    fun setPushTarget(remote: String, localBranch: String, remoteBranch: String) {
        _state.value = _state.value.copy(
            pushRemote = remote,
            pushLocalBranch = localBranch,
            pushRemoteBranch = remoteBranch
        )
    }

    fun addRemote(name: String, url: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.addOrSetRemote(repoPath, name, url) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

    fun removeRemote(name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.removeRemote(repoPath, name) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            if (result is GitOpResult.Success) loadStatus(showRefreshing = false)
        }
    }

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
                    signOff = _state.value.signOff,
                    amend = _state.value.amend
                )
            }
            if (result is GitOpResult.Success) {
                clearCommitDraft(repoPath)
                _state.value = _state.value.copy(
                    busy = false,
                    lastResult = result,
                    commitMessage = "",
                    amend = false
                )
            } else {
                _state.value = _state.value.copy(busy = false, lastResult = result)
            }
            loadStatus(showRefreshing = false)
        }
    }

    fun push(force: Boolean = false, forceWithLease: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            notifier.start(GitProgressNotifier.Kind.PUSH, "Pushing…", "Starting…")
            val remote = _state.value.pushRemote.ifBlank { "origin" }
            val lb = _state.value.pushLocalBranch.ifBlank { null }
            val rb = _state.value.pushRemoteBranch.ifBlank { null }
            val result = withContext(Dispatchers.IO) {
                repoManager.push(
                    repoPath,
                    force = force,
                    forceWithLease = forceWithLease,
                    remote = remote,
                    localBranch = lb,
                    remoteBranch = rb
                ) { progress ->
                    val percent = parsePercent(progress)
                    notifier.update(GitProgressNotifier.Kind.PUSH, progress, percent)
                }
            }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            when (result) {
                is GitOpResult.Success -> notifier.finish(GitProgressNotifier.Kind.PUSH, "Push finished")
                else -> notifier.cancel(GitProgressNotifier.Kind.PUSH)
            }
        }
    }

    fun pull() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            notifier.start(GitProgressNotifier.Kind.PULL, "Pulling…", "Starting…")
            val result = withContext(Dispatchers.IO) {
                repoManager.pull(repoPath) { progress ->
                    val percent = parsePercent(progress)
                    notifier.update(GitProgressNotifier.Kind.PULL, progress, percent)
                }
            }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            when (result) {
                is GitOpResult.Success, is GitOpResult.UpToDate ->
                    notifier.finish(GitProgressNotifier.Kind.PULL, "Pull finished")
                else -> notifier.cancel(GitProgressNotifier.Kind.PULL)
            }
            loadStatus(showRefreshing = false)
        }
    }

    /** Git pull then LFS pull (when supported). */
    fun pullWithLfs() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            notifier.start(GitProgressNotifier.Kind.PULL, "Pulling…", "Starting…")
            val pullResult = withContext(Dispatchers.IO) {
                repoManager.pull(repoPath) { progress ->
                    notifier.update(GitProgressNotifier.Kind.PULL, progress, parsePercent(progress))
                }
            }
            if (pullResult is GitOpResult.Error || pullResult is GitOpResult.AuthRequired || pullResult is GitOpResult.Conflict) {
                _state.value = _state.value.copy(busy = false, lastResult = pullResult)
                notifier.cancel(GitProgressNotifier.Kind.PULL)
                loadStatus(showRefreshing = false)
                return@launch
            }
            notifier.update(GitProgressNotifier.Kind.PULL, "Fetching LFS…")
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
            if (lfsResult is GitOpResult.Error) notifier.cancel(GitProgressNotifier.Kind.PULL)
            else notifier.finish(GitProgressNotifier.Kind.PULL, "Pull finished")
            loadStatus(showRefreshing = false)
        }
    }

    /** Git push then LFS push (LFS upload already runs inside push; this also covers LFS-only leftovers). */
    fun pushWithLfs(force: Boolean = false, forceWithLease: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            notifier.start(GitProgressNotifier.Kind.PUSH, "Pushing…", "Starting…")
            val pushResult = withContext(Dispatchers.IO) {
                repoManager.push(repoPath, force = force, forceWithLease = forceWithLease) { progress ->
                    notifier.update(GitProgressNotifier.Kind.PUSH, progress, parsePercent(progress))
                }
            }
            if (pushResult is GitOpResult.Error || pushResult is GitOpResult.AuthRequired) {
                _state.value = _state.value.copy(busy = false, lastResult = pushResult)
                notifier.cancel(GitProgressNotifier.Kind.PUSH)
                return@launch
            }
            notifier.update(GitProgressNotifier.Kind.PUSH, "Uploading LFS…")
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
            if (lfsResult is GitOpResult.Error) notifier.cancel(GitProgressNotifier.Kind.PUSH)
            else notifier.finish(GitProgressNotifier.Kind.PUSH, "Push finished")
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
            _state.value = _state.value.copy(busy = false, statusDetail = msg, lastResult = null)
        }
    }

    /** Combined git + LFS status text. */
    fun fullStatus() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val msg = withContext(Dispatchers.IO) {
                try {
                    repoManager.gitStatusSummary(repoPath)
                } catch (e: Exception) {
                    e.message ?: "Status failed"
                }
            }
            _state.value = _state.value.copy(busy = false, statusDetail = msg, lastResult = null)
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

    fun consumeResult() { _state.value = _state.value.copy(lastResult = null, statusMessage = null, statusDetail = null) }

    fun dismissStatusDetail() { _state.value = _state.value.copy(statusDetail = null) }

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
