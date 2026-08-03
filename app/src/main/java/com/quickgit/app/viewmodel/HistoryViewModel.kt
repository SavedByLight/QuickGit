package com.quickgit.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.RepoManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class HistoryViewModel(application: Application, private val repoManager: RepoManager) : AndroidViewModel(application) {
    
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun refreshHistory(repoDir: File) {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Background refresh execution logic placeholder or synchronization trigger
            } catch (e: Exception) {
                _errorMessage.value = e.localizedMessage
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun revertCommit(repoDir: File, commitHash: String, onComplete: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = repoManager.revertCommit(repoDir, commitHash)
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()?.localizedMessage
            }
            onComplete(result)
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
