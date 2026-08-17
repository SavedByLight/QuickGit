package com.quickgit.app.data.models

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
