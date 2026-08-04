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
    /** True while create/checkout/delete is running — used to disable buttons. */
    val busy: Boolean = false,
    /** True only while a pull-to-refresh (or the initial load) is in flight — drives the refresh indicator. */
    val refreshing: Boolean = false,
    val lastResult: GitOpResult? = null
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
            _state.value = _state.value.copy(
                branches = branches,
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

    fun consumeResult() { _state.value = _state.value.copy(lastResult = null) }
}
