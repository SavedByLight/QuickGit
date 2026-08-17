package com.quickgit.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var cherryTarget by remember { mutableStateOf<String?>(null) }
    var showCherryDialog by remember { mutableStateOf(false) }

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
                title = {
                    Column {
                        Text("History")
                        state.logRef?.let { ref ->
                            Text(
                                "from $ref",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::refreshHistory) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Branch / fork ref picker for viewing commits to cherry-pick
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = state.logRef == null,
                    onClick = { vm.setLogRef(null) },
                    label = { Text("Current branch") }
                )
                state.remoteBranches.take(20).forEach { ref ->
                    FilterChip(
                        selected = state.logRef == ref,
                        onClick = { vm.setLogRef(ref) },
                        label = { Text(ref) }
                    )
                }
            }
            if (state.remoteBranches.isEmpty()) {
                Text(
                    "Tip: add a fork under Branches → Add remote, Fetch it, then pick its branch here to cherry-pick commits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = vm::refreshHistory,
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    state.loading && state.commits.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    state.commits.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "No commits found",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.commits, key = { it.id }) { commit ->
                                Surface(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                commit.shortId,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Row {
                                                OutlinedButton(onClick = {
                                                    cherryTarget = commit.id
                                                    showCherryDialog = true
                                                }) {
                                                    Text("Cherry-pick")
                                                }
                                                Spacer(Modifier.width(8.dp))
                                                Button(onClick = {
                                                    revertTarget = commit.id
                                                    revertMessage = "Revert \"${commit.message}\""
                                                    showRevertDialog = true
                                                }) {
                                                    Text("Revert")
                                                }
                                            }
                                        }
                                        Spacer(Modifier.padding(top = 6.dp))
                                        Text(
                                            commit.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Spacer(Modifier.padding(top = 4.dp))
                                        Text(
                                            "${commit.authorName} <${commit.authorEmail}>",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            DateFormat.getDateTimeInstance()
                                                .format(Date(commit.timeEpochSeconds * 1000L)),
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
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
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
                TextButton(onClick = { showRevertDialog = false; revertTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCherryDialog && cherryTarget != null) {
        AlertDialog(
            onDismissRequest = { showCherryDialog = false; cherryTarget = null },
            title = { Text("Cherry-pick commit?") },
            text = {
                Text(
                    "Apply ${cherryTarget!!.take(7)} onto your current branch. " +
                        "If you are viewing a fork remote, make sure that remote was fetched first."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val hash = cherryTarget ?: return@TextButton
                    vm.cherryPick(hash)
                    showCherryDialog = false
                    cherryTarget = null
                }) { Text("Cherry-pick") }
            },
            dismissButton = {
                TextButton(onClick = { showCherryDialog = false; cherryTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
