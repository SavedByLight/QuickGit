package com.quickgit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.models.RepoEntry
import com.quickgit.app.ui.theme.GitBlue
import com.quickgit.app.viewmodel.FilesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    repoPath: String,
    vm: FilesViewModel,
    onBack: () -> Unit,
    onOpenFile: (relativePath: String) -> Unit
) {
    LaunchedEffect(repoPath) { vm.init(repoPath) }
    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var showNewMenu by remember { mutableStateOf(false) }
    var createMode by remember { mutableStateOf<CreateMode?>(null) }

    LaunchedEffect(state.openAfterCreate) {
        state.openAfterCreate?.let { path ->
            vm.consumeOpenAfterCreate()
            onOpenFile(path)
        }
    }
    LaunchedEffect(state.statusMessage, state.error) {
        state.statusMessage?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeMessages()
        }
        state.error?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Files", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.currentDir.isBlank()) "/" else "/${state.currentDir}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.currentDir.isNotBlank()) vm.goUp() else onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showNewMenu = true }) {
                            Icon(Icons.Default.Add, "New")
                        }
                        DropdownMenu(
                            expanded = showNewMenu,
                            onDismissRequest = { showNewMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("New file") },
                                onClick = {
                                    showNewMenu = false
                                    createMode = CreateMode.FILE
                                },
                                leadingIcon = { Icon(Icons.Default.NoteAdd, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("New folder") },
                                onClick = {
                                    showNewMenu = false
                                    createMode = CreateMode.FOLDER
                                },
                                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.entries.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                state.entries.isEmpty() -> {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { createMode = CreateMode.FILE }) {
                            Icon(Icons.Default.NoteAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("New file")
                        }
                    }
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.entries, key = { it.relativePath }) { entry ->
                            FileRow(
                                entry = entry,
                                onClick = {
                                    if (entry.isDirectory) vm.openDir(entry.relativePath)
                                    else onOpenFile(entry.relativePath)
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    createMode?.let { mode ->
        CreateEntryDialog(
            mode = mode,
            currentDir = state.currentDir,
            onDismiss = { createMode = null },
            onConfirm = { name ->
                when (mode) {
                    CreateMode.FILE -> vm.createFile(name)
                    CreateMode.FOLDER -> vm.createFolder(name)
                }
                createMode = null
            }
        )
    }
}

private enum class CreateMode { FILE, FOLDER }

@Composable
private fun CreateEntryDialog(
    mode: CreateMode,
    currentDir: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val title = if (mode == CreateMode.FILE) "New file" else "New folder"
    val label = if (mode == CreateMode.FILE) "File name" else "Folder name"
    val placeholder = if (mode == CreateMode.FILE) "README.md" else "src"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    if (currentDir.isBlank()) "Creating in /"
                    else "Creating in /$currentDir",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(label) },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank() && !name.contains("..") && !name.contains('/')
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun FileRow(entry: RepoEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                entry.isDirectory -> Icons.Default.Folder
                entry.name.endsWith(".md", true) ||
                    entry.name.endsWith(".kt", true) ||
                    entry.name.endsWith(".java", true) ||
                    entry.name.endsWith(".xml", true) ||
                    entry.name.endsWith(".json", true) -> Icons.Default.Description
                else -> Icons.Default.InsertDriveFile
            },
            contentDescription = null,
            tint = if (entry.isDirectory) GitBlue else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!entry.isDirectory && entry.sizeBytes > 0) {
                Text(
                    formatSize(entry.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}
