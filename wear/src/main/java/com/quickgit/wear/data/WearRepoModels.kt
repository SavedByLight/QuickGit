package com.quickgit.wear.data

/**
 * Snapshot of a repository that lives on the phone and is mirrored to the watch
 * over the Wearable Data Layer. Full git operations remain on the phone app;
 * the wear surface focuses on glanceable status.
 */
data class WearRepoSummary(
    val name: String,
    val path: String,
    val branch: String,
    val remoteUrl: String?,
    val hasUncommittedChanges: Boolean
)

sealed class WearConnectionState {
    data object Loading : WearConnectionState()
    data object Connected : WearConnectionState()
    data object Disconnected : WearConnectionState()
    data class Error(val message: String) : WearConnectionState()
}

object WearRoutes {
    const val LIST = "list"
    const val DETAIL = "detail/{path}"
    const val ABOUT = "about"

    fun detail(path: String): String {
        val encoded = android.util.Base64.encodeToString(
            path.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        return "detail/$encoded"
    }

    fun decodePath(encoded: String): String {
        val bytes = android.util.Base64.decode(
            encoded,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        return bytes.toString(Charsets.UTF_8)
    }
}
