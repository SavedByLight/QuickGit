package com.quickgit.app.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.RepoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Pushes the phone's local repo list to paired Wear OS devices via the
 * Wearable Data Layer, and answers sync requests from the watch.
 *
 * Data path:  /quickgit/repos
 * Message path for request: /quickgit/request_sync
 */
class WearSyncManager(
    private val context: Context,
    private val repoManager: RepoManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)

    fun start() {
        // Publish capability so the watch can discover us.
        scope.launch {
            runCatching {
                Wearable.getCapabilityClient(context)
                    .addLocalCapability(CAPABILITY_PHONE)
                    .await()
            }.onFailure { Log.w(TAG, "addLocalCapability failed: ${it.message}") }
        }
        // Keep the watch list in sync when clones / imports change the set.
        repoManager.addLocalReposChangeListener { syncReposToWear() }
        // Initial push (best-effort; may fail if no node is connected yet).
        syncReposToWear()
    }

    /** Call whenever the local repo set changes (clone, import, delete, etc.). */
    fun syncReposToWear() {
        scope.launch {
            runCatching {
                val repos = repoManager.listLocalRepos()
                putReposDataItem(repos)
                Log.i(TAG, "Pushed ${repos.size} repos to Wear Data Layer")
            }.onFailure { e ->
                Log.w(TAG, "syncReposToWear failed: ${e.message}")
            }
        }
    }

    private suspend fun putReposDataItem(repos: List<RepoInfo>) {
        val request = PutDataMapRequest.create(PATH_REPOS).apply {
            dataMap.putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            val list = ArrayList<com.google.android.gms.wearable.DataMap>(repos.size)
            for (r in repos) {
                val m = com.google.android.gms.wearable.DataMap()
                m.putString(KEY_NAME, r.name)
                m.putString(KEY_PATH, r.localPath)
                m.putString(KEY_BRANCH, r.currentBranch)
                m.putString(KEY_REMOTE, r.remoteUrl ?: "")
                m.putBoolean(KEY_DIRTY, r.hasUncommittedChanges)
                list.add(m)
            }
            dataMap.putDataMapArrayList(KEY_REPOS, list)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
    }

    companion object {
        private const val TAG = "WearSyncManager"

        const val PATH_REPOS = "/quickgit/repos"
        const val PATH_REQUEST_SYNC = "/quickgit/request_sync"
        const val CAPABILITY_PHONE = "quickgit_phone"

        const val KEY_UPDATED_AT = "updatedAt"
        const val KEY_REPOS = "repos"
        const val KEY_NAME = "name"
        const val KEY_PATH = "path"
        const val KEY_BRANCH = "branch"
        const val KEY_REMOTE = "remote"
        const val KEY_DIRTY = "dirty"
    }
}
