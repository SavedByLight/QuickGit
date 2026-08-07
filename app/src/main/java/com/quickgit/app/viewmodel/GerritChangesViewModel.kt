package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GerritAccountManager
import com.quickgit.app.data.models.FileDiff
import com.quickgit.app.data.models.GerritChange
import com.quickgit.app.data.models.GerritFileChange
import com.quickgit.app.data.models.PrOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GerritChangeFilter(val label: String, val query: String) {
    OPEN("Open", "status:open"),
    MERGED("Merged", "status:merged"),
    ABANDONED("Abandoned", "status:abandoned"),
    MINE("Mine", "owner:self status:open")
}

data class GerritChangesUiState(
    val connected: Boolean = false,
    val host: String? = null,
    val username: String? = null,
    val filter: GerritChangeFilter = GerritChangeFilter.OPEN,
    val changes: List<GerritChange> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val authRequired: Boolean = false,
    // detail
    val selected: GerritChange? = null,
    val files: List<GerritFileChange> = emptyList(),
    val detailLoading: Boolean = false,
    // file diff
    val selectedFile: GerritFileChange? = null,
    val fileDiff: FileDiff? = null,
    val diffLoading: Boolean = false
)

class GerritChangesViewModel(
    private val gerritAccountManager: GerritAccountManager
) : ViewModel() {

    private val _state = MutableStateFlow(GerritChangesUiState())
    val state: StateFlow<GerritChangesUiState> = _state.asStateFlow()

    init {
        refreshConnections()
    }

    fun refreshConnections() {
        viewModelScope.launch {
            val host = gerritAccountManager.primaryHost()
                ?: gerritAccountManager.host.takeIf { gerritAccountManager.isConnected(it) }
            val connected = host != null && gerritAccountManager.isConnected(host)
            if (!connected) {
                _state.value = GerritChangesUiState(authRequired = true)
                return@launch
            }
            val user = gerritAccountManager.storedUsername(host!!)
            _state.value = _state.value.copy(
                connected = true,
                host = host,
                username = user,
                authRequired = false
            )
            refresh()
        }
    }

    fun setFilter(filter: GerritChangeFilter) {
        if (_state.value.filter == filter) return
        _state.value = _state.value.copy(filter = filter)
        refresh()
    }

    fun refresh() {
        val host = _state.value.host ?: return
        if (!_state.value.connected) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
            val (list, result) = withContext(Dispatchers.IO) {
                gerritAccountManager.listChanges(
                    query = _state.value.filter.query,
                    limit = 50,
                    h = host
                )
            }
            _state.value = applyResult(
                _state.value.copy(loading = false, changes = list),
                result
            )
        }
    }

    fun openChange(change: GerritChange) {
        val host = _state.value.host ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                selected = change,
                files = emptyList(),
                selectedFile = null,
                fileDiff = null,
                detailLoading = true,
                errorMessage = null
            )
            // Prefer numeric change number — more reliable than the project~branch~I… triplet
            val changeId = if (change.number > 0) change.number.toString()
                else change.id.ifBlank { change.number.toString() }

            val (detail, files, result) = withContext(Dispatchers.IO) {
                val triple = gerritAccountManager.getChangeWithFiles(changeId, host)
                if (triple.second.isNotEmpty()) {
                    triple
                } else {
                    // No files from detail (or detail failed) — hit /files endpoint
                    val (fallbackFiles, fallbackResult) = gerritAccountManager.listChangeFiles(
                        changeId = changeId,
                        revision = "current",
                        h = host
                    )
                    val mergedDetail = triple.first
                    val mergedResult = when {
                        fallbackFiles.isNotEmpty() -> PrOpResult.Success
                        triple.third is PrOpResult.Success -> fallbackResult
                        else -> triple.third
                    }
                    Triple(mergedDetail, fallbackFiles, mergedResult)
                }
            }
            _state.value = applyResult(
                _state.value.copy(
                    detailLoading = false,
                    files = files,
                    selected = detail ?: change
                ),
                result
            )
        }
    }

    fun closeDetail() {
        _state.value = _state.value.copy(
            selected = null,
            files = emptyList(),
            selectedFile = null,
            fileDiff = null
        )
    }

    fun openFileDiff(file: GerritFileChange) {
        val change = _state.value.selected ?: return
        val host = _state.value.host ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                selectedFile = file,
                fileDiff = null,
                diffLoading = true,
                errorMessage = null
            )
            val changeId = if (change.number > 0) change.number.toString()
                else change.id.ifBlank { change.number.toString() }
            val (diff, result) = withContext(Dispatchers.IO) {
                // Try "current" first (most reliable), then the SHA if present
                var pair = gerritAccountManager.getChangeFileDiff(
                    changeId = changeId,
                    filePath = file.path,
                    revision = "current",
                    h = host
                )
                if (pair.first == null && !change.currentRevision.isNullOrBlank()
                    && change.currentRevision != "current"
                ) {
                    pair = gerritAccountManager.getChangeFileDiff(
                        changeId = changeId,
                        filePath = file.path,
                        revision = change.currentRevision!!,
                        h = host
                    )
                }
                pair
            }
            _state.value = applyResult(
                _state.value.copy(diffLoading = false, fileDiff = diff),
                result
            )
        }
    }

    fun closeFileDiff() {
        _state.value = _state.value.copy(selectedFile = null, fileDiff = null)
    }

    fun consumeMessages() {
        _state.value = _state.value.copy(errorMessage = null, statusMessage = null)
    }

    private fun applyResult(state: GerritChangesUiState, result: PrOpResult): GerritChangesUiState {
        return when (result) {
            is PrOpResult.Success -> state
            is PrOpResult.AuthRequired -> state.copy(
                authRequired = true,
                connected = false,
                errorMessage = "Authentication required for ${result.host}"
            )
            is PrOpResult.Error -> state.copy(errorMessage = result.message)
            else -> state
        }
    }
}
