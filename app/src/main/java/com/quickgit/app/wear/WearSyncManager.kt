package com.quickgit.app.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
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
 * Phone ↔ watch bridge.
 *
 * Watch asks with Message path [PATH_REQUEST_SYNC]; phone replies to that node
 * with [PATH_REPOS_PAYLOAD] (UTF-8 JSON). Also writes a DataItem on [PATH_REPOS]
 * (works when both APKs share applicationId com.quickgit.app).
 */
class WearSyncManager(
    private val context: Context,
    private val repoManager: RepoManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)
    private val dataClient = Wearable.getDataClient(context)

    /** Foreground listener so we still answer while MainActivity is open. */
    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        handleIncomingMessage(event)
    }

    fun start() {
        messageClient.addListener(messageListener)
        scope.launch {
            runCatching {
                Wearable.getCapabilityClient(context)
                    .addLocalCapability(CAPABILITY_PHONE)
                    .await()
                Log.i(TAG, "Advertised capability $CAPABILITY_PHONE")
            }.onFailure { Log.w(TAG, "addLocalCapability: ${it.message}") }

            runCatching {
                val nodes = nodeClient.connectedNodes.await()
                Log.i(TAG, "Connected wear nodes at start: ${nodes.map { it.displayName }}")
            }
        }
        repoManager.addLocalReposChangeListener { syncReposToWear() }
        syncReposToWear()
    }

    fun stop() {
        messageClient.removeListener(messageListener)
    }

    fun handleIncomingMessage(event: MessageEvent) {
        Log.i(TAG, "Incoming message path=${event.path} from=${event.sourceNodeId}")
        if (event.path == PATH_REQUEST_SYNC) {
            replyReposToNode(event.sourceNodeId)
        }
    }

    fun syncReposToWear() {
        scope.launch {
            runCatching {
                val repos = repoManager.listLocalRepos()
                val payload = encodeRepos(repos)
                putReposDataItem(repos)
                val nodes = nodeClient.connectedNodes.await()
                Log.i(TAG, "Pushing ${repos.size} repos to ${nodes.size} node(s)")
                if (nodes.isEmpty()) {
                    Log.w(TAG, "No connected Wear nodes — open the watch app after pairing")
                    return@launch
                }
                for (node in nodes) {
                    runCatching {
                        messageClient.sendMessage(node.id, PATH_REPOS_PAYLOAD, payload).await()
                        Log.i(TAG, "Sent payload to ${node.displayName}")
                    }.onFailure {
                        Log.w(TAG, "send to ${node.id} failed: ${it.message}")
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "syncReposToWear failed: ${e.message}")
            }
        }
    }

    fun replyReposToNode(nodeId: String) {
        scope.launch {
            runCatching {
                val repos = repoManager.listLocalRepos()
                val payload = encodeRepos(repos)
                putReposDataItem(repos)
                messageClient.sendMessage(nodeId, PATH_REPOS_PAYLOAD, payload).await()
                Log.i(TAG, "Replied ${repos.size} repos to $nodeId (${payload.size} bytes)")
            }.onFailure { e ->
                Log.e(TAG, "replyReposToNode($nodeId) failed: ${e.message}", e)
            }
        }
    }

    private suspend fun putReposDataItem(repos: List<RepoInfo>) {
        runCatching {
            val request = PutDataMapRequest.create(PATH_REPOS).apply {
                // Always change a field so putDataItem is not deduped away.
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
            Log.d(TAG, "putDataItem: ${it.message}")
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
        return JSONObject()
            .put(KEY_UPDATED_AT, System.currentTimeMillis())
            .put(KEY_REPOS, arr)
            .toString()
            .toByteArray(Charsets.UTF_8)
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
