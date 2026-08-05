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
import java.io.File

data class CloneUiState(
    val inProgress: Boolean = false,
    val progressText: String = "",
    val result: GitOpResult? = null,
    val destinationPath: String? = null,
    val destinationError: String? = null
)

class CloneViewModel(private val repoManager: RepoManager) : ViewModel() {
    private val _state = MutableStateFlow(CloneUiState())
    val state: StateFlow<CloneUiState> = _state.asStateFlow()

    private var pickedDestination: File? = null

    /** Called with the tree the user picked via `ActivityResultContracts.OpenDocumentTree()`. */
    fun onDestinationPicked(treeUri: android.net.Uri) {
        when (val result = repoManager.resolveCloneDestination(treeUri)) {
            is RepoManager.ResolveCloneDestinationResult.Success -> {
                pickedDestination = result.path
                _state.value = _state.value.copy(
                    destinationPath = result.path.absolutePath,
                    destinationError = null
                )
            }
            is RepoManager.ResolveCloneDestinationResult.Error -> {
                pickedDestination = null
                _state.value = _state.value.copy(
                    destinationPath = null,
                    destinationError = result.message
                )
            }
        }
    }

    fun clone(url: String) {
        val destination = pickedDestination ?: return
        _state.value = _state.value.copy(inProgress = true, progressText = "Starting…")
        viewModelScope.launch(Dispatchers.IO) {
            val result = repoManager.cloneRepo(url, destination) { progress ->
                _state.value = _state.value.copy(progressText = progress)
            }
            _state.value = _state.value.copy(inProgress = false, result = result)
        }
    }

    fun consumeResult() { _state.value = _state.value.copy(result = null) }
}

