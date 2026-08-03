package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.FileDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiffViewModel(private val repoManager: RepoManager) : ViewModel() {
    private val _diff = MutableStateFlow<FileDiff?>(null)
    val diff: StateFlow<FileDiff?> = _diff.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** mode is "working", "staged", or "commit:<sha>" */
    fun load(repoPath: String, filePath: String, mode: String) {
        viewModelScope.launch {
            _loading.value = true
            _diff.value = withContext(Dispatchers.IO) {
                when {
                    mode == "working" -> repoManager.getWorkingDiff(repoPath, filePath)
                    mode == "staged" -> repoManager.getStagedDiff(repoPath, filePath)
                    mode.startsWith("commit:") -> repoManager.getCommitDiff(repoPath, mode.removePrefix("commit:"), filePath)
                    else -> repoManager.getWorkingDiff(repoPath, filePath)
                }
            }
            _loading.value = false
        }
    }
}
