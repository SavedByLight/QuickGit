package com.quickgit.wear.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
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
 * Pulls repos from the phone via MessageClient (+ DataItem when applicationIds match).
 *
 * applicationId on both modules must be com.quickgit.app.
 */
class WearRepoRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val dataClient: DataClient = Wearable.getDataClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)

    private val _repos = MutableStateFlow<List<WearRepoSummary>>(emptyList())
    val repos: StateFlow<List<WearRepoSummary>> = _repos.asStateFlow()

    private val _connection = MutableStateFlow<WearConnectionState>(WearConnectionState.Loading)
    val connection: StateFlow<WearConnectionState> = _connection.asStateFlow()

    private val _diagnostics = MutableStateFlow("")
    val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    private var timeoutJob: Job? = null

    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        Log.i(TAG, "Watch got message path=${event.path} bytes=${event.data.size}")
        if (event.path != PATH_REPOS_PAYLOAD) return@OnMessageReceivedListener
        runCatching { applyJsonPayload(event.data) }
            .onFailure {
                Log.w(TAG, "parse message failed: ${it.message}")
                _connection.value = WearConnectionState.Error(it.message ?: "Bad payload")
            }
    }

    private val dataListener = DataClient.OnDataChangedListener { buffer ->
        try {
            for (event in buffer) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                if (event.dataItem.uri.path != PATH_REPOS) continue
                Log.i(TAG, "Watch got DataItem ${event.dataItem.uri}")
                runCatching {
                    applyDataMap(DataMapItem.fromDataItem(event.dataItem).dataMap)
                }.onFailure { Log.w(TAG, "DataItem parse: ${it.message}") }
            }
        } finally {
            buffer.release()
        }
    }

    fun start() {
        messageClient.addListener(messageListener)
        dataClient.addListener(dataListener)
        // Load any cached DataItem already on the device.
        scope.launch {
            runCatching {
                val items = dataClient.dataItems.await()
                try {
                    for (item in items) {
                        if (item.uri.path == PATH_REPOS) {
                            applyDataMap(DataMapItem.fromDataItem(item).dataMap)
                        }
                    }
                } finally {
                    items.release()
                }
            }
        }
        requestSyncFromPhone()
    }

    fun stop() {
        timeoutJob?.cancel()
        messageClient.removeListener(messageListener)
        dataClient.removeListener(dataListener)
    }

    fun getRepo(path: String): WearRepoSummary? =
        _repos.value.firstOrNull { it.path == path }

    fun requestSyncFromPhone() {
        timeoutJob?.cancel()
        _connection.value = WearConnectionState.Loading
        scope.launch {
            try {
                val connected = runCatching { nodeClient.connectedNodes.await() }.getOrDefault(emptyList())
                val capabilityNodes = runCatching {
                    capabilityClient
                        .getCapability(CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                        .await()
                        .nodes
                        .toList()
                }.getOrDefault(emptyList())

                val diag = buildString {
                    append("nodes=${connected.size}")
                    if (connected.isNotEmpty()) {
                        append(" [")
                        append(connected.joinToString { it.displayName })
                        append("]")
                    }
                    append("  cap=${capabilityNodes.size}")
                }
                _diagnostics.value = diag
                Log.i(TAG, "Discovery: $diag")

                val targets: List<Node> = when {
                    capabilityNodes.isNotEmpty() -> capabilityNodes
                    connected.isNotEmpty() -> connected
                    else -> emptyList()
                }

                if (targets.isEmpty()) {
                    _connection.value = WearConnectionState.Disconnected(
                        "No phone linked over Wear OS.\n\n" +
                            "1. Pair watch in Galaxy Wearable\n" +
                            "2. Install QuickGit on the PHONE\n" +
                            "3. Open QuickGit on the phone\n" +
                            "4. Tap Refresh here\n\n" +
                            "Wireless debugging is fine; Bluetooth to the phone must stay on."
                    )
                    return@launch
                }

                var sent = false
                for (node in targets) {
                    runCatching {
                        messageClient.sendMessage(node.id, PATH_REQUEST_SYNC, ByteArray(0)).await()
                        sent = true
                        Log.i(TAG, "request_sync → ${node.displayName}")
                    }.onFailure {
                        Log.w(TAG, "sendMessage failed: ${it.message}")
                    }
                }

                if (!sent) {
                    _connection.value = WearConnectionState.Disconnected(
                        "Found phone but could not send.\nOpen QuickGit on the phone, then Refresh."
                    )
                    return@launch
                }

                timeoutJob = scope.launch {
                    delay(SYNC_TIMEOUT_MS)
                    if (_connection.value is WearConnectionState.Loading) {
                        _connection.value = WearConnectionState.Disconnected(
                            "Phone did not reply ($diag).\n\n" +
                                "Open QuickGit on the phone and leave it open,\n" +
                                "then Refresh. Check Logcat for WearSyncManager."
                        )
                        Log.w(TAG, "Timed out. $diag")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "requestSyncFromPhone: ${e.message}", e)
                _connection.value = WearConnectionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun applyJsonPayload(bytes: ByteArray) {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
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
        publish(summaries, root.optLong(KEY_UPDATED_AT, System.currentTimeMillis()))
    }

    private fun applyDataMap(map: com.google.android.gms.wearable.DataMap) {
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
        }
        publish(summaries, map.getLong(KEY_UPDATED_AT, System.currentTimeMillis()))
    }

    private fun publish(summaries: List<WearRepoSummary>, updatedAt: Long) {
        timeoutJob?.cancel()
        _repos.value = summaries.sortedBy { it.name.lowercase() }
        _connection.value = WearConnectionState.Connected
        Log.i(TAG, "Synced ${summaries.size} repos (updatedAt=$updatedAt)")
    }

    companion object {
        private const val TAG = "WearRepoRepository"
        private const val SYNC_TIMEOUT_MS = 15_000L

        const val PATH_REQUEST_SYNC = "/quickgit/request_sync"
        const val PATH_REPOS_PAYLOAD = "/quickgit/repos_payload"
        const val PATH_REPOS = "/quickgit/repos"
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
