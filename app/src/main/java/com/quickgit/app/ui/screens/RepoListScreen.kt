package com.quickgit.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.models.RepoInfo
import com.quickgit.app.viewmodel.RepoListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoListScreen(
    vm: RepoListViewModel,
    onOpenRepo: (RepoInfo) -> Unit,
    onClone: () -> Unit,
    onSettings: () -> Unit
) {
    val repos by vm.repos.collectAsState()
    val loading by vm.loading.collectAsState()
    var repoToDelete by remember { mutableStateOf<RepoInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QuickGit") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onClone, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Clone") })
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (loading && repos.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (repos.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No repositories yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap Clone to pull down a repo from GitHub, GitLab, or any git remote.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(repos, key = { it.localPath }) { repo ->
                        RepoRow(repo, onClick = { onOpenRepo(repo) }, onDelete = { repoToDelete = repo })
                        HorizontalDivider()
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    repoToDelete?.let { repo ->
        AlertDialog(
            onDismissRequest = { repoToDelete = null },
            title = { Text("Remove ${repo.name}?") },
            text = { Text("This deletes the local clone from this device. It won't affect the remote.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteRepo(repo); repoToDelete = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { repoToDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RepoRow(repo: RepoInfo, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onClick)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(repo.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (repo.hasUncommittedChanges) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.error, shape = androidx.compose.foundation.shape.CircleShape)
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${repo.currentBranch}${repo.remoteUrl?.let { " · $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove") }
    }
}
