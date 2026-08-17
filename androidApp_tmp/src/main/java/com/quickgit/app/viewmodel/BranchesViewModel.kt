package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.BranchInfo
import com.quickgit.app.data.models.GitOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BranchesUiState(
    val branches: List<BranchInfo> = emptyList(),
    val remotes: Map<String, String> = emptyMap(),
    /** True while a create/checkout/delete/remote op is running — used to disable buttons. */
    val busy: Boolean = false,
    /** True only while a pull-to-refresh (or the initial load) is in flight — drives the refresh indicator. */
    val refreshing: Boolean = false,
    val lastResult: GitOpResult? = null,
    val statusMessage: String? = null
)

class BranchesViewModel(private val repoManager: RepoManager) : ViewModel() {
    private val _state = MutableStateFlow(BranchesUiState())
    val state: StateFlow<BranchesUiState> = _state.asStateFlow()
    private lateinit var repoPath: String

    fun init(repoPath: String) {
        this.repoPath = repoPath
        loadBranches(showRefreshing = true)
    }

    /** Pull-to-refresh entry point — reloads the branch list and shows the refresh indicator while doing so. */
    fun refresh() = loadBranches(showRefreshing = true)

    private fun loadBranches(showRefreshing: Boolean) {
        viewModelScope.launch {
            if (showRefreshing) _state.value = _state.value.copy(refreshing = true)
            val branches = withContext(Dispatchers.IO) { repoManager.listBranches(repoPath) }
            val remotes = withContext(Dispatchers.IO) { repoManager.listRemotes(repoPath) }
            _state.value = _state.value.copy(
                branches = branches,
                remotes = remotes,
                refreshing = if (showRefreshing) false else _state.value.refreshing
            )
        }
    }

    fun createBranch(name: String, checkout: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.createBranch(repoPath, name, checkout) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            loadBranches(showRefreshing = false)
        }
    }

    fun checkout(name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.checkoutBranch(repoPath, name) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            loadBranches(showRefreshing = false)
        }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.deleteBranch(repoPath, name, false) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            loadBranches(showRefreshing = false)
        }
    }

    fun addRemote(name: String, url: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, statusMessage = null)
            val result = withContext(Dispatchers.IO) { repoManager.addOrSetRemote(repoPath, name, url) }
            _state.value = _state.value.copy(
                busy = false,
                lastResult = result,
                statusMessage = if (result is GitOpResult.Success) "Remote '$name' saved" else null
            )
            loadBranches(showRefreshing = false)
        }
    }

    fun removeRemote(name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, statusMessage = null)
            val result = withContext(Dispatchers.IO) { repoManager.removeRemote(repoPath, name) }
            _state.value = _state.value.copy(
                busy = false,
                lastResult = result,
                statusMessage = if (result is GitOpResult.Success) "Remote '$name' removed" else null
            )
            loadBranches(showRefreshing = false)
        }
    }

    fun fetchRemote(name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, statusMessage = null)
            val result = withContext(Dispatchers.IO) {
                repoManager.fetchRemote(repoPath, name) { /* progress ignored in VM for now */ }
            }
            _state.value = _state.value.copy(
                busy = false,
                lastResult = result,
                statusMessage = when (result) {
                    is GitOpResult.Success -> "Fetched '$name' — remote branches updated"
                    is GitOpResult.AuthRequired -> null
                    else -> null
                }
            )
            loadBranches(showRefreshing = false)
        }
    }

    fun consumeResult() {
        _state.value = _state.value.copy(lastResult = null, statusMessage = null)
    }
}
