package com.quickgit.app.data

import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.github.toPrOpResult
import com.quickgit.app.data.gitlab.GitLabApi
import com.quickgit.app.data.models.GitLabIssue
import com.quickgit.app.data.models.GitLabNote
import com.quickgit.app.data.models.Issue
import com.quickgit.app.data.models.PrComment
import com.quickgit.app.data.models.PrOpResult

/**
 * Issues for GitHub and GitLab remotes, using the same HTTPS token as git operations.
 */
class IssueManager(private val repoManager: RepoManager, private val credentialStore: CredentialStore) {

    private val TAG = "IssueManager"

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
        if (hostLower.contains("github.com") || hostLower == "github.com") {
            val parsed = GitHubApi(null).parseOwnerRepo(remoteUrl) ?: return null
            return ProjectRef(host, false, parsed.owner, parsed.repo)
        }
        return null
    }

    /** Compatibility for callers that only need owner/repo (GitHub-shaped). */
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

    private fun GitLabIssue.toIssue(): Issue = Issue(
        number = iid,
        title = title,
        body = description,
        state = if (state == "opened") "open" else state,
        authorLogin = authorUsername,
        commentsCount = 0,
        labels = labels,
        htmlUrl = webUrl,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isPullRequest = false
    )

    private fun GitLabNote.toComment(): PrComment = PrComment(
        id = id,
        authorLogin = authorUsername,
        body = body,
        createdAt = createdAt
    )

    fun listIssues(ref: ProjectRef, state: String): Pair<List<Issue>, PrOpResult> {
        return if (ref.isGitLab) {
            val result = gitlabApi(ref.host).listIssues(ref.projectPath, mapStateToGitLab(state))
            (result.getOrNull()?.map { it.toIssue() } ?: emptyList()) to result.toPrOpResult(ref.host)
        } else {
            val result = githubApi().listIssues(ref.owner, ref.repo, state)
            (result.getOrNull() ?: emptyList()) to result.toPrOpResult(ref.host)
        }
    }

    fun getIssue(ref: ProjectRef, number: Int): Pair<Issue?, PrOpResult> {
        return if (ref.isGitLab) {
            val result = gitlabApi(ref.host).getIssue(ref.projectPath, number)
            result.getOrNull()?.toIssue() to result.toPrOpResult(ref.host)
        } else {
            val result = githubApi().getIssue(ref.owner, ref.repo, number)
            result.getOrNull() to result.toPrOpResult(ref.host)
        }
    }

    fun listComments(ref: ProjectRef, number: Int): Pair<List<PrComment>, PrOpResult> {
        return if (ref.isGitLab) {
            val result = gitlabApi(ref.host).listIssueNotes(ref.projectPath, number)
            val notes = result.getOrNull()?.filter { !it.system }?.map { it.toComment() } ?: emptyList()
            notes to result.toPrOpResult(ref.host)
        } else {
            val result = githubApi().listComments(ref.owner, ref.repo, number)
            (result.getOrNull() ?: emptyList()) to result.toPrOpResult(ref.host)
        }
    }

    fun createIssue(ref: ProjectRef, title: String, body: String): Pair<Issue?, PrOpResult> {
        AppLog.i(TAG, "createIssue: ${ref.projectPath} \"$title\"")
        return if (ref.isGitLab) {
            val result = gitlabApi(ref.host).createIssue(ref.projectPath, title, body)
            result.getOrNull()?.toIssue() to result.toPrOpResult(ref.host)
        } else {
            val result = githubApi().createIssue(ref.owner, ref.repo, title, body)
            result.getOrNull() to result.toPrOpResult(ref.host)
        }
    }

    fun setIssueState(ref: ProjectRef, number: Int, open: Boolean): PrOpResult {
        AppLog.i(TAG, "setIssueState: ${ref.projectPath} #$number open=$open")
        return if (ref.isGitLab) {
            gitlabApi(ref.host).setIssueState(ref.projectPath, number, open).toPrOpResult(ref.host)
        } else {
            githubApi().setIssueState(ref.owner, ref.repo, number, open).toPrOpResult(ref.host)
        }
    }

    fun addComment(ref: ProjectRef, number: Int, body: String): PrOpResult {
        AppLog.i(TAG, "addComment: ${ref.projectPath} #$number")
        return if (ref.isGitLab) {
            gitlabApi(ref.host).addIssueNote(ref.projectPath, number, body).toPrOpResult(ref.host)
        } else {
            githubApi().addComment(ref.owner, ref.repo, number, body).toPrOpResult(ref.host)
        }
    }

    // ---- Legacy owner/repo overloads (GitHub-only callers / older VMs) ----

    fun listIssues(owner: String, repo: String, state: String): Pair<List<Issue>, PrOpResult> =
        listIssues(ProjectRef("github.com", false, owner, repo), state)

    fun getIssue(owner: String, repo: String, number: Int): Pair<Issue?, PrOpResult> =
        getIssue(ProjectRef("github.com", false, owner, repo), number)

    fun listComments(owner: String, repo: String, number: Int): Pair<List<PrComment>, PrOpResult> =
        listComments(ProjectRef("github.com", false, owner, repo), number)

    fun createIssue(owner: String, repo: String, title: String, body: String): Pair<Issue?, PrOpResult> =
        createIssue(ProjectRef("github.com", false, owner, repo), title, body)

    fun setIssueState(owner: String, repo: String, number: Int, open: Boolean): PrOpResult =
        setIssueState(ProjectRef("github.com", false, owner, repo), number, open)

    fun addComment(owner: String, repo: String, number: Int, body: String): PrOpResult =
        addComment(ProjectRef("github.com", false, owner, repo), number, body)
}
