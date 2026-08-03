package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.RepoManager
import com.quickgit.app.data.models.RepoEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FilesUiState(
    val currentDir: String = "",
    val entries: List<RepoEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val statusMessage: String? = null,
    /** Relative path of a file just created — UI can navigate to editor. */
    val openAfterCreate: String? = null
)

class FilesViewModel(private val repoManager: RepoManager) : ViewModel() {
    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()
    private lateinit var repoPath: String

    fun init(repoPath: String) {
        this.repoPath = repoPath
        openDir("")
    }

    fun openDir(relativeDir: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, currentDir = relativeDir)
            try {
                val entries = withContext(Dispatchers.IO) {
                    repoManager.listDirectory(repoPath, relativeDir)
                }
                _state.value = _state.value.copy(entries = entries, loading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Failed to list directory")
            }
        }
    }

    fun goUp() {
        val current = _state.value.currentDir
        if (current.isBlank()) return
        val parent = current.substringBeforeLast('/', missingDelimiterValue = "")
        openDir(parent)
    }

    fun createFile(name: String) {
        val dir = _state.value.currentDir
        val relative = if (dir.isBlank()) name.trim() else "$dir/${name.trim()}"
        viewModelScope.launch {
            try {
                val created = withContext(Dispatchers.IO) {
                    repoManager.createTextFile(repoPath, relative, "")
                }
                _state.value = _state.value.copy(
                    statusMessage = "Created $created",
                    openAfterCreate = created
                )
                openDir(dir)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Could not create file",
                    statusMessage = null
                )
            }
        }
    }

    fun createFolder(name: String) {
        val dir = _state.value.currentDir
        val relative = if (dir.isBlank()) name.trim() else "$dir/${name.trim()}"
        viewModelScope.launch {
            try {
                val created = withContext(Dispatchers.IO) {
                    repoManager.createDirectory(repoPath, relative)
                }
                _state.value = _state.value.copy(statusMessage = "Created folder $created")
                openDir(dir)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message ?: "Could not create folder",
                    statusMessage = null
                )
            }
        }
    }

    fun consumeOpenAfterCreate() {
        _state.value = _state.value.copy(openAfterCreate = null)
    }

    fun consumeMessages() {
        _state.value = _state.value.copy(error = null, statusMessage = null)
    }
}
