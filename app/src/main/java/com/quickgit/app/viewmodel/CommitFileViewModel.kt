package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.RepoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CommitFileViewModel(private val repoManager: RepoManager) : ViewModel() {
    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun load(repoPath: String, commitId: String, filePath: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val text = withContext(Dispatchers.IO) {
                    repoManager.readTextAtCommit(repoPath, commitId, filePath)
                }
                _content.value = text
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to read file"
                _content.value = ""
            }
            _loading.value = false
        }
    }
}
