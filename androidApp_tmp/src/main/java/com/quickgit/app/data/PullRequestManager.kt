package com.quickgit.app.data

import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.github.toPrOpResult
import com.quickgit.app.data.gitlab.GitLabApi
import com.quickgit.app.data.models.GitLabNote
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.data.models.MergeMethod
import com.quickgit.app.data.models.MergeRequest
import com.quickgit.app.data.models.PrComment
import com.quickgit.app.data.models.PrOpResult
import com.quickgit.app.data.models.PullRequest

/**
 * Pull requests (GitHub) and merge requests (GitLab) for remotes, using the same HTTPS token
 * as git push/pull. Local checkout of a PR/MR head goes through JGit.
 */
class PullRequestManager(private val repoManager: RepoManager, private val credentialStore: CredentialStore) {

    private val TAG = "PullRequestManager"

    data class ProjectRef(
        val host: String,
        val isGitLab: Boolean,
        val owner: String,
        val repo: String
    ) {
        val projectPath: String get() = "$owner/$repo"
    }

    fun projectFor(path: String): ProjectRef? {
        val remoteUrl = repoManager.openGit(path).use {
            it.repository.config.getString("remote", "origin", "url")
        } ?: return null
        val host = CredentialStore.hostOf(remoteUrl)
        val hostLower = host.lowercase()
        if (hostLower.contains("gitlab")) {
            val parsed = GitLabApi(host, null).parseOwnerProject(remoteUrl) ?: return null
            return ProjectRef(host, true, parsed.owner, parsed.project)
        }
        if (hostLower.contains("github.com")) {
            val parsed = GitHubApi(null).parseOwnerRepo(remoteUrl) ?: return null
            return ProjectRef(host, false, parsed.owner, parsed.repo)
        }
        return null
    }

    fun ownerRepoFor(path: String): GitHubApi.OwnerRepo? {
        val p = projectFor(path) ?: return null
        return GitHubApi.OwnerRepo(p.owner, p.repo)
    }

    private fun githubApi(): GitHubApi = GitHubApi(credentialStore.getHttpsToken("github.com"))

    private fun gitlabApi(host: String): GitLabApi =
        GitLabApi(host, credentialStore.getHttpsToken(host))

    private fun mapStateToGitLab(state: String): String = when (state.lowercase()) {
        "open" -> "opened"
        "closed" -> "closed"
        "all" -> "all"
        else -> state
    }

    private fun MergeRequest.toPullRequest(): PullRequest = PullRequest(
        number = iid,
        title = title,
        body = description,
        state = when (state) {
            "opened" -> "open"
            "merged" -> "closed"
            else -> state
        },
        isDraft = draft,
        merged = merged,
        mergeable = when (mergeStatus) {
            "can_be_merged" -> true
            "cannot_be_merged" -> false
            else -> null
        },
        authorLogin = authorUsername,
        headRef = sourceBranch,
        headRepoFullName = null,
        headSha = "",
        baseRef = targetBranch,
        commentsCount = userNotesCount,
        htmlUrl = webUrl,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun GitLabNote.toComment(): PrComment = PrComment(
        id = id,
        authorLogin = authorUsername,
        body = body,
        createdAt = createdAt
    )

    fun listPullRequests(ref: ProjectRef, state: String): Pair<List<PullRequest>, PrOpResult> {
        return if (ref.isGitLab) {
            val result = gitlabApi(ref.host).listMergeRequests(ref.projectPath, mapStateToGitLab(state))
            (result.getOrNull()?.map { it.toPullRequest() } ?: emptyList()) to result.toPrOpResult(ref.host)
        } else {
            val result = githubApi().listPullRequests(ref.owner, ref.repo, state)
            (result.getOrNull() ?: emptyList()) to result.toPrOpResult(ref.host)
        }
    }

    fun getPullRequest(ref: ProjectRef, number: Int): Pair<PullRequest?, PrOpResult> {
        return if (ref.isGitLab) {
            val result = gitlabApi(ref.host).getMergeRequest(ref.projectPath, number)
            result.getOrNull()?.toPullRequest() to result.toPrOpResult(ref.host)
        } else {
            val result = githubApi().getPullRequest(ref.owner, ref.repo, number)
            result.getOrNull() to result.toPrOpResult(ref.host)
        }
    }

    fun listComments(ref: ProjectRef, number: Int): Pair<List<PrComment>, PrOpResult> {
        return if (ref.isGitLab) {
            val result = gitlabApi(ref.host).listMrNotes(ref.projectPath, number)
            val notes = result.getOrNull()?.filter { !it.system }?.map { it.toComment() } ?: emptyList()
            notes to result.toPrOpResult(ref.host)
        } else {
            val result = githubApi().listComments(ref.owner, ref.repo, number)
            (result.getOrNull() ?: emptyList()) to result.toPrOpResult(ref.host)
        }
    }

    fun createPullRequest(
        ref: ProjectRef,
        title: String,
        body: String,
        head: String,
        base: String,
        draft: Boolean
    ): Pair<PullRequest?, PrOpResult> {
        AppLog.i(TAG, "createPullRequest: ${ref.projectPath} $head -> $base")
        return if (ref.isGitLab) {
            val result = gitlabApi(ref.host).createMergeRequest(ref.projectPath, title, body, head, base, draft)
            result.getOrNull()?.toPullRequest() to result.toPrOpResult(ref.host)
        } else {
            val result = githubApi().createPullRequest(ref.owner, ref.repo, title, body, head, base, draft)
            result.getOrNull() to result.toPrOpResult(ref.host)
        }
    }

    fun mergePullRequest(
        ref: ProjectRef,
        number: Int,
        method: MergeMethod,
        commitTitle: String?
    ): PrOpResult {
        AppLog.i(TAG, "mergePullRequest: ${ref.projectPath} #$number ($method)")
        return if (ref.isGitLab) {
            // GitLab merge API: squash flag; rebase/merge are server-side settings
            val squash = method == MergeMethod.SQUASH
            gitlabApi(ref.host).mergeMergeRequest(ref.projectPath, number, squash).toPrOpResult(ref.host)
        } else {
            githubApi().mergePullRequest(ref.owner, ref.repo, number, method, commitTitle).toPrOpResult(ref.host)
        }
    }

    fun setPullRequestState(ref: ProjectRef, number: Int, open: Boolean): PrOpResult {
        AppLog.i(TAG, "setPullRequestState: ${ref.projectPath} #$number open=$open")
        return if (ref.isGitLab) {
            gitlabApi(ref.host).setMergeRequestState(ref.projectPath, number, open).toPrOpResult(ref.host)
        } else {
            githubApi().setPullRequestState(ref.owner, ref.repo, number, open).toPrOpResult(ref.host)
        }
    }

    fun addComment(ref: ProjectRef, number: Int, body: String): PrOpResult {
        AppLog.i(TAG, "addComment: ${ref.projectPath} #$number")
        return if (ref.isGitLab) {
            gitlabApi(ref.host).addMrNote(ref.projectPath, number, body).toPrOpResult(ref.host)
        } else {
            githubApi().addComment(ref.owner, ref.repo, number, body).toPrOpResult(ref.host)
        }
    }

    fun listBaseBranches(ref: ProjectRef): List<String> {
        return if (ref.isGitLab) {
            emptyList() // UI falls back to local branches
        } else {
            githubApi().listLocalAndRemoteBranches(ref.owner, ref.repo).getOrNull() ?: emptyList()
        }
    }

    fun checkoutPullRequestLocally(path: String, number: Int, isGitLab: Boolean = false): GitOpResult {
        val refSpec = if (isGitLab) {
            "+refs/merge-requests/$number/head:refs/heads/mr-$number"
        } else {
            "+refs/pull/$number/head:refs/heads/pr-$number"
        }
        val localBranch = if (isGitLab) "mr-$number" else "pr-$number"
        return repoManager.fetchAndCheckoutRef(path, refSpec = refSpec, localBranch = localBranch)
    }

    // ---- Legacy GitHub-shaped overloads ----

    fun listPullRequests(owner: String, repo: String, state: String): Pair<List<PullRequest>, PrOpResult> =
        listPullRequests(ProjectRef("github.com", false, owner, repo), state)

    fun getPullRequest(owner: String, repo: String, number: Int): Pair<PullRequest?, PrOpResult> =
        getPullRequest(ProjectRef("github.com", false, owner, repo), number)

    fun listComments(owner: String, repo: String, number: Int): Pair<List<PrComment>, PrOpResult> =
        listComments(ProjectRef("github.com", false, owner, repo), number)

    fun createPullRequest(
        owner: String, repo: String, title: String, body: String, head: String, base: String, draft: Boolean
    ): Pair<PullRequest?, PrOpResult> =
        createPullRequest(ProjectRef("github.com", false, owner, repo), title, body, head, base, draft)

    fun mergePullRequest(owner: String, repo: String, number: Int, method: MergeMethod, commitTitle: String?): PrOpResult =
        mergePullRequest(ProjectRef("github.com", false, owner, repo), number, method, commitTitle)

    fun setPullRequestState(owner: String, repo: String, number: Int, open: Boolean): PrOpResult =
        setPullRequestState(ProjectRef("github.com", false, owner, repo), number, open)

    fun addComment(owner: String, repo: String, number: Int, body: String): PrOpResult =
        addComment(ProjectRef("github.com", false, owner, repo), number, body)

    fun listBaseBranches(owner: String, repo: String): List<String> =
        listBaseBranches(ProjectRef("github.com", false, owner, repo))

    fun checkoutPullRequestLocally(path: String, number: Int): GitOpResult =
        checkoutPullRequestLocally(path, number, isGitLab = false)
}
