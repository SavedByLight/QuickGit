package com.quickgit.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quickgit.app.ui.components.PullToRefreshBox
import com.quickgit.app.viewmodel.RemoteBrowseViewModel

/**
 * Read-only file browser for someone else's (or your own) repo, backed directly by the
 * GitHub Contents API — nothing gets cloned. Directories drill down in place; files hand off
 * to [RemoteFileScreen] for viewing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteBrowseScreen(
    owner: String,
    repo: String,
    ref: String,
    path: String,
    vm: RemoteBrowseViewModel,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onNeedsAuth: () -> Unit
) {
    LaunchedEffect(owner, repo, ref, path) { vm.init(owner, repo, ref, path) }

    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.authRequired) {
        if (state.authRequired) {
            // Consume first so Back from Credentials does not re-open Settings in a loop.
            vm.consumeAuthRequired()
            onNeedsAuth()
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(repo)
                        Text(
                            if (state.currentPath.isBlank()) "$owner / $ref" else state.currentPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (state.currentPath.isNotBlank()) {
                        IconButton(onClick = vm::goUp) { Icon(Icons.Default.FolderOpen, contentDescription = "Up") }
                    }
                    IconButton(onClick = { vm.openDir(state.currentPath) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { vm.openDir(state.currentPath) },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                state.loading && state.entries.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                state.entries.isEmpty() -> {
                    Text(
                        "This folder is empty",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.entries, key = { it.path }) { entry ->
                            val isDir = entry.type == "dir"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isDir) vm.openDir(entry.path) else onOpenFile(entry.path)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                    contentDescription = null
                                )
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name, fontWeight = FontWeight.Medium)
                                    if (!isDir) {
                                        Text(
                                            "${entry.sizeBytes} bytes",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
