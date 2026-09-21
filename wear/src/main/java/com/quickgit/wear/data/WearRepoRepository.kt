package com.quickgit.wear.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Receives the phone's local repo list over the Wearable Data Layer and
 * exposes it as reactive state for the Wear UI.
 *
 * Protocol (must match phone WearSyncManager):
 *   Data path:    /quickgit/repos
 *   Message path: /quickgit/request_sync
 *   Capability:   quickgit_phone
 */
class WearRepoRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    private val _repos = MutableStateFlow<List<WearRepoSummary>>(emptyList())
    val repos: StateFlow<List<WearRepoSummary>> = _repos.asStateFlow()

    private val _connection = MutableStateFlow<WearConnectionState>(WearConnectionState.Loading)
    val connection: StateFlow<WearConnectionState> = _connection.asStateFlow()

    private val _updatedAt = MutableStateFlow(0L)
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()

    private val dataListener = DataClient.OnDataChangedListener { buffer: DataEventBuffer ->
        try {
            for (event in buffer) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val uri = event.dataItem.uri
                if (uri.path != PATH_REPOS) continue
                applyDataItem(DataMapItem.fromDataItem(event.dataItem).dataMap)
            }
        } finally {
            buffer.release()
        }
    }

    fun start() {
        dataClient.addListener(dataListener)
        refreshFromDataLayer()
        requestSyncFromPhone()
    }

    fun stop() {
        dataClient.removeListener(dataListener)
    }

    fun getRepo(path: String): WearRepoSummary? =
        _repos.value.firstOrNull { it.path == path }

    fun requestSyncFromPhone() {
        scope.launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isEmpty()) {
                    _connection.value = WearConnectionState.Disconnected
                    Log.i(TAG, "No connected nodes — phone app not reachable")
                    return@launch
                }
                var sent = false
                for (node in nodes) {
                    runCatching {
                        messageClient.sendMessage(
                            node.id,
                            PATH_REQUEST_SYNC,
                            ByteArray(0)
                        ).await()
                        sent = true
                        Log.i(TAG, "Requested sync from node ${node.displayName}")
                    }.onFailure {
                        Log.w(TAG, "sendMessage to ${node.id} failed: ${it.message}")
                    }
                }
                if (!sent && _repos.value.isEmpty()) {
                    _connection.value = WearConnectionState.Disconnected
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestSyncFromPhone failed: ${e.message}")
                if (_repos.value.isEmpty()) {
                    _connection.value = WearConnectionState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun refreshFromDataLayer() {
        dataClient.dataItems
            .addOnSuccessListener { buffer ->
                try {
                    var found = false
                    for (item in buffer) {
                        if (item.uri.path == PATH_REPOS) {
                            applyDataItem(DataMapItem.fromDataItem(item).dataMap)
                            found = true
                        }
                    }
                    if (!found && _repos.value.isEmpty()) {
                        _connection.value = WearConnectionState.Loading
                    }
                } finally {
                    buffer.release()
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "dataItems query failed: ${e.message}")
                if (_repos.value.isEmpty()) {
                    _connection.value = WearConnectionState.Error(e.message ?: "Data Layer error")
                }
            }
    }

    private fun applyDataItem(map: com.google.android.gms.wearable.DataMap) {
        val list = map.getDataMapArrayList(KEY_REPOS) ?: emptyList()
        val summaries = list.mapNotNull { m ->
            val name = m.getString(KEY_NAME) ?: return@mapNotNull null
            val path = m.getString(KEY_PATH) ?: return@mapNotNull null
            WearRepoSummary(
                name = name,
                path = path,
                branch = m.getString(KEY_BRANCH) ?: "(unknown)",
                remoteUrl = m.getString(KEY_REMOTE)?.takeIf { it.isNotBlank() },
                hasUncommittedChanges = m.getBoolean(KEY_DIRTY, false)
            )
        }.sortedBy { it.name.lowercase() }

        _repos.value = summaries
        _updatedAt.value = map.getLong(KEY_UPDATED_AT, System.currentTimeMillis())
        _connection.value = WearConnectionState.Connected
        Log.i(TAG, "Received ${summaries.size} repos from phone")
    }

    companion object {
        private const val TAG = "WearRepoRepository"

        const val PATH_REPOS = "/quickgit/repos"
        const val PATH_REQUEST_SYNC = "/quickgit/request_sync"

        const val KEY_UPDATED_AT = "updatedAt"
        const val KEY_REPOS = "repos"
        const val KEY_NAME = "name"
        const val KEY_PATH = "path"
        const val KEY_BRANCH = "branch"
        const val KEY_REMOTE = "remote"
        const val KEY_DIRTY = "dirty"
    }
}
