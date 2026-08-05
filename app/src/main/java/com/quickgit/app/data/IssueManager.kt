package com.quickgit.app.data

import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.github.toPrOpResult
import com.quickgit.app.data.models.Issue
import com.quickgit.app.data.models.PrComment
import com.quickgit.app.data.models.PrOpResult

/**
 * GitHub Issues management for github.com remotes, using the same HTTPS token as git/PR flows.
 */
class IssueManager(private val repoManager: RepoManager, private val credentialStore: CredentialStore) {

    private val TAG = "IssueManager"
    private val host = "github.com"

    private val api: GitHubApi get() = GitHubApi(credentialStore.getHttpsToken(host))

    fun ownerRepoFor(path: String): GitHubApi.OwnerRepo? {
        val remoteUrl = repoManager.openGit(path).use { it.repository.config.getString("remote", "origin", "url") }
        return GitHubApi(null).parseOwnerRepo(remoteUrl)
    }

    fun listIssues(owner: String, repo: String, state: String): Pair<List<Issue>, PrOpResult> {
        val result = api.listIssues(owner, repo, state)
        return (result.getOrNull() ?: emptyList()) to result.toPrOpResult(host)
    }

    fun getIssue(owner: String, repo: String, number: Int): Pair<Issue?, PrOpResult> {
        val result = api.getIssue(owner, repo, number)
        return result.getOrNull() to result.toPrOpResult(host)
    }

    fun listComments(owner: String, repo: String, number: Int): Pair<List<PrComment>, PrOpResult> {
        val result = api.listComments(owner, repo, number)
        return (result.getOrNull() ?: emptyList()) to result.toPrOpResult(host)
    }

    fun createIssue(owner: String, repo: String, title: String, body: String): Pair<Issue?, PrOpResult> {
        AppLog.i(TAG, "createIssue: $owner/$repo \"$title\"")
        val result = api.createIssue(owner, repo, title, body)
        return result.getOrNull() to result.toPrOpResult(host)
    }

    fun setIssueState(owner: String, repo: String, number: Int, open: Boolean): PrOpResult {
        AppLog.i(TAG, "setIssueState: $owner/$repo #$number open=$open")
        return api.setIssueState(owner, repo, number, open).toPrOpResult(host)
    }

    fun addComment(owner: String, repo: String, number: Int, body: String): PrOpResult {
        AppLog.i(TAG, "addComment: $owner/$repo #$number")
        return api.addComment(owner, repo, number, body).toPrOpResult(host)
    }
}
