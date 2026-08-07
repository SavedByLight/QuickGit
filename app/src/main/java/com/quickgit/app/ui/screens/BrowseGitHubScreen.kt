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
import com.quickgit.app.data.models.GerritProject
import com.quickgit.app.data.models.GitHubRemoteRepo
import com.quickgit.app.data.models.GitLabProject
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
    var pendingFolderAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val destinationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val action = pendingFolderAction
        pendingFolderAction = null
        if (uri != null) {
            action?.invoke()
            vm.onDestinationPicked(uri)
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

    if (showCreateDialog) {
        CreateRepoDialog(
            creating = state.creating,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, description, isPrivate ->
                vm.createRepo(name, description, isPrivate)
                showCreateDialog = false
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Browse repos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.githubConnected) {
                        TextButton(onClick = { showCreateDialog = true }) {
                            Text("New")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.authRequired) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Connect GitHub, GitLab, or Gerrit in Settings to browse remote repos.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onNeedsAuth) { Text("Open Settings") }
                }
            }
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = vm::onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search repos…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true
                    )
                }

                if (state.cloning && state.progressText.isNotBlank()) {
                    item {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        Text(
                            state.progressText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }

                // ---- GitHub ----
                item {
                    ProviderSectionHeader(
                        title = "GitHub",
                        subtitle = when {
                            !state.githubConnected -> "Not connected"
                            state.githubLogin != null -> "@${state.githubLogin}"
                            else -> "Connected"
                        },
                        connected = state.githubConnected,
                        count = state.githubRepos.size
                    )
                }
                if (state.githubConnected) {
                    if (state.githubRepos.isEmpty() && !state.loading) {
                        item { EmptySectionHint("No GitHub repositories match.") }
                    }
                    items(state.githubRepos, key = { "gh-${it.id}" }) { repo ->
                        GitHubRepoRow(
                            repo = repo,
                            cloning = state.cloningKey == "gh:${repo.id}",
                            enabled = !state.cloning,
                            onClone = { vm.cloneGitHubRepo(repo) },
                            onCloneToFolder = {
                                pendingFolderAction = { vm.cloneGitHubRepoAfterPickingFolder(repo) }
                                destinationPicker.launch(null)
                            }
                        )
                        HorizontalDivider(Modifier.padding(start = 50.dp))
                    }
                }

                // ---- GitLab ----
                item {
                    ProviderSectionHeader(
                        title = "GitLab",
                        subtitle = when {
                            !state.gitlabConnected -> "Not connected"
                            state.gitlabUsername != null -> "@${state.gitlabUsername}"
                            else -> "Connected"
                        },
                        connected = state.gitlabConnected,
                        count = state.gitlabProjects.size
                    )
                }
                if (state.gitlabConnected) {
                    if (state.gitlabProjects.isEmpty() && !state.loading) {
                        item { EmptySectionHint("No GitLab projects match.") }
                    }
                    items(state.gitlabProjects, key = { "gl-${it.id}" }) { project ->
                        GitLabProjectRow(
                            project = project,
                            cloning = state.cloningKey == "gl:${project.id}",
                            enabled = !state.cloning,
                            onClone = { vm.cloneGitLabProject(project) },
                            onCloneToFolder = {
                                pendingFolderAction = { vm.cloneGitLabProjectAfterPickingFolder(project) }
                                destinationPicker.launch(null)
                            }
                        )
                        HorizontalDivider(Modifier.padding(start = 50.dp))
                    }
                }

                // ---- Gerrit ----
                item {
                    ProviderSectionHeader(
                        title = "Gerrit",
                        subtitle = when {
                            !state.gerritConnected -> "Not connected"
                            state.gerritUsername != null && state.gerritHost != null ->
                                "${state.gerritUsername} @ ${state.gerritHost}"
                            state.gerritHost != null -> state.gerritHost!!
                            else -> "Connected"
                        },
                        connected = state.gerritConnected,
                        count = state.gerritProjects.size
                    )
                }
                if (state.gerritConnected) {
                    if (state.gerritProjects.isEmpty() && !state.loading) {
                        item { EmptySectionHint("No Gerrit projects match.") }
                    }
                    items(state.gerritProjects, key = { "ge-${it.id}" }) { project ->
                        GerritProjectRow(
                            project = project,
                            cloning = state.cloningKey == "ge:${project.id}",
                            enabled = !state.cloning,
                            onClone = { vm.cloneGerritProject(project) },
                            onCloneToFolder = {
                                pendingFolderAction = { vm.cloneGerritProjectAfterPickingFolder(project) }
                                destinationPicker.launch(null)
                            }
                        )
                        HorizontalDivider(Modifier.padding(start = 50.dp))
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ProviderSectionHeader(
    title: String,
    subtitle: String,
    connected: Boolean,
    count: Int
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (connected) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = if (connected) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun EmptySectionHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun GitHubRepoRow(
    repo: GitHubRemoteRepo,
    cloning: Boolean,
    enabled: Boolean,
    onClone: () -> Unit,
    onCloneToFolder: () -> Unit
) {
    RemoteRepoRow(
        title = repo.fullName,
        subtitle = buildString {
            repo.description?.let { append(it) }
            if (repo.language != null) {
                if (isNotEmpty()) append(" · ")
                append(repo.language)
            }
            if (repo.isFork) {
                if (isNotEmpty()) append(" · ")
                append("fork")
            }
        }.ifBlank { null },
        isPrivate = repo.isPrivate,
        cloning = cloning,
        enabled = enabled,
        onClone = onClone,
        onCloneToFolder = onCloneToFolder
    )
}

@Composable
private fun GitLabProjectRow(
    project: GitLabProject,
    cloning: Boolean,
    enabled: Boolean,
    onClone: () -> Unit,
    onCloneToFolder: () -> Unit
) {
    RemoteRepoRow(
        title = project.pathWithNamespace,
        subtitle = project.description,
        isPrivate = project.isPrivate,
        cloning = cloning,
        enabled = enabled,
        onClone = onClone,
        onCloneToFolder = onCloneToFolder
    )
}

@Composable
private fun GerritProjectRow(
    project: GerritProject,
    cloning: Boolean,
    enabled: Boolean,
    onClone: () -> Unit,
    onCloneToFolder: () -> Unit
) {
    RemoteRepoRow(
        title = project.name,
        subtitle = buildString {
            project.description?.let { append(it) }
            if (project.state != "ACTIVE") {
                if (isNotEmpty()) append(" · ")
                append(project.state)
            }
        }.ifBlank { null },
        isPrivate = false,
        cloning = cloning,
        enabled = enabled,
        onClone = onClone,
        onCloneToFolder = onCloneToFolder
    )
}

@Composable
private fun RemoteRepoRow(
    title: String,
    subtitle: String?,
    isPrivate: Boolean,
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
            if (isPrivate) Icons.Default.Lock else Icons.Default.Public,
            contentDescription = if (isPrivate) "Private" else "Public",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable(enabled = enabled, onClick = onClone)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
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

@Composable
private fun CreateRepoDialog(
    creating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String?, isPrivate: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text("New GitHub repository") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !creating) { isPrivate = !isPrivate },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isPrivate, onCheckedChange = { isPrivate = it }, enabled = !creating)
                    Text("Private repository")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, description.ifBlank { null }, isPrivate) },
                enabled = !creating && name.isNotBlank()
            ) {
                if (creating) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) { Text("Cancel") }
        }
    )
}
