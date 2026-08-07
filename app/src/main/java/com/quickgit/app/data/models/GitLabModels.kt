package com.quickgit.app.data.models

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
