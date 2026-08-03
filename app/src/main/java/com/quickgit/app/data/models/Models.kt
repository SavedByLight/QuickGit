package com.quickgit.app.data.models

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
