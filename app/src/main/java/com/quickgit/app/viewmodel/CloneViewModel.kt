package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.GitOpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CloneUiState(
    val inProgress: Boolean = false,
    val progressText: String = "",
    val result: GitOpResult? = null
)

class CloneViewModel(private val repoManager: RepoManager) : ViewModel() {
    private val _state = MutableStateFlow(CloneUiState())
    val state: StateFlow<CloneUiState> = _state.asStateFlow()

    fun clone(url: String, folderName: String) {
        _state.value = CloneUiState(inProgress = true, progressText = "Starting…")
        viewModelScope.launch(Dispatchers.IO) {
            val result = repoManager.cloneRepo(url, folderName) { progress ->
                _state.value = _state.value.copy(progressText = progress)
            }
            _state.value = CloneUiState(inProgress = false, result = result)
        }
    }

    fun consumeResult() { _state.value = _state.value.copy(result = null) }
}
