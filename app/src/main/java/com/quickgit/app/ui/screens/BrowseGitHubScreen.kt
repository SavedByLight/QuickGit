package com.quickgit.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.models.GitHubRemoteRepo
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.ui.components.PullToRefreshBox
import com.quickgit.app.viewmodel.BrowseGitHubViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseGitHubScreen(
    vm: BrowseGitHubViewModel,
    onBack: () -> Unit,
    onCloned: () -> Unit,
    onNeedsAuth: () -> Unit
) {
    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var repoAwaitingFolder by remember { mutableStateOf<GitHubRemoteRepo?>(null) }

    val destinationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val pending = repoAwaitingFolder
            repoAwaitingFolder = null
            if (pending != null) {
                vm.cloneRepoAfterPickingFolder(pending)
            }
            vm.onDestinationPicked(uri)
        } else {
            repoAwaitingFolder = null
        }
    }

    LaunchedEffect(state.cloneResult, state.errorMessage, state.statusMessage) {
        when (val r = state.cloneResult) {
            is GitOpResult.Success -> {
                snackbarHost.showSnackbar(state.statusMessage ?: "Cloned")
                vm.consumeMessages()
                onCloned()
            }
            is GitOpResult.AuthRequired -> {
                vm.consumeMessages()
                onNeedsAuth()
            }
            is GitOpResult.Error -> {
                snackbarHost.showSnackbar(r.message)
                vm.consumeMessages()
            }
            else -> {}
        }
        state.errorMessage?.let {
            if (state.cloneResult == null) {
                snackbarHost.showSnackbar(it)
                vm.consumeMessages()
            }
        }
        state.statusMessage?.let {
            if (state.cloneResult == null) {
                snackbarHost.showSnackbar(it)
                vm.consumeMessages()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GitHub repositories")
                        state.accountLogin?.let { login ->
                            Text(
                                "@$login",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        when {
            state.authRequired && !state.connected -> {
                Box(
                    Modifier.padding(padding).fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Connect your GitHub account",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Save a personal access token with the repo scope in Settings, then come back to browse and clone your repositories.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onNeedsAuth) { Text("Open Settings") }
                    }
                }
            }
            else -> {
                Column(Modifier.padding(padding).fillMaxSize()) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = vm::setQuery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search your repos") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true
                    )

                    if (state.cloning) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(4.dp))
                            Text(state.progressText, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    PullToRefreshBox(
                        isRefreshing = state.loading,
                        onRefresh = vm::refresh,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        when {
                            state.loading && state.repos.isEmpty() -> {
                                CircularProgressIndicator(Modifier.align(Alignment.Center))
                            }
                            state.repos.isEmpty() -> {
                                Text(
                                    if (state.query.isBlank()) "No repositories found"
                                    else "No matches for \"${state.query}\"",
                                    Modifier.align(Alignment.Center).padding(32.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            else -> {
                                LazyColumn(Modifier.fillMaxSize()) {
                                    items(state.repos, key = { it.id }) { repo ->
                                        GitHubRepoRow(
                                            repo = repo,
                                            cloning = state.cloningRepoId == repo.id,
                                            enabled = !state.cloning,
                                            onClone = { vm.cloneRepo(repo) },
                                            onCloneToFolder = {
                                                repoAwaitingFolder = repo
                                                destinationPicker.launch(null)
                                            }
                                        )
                                        HorizontalDivider()
                                    }
                                    item { Spacer(Modifier.height(24.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GitHubRepoRow(
    repo: GitHubRemoteRepo,
    cloning: Boolean,
    enabled: Boolean,
    onClone: () -> Unit,
    onCloneToFolder: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (repo.isPrivate) Icons.Default.Lock else Icons.Default.Public,
            contentDescription = if (repo.isPrivate) "Private" else "Public",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable(enabled = enabled, onClick = onClone)) {
            Text(
                repo.fullName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = buildString {
                repo.description?.let { append(it) }
                if (repo.language != null) {
                    if (isNotEmpty()) append(" · ")
                    append(repo.language)
                }
                if (repo.isFork) {
                    if (isNotEmpty()) append(" · ")
                    append("fork")
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (cloning) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Box {
                IconButton(onClick = { menuExpanded = true }, enabled = enabled) {
                    Icon(Icons.Default.CloudDownload, "Clone")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Clone") },
                        onClick = {
                            menuExpanded = false
                            onClone()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Clone into folder…") },
                        onClick = {
                            menuExpanded = false
                            onCloneToFolder()
                        }
                    )
                }
            }
        }
    }
}
