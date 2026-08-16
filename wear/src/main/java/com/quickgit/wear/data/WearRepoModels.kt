package com.quickgit.wear.data

/**
 * Lightweight repo summary for the watch UI.
 * Full git operations remain on the phone app; the wear surface focuses on
 * status at a glance and deep-links / pairing messaging.
 */
data class WearRepoSummary(
    val name: String,
    val path: String,
    val branch: String,
    val dirty: Boolean,
    val remoteUrl: String?
)

sealed class WearDest {
    data object RepoList : WearDest()
    data class RepoDetail(val path: String) : WearDest()
    data object About : WearDest()
}
