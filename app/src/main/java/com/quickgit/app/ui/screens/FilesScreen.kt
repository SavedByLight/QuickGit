package com.quickgit.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quickgit.app.ui.components.PullToRefreshBox
import com.quickgit.app.viewmodel.FilesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    repoPath: String,
    vm: FilesViewModel,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit
) {
    LaunchedEffect(repoPath) { vm.init(repoPath) }

    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var createMenuExpanded by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<com.quickgit.app.data.models.RepoEntry?>(null) }
    var createFolderMode by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val importFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (!uris.isNullOrEmpty()) vm.importFiles(uris) }

    LaunchedEffect(state.error, state.statusMessage, state.openAfterCreate) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessages()
        }
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessages()
        }
        state.openAfterCreate?.let {
            onOpenFile(it)
            vm.consumeOpenAfterCreate()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Files")
                        Text(
                            if (state.currentDir.isBlank()) "/" else state.currentDir,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { vm.openDir(state.currentDir) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    Box {
                        IconButton(onClick = { createMenuExpanded = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Create")
                        }
                        DropdownMenu(
                            expanded = createMenuExpanded,
                            onDismissRequest = { createMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("New file") },
                                onClick = {
                                    createMenuExpanded = false
                                    createFolderMode = false
                                    newName = ""
                                    showCreateDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("New folder") },
                                onClick = {
                                    createMenuExpanded = false
                                    createFolderMode = true
                                    newName = ""
                                    showCreateDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Add from device") },
                                leadingIcon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                                onClick = {
                                    createMenuExpanded = false
                                    importFilesLauncher.launch(arrayOf("*/*"))
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (state.entries.isNotEmpty()) {
                FloatingActionButton(onClick = {
                    createFolderMode = false
                    newName = ""
                    showCreateDialog = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = "New file")
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { vm.openDir(state.currentDir) },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                state.loading && state.entries.isEmpty() && state.currentDir.isBlank() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        // Parent folder — shown above files/folders when not at repo root
                        if (state.currentDir.isNotBlank()) {
                            item(key = "__parent__") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(onClick = vm::goUp)
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "…",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.size(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("…", fontWeight = FontWeight.Medium)
                                        Text(
                                            "Parent folder",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                        if (!state.loading && state.entries.isEmpty()) {
                            item(key = "__empty__") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No files here",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        items(state.entries, key = { it.relativePath }) { entry ->
                            val isDir = entry.isDirectory
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isDir) {
                                            vm.openDir(entry.relativePath)
                                        } else {
                                            onOpenFile(entry.relativePath)
                                        }
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                    contentDescription = null
                                )
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (isDir) entry.relativePath else "${entry.sizeBytes} bytes",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { entryToDelete = entry }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (state.loading && state.entries.isEmpty() && state.currentDir.isNotBlank()) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }


    entryToDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { entryToDelete = null },
            title = { Text(if (entry.isDirectory) "Delete folder?" else "Delete file?") },
            text = {
                Text(
                    if (entry.isDirectory)
                        "Delete “${entry.name}” and everything inside it from the working tree?"
                    else
                        "Delete “${entry.name}” from the working tree?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteEntry(entry)
                        entryToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { entryToDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(if (createFolderMode) "New folder" else "New file") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(if (createFolderMode) "Folder name" else "File name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newName.trim()
                        if (createFolderMode) vm.createFolder(name) else vm.createFile(name)
                        showCreateDialog = false
                    },
                    enabled = newName.isNotBlank()
                ) {
                    Text(if (createFolderMode) "Create folder" else "Create file")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
            }
        )
    }

    state.importConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = { vm.cancelImportConflict() },
            title = { Text("Replace existing file?") },
            text = {
                Text("\"${conflict.fileName}\" already exists in this folder. Overwrite it with the file you're adding?")
            },
            confirmButton = {
                Button(onClick = { vm.confirmOverwrite() }) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelImportConflict() }) { Text("Skip") }
            }
        )
    }
}
