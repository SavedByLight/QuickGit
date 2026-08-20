package com.quickgit.app.viewmodel

import android.net.Uri
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

/** A file picked from local storage that collides with an existing name in the current folder. */
data class PendingImportConflict(val uri: Uri, val fileName: String)

data class FilesUiState(
    val currentDir: String = "",
    val entries: List<RepoEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val statusMessage: String? = null,
    /** Relative path of a file just created — UI can navigate to editor. */
    val openAfterCreate: String? = null,
    /** Non-null while waiting for the user to confirm overwriting a same-named file. */
    val importConflict: PendingImportConflict? = null
)

class FilesViewModel(private val repoManager: RepoManager) : ViewModel() {
    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()
    private lateinit var repoPath: String

    private val importQueue = ArrayDeque<Uri>()
    private var importedCount = 0
    private val importFailures = mutableListOf<String>()

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

    fun deleteEntry(entry: com.quickgit.app.data.models.RepoEntry) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repoManager.deleteWorkingPath(repoPath, entry.relativePath)
                }
                val kind = if (entry.isDirectory) "folder" else "file"
                _state.value = _state.value.copy(statusMessage = "Deleted $kind ${entry.name}")
                openDir(_state.value.currentDir)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Could not delete")
            }
        }
    }

    fun renameEntry(entry: com.quickgit.app.data.models.RepoEntry, newName: String) {
        viewModelScope.launch {
            try {
                val newRel = withContext(Dispatchers.IO) {
                    repoManager.renameWorkingPath(repoPath, entry.relativePath, newName)
                }
                val kind = if (entry.isDirectory) "folder" else "file"
                _state.value = _state.value.copy(statusMessage = "Renamed $kind to ${newRel.substringAfterLast('/')}")
                openDir(_state.value.currentDir)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Could not rename")
            }
        }
    }


    /**
     * Begins importing one or more files picked from local/device storage into the current
     * folder. Files that collide with an existing name are queued for a one-at-a-time overwrite
     * confirmation via [state]'s `importConflict`; the rest are copied in directly.
     */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importQueue.clear()
        importQueue.addAll(uris)
        importedCount = 0
        importFailures.clear()
        processNextImport()
    }

    /**
     * Import a folder picked via SAF tree URI into the current directory.
     * Existing same-named folder is merged; existing files are overwritten.
     */
    fun importFolder(treeUri: Uri) {
        val dir = _state.value.currentDir
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    repoManager.importDirectory(repoPath, dir, treeUri, overwriteExistingFiles = true)
                }
                val parts = mutableListOf<String>()
                if (result.filesCopied > 0) parts += "${result.filesCopied} added"
                if (result.filesOverwritten > 0) parts += "${result.filesOverwritten} overwritten"
                if (result.dirsCreated > 0) parts += "${result.dirsCreated} folders"
                val summary = if (parts.isEmpty()) {
                    "Merged folder ${result.folderRelativePath.substringAfterLast('/')} (no file changes)"
                } else {
                    "Imported folder ${result.folderRelativePath.substringAfterLast('/')}: ${parts.joinToString(", ")}"
                }
                openDir(dir)
                _state.value = _state.value.copy(statusMessage = summary)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Could not import folder")
            }
        }
    }


    /** User chose to overwrite the file named in the current `importConflict`. */
    fun confirmOverwrite() {
        val conflict = _state.value.importConflict ?: return
        _state.value = _state.value.copy(importConflict = null)
        importOne(conflict.uri, conflict.fileName, overwrite = true)
    }

    /** User chose not to overwrite — skip this file and continue with the rest of the batch. */
    fun cancelImportConflict() {
        if (_state.value.importConflict == null) return
        _state.value = _state.value.copy(importConflict = null)
        processNextImport()
    }

    private fun processNextImport() {
        val uri = importQueue.removeFirstOrNull()
        if (uri == null) {
            finishImportBatch()
            return
        }
        val dir = _state.value.currentDir
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { repoManager.displayNameFor(uri) }
                val exists = withContext(Dispatchers.IO) { repoManager.fileExists(repoPath, dir, name) }
                if (exists) {
                    _state.value = _state.value.copy(importConflict = PendingImportConflict(uri, name))
                } else {
                    importOne(uri, name, overwrite = false)
                }
            } catch (e: Exception) {
                importFailures += e.message ?: "a selected file"
                processNextImport()
            }
        }
    }

    private fun importOne(uri: Uri, name: String, overwrite: Boolean) {
        val dir = _state.value.currentDir
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repoManager.importFile(repoPath, dir, uri, name, overwrite)
                }
                importedCount++
            } catch (e: Exception) {
                importFailures += name
            }
            processNextImport()
        }
    }

    private fun finishImportBatch() {
        val summary = buildString {
            if (importedCount > 0) append(if (importedCount == 1) "Added 1 file" else "Added $importedCount files")
            if (importFailures.isNotEmpty()) {
                if (isNotEmpty()) append(" — ")
                append("couldn't add: ${importFailures.joinToString(", ")}")
            }
        }
        openDir(_state.value.currentDir)
        if (summary.isNotBlank()) {
            _state.value = _state.value.copy(statusMessage = summary)
        }
    }

    fun consumeOpenAfterCreate() {
        _state.value = _state.value.copy(openAfterCreate = null)
    }

    fun consumeMessages() {
        _state.value = _state.value.copy(error = null, statusMessage = null)
    }
}
