package com.quickgit.app.data.models

/** A Gerrit Change (the core review unit). */
data class GerritChange(
    val id: String,               // change-id e.g. "myProject~master~I8473b95934b5732ac55d26310c6c9e5f4a3d8e3c"
    val number: Int,              // change number shown in UI
    val project: String,
    val branch: String,
    val subject: String,
    val status: String,           // NEW | MERGED | ABANDONED | ...
    val ownerName: String,
    val ownerEmail: String?,
    val created: String,
    val updated: String,
    val insertions: Int = 0,
    val deletions: Int = 0,
    val unresolvedCommentCount: Int = 0,
    val labels: Map<String, GerritLabelInfo> = emptyMap(),
    val currentRevision: String? = null,
    val webUrl: String? = null
)

data class GerritLabelInfo(
    val approved: Boolean = false,
    val rejected: Boolean = false,
    val value: Int? = null,       // current score if any
    val all: List<GerritApproval> = emptyList()
)

data class GerritApproval(
    val value: Int,
    val name: String,
    val email: String? = null,
    val date: String? = null
)

/** A message / comment on a Change. */
data class GerritMessage(
    val id: String,
    val message: String,
    val authorName: String,
    val date: String,
    val revisionNumber: Int? = null
)

/** Result of posting a review (comment + optional votes). */
data class GerritReviewInput(
    val message: String? = null,
    val labels: Map<String, Int> = emptyMap(),   // e.g. "Code-Review" to +2 / -1 / 0
    val draft: Boolean = false
)

/** Common Code-Review vote values. */
object GerritVotes {
    const val CODE_REVIEW = "Code-Review"
    const val VERIFIED = "Verified"
    val COMMON_VALUES = listOf(+2, +1, 0, -1, -2)
}

/** A Gerrit project (git repository) from the Projects API. */
data class GerritProject(
    val id: String,              // URL-encoded id from API
    val name: String,            // project name / path
    val description: String?,
    val state: String,           // ACTIVE | READ_ONLY | HIDDEN
    val webUrl: String,
    val cloneUrl: String,
    val sshUrl: String
)

/** A file touched by a Gerrit change revision. */
data class GerritFileChange(
    val path: String,
    val status: String,          // A | D | M | R | C | W | …
    val linesInserted: Int = 0,
    val linesDeleted: Int = 0,
    val sizeDelta: Long = 0,
    val binary: Boolean = false,
    val oldPath: String? = null   // for renames
) {
    val isAdded get() = status.equals("A", ignoreCase = true)
    val isDeleted get() = status.equals("D", ignoreCase = true)
    val isModified get() = status.equals("M", ignoreCase = true) || status.equals("R", ignoreCase = true)
}
