package com.quickgit.app.data.models

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
