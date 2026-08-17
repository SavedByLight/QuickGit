package com.quickgit.desktop.data.models

import java.io.File

/** A repository the app knows about, backed by a local clone under app-private storage. */
data class RepoInfo(
    val name: String,
    val localPath: String,
    val currentBranch: String,
    val remoteUrl: String?,
    val hasUncommittedChanges: Boolean
) {
    val dir: File get() = File(localPath)
}

enum class ChangeType { ADDED, MODIFIED, DELETED, RENAMED, CONFLICTING, UNTRACKED }

data class FileChange(
    val path: String,
    val type: ChangeType,
    val staged: Boolean
)

data class RepoStatus(
    val staged: List<FileChange>,
    val unstaged: List<FileChange>,
    val untracked: List<FileChange>,
    val conflicting: List<FileChange>
) {
    val isClean: Boolean
        get() = staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty() && conflicting.isEmpty()
}

data class CommitInfo(
    val id: String,
    val shortId: String,
    val message: String,
    val authorName: String,
    val authorEmail: String,
    val timeEpochSeconds: Long
)

data class BranchInfo(
    val name: String,
    val isCurrent: Boolean,
    val isRemote: Boolean
)

enum class DiffLineType { CONTEXT, ADDED, REMOVED, HEADER }

data class DiffLine(val type: DiffLineType, val text: String)

data class FileDiff(
    val path: String,
    val lines: List<DiffLine>,
    val isBinary: Boolean = false
)

/** Result of a push/pull/fetch operation, surfaced to the UI. */
sealed class GitOpResult {
    data object Success : GitOpResult()
    data class UpToDate(val message: String = "Already up to date") : GitOpResult()
    data class Conflict(val paths: List<String>) : GitOpResult()
    data class AuthRequired(val remoteUrl: String) : GitOpResult()
    data class Error(val message: String, val cause: Throwable? = null) : GitOpResult()
}

enum class AuthType { HTTPS_TOKEN, SSH_KEY, NONE }

/** Entry in the repo file browser (file or directory). */
data class RepoEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L
)

/** A repository returned by the GitHub API for the authenticated user. */
data class GitHubRemoteRepo(
    val id: Long,
    val name: String,
    val fullName: String,
    val description: String?,
    val htmlUrl: String,
    val cloneUrl: String,
    val sshUrl: String,
    val isPrivate: Boolean,
    val isFork: Boolean,
    val ownerLogin: String,
    val defaultBranch: String,
    val updatedAt: String,
    val language: String?
)


enum class IssueStateFilter(val apiValue: String, val label: String) {
    OPEN("open", "Open"),
    CLOSED("closed", "Closed"),
    ALL("all", "All")
}

data class Issue(
    val number: Int,
    val title: String,
    val body: String?,
    val state: String, // "open" | "closed"
    val authorLogin: String,
    val commentsCount: Int,
    val labels: List<String>,
    val htmlUrl: String,
    val createdAt: String,
    val updatedAt: String,
    val isPullRequest: Boolean = false
)


/** Merge strategy offered by GitHub's "merge pull request" API. */
enum class MergeMethod(val apiValue: String, val label: String) {
    MERGE("merge", "Merge commit"),
    SQUASH("squash", "Squash and merge"),
    REBASE("rebase", "Rebase and merge")
}

enum class PrStateFilter(val apiValue: String, val label: String) {
    OPEN("open", "Open"),
    CLOSED("closed", "Closed"),
    ALL("all", "All")
}

data class PullRequest(
    val number: Int,
    val title: String,
    val body: String?,
    val state: String,            // "open" | "closed"
    val isDraft: Boolean,
    val merged: Boolean,
    val mergeable: Boolean?,      // null while GitHub is still computing it
    val authorLogin: String,
    val headRef: String,          // branch name on the (possibly forked) head repo
    val headRepoFullName: String?,// e.g. "someone/quickgit" — null if the head repo/fork was deleted
    val headSha: String,
    val baseRef: String,
    val commentsCount: Int,
    val htmlUrl: String,
    val createdAt: String,
    val updatedAt: String
)

data class PrComment(
    val id: Long,
    val authorLogin: String,
    val body: String,
    val createdAt: String
)

/** Result of a GitHub PR operation. Mirrors GitOpResult so the same snackbar/auth-routing UI logic applies. */
sealed class PrOpResult {
    data object Success : PrOpResult()
    data class Error(val message: String, val cause: Throwable? = null) : PrOpResult()
    data class AuthRequired(val host: String) : PrOpResult()
}


/** A project returned by the GitLab API. */
data class GitLabProject(
    val id: Long,
    val name: String,
    val pathWithNamespace: String,
    val description: String?,
    val webUrl: String,
    val httpUrlToRepo: String,
    val sshUrlToRepo: String,
    val isPrivate: Boolean,
    val isFork: Boolean,
    val defaultBranch: String,
    val updatedAt: String,
    val starCount: Int = 0
)

/** GitLab Merge Request (MR). */
data class MergeRequest(
    val iid: Int,                 // project-scoped ID (shown in UI)
    val id: Long,                 // global ID
    val title: String,
    val description: String?,
    val state: String,            // opened | closed | merged | locked
    val draft: Boolean,
    val merged: Boolean,
    val mergeStatus: String?,     // can_be_merged | cannot_be_merged | unchecked | ...
    val authorUsername: String,
    val sourceBranch: String,
    val targetBranch: String,
    val webUrl: String,
    val createdAt: String,
    val updatedAt: String,
    val userNotesCount: Int = 0
)

/** GitLab issue. */
data class GitLabIssue(
    val iid: Int,
    val id: Long,
    val title: String,
    val description: String?,
    val state: String,            // opened | closed
    val authorUsername: String,
    val webUrl: String,
    val createdAt: String,
    val updatedAt: String,
    val labels: List<String> = emptyList()
)

/** Comment / note on an MR or issue. */
data class GitLabNote(
    val id: Long,
    val body: String,
    val authorUsername: String,
    val createdAt: String,
    val system: Boolean = false
)

enum class MrStateFilter(val apiValue: String, val label: String) {
    OPENED("opened", "Open"),
    CLOSED("closed", "Closed"),
    MERGED("merged", "Merged"),
    ALL("all", "All")
}
