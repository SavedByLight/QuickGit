package com.quickgit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.quickgit.app.viewmodel.HistoryViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    repoPath: String,
    vm: HistoryViewModel,
    onBack: () -> Unit
) {
    LaunchedEffect(repoPath) { vm.init(repoPath) }

    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var revertTarget by remember { mutableStateOf<String?>(null) }
    var revertMessage by remember { mutableStateOf("") }
    var showRevertDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.errorMessage, state.statusMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearMessages()
        }
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Commit history") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = vm::refreshHistory) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = vm::refreshHistory,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                state.loading && state.commits.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                state.commits.isEmpty() -> {
                    Text(
                        "No commits found",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.commits, key = { it.id }) { commit ->
                            Surface(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(commit.message, fontWeight = FontWeight.Medium)
                                            Text(
                                                commit.shortId,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Button(onClick = {
                                            revertTarget = commit.id
                                            revertMessage = "Revert ${commit.shortId}"
                                            showRevertDialog = true
                                        }) {
                                            Text("Revert")
                                        }
                                    }
                                    Spacer(Modifier.padding(top = 6.dp))
                                    Text(
                                        "${commit.authorName} <${commit.authorEmail}>",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        DateFormat.getDateTimeInstance().format(Date(commit.timeEpochSeconds * 1000L)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showRevertDialog && revertTarget != null) {
        AlertDialog(
            onDismissRequest = { showRevertDialog = false; revertTarget = null },
            title = { Text("Revert commit?") },
            text = {
                Column {
                    Text("This will create a new commit that undoes the changes.")
                    Spacer(Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        value = revertMessage,
                        onValueChange = { revertMessage = it },
                        label = { Text("Message") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val hash = revertTarget ?: return@TextButton
                    vm.revertCommit(hash, revertMessage)
                    showRevertDialog = false
                    revertTarget = null
                }) { Text("Revert") }
            },
            dismissButton = {
                TextButton(onClick = { showRevertDialog = false; revertTarget = null }) { Text("Cancel") }
            }
        )
    }
}
