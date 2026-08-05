package com.quickgit.app.data

import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.github.toPrOpResult
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.data.models.MergeMethod
import com.quickgit.app.data.models.PrComment
import com.quickgit.app.data.models.PrOpResult
import com.quickgit.app.data.models.PullRequest

/**
 * Pull-request management for GitHub-hosted remotes, layered on top of RepoManager.
 * Remote actions (list/create/merge/close/comment) go through GitHubApi using the same
 * per-host HTTPS token already stored in CredentialStore for git push/pull; local actions
 * (checking out a PR's branch to review/test it) go through JGit directly.
 */
class PullRequestManager(private val repoManager: RepoManager, private val credentialStore: CredentialStore) {

    private val TAG = "PullRequestManager"
    private val host = "github.com"

    private val api: GitHubApi get() = GitHubApi(credentialStore.getHttpsToken(host))

    /** null if the repo's origin isn't a github.com remote — the UI should show a clear message in that case. */
    fun ownerRepoFor(path: String): GitHubApi.OwnerRepo? {
        val remoteUrl = repoManager.openGit(path).use { it.repository.config.getString("remote", "origin", "url") }
        return GitHubApi(null).parseOwnerRepo(remoteUrl)
    }

    fun listPullRequests(owner: String, repo: String, state: String): Pair<List<PullRequest>, PrOpResult> {
        val result = api.listPullRequests(owner, repo, state)
        return (result.getOrNull() ?: emptyList()) to result.toPrOpResult(host)
    }

    fun getPullRequest(owner: String, repo: String, number: Int): Pair<PullRequest?, PrOpResult> {
        val result = api.getPullRequest(owner, repo, number)
        return result.getOrNull() to result.toPrOpResult(host)
    }

    fun listComments(owner: String, repo: String, number: Int): Pair<List<PrComment>, PrOpResult> {
        val result = api.listComments(owner, repo, number)
        return (result.getOrNull() ?: emptyList()) to result.toPrOpResult(host)
    }

    fun createPullRequest(
        owner: String, repo: String, title: String, body: String, head: String, base: String, draft: Boolean
    ): Pair<PullRequest?, PrOpResult> {
        AppLog.i(TAG, "createPullRequest: $owner/$repo $head -> $base")
        val result = api.createPullRequest(owner, repo, title, body, head, base, draft)
        return result.getOrNull() to result.toPrOpResult(host)
    }

    fun mergePullRequest(owner: String, repo: String, number: Int, method: MergeMethod, commitTitle: String?): PrOpResult {
        AppLog.i(TAG, "mergePullRequest: $owner/$repo #$number ($method)")
        return api.mergePullRequest(owner, repo, number, method, commitTitle).toPrOpResult(host)
    }

    fun setPullRequestState(owner: String, repo: String, number: Int, open: Boolean): PrOpResult {
        AppLog.i(TAG, "setPullRequestState: $owner/$repo #$number open=$open")
        return api.setPullRequestState(owner, repo, number, open).toPrOpResult(host)
    }

    fun addComment(owner: String, repo: String, number: Int, body: String): PrOpResult {
        AppLog.i(TAG, "addComment: $owner/$repo #$number")
        return api.addComment(owner, repo, number, body).toPrOpResult(host)
    }

    fun listBaseBranches(owner: String, repo: String): List<String> =
        api.listLocalAndRemoteBranches(owner, repo).getOrNull() ?: emptyList()

    /**
     * Fetches refs/pull/<number>/head from origin and checks it out as a local branch
     * (pr-<number>), so the PR's actual code can be reviewed/tested/built locally —
     * this is the "fully manage" half that a pure REST client can't offer.
     */
    fun checkoutPullRequestLocally(path: String, number: Int): GitOpResult =
        repoManager.fetchAndCheckoutRef(
            path,
            refSpec = "+refs/pull/$number/head:refs/heads/pr-$number",
            localBranch = "pr-$number"
        )
}
