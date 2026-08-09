package com.quickgit.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GitProgressNotifier
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
    val destinationError: String? = null,
    /** When true, destination is the default under reposRoot (no SAF pick required). */
    val usingDefaultDestination: Boolean = true
)

class CloneViewModel(
    private val repoManager: RepoManager,
    private val app: Application
) : ViewModel() {
    private val _state = MutableStateFlow(CloneUiState())
    val state: StateFlow<CloneUiState> = _state.asStateFlow()

    private var pickedDestination: File? = null
    private val notifier = GitProgressNotifier(app)

    /** Suggest a default folder name from a git URL (last path segment without .git). */
    fun defaultFolderNameFor(url: String): String {
        val trimmed = url.trim().removeSuffix("/").removeSuffix(".git")
        val last = trimmed.substringAfterLast('/').substringAfterLast(':')
        return last.ifBlank { "repo" }
    }

    /** Update the preview path for the default (reposRoot / folderName) destination. */
    fun previewDefaultDestination(url: String) {
        if (pickedDestination != null) return
        val name = defaultFolderNameFor(url)
        if (name.isBlank()) {
            _state.value = _state.value.copy(destinationPath = null, usingDefaultDestination = true)
            return
        }
        val dest = File(repoManager.reposRoot, name)
        _state.value = _state.value.copy(
            destinationPath = dest.absolutePath,
            destinationError = null,
            usingDefaultDestination = true
        )
    }

    /** Called with the tree the user picked via `ActivityResultContracts.OpenDocumentTree()`. */
    fun onDestinationPicked(treeUri: android.net.Uri) {
        when (val result = repoManager.resolveCloneDestination(treeUri)) {
            is RepoManager.ResolveCloneDestinationResult.Success -> {
                pickedDestination = result.path
                _state.value = _state.value.copy(
                    destinationPath = result.path.absolutePath,
                    destinationError = null,
                    usingDefaultDestination = false
                )
            }
            is RepoManager.ResolveCloneDestinationResult.Error -> {
                pickedDestination = null
                _state.value = _state.value.copy(
                    destinationPath = null,
                    destinationError = result.message,
                    usingDefaultDestination = true
                )
            }
        }
    }

    fun clearPickedDestination(url: String) {
        pickedDestination = null
        previewDefaultDestination(url)
    }

    fun clone(url: String) {
        val destination = pickedDestination
            ?: File(repoManager.reposRoot, defaultFolderNameFor(url)).also {
                it.parentFile?.mkdirs()
            }
        _state.value = _state.value.copy(
            inProgress = true,
            progressText = "Starting…",
            destinationPath = destination.absolutePath
        )
        notifier.start("Cloning…", "Starting…")
        viewModelScope.launch(Dispatchers.IO) {
            val result = repoManager.cloneRepo(url, destination) { progress ->
                _state.value = _state.value.copy(progressText = progress)
                val percent = parsePercent(progress)
                notifier.update(progress, percent)
            }
            _state.value = _state.value.copy(inProgress = false, result = result)
            when (result) {
                is GitOpResult.Success -> notifier.finish("Clone finished")
                is GitOpResult.UpToDate -> notifier.finish(result.message)
                is GitOpResult.AuthRequired -> notifier.cancel()
                is GitOpResult.Conflict -> notifier.cancel()
                is GitOpResult.Error -> {
                    notifier.update(result.message ?: "Clone failed")
                    notifier.cancel()
                }
            }
        }
    }

    fun consumeResult() { _state.value = _state.value.copy(result = null) }

    private fun parsePercent(text: String): Int? {
        // TextProgress emits e.g. "Receiving objects: 42%"
        val match = Regex("""(\d{1,3})\s*%""").find(text) ?: return null
        return match.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
    }
}
