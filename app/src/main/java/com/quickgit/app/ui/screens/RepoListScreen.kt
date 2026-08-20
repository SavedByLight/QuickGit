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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Key
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
import com.quickgit.app.ui.adaptive.AdaptiveContent
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
    onOpenProfile: () -> Unit = {},
    onSearchPeople: () -> Unit = {},
    onSettings: () -> Unit,
    onCredentials: () -> Unit = onSettings,
    onLogs: () -> Unit,
    onNeedsAuth: () -> Unit = onCredentials,
    // Tablet / Chromebook / desktop-window layout: the left NavigationRail already
    // exposes Profile, Search people, and Settings (matching the Linux/Mac desktop
    // app's RepoListScreen, which has no such menu of its own), so this screen's
    // own account dropdown and Settings icon would be pure duplicates.
    isDesktopLayout: Boolean = false
) {
    val repos by vm.repos.collectAsState()
    val loading by vm.loading.collectAsState()
    val account by vm.account.collectAsState()
    var repoToDelete by remember { mutableStateOf<RepoInfo?>(null) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }
    var showCreateRepoDialog by remember { mutableStateOf(false) }
    val creating by vm.creating.collectAsState()
    val statusMessage by vm.statusMessage.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val authRequired by vm.authRequired.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { vm.importFromTree(it) }
    }

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

    LaunchedEffect(statusMessage, errorMessage, authRequired) {
        statusMessage?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeMessages()
        }
        errorMessage?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeMessages()
        }
        if (authRequired) {
            vm.consumeMessages()
            onNeedsAuth()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("QuickGit") },
                actions = {
                    // Profile + Search people in one menu — hidden on tablet/Chromebook
                    // layout since the NavigationRail already provides both.
                    if (!isDesktopLayout) {
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
                    }
                    IconButton(onClick = onLogs) {
                        Icon(Icons.Default.Terminal, contentDescription = "Logs")
                    }
                    // Creds / Settings — hidden on tablet/Chromebook layout since the
                    // NavigationRail already provides them (matching desktop).
                    if (!isDesktopLayout) {
                        IconButton(onClick = onCredentials) {
                            Icon(Icons.Default.Key, contentDescription = "Credentials")
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }
            )
        },
        // Phone: bottom-right FAB menu. Tablet/Chromebook/desktop-window (isDesktopLayout):
        // match the Linux desktop app — no FAB; "New repository" lives in the list header.
        floatingActionButton = {
            if (!isDesktopLayout) {
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedVisibility(
                        visible = fabExpanded,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut()
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            FabMenuItem(
                                label = "New repository",
                                icon = Icons.Default.Create,
                                onClick = {
                                    fabExpanded = false
                                    if (vm.canCreateRemoteRepo()) {
                                        showCreateRepoDialog = true
                                    } else {
                                        onNeedsAuth()
                                    }
                                }
                            )
                            Spacer(Modifier.height(12.dp))
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
                            FabMenuItem(
                                label = "Import folder",
                                icon = Icons.Default.FolderOpen,
                                onClick = {
                                    fabExpanded = false
                                    importFolderLauncher.launch(null)
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
        }
    ) { padding ->
        AdaptiveContent(Modifier.padding(padding)) {
            Box(Modifier.fillMaxSize()) {
                // Dim overlay when FAB menu is open so taps dismiss it
                if (!isDesktopLayout && fabExpanded) {
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
                                if (isDesktopLayout)
                                    "Create a new one, clone a URL, or browse your account from the side rail."
                                else
                                    "Tap + to clone a URL, browse GitHub/GitLab, or import an existing folder.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isDesktopLayout) {
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        if (vm.canCreateRemoteRepo()) {
                                            showCreateRepoDialog = true
                                        } else {
                                            onNeedsAuth()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("New repository")
                                }
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            // Tablet/desktop layout: header with "New repository" like the Linux app
                            if (isDesktopLayout) {
                                item(key = "__header__") {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Local repositories",
                                            style = MaterialTheme.typography.titleLarge
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Button(
                                                onClick = {
                                                    if (vm.canCreateRemoteRepo()) {
                                                        showCreateRepoDialog = true
                                                    } else {
                                                        onNeedsAuth()
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("New repository")
                                            }
                                            TextButton(onClick = { importFolderLauncher.launch(null) }) {
                                                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Import")
                                            }
                                            TextButton(onClick = vm::refresh) {
                                                Text("Refresh")
                                            }
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                            items(repos, key = { it.localPath }) { repo ->
                                RepoRow(repo, onClick = { onOpenRepo(repo) }, onDelete = { repoToDelete = repo })
                                HorizontalDivider()
                            }
                            // Extra bottom space only needed when FAB is present (phone)
                            if (!isDesktopLayout) {
                                item { Spacer(Modifier.height(96.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    repoToDelete?.let { repo ->
        val external = vm.isExternalRepo(repo)
        AlertDialog(
            onDismissRequest = { repoToDelete = null },
            title = { Text("Remove ${repo.name}?") },
            text = {
                Text(
                    if (external) {
                        "This removes the repo from QuickGit's list only. Files on disk are not deleted."
                    } else {
                        "This deletes the local clone from this device. It won't affect the remote."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteRepo(repo); repoToDelete = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { repoToDelete = null }) { Text("Cancel") } }
        )
    }

    if (showCreateRepoDialog) {
        CreateRepoFromListDialog(
            creating = creating,
            availableProviders = vm.availableCreateProviders(),
            onDismiss = { if (!creating) showCreateRepoDialog = false },
            onCreate = { name, description, isPrivate, provider ->
                vm.createRepo(name, description, isPrivate, provider)
                showCreateRepoDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateRepoFromListDialog(
    creating: Boolean,
    availableProviders: List<com.quickgit.app.viewmodel.CreateRepoProvider>,
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String?, isPrivate: Boolean, provider: com.quickgit.app.viewmodel.CreateRepoProvider) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var provider by remember {
        mutableStateOf(
            availableProviders.firstOrNull()
                ?: com.quickgit.app.viewmodel.CreateRepoProvider.LOCAL
        )
    }

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text("New repository") },
        text = {
            Column {
                if (availableProviders.size > 1) {
                    Text(
                        "Where",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableProviders.forEach { p ->
                            FilterChip(
                                selected = provider == p,
                                onClick = { provider = p },
                                enabled = !creating,
                                label = {
                                    Text(
                                        when (p) {
                                            com.quickgit.app.viewmodel.CreateRepoProvider.LOCAL -> "Local"
                                            com.quickgit.app.viewmodel.CreateRepoProvider.GITHUB -> "GitHub"
                                            com.quickgit.app.viewmodel.CreateRepoProvider.GITLAB -> "GitLab"
                                        }
                                    )
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                } else if (availableProviders.size == 1) {
                    Text(
                        when (availableProviders.first()) {
                            com.quickgit.app.viewmodel.CreateRepoProvider.LOCAL -> "Local only (no remote until you push)"
                            com.quickgit.app.viewmodel.CreateRepoProvider.GITHUB -> "Creating on GitHub"
                            com.quickgit.app.viewmodel.CreateRepoProvider.GITLAB -> "Creating on GitLab"
                        },
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
                if (provider == com.quickgit.app.viewmodel.CreateRepoProvider.LOCAL) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Creates a local git repo on branch main. Add a remote later and push when you're ready — nothing is uploaded until then.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
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
                        Text(
                            if (provider == com.quickgit.app.viewmodel.CreateRepoProvider.GITLAB)
                                "Private project"
                            else
                                "Private repository"
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, description.ifBlank { null }, isPrivate, provider) },
                enabled = !creating && name.isNotBlank() && availableProviders.isNotEmpty()
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
