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
import com.quickgit.app.viewmodel.BrowseProviderTab

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
            defaultGitLab = state.selectedTab == BrowseProviderTab.GITLAB,
            githubConnected = state.githubConnected,
            gitlabConnected = state.gitlabConnected,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, description, isPrivate, forGitLab ->
                vm.createRepo(name, description, isPrivate, forGitLab)
                showCreateDialog = false
            }
        )
    }

    val tabs = listOf(
        BrowseProviderTab.GITHUB to "GitHub",
        BrowseProviderTab.GITLAB to "GitLab",
        BrowseProviderTab.GERRIT to "Gerrit"
    )
    val selectedIndex = tabs.indexOfFirst { it.first == state.selectedTab }.coerceAtLeast(0)

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
                    val canCreate =
                        (state.selectedTab == BrowseProviderTab.GITHUB && state.githubConnected) ||
                            (state.selectedTab == BrowseProviderTab.GITLAB && state.gitlabConnected)
                    if (canCreate) {
                        TextButton(onClick = { showCreateDialog = true }) { Text("New") }
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
                        "Connect GitHub, GitLab, or Gerrit in Settings / Credentials to browse remote repos.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onNeedsAuth) { Text("Open Settings") }
                }
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedIndex) {
                tabs.forEachIndexed { index, (tab, label) ->
                    val connected = when (tab) {
                        BrowseProviderTab.GITHUB -> state.githubConnected
                        BrowseProviderTab.GITLAB -> state.gitlabConnected
                        BrowseProviderTab.GERRIT -> state.gerritConnected
                    }
                    Tab(
                        selected = selectedIndex == index,
                        onClick = { vm.selectTab(tab) },
                        text = {
                            Text(
                                if (connected) label else "$label · off",
                                maxLines = 1
                            )
                        },
                        enabled = connected || tab == state.selectedTab
                    )
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = {
                    Text(
                        when (state.selectedTab) {
                            BrowseProviderTab.GITHUB -> "Search GitHub repos…"
                            BrowseProviderTab.GITLAB -> "Search GitLab projects…"
                            BrowseProviderTab.GERRIT -> "Search Gerrit projects…"
                        }
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true
            )

            val accountLine = when (state.selectedTab) {
                BrowseProviderTab.GITHUB -> state.githubLogin?.let { "@$it" }
                BrowseProviderTab.GITLAB -> state.gitlabUsername?.let { "@$it" }
                BrowseProviderTab.GERRIT -> {
                    val host = state.gerritHost
                    val user = state.gerritUsername
                    when {
                        host != null && user != null -> "$user@$host"
                        host != null -> host
                        else -> null
                    }
                }
            }
            if (accountLine != null) {
                Text(
                    accountLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            if (state.cloning && state.progressText.isNotBlank()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Text(
                    state.progressText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                when (state.selectedTab) {
                    BrowseProviderTab.GITHUB -> ProviderList(
                        empty = state.githubRepos.isEmpty() && state.githubLoaded && !state.loading,
                        emptyText = if (!state.githubConnected) "Connect GitHub in Settings."
                        else "No GitHub repositories match.",
                        loadingMore = state.loadingMore,
                        hasMore = state.githubHasMore,
                        onLoadMore = vm::loadMore
                    ) {
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
                    BrowseProviderTab.GITLAB -> ProviderList(
                        empty = state.gitlabProjects.isEmpty() && state.gitlabLoaded && !state.loading,
                        emptyText = if (!state.gitlabConnected) "Connect GitLab in Settings."
                        else "No GitLab projects match.",
                        loadingMore = state.loadingMore,
                        hasMore = state.gitlabHasMore,
                        onLoadMore = vm::loadMore
                    ) {
                        items(state.gitlabProjects, key = { "gl-${it.id}" }) { project ->
                            RemoteRepoRow(
                                title = project.pathWithNamespace,
                                subtitle = project.description,
                                isPrivate = project.isPrivate,
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
                    BrowseProviderTab.GERRIT -> ProviderList(
                        empty = state.gerritProjects.isEmpty() && state.gerritLoaded && !state.loading,
                        emptyText = if (!state.gerritConnected) "Connect Gerrit under Credentials."
                        else "No Gerrit projects match.",
                        loadingMore = state.loadingMore,
                        hasMore = state.gerritHasMore,
                        onLoadMore = { vm.loadMore() }
                    ) {
                        items(state.gerritProjects, key = { "gr-${it.id}" }) { project ->
                            RemoteRepoRow(
                                title = project.name,
                                subtitle = buildString {
                                    project.description?.let { append(it) }
                                    project.state?.let {
                                        if (isNotEmpty()) append(" · ")
                                        append(it)
                                    }
                                }.ifBlank { null },
                                isPrivate = true,
                                cloning = state.cloningKey == "gr:${project.id}",
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
                }
            }
        }
    }
}

@Composable
private fun ProviderList(
    empty: Boolean,
    emptyText: String,
    loadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (empty) {
            item {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
        content()
        if (hasMore) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (loadingMore) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    } else {
                        OutlinedButton(onClick = onLoadMore) { Text("Load more") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateRepoDialog(
    creating: Boolean,
    defaultGitLab: Boolean,
    githubConnected: Boolean,
    gitlabConnected: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String?, isPrivate: Boolean, onGitLab: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var onGitLab by remember {
        mutableStateOf(
            when {
                defaultGitLab && gitlabConnected -> true
                githubConnected -> false
                gitlabConnected -> true
                else -> false
            }
        )
    }
    val both = githubConnected && gitlabConnected

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text("New repository") },
        text = {
            Column {
                if (both) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !onGitLab,
                            onClick = { onGitLab = false },
                            enabled = !creating,
                            label = { Text("GitHub") }
                        )
                        FilterChip(
                            selected = onGitLab,
                            onClick = { onGitLab = true },
                            enabled = !creating,
                            label = { Text("GitLab") }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                } else {
                    Text(
                        if (onGitLab) "Creating on GitLab" else "Creating on GitHub",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
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
                    Text(if (onGitLab) "Private project" else "Private repository")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, description.ifBlank { null }, isPrivate, onGitLab) },
                enabled = !creating && name.isNotBlank() && (githubConnected || gitlabConnected)
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
