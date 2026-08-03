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
    val busy: Boolean = false,
    val lastResult: GitOpResult? = null
)

class BranchesViewModel(private val repoManager: RepoManager) : ViewModel() {
    private val _state = MutableStateFlow(BranchesUiState())
    val state: StateFlow<BranchesUiState> = _state.asStateFlow()
    private lateinit var repoPath: String

    fun init(repoPath: String) {
        this.repoPath = repoPath
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val branches = withContext(Dispatchers.IO) { repoManager.listBranches(repoPath) }
            _state.value = _state.value.copy(branches = branches, busy = false)
        }
    }

    fun createBranch(name: String, checkout: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.createBranch(repoPath, name, checkout) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            refresh()
        }
    }

    fun checkout(name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.checkoutBranch(repoPath, name) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            refresh()
        }
    }

    fun delete(name: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { repoManager.deleteBranch(repoPath, name, false) }
            _state.value = _state.value.copy(busy = false, lastResult = result)
            refresh()
        }
    }

    fun consumeResult() { _state.value = _state.value.copy(lastResult = null) }
}
