package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.CommitInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HistoryViewModel(private val repoManager: RepoManager) : ViewModel() {
    private val _commits = MutableStateFlow<List<CommitInfo>>(emptyList())
    val commits: StateFlow<List<CommitInfo>> = _commits.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun load(repoPath: String) {
        viewModelScope.launch {
            _loading.value = true
            _commits.value = withContext(Dispatchers.IO) { repoManager.getLog(repoPath, 200) }
            _loading.value = false
        }
    }
}
