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

data class EditorUiState(
    val relativePath: String = "",
    val content: String = "",
    val originalContent: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val savedMessage: String? = null
) {
    val isDirty: Boolean get() = content != originalContent
}

class EditorViewModel(private val repoManager: RepoManager) : ViewModel() {
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()
    private lateinit var repoPath: String

    fun load(repoPath: String, relativePath: String) {
        this.repoPath = repoPath
        viewModelScope.launch {
            _state.value = EditorUiState(relativePath = relativePath, loading = true)
            try {
                val text = withContext(Dispatchers.IO) {
                    repoManager.readTextFile(repoPath, relativePath)
                }
                _state.value = EditorUiState(
                    relativePath = relativePath,
                    content = text,
                    originalContent = text,
                    loading = false
                )
            } catch (e: Exception) {
                _state.value = EditorUiState(
                    relativePath = relativePath,
                    loading = false,
                    error = e.message ?: "Failed to open file"
                )
            }
        }
    }

    fun setContent(text: String) {
        _state.value = _state.value.copy(content = text, savedMessage = null)
    }

    fun save() {
        val s = _state.value
        if (!s.isDirty || s.saving) return
        viewModelScope.launch {
            _state.value = s.copy(saving = true, error = null)
            try {
                withContext(Dispatchers.IO) {
                    repoManager.writeTextFile(repoPath, s.relativePath, s.content)
                }
                _state.value = _state.value.copy(
                    saving = false,
                    originalContent = s.content,
                    savedMessage = "Saved"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    saving = false,
                    error = e.message ?: "Save failed"
                )
            }
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(savedMessage = null, error = null)
    }
}
