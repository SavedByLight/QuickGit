package com.quickgit.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.default.Add
import androidx.compose.material.icons.default.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.quickgit.app.viewmodel.FilesViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel,
    repoDir: File,
    currentPath: String
) {
    val context = LocalContext.current
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showRenameDialog by remember { mutableStateOf(false) }
    var fileToRename by remember { mutableStateOf("") }
    var newFileName by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            val fileName = it.lastPathSegment?.substringAfterLast('/') ?: "uploaded_file"
            if (inputStream != null) {
                viewModel.uploadFile(repoDir, currentPath, fileName, inputStream) { result ->
                    if (result.isSuccess) {
                        viewModel.refreshFiles(repoDir, currentPath)
                    }
                }
            }
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files Explorer") },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshFiles(repoDir, currentPath) },
                        enabled = !isRefreshing
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Files")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { filePickerLauncher.launch("*/*") }) {
                Icon(Icons.Default.Add, contentDescription = "Upload File")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Tap the plus button to upload files or click existing entries to rename them.")
            }
        }

        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename File") },
                text = {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("New file name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newFileName.isNotBlank()) {
                            val oldPath = if (currentPath.isEmpty()) fileToRename else "$currentPath/$fileToRename"
                            val newPath = if (currentPath.isEmpty()) newFileName else "$currentPath/$newFileName"
                            viewModel.renameFile(repoDir, oldPath, newPath) { result ->
                                if (result.isSuccess) {
                                    showRenameDialog = false
                                    newFileName = ""
                                    viewModel.refreshFiles(repoDir, currentPath)
                                }
                            }
                        }
                    }) {
                        Text("Rename")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        showRenameDialog = false 
                        newFileName = ""
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
