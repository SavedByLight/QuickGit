package com.quickgit.app.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.RepoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pushes the phone's local repo list to paired Wear OS devices.
 *
 * Important: DataItems are partitioned by applicationId. The phone
 * (com.quickgit.app) and wear (com.quickgit.app.wear) packages differ, so
 * DataItems written here are invisible to the watch. We therefore send the
 * list as a Message payload to the requesting node (works across packages).
 * DataItems are still written for same-package / future unified installs.
 *
 * Message request:  /quickgit/request_sync
 * Message response: /quickgit/repos_payload  (UTF-8 JSON body)
 */
class WearSyncManager(
    private val context: Context,
    private val repoManager: RepoManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)
    private val dataClient = Wearable.getDataClient(context)

    fun start() {
        scope.launch {
            runCatching {
                Wearable.getCapabilityClient(context)
                    .addLocalCapability(CAPABILITY_PHONE)
                    .await()
            }.onFailure { Log.w(TAG, "addLocalCapability failed: ${it.message}") }
        }
        repoManager.addLocalReposChangeListener { syncReposToWear() }
        // Best-effort push to any connected nodes (covers already-open watch).
        syncReposToWear()
    }

    /** Broadcast current list to every connected node + optional DataItem cache. */
    fun syncReposToWear() {
        scope.launch {
            runCatching {
                val repos = repoManager.listLocalRepos()
                val payload = encodeRepos(repos)
                // Same-package cache (no-op for the current wear applicationId).
                putReposDataItem(repos)
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isEmpty()) {
                    Log.i(TAG, "No connected Wear nodes to push ${repos.size} repos to")
                    return@launch
                }
                for (node in nodes) {
                    runCatching {
                        messageClient.sendMessage(node.id, PATH_REPOS_PAYLOAD, payload).await()
                        Log.i(TAG, "Sent ${repos.size} repos to node ${node.displayName}")
                    }.onFailure {
                        Log.w(TAG, "sendMessage to ${node.id} failed: ${it.message}")
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "syncReposToWear failed: ${e.message}")
            }
        }
    }

    /** Reply only to the watch that requested a sync. */
    fun replyReposToNode(nodeId: String) {
        scope.launch {
            runCatching {
                val repos = repoManager.listLocalRepos()
                val payload = encodeRepos(repos)
                messageClient.sendMessage(nodeId, PATH_REPOS_PAYLOAD, payload).await()
                Log.i(TAG, "Replied ${repos.size} repos to requesting node $nodeId")
            }.onFailure { e ->
                Log.w(TAG, "replyReposToNode failed: ${e.message}")
            }
        }
    }

    private suspend fun putReposDataItem(repos: List<RepoInfo>) {
        runCatching {
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
        }.onFailure {
            Log.d(TAG, "putDataItem skipped/failed (expected across different package names): ${it.message}")
        }
    }

    private fun encodeRepos(repos: List<RepoInfo>): ByteArray {
        val arr = JSONArray()
        for (r in repos) {
            arr.put(
                JSONObject()
                    .put(KEY_NAME, r.name)
                    .put(KEY_PATH, r.localPath)
                    .put(KEY_BRANCH, r.currentBranch)
                    .put(KEY_REMOTE, r.remoteUrl ?: "")
                    .put(KEY_DIRTY, r.hasUncommittedChanges)
            )
        }
        val root = JSONObject()
            .put(KEY_UPDATED_AT, System.currentTimeMillis())
            .put(KEY_REPOS, arr)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "WearSyncManager"

        const val PATH_REPOS = "/quickgit/repos"
        const val PATH_REQUEST_SYNC = "/quickgit/request_sync"
        const val PATH_REPOS_PAYLOAD = "/quickgit/repos_payload"
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
