package com.quickgit.wear.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Receives the phone's local repo list over the Wearable MessageClient.
 *
 * Phone and wear use different applicationIds (com.quickgit.app vs
 * com.quickgit.app.wear), so DataItems are NOT shared. The phone replies to
 * /quickgit/request_sync with a Message on /quickgit/repos_payload containing
 * UTF-8 JSON.
 */
class WearRepoRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val nodeClient: NodeClient = Wearable.getNodeClient(context)

    private val _repos = MutableStateFlow<List<WearRepoSummary>>(emptyList())
    val repos: StateFlow<List<WearRepoSummary>> = _repos.asStateFlow()

    private val _connection = MutableStateFlow<WearConnectionState>(WearConnectionState.Loading)
    val connection: StateFlow<WearConnectionState> = _connection.asStateFlow()

    private val _updatedAt = MutableStateFlow(0L)
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()

    private var timeoutJob: Job? = null

    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        if (event.path != PATH_REPOS_PAYLOAD) return@OnMessageReceivedListener
        runCatching {
            applyPayload(event.data)
        }.onFailure {
            Log.w(TAG, "Failed to parse repos payload: ${it.message}")
            _connection.value = WearConnectionState.Error(it.message ?: "Bad payload")
        }
    }

    fun start() {
        messageClient.addListener(messageListener)
        requestSyncFromPhone()
    }

    fun stop() {
        timeoutJob?.cancel()
        messageClient.removeListener(messageListener)
    }

    fun getRepo(path: String): WearRepoSummary? =
        _repos.value.firstOrNull { it.path == path }

    fun requestSyncFromPhone() {
        timeoutJob?.cancel()
        _connection.value = WearConnectionState.Loading
        scope.launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isEmpty()) {
                    _connection.value = WearConnectionState.Disconnected
                    Log.i(TAG, "No connected nodes — open QuickGit on the phone")
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
                if (!sent) {
                    _connection.value = WearConnectionState.Disconnected
                    return@launch
                }
                // If the phone never replies, leave Loading only briefly.
                timeoutJob = scope.launch {
                    delay(SYNC_TIMEOUT_MS)
                    if (_connection.value is WearConnectionState.Loading) {
                        _connection.value = WearConnectionState.Disconnected
                        Log.w(TAG, "Sync timed out waiting for phone reply")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestSyncFromPhone failed: ${e.message}")
                _connection.value = WearConnectionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun applyPayload(bytes: ByteArray) {
        val text = bytes.toString(Charsets.UTF_8)
        val root = JSONObject(text)
        val arr = root.optJSONArray(KEY_REPOS)
        val summaries = mutableListOf<WearRepoSummary>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString(KEY_NAME)
                val path = o.optString(KEY_PATH)
                if (name.isBlank() || path.isBlank()) continue
                summaries += WearRepoSummary(
                    name = name,
                    path = path,
                    branch = o.optString(KEY_BRANCH, "(unknown)"),
                    remoteUrl = o.optString(KEY_REMOTE).takeIf { it.isNotBlank() },
                    hasUncommittedChanges = o.optBoolean(KEY_DIRTY, false)
                )
            }
        }
        summaries.sortBy { it.name.lowercase() }
        timeoutJob?.cancel()
        _repos.value = summaries
        _updatedAt.value = root.optLong(KEY_UPDATED_AT, System.currentTimeMillis())
        _connection.value = WearConnectionState.Connected
        Log.i(TAG, "Received ${summaries.size} repos from phone via message")
    }

    companion object {
        private const val TAG = "WearRepoRepository"
        private const val SYNC_TIMEOUT_MS = 12_000L

        const val PATH_REQUEST_SYNC = "/quickgit/request_sync"
        const val PATH_REPOS_PAYLOAD = "/quickgit/repos_payload"

        const val KEY_UPDATED_AT = "updatedAt"
        const val KEY_REPOS = "repos"
        const val KEY_NAME = "name"
        const val KEY_PATH = "path"
        const val KEY_BRANCH = "branch"
        const val KEY_REMOTE = "remote"
        const val KEY_DIRTY = "dirty"
    }
}
