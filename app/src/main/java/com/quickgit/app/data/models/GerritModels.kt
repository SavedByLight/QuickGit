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
