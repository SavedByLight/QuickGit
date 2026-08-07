package com.quickgit.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.quickgit.app.data.models.RepoInfo
import com.quickgit.app.ui.components.PullToRefreshBox
import com.quickgit.app.ui.components.UserAvatar
import com.quickgit.app.viewmodel.RepoListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoListScreen(
    vm: RepoListViewModel,
    onOpenRepo: (RepoInfo) -> Unit,
    onClone: () -> Unit,
    onBrowseGitHub: () -> Unit = {},
    onGerritChanges: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onSearchPeople: () -> Unit = {},
    onSettings: () -> Unit,
    onLogs: () -> Unit
) {
    val repos by vm.repos.collectAsState()
    val loading by vm.loading.collectAsState()
    val account by vm.account.collectAsState()
    var repoToDelete by remember { mutableStateOf<RepoInfo?>(null) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }

    // Re-scan after clone/settings: ViewModel stays alive on the back stack, so init{}
    // only runs once. Refresh whenever this screen is shown again.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QuickGit") },
                actions = {
                    // Profile + Search people in one menu
                    Box {
                        IconButton(onClick = { accountMenuExpanded = true }) {
                            val avatarUrl = account?.avatarUrl
                            if (avatarUrl.isNullOrBlank()) {
                                Icon(Icons.Default.Person, contentDescription = "Account")
                            } else {
                                UserAvatar(
                                    avatarUrl = avatarUrl,
                                    login = account?.login ?: "?",
                                    size = 28.dp
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = accountMenuExpanded,
                            onDismissRequest = { accountMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (account?.login.isNullOrBlank()) "Profile"
                                        else "Profile (${account?.login})"
                                    )
                                },
                                onClick = {
                                    accountMenuExpanded = false
                                    onOpenProfile()
                                },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Search people") },
                                onClick = {
                                    accountMenuExpanded = false
                                    onSearchPeople()
                                },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                            )
                        }
                    }
                    IconButton(onClick = onGerritChanges) {
                        Icon(Icons.Default.RateReview, contentDescription = "Gerrit changes")
                    }
                    IconButton(onClick = onLogs) {
                        Icon(Icons.Default.Terminal, contentDescription = "Logs")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                AnimatedVisibility(
                    visible = fabExpanded,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        FabMenuItem(
                            label = "Browse repos",
                            icon = Icons.Default.CloudDownload,
                            onClick = {
                                fabExpanded = false
                                onBrowseGitHub()
                            }
                        )
                        Spacer(Modifier.height(12.dp))
                        FabMenuItem(
                            label = "Clone URL",
                            icon = Icons.Default.Add,
                            onClick = {
                                fabExpanded = false
                                onClone()
                            }
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
                FloatingActionButton(
                    onClick = { fabExpanded = !fabExpanded }
                ) {
                    Icon(
                        if (fabExpanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = if (fabExpanded) "Close" else "Add"
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            // Dim overlay when FAB menu is open so taps dismiss it
            if (fabExpanded) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable { fabExpanded = false }
                )
            }

            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize()
            ) {
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
                            "Tap + to clone a URL or browse GitHub, GitLab, or Gerrit.",
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
                        item { Spacer(Modifier.height(96.dp)) }
                    }
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
private fun FabMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
        SmallFloatingActionButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
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
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
    }
}
