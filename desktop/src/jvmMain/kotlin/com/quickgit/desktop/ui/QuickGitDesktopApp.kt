package com.quickgit.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import com.quickgit.desktop.data.*
import com.quickgit.desktop.data.github.GitHubApi
import com.quickgit.desktop.data.gitlab.GitLabApi
import com.quickgit.desktop.data.models.WorkflowInput
import com.quickgit.desktop.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

enum class DesktopScreen {
    RepoList, RepoDetail, Clone, BrowseAccount, Profile, UserSearch, Logs, Settings, Credentials
}

@OptIn(ExperimentalMaterial3Api::class)
private fun formatBytes(n: Long): String {
    if (n < 1024) return "$n B"
    if (n < 1024 * 1024) return "${"%.1f".format(n / 1024.0)} KB"
    if (n < 1024L * 1024 * 1024) return "${"%.1f".format(n / (1024.0 * 1024))} MB"
    return "${"%.2f".format(n / (1024.0 * 1024 * 1024))} GB"
}

private fun statusColor(status: String, conclusion: String?): Color {
    return when {
        conclusion == "success" -> Color(0xFF3FB950)
        conclusion == "failure" || conclusion == "timed_out" -> Color(0xFFF85149)
        conclusion == "cancelled" -> Color(0xFF8B949E)
        status == "in_progress" || status == "queued" -> Color(0xFFD29922)
        else -> Color(0xFF8B949E)
    }
}

@Composable
fun QuickGitDesktopApp(

    repoManager: DesktopRepoManager,
    credentialStore: DesktopCredentialStore,
    updateManager: DesktopAppUpdateManager = DesktopAppUpdateManager(credentialStore)
) {
    var currentScreen by remember { mutableStateOf(DesktopScreen.RepoList) }
    var selectedRepoPath by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun showMessage(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    fun githubApi(): GitHubApi {
        val token = credentialStore.getGithubToken()
            ?: credentialStore.getHttpsToken("github.com")
        return GitHubApi(token)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            // Navigation rail
            NavigationRail(modifier = Modifier.fillMaxHeight()) {
                NavRailItem(Icons.Default.Folder, "Repos", currentScreen == DesktopScreen.RepoList) {
                    currentScreen = DesktopScreen.RepoList
                }
                NavRailItem(Icons.Default.CloudDownload, "Clone", currentScreen == DesktopScreen.Clone) {
                    currentScreen = DesktopScreen.Clone
                }
                NavRailItem(Icons.Default.TravelExplore, "Browse", currentScreen == DesktopScreen.BrowseAccount) {
                    currentScreen = DesktopScreen.BrowseAccount
                }
                NavRailItem(Icons.Default.Person, "Profile", currentScreen == DesktopScreen.Profile) {
                    currentScreen = DesktopScreen.Profile
                }
                NavRailItem(Icons.Default.Search, "Users", currentScreen == DesktopScreen.UserSearch) {
                    currentScreen = DesktopScreen.UserSearch
                }
                Spacer(Modifier.weight(1f))
                NavRailItem(Icons.Default.Article, "Logs", currentScreen == DesktopScreen.Logs) {
                    currentScreen = DesktopScreen.Logs
                }
                NavRailItem(Icons.Default.Key, "Creds", currentScreen == DesktopScreen.Credentials) {
                    currentScreen = DesktopScreen.Credentials
                }
                NavRailItem(Icons.Default.Settings, "Settings", currentScreen == DesktopScreen.Settings) {
                    currentScreen = DesktopScreen.Settings
                }
            }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (currentScreen) {
                    DesktopScreen.RepoList -> RepoListScreen(
                        repoManager = repoManager,
                        credentialStore = credentialStore,
                        githubApi = githubApi(),
                        onSelect = {
                            selectedRepoPath = it
                            currentScreen = DesktopScreen.RepoDetail
                        },
                        onMessage = ::showMessage
                    )
                    DesktopScreen.RepoDetail -> selectedRepoPath?.let { path ->
                        RepoDetailScreen(
                            repoPath = path,
                            repoManager = repoManager,
                            credentialStore = credentialStore,
                            githubApi = githubApi(),
                            onBack = { currentScreen = DesktopScreen.RepoList },
                            onMessage = ::showMessage
                        )
                    } ?: run { currentScreen = DesktopScreen.RepoList }
                    DesktopScreen.Clone -> CloneScreen(
                        repoManager = repoManager,
                        credentialStore = credentialStore,
                        githubApi = githubApi(),
                        onCloned = {
                            selectedRepoPath = it
                            currentScreen = DesktopScreen.RepoDetail
                            showMessage("Cloned successfully")
                        },
                        onMessage = ::showMessage
                    )
                    DesktopScreen.BrowseAccount -> BrowseAccountScreen(
                        credentialStore = credentialStore,
                        githubApi = githubApi(),
                        repoManager = repoManager,
                        onCloned = {
                            selectedRepoPath = it
                            currentScreen = DesktopScreen.RepoDetail
                            showMessage("Cloned successfully")
                        },
                        onMessage = ::showMessage
                    )
                    DesktopScreen.Profile -> ProfileScreen(
                        credentialStore = credentialStore,
                        githubApi = githubApi(),
                        repoManager = repoManager,
                        onCloned = {
                            selectedRepoPath = it
                            currentScreen = DesktopScreen.RepoDetail
                            showMessage("Cloned successfully")
                        },
                        onMessage = ::showMessage
                    )
                    DesktopScreen.UserSearch -> UserSearchScreen(
                        githubApi = githubApi(),
                        repoManager = repoManager,
                        onCloned = {
                            selectedRepoPath = it
                            currentScreen = DesktopScreen.RepoDetail
                            showMessage("Cloned successfully")
                        },
                        onMessage = ::showMessage
                    )
                    DesktopScreen.Logs -> LogsScreen(onMessage = ::showMessage)
                    DesktopScreen.Settings -> SettingsScreen(
                        repoManager = repoManager,
                        credentialStore = credentialStore,
                        updateManager = updateManager,
                        onMessage = ::showMessage
                    )
                    DesktopScreen.Credentials -> CredentialsScreen(
                        repoManager = repoManager,
                        credentialStore = credentialStore,
                        onMessage = ::showMessage
                    )
                }
            }
        }
    }
}

@Composable
private fun NavRailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) }
    )
}

@Composable
fun RepoListScreen(
    repoManager: DesktopRepoManager,
    credentialStore: DesktopCredentialStore,
    githubApi: GitHubApi,
    onSelect: (String) -> Unit,
    onMessage: (String) -> Unit
) {
    var repos by remember { mutableStateOf(emptyList<DesktopRepoManager.LocalRepo>()) }
    var loading by remember { mutableStateOf(true) }
    var showCreate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var repoToDelete by remember { mutableStateOf<DesktopRepoManager.LocalRepo?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            loading = true
            repos = withContext(Dispatchers.IO) { repoManager.listLocalRepos() }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Local repositories", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { showCreate = true }, enabled = !busy) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New repository")
                }
                TextButton(onClick = { reload() }) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Refresh")
                }
            }
        }
        Text(
            "Root: ${repoManager.getReposRoot().absolutePath}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (repos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No local repos yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Create a new one, clone a URL, or browse your GitHub account",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showCreate = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New repository")
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(repos, key = { it.path }) { repo ->
                    Card(
                        Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Row(
                            Modifier
                                .clickable { onSelect(repo.path) }
                                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    repo.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    repo.branch ?: "(no branch)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                repo.remoteUrl?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (repo.isDirty) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .background(Color(0xFFFF9800), shape = MaterialTheme.shapes.small)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            IconButton(
                                onClick = { repoToDelete = repo },
                                enabled = !busy
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete repository",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    repoToDelete?.let { repo ->
        val external = remember(repo.path) {
            repoManager.isExternalRepo(java.io.File(repo.path))
        }
        AlertDialog(
            onDismissRequest = { if (!busy) repoToDelete = null },
            title = { Text(if (external) "Remove from list?" else "Delete local repository?") },
            text = {
                Column {
                    Text(
                        if (external) {
                            "This removes '${repo.name}' from QuickGit's list only. Files on disk are not deleted."
                        } else {
                            "This permanently deletes '${repo.name}' from disk under the repos root. This cannot be undone."
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        repo.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            val result = withContext(Dispatchers.IO) {
                                repoManager.removeFromList(java.io.File(repo.path))
                            }
                            busy = false
                            repoToDelete = null
                            result.fold(
                                onSuccess = {
                                    onMessage(
                                        if (external) "Removed '${repo.name}' from the list (files left on disk)"
                                        else "Deleted '${repo.name}'"
                                    )
                                    reload()
                                },
                                onFailure = {
                                    onMessage("Could not remove '${repo.name}': ${it.message}")
                                }
                            )
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(if (external) "Remove" else "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { repoToDelete = null }, enabled = !busy) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var branch by remember { mutableStateOf("main") }
        var onGitHub by remember { mutableStateOf(false) }
        var isPrivate by remember { mutableStateOf(true) }
        var alsoClone by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { if (!busy) showCreate = false },
            title = { Text("New repository") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.filter { c -> c != '/' && c != '\\' } },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Folder under ${repoManager.getReposRoot().name}/") }
                    )
                    OutlinedTextField(
                        value = branch,
                        onValueChange = { branch = it },
                        label = { Text("Initial branch") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = onGitHub, onCheckedChange = { onGitHub = it })
                        Column {
                            Text("Also create on GitHub")
                            Text(
                                "Requires a GitHub token in Credentials",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (onGitHub) {
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Description (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isPrivate, onCheckedChange = { isPrivate = it })
                            Text("Private repository")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = alsoClone, onCheckedChange = { alsoClone = it })
                            Text("Clone locally after create")
                        }
                    } else {
                        Text(
                            "Creates an empty local git repo (no remote). Add a remote later from Branches.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val n = name.trim()
                        if (n.isBlank()) return@TextButton
                        scope.launch {
                            busy = true
                            try {
                                if (onGitHub) {
                                    val token = withContext(Dispatchers.IO) {
                                        credentialStore.getGithubToken()
                                            ?: credentialStore.getHttpsToken("github.com")
                                    }
                                    if (token.isNullOrBlank()) {
                                        onMessage("Add a GitHub token in Credentials first")
                                        return@launch
                                    }
                                    val created = withContext(Dispatchers.IO) {
                                        githubApi.createRepo(
                                            n,
                                            description.trim().ifBlank { null },
                                            isPrivate
                                        )
                                    }
                                    created.fold(
                                        onSuccess = { remote ->
                                            if (alsoClone) {
                                                val cloneUrl = remote.cloneUrl.ifBlank {
                                                    "https://github.com/${remote.fullName}.git"
                                                }
                                                val cloneResult = withContext(Dispatchers.IO) {
                                                    repoManager.cloneRepo(cloneUrl, n) { }
                                                }
                                                cloneResult.fold(
                                                    onSuccess = { dir ->
                                                        showCreate = false
                                                        onMessage("Created GitHub repo ${remote.fullName} and cloned")
                                                        onSelect(dir.absolutePath)
                                                    },
                                                    onFailure = {
                                                        onMessage(
                                                            "GitHub repo created (${remote.fullName}) but clone failed: ${it.message}"
                                                        )
                                                        showCreate = false
                                                        reload()
                                                    }
                                                )
                                            } else {
                                                // Local empty repo pointing at the new remote is still useful
                                                val local = withContext(Dispatchers.IO) {
                                                    repoManager.initLocalRepo(n, branch.trim().ifBlank { "main" })
                                                }
                                                local.fold(
                                                    onSuccess = { dir ->
                                                        // Set origin if possible
                                                        withContext(Dispatchers.IO) {
                                                            try {
                                                                val url = remote.cloneUrl.ifBlank {
                                                                    "https://github.com/${remote.fullName}.git"
                                                                }
                                                                org.eclipse.jgit.api.Git.open(dir).use { git ->
                                                                    git.remoteAdd()
                                                                        .setName("origin")
                                                                        .setUri(org.eclipse.jgit.transport.URIish(url))
                                                                        .call()
                                                                }
                                                            } catch (_: Exception) { }
                                                        }
                                                        showCreate = false
                                                        onMessage("Created ${remote.fullName} on GitHub + local folder")
                                                        onSelect(dir.absolutePath)
                                                    },
                                                    onFailure = {
                                                        onMessage(
                                                            "GitHub repo created (${remote.fullName}); local init failed: ${it.message}"
                                                        )
                                                        showCreate = false
                                                        reload()
                                                    }
                                                )
                                            }
                                        },
                                        onFailure = {
                                            onMessage("GitHub create failed: ${it.message}")
                                        }
                                    )
                                } else {
                                    val local = withContext(Dispatchers.IO) {
                                        repoManager.initLocalRepo(n, branch.trim().ifBlank { "main" })
                                    }
                                    local.fold(
                                        onSuccess = { dir ->
                                            showCreate = false
                                            onMessage("Created local repository ${dir.name}")
                                            onSelect(dir.absolutePath)
                                        },
                                        onFailure = {
                                            onMessage("Create failed: ${it.message}")
                                        }
                                    )
                                }
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = name.isNotBlank() && !busy
                ) { Text(if (busy) "Creating…" else "Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }, enabled = !busy) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(
    repoPath: String,
    repoManager: DesktopRepoManager,
    credentialStore: DesktopCredentialStore,
    githubApi: GitHubApi,
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    var tabIndex by remember { mutableStateOf(0) }
    // Changes first, then Files (file manager), Branches, Issues, PRs, Actions, Releases, History last
    val tabs = listOf("Changes", "Files", "Branches", "Issues", "PRs", "Actions", "Releases", "History")
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pullMenuExpanded by remember { mutableStateOf(false) }
    var pushMenuExpanded by remember { mutableStateOf(false) }
    var lfsMenuExpanded by remember { mutableStateOf(false) }
    var showForcePushConfirm by remember { mutableStateOf(false) }
    var forcePushUseLease by remember { mutableStateOf(true) }
    var showLfsTrack by remember { mutableStateOf(false) }
    var lfsTrackPattern by remember { mutableStateOf("*.psd") }
    val gitRed = com.quickgit.desktop.ui.theme.GitRed

    fun doPush(force: Boolean = false, forceWithLease: Boolean = false, withLfs: Boolean = false) {
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) {
                if (withLfs) repoManager.pushWithLfs(repoPath, force = force, forceWithLease = forceWithLease)
                else repoManager.push(repoPath, force = force, forceWithLease = forceWithLease)
            }
            busy = false
            val label = when {
                forceWithLease -> "Force with lease"
                force -> "Force push"
                withLfs -> "Push + LFS"
                else -> "Push"
            }
            r.fold({ onMessage("$label OK") }, { onMessage("$label failed: ${it.message}") })
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text(repoPath.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Box {
                Button(onClick = { pullMenuExpanded = true }, enabled = !busy) { Text("Pull ▾") }
                DropdownMenu(expanded = pullMenuExpanded, onDismissRequest = { pullMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Git pull") }, onClick = {
                        pullMenuExpanded = false
                        scope.launch {
                            busy = true
                            val r = withContext(Dispatchers.IO) { repoManager.pull(repoPath) }
                            busy = false
                            r.fold({ onMessage("Pull OK") }, { onMessage("Pull failed: ${it.message}") })
                        }
                    })
                    DropdownMenuItem(text = { Text("LFS pull only") }, onClick = {
                        pullMenuExpanded = false
                        scope.launch {
                            busy = true
                            val r = withContext(Dispatchers.IO) { repoManager.fetchLfs(repoPath) }
                            busy = false
                            r.fold({ onMessage("LFS: $it") }, { onMessage("LFS pull failed: ${it.message}") })
                        }
                    })
                    DropdownMenuItem(text = { Text("Git pull + LFS") }, onClick = {
                        pullMenuExpanded = false
                        scope.launch {
                            busy = true
                            val r = withContext(Dispatchers.IO) { repoManager.pullWithLfs(repoPath) }
                            busy = false
                            r.fold({ onMessage("Pull + LFS OK") }, { onMessage("Pull + LFS failed: ${it.message}") })
                        }
                    })
                }
            }
            Spacer(Modifier.width(8.dp))
            Box {
                Button(onClick = { pushMenuExpanded = true }, enabled = !busy) { Text("Push ▾") }
                DropdownMenu(
                    expanded = pushMenuExpanded,
                    onDismissRequest = { pushMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Git push") },
                        onClick = {
                            pushMenuExpanded = false
                            doPush()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("LFS push only") },
                        onClick = {
                            pushMenuExpanded = false
                            scope.launch {
                                busy = true
                                val r = withContext(Dispatchers.IO) { repoManager.pushLfs(repoPath) }
                                busy = false
                                r.fold({ onMessage("LFS: $it") }, { onMessage("LFS push failed: ${it.message}") })
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Git push + LFS") },
                        onClick = {
                            pushMenuExpanded = false
                            doPush(withLfs = true)
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Force with lease…", color = gitRed) },
                        onClick = {
                            pushMenuExpanded = false
                            forcePushUseLease = true
                            showForcePushConfirm = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Force (no lease)…", color = gitRed) },
                        onClick = {
                            pushMenuExpanded = false
                            forcePushUseLease = false
                            showForcePushConfirm = true
                        }
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box {
                OutlinedButton(onClick = { lfsMenuExpanded = true }, enabled = !busy) { Text("LFS ▾") }
                DropdownMenu(expanded = lfsMenuExpanded, onDismissRequest = { lfsMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text("LFS install") }, onClick = {
                        lfsMenuExpanded = false
                        scope.launch {
                            busy = true
                            val r = withContext(Dispatchers.IO) { repoManager.lfsInstall(repoPath) }
                            busy = false
                            r.fold({ onMessage(it) }, { onMessage("LFS install failed: ${it.message}") })
                        }
                    })
                    DropdownMenuItem(text = { Text("LFS track…") }, onClick = {
                        lfsMenuExpanded = false
                        showLfsTrack = true
                    })
                    DropdownMenuItem(text = { Text("LFS status") }, onClick = {
                        lfsMenuExpanded = false
                        scope.launch {
                            busy = true
                            val r = withContext(Dispatchers.IO) { repoManager.lfsStatus(repoPath) }
                            busy = false
                            r.fold(
                                {
                                    onMessage(
                                        "LFS: ${it.pointerCount} pointers, ${it.missingObjectCount} missing, " +
                                            "tracked: ${it.trackedPatterns.joinToString().ifBlank { "(none)" }}"
                                    )
                                },
                                { onMessage("LFS status failed: ${it.message}") }
                            )
                        }
                    })
                    DropdownMenuItem(text = { Text("LFS pull") }, onClick = {
                        lfsMenuExpanded = false
                        scope.launch {
                            busy = true
                            val r = withContext(Dispatchers.IO) { repoManager.fetchLfs(repoPath) }
                            busy = false
                            r.fold({ onMessage("LFS: $it") }, { onMessage("LFS pull failed: ${it.message}") })
                        }
                    })
                    DropdownMenuItem(text = { Text("LFS push") }, onClick = {
                        lfsMenuExpanded = false
                        scope.launch {
                            busy = true
                            val r = withContext(Dispatchers.IO) { repoManager.pushLfs(repoPath) }
                            busy = false
                            r.fold({ onMessage("LFS: $it") }, { onMessage("LFS push failed: ${it.message}") })
                        }
                    })
                }
            }
            if (busy) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        }
        if (showLfsTrack) {
            AlertDialog(
                onDismissRequest = { showLfsTrack = false },
                title = { Text("Track with Git LFS") },
                text = {
                    Column {
                        Text(
                            "Files matching this pattern will be stored in LFS when staged.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = lfsTrackPattern,
                            onValueChange = { lfsTrackPattern = it },
                            label = { Text("Pattern (e.g. *.psd)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showLfsTrack = false
                        val pattern = lfsTrackPattern
                        scope.launch {
                            busy = true
                            val r = withContext(Dispatchers.IO) { repoManager.lfsTrack(repoPath, pattern) }
                            busy = false
                            r.fold({ onMessage(it) }, { onMessage("LFS track failed: ${it.message}") })
                        }
                    }) { Text("Track") }
                },
                dismissButton = {
                    TextButton(onClick = { showLfsTrack = false }) { Text("Cancel") }
                }
            )
        }
        if (showForcePushConfirm) {
            AlertDialog(
                onDismissRequest = { showForcePushConfirm = false },
                title = { Text("Force push") },
                text = {
                    Column {
                        Text(
                            "Choose how to overwrite the remote branch:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { forcePushUseLease = true }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = forcePushUseLease, onClick = { forcePushUseLease = true })
                            Column {
                                Text("Force with lease", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Only if remote still matches your last fetch (safer)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { forcePushUseLease = false }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = !forcePushUseLease, onClick = { forcePushUseLease = false })
                            Column {
                                Text("Force (no lease)", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Always overwrite — can discard others’ commits",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showForcePushConfirm = false
                            if (forcePushUseLease) doPush(forceWithLease = true)
                            else doPush(force = true)
                        },
                        colors = if (forcePushUseLease) ButtonDefaults.buttonColors()
                        else ButtonDefaults.buttonColors(containerColor = gitRed)
                    ) {
                        Text(if (forcePushUseLease) "Force with lease" else "Force push")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showForcePushConfirm = false }) { Text("Cancel") }
                }
            )
        }
        TabRow(selectedTabIndex = tabIndex) {
            tabs.forEachIndexed { i, title -> Tab(selected = tabIndex == i, onClick = { tabIndex = i }, text = { Text(title) }) }
        }
        when (tabIndex) {
            0 -> ChangesTab(repoPath, repoManager, onMessage)
            1 -> FilesEditorTab(repoPath, repoManager, onMessage)
            2 -> BranchesTab(repoPath, repoManager, onMessage)
            3 -> IssuesTab(repoPath, repoManager, credentialStore, githubApi, onMessage)
            4 -> PullRequestsTab(repoPath, repoManager, credentialStore, githubApi, onMessage)
            5 -> WorkflowsTab(repoPath, repoManager, credentialStore, githubApi, onMessage)
            6 -> ReleasesTab(repoPath, repoManager, credentialStore, githubApi, onMessage)
            7 -> HistoryTab(repoPath, repoManager, onMessage)
        }
    }
}

@Composable
fun ChangesTab(repoPath: String, repoManager: DesktopRepoManager, onMessage: (String) -> Unit) {
    var changes by remember { mutableStateOf(emptyList<DesktopRepoManager.FileChange>()) }
    var commitMessage by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun reload() { scope.launch { loading = true; changes = withContext(Dispatchers.IO) { repoManager.getStatus(repoPath) }; loading = false } }
    LaunchedEffect(repoPath) { reload() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { withContext(Dispatchers.IO) { repoManager.stageAll(repoPath) }; reload(); onMessage("Staged all") } }) { Text("Stage all") }
            OutlinedButton(onClick = { if (selected.isNotEmpty()) scope.launch { withContext(Dispatchers.IO) { repoManager.stage(repoPath, selected.toList()) }; selected = emptySet(); reload() } }, enabled = selected.isNotEmpty()) { Text("Stage") }
            OutlinedButton(onClick = { if (selected.isNotEmpty()) scope.launch { withContext(Dispatchers.IO) { repoManager.unstage(repoPath, selected.toList()) }; selected = emptySet(); reload() } }, enabled = selected.isNotEmpty()) { Text("Unstage") }
            OutlinedButton(onClick = { if (selected.isNotEmpty()) scope.launch { withContext(Dispatchers.IO) { repoManager.discard(repoPath, selected.toList()) }; selected = emptySet(); reload(); onMessage("Discarded") } }, enabled = selected.isNotEmpty()) { Text("Discard") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { reload() }) { Icon(Icons.Default.Refresh, null) }
        }
        Spacer(Modifier.height(8.dp))
        if (loading) CircularProgressIndicator()
        else if (changes.isEmpty()) Text("Working tree clean", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(changes) { change ->
                val isSelected = change.path in selected
                Row(Modifier.fillMaxWidth().clickable { selected = if (isSelected) selected - change.path else selected + change.path }.background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(0.3f) else Color.Transparent).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isSelected, onCheckedChange = { selected = if (it) selected + change.path else selected - change.path })
                    Text(when (change.status) {
                        DesktopRepoManager.ChangeStatus.STAGED -> "S"
                        DesktopRepoManager.ChangeStatus.UNSTAGED -> "M"
                        DesktopRepoManager.ChangeStatus.UNTRACKED -> "?"
                        DesktopRepoManager.ChangeStatus.CONFLICT -> "C"
                    }, fontWeight = FontWeight.Bold, color = when (change.status) {
                        DesktopRepoManager.ChangeStatus.STAGED -> Color(0xFF4CAF50)
                        DesktopRepoManager.ChangeStatus.UNSTAGED -> Color(0xFFFF9800)
                        DesktopRepoManager.ChangeStatus.UNTRACKED -> Color(0xFF2196F3)
                        DesktopRepoManager.ChangeStatus.CONFLICT -> Color(0xFFF44336)
                    }, modifier = Modifier.width(24.dp))
                    Text(change.path)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = commitMessage, onValueChange = { commitMessage = it }, label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
        Button(onClick = {
            if (commitMessage.isNotBlank()) scope.launch {
                val r = withContext(Dispatchers.IO) { repoManager.commit(repoPath, commitMessage) }
                r.fold({ commitMessage = ""; reload(); onMessage("Committed ${it.take(7)}") }, { onMessage("Commit failed: ${it.message}") })
            }
        }, enabled = commitMessage.isNotBlank(), modifier = Modifier.align(Alignment.End)) { Text("Commit") }
    }
}

@Composable
fun HistoryTab(repoPath: String, repoManager: DesktopRepoManager, onMessage: (String) -> Unit) {
    var commits by remember { mutableStateOf(emptyList<DesktopRepoManager.CommitInfo>()) }
    var branches by remember { mutableStateOf(emptyList<DesktopRepoManager.BranchInfo>()) }
    /** null = current HEAD; otherwise a local or remote-tracking branch name. */
    var logRef by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirmCherry by remember { mutableStateOf<DesktopRepoManager.CommitInfo?>(null) }
    var confirmRevert by remember { mutableStateOf<DesktopRepoManager.CommitInfo?>(null) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            loading = true
            branches = withContext(Dispatchers.IO) { repoManager.listBranches(repoPath) }
            commits = withContext(Dispatchers.IO) {
                runCatching { repoManager.getHistory(repoPath, 200, logRef) }
                    .getOrElse {
                        onMessage("Could not load history for ${logRef ?: "HEAD"}: ${it.message}")
                        emptyList()
                    }
            }
            loading = false
        }
    }
    LaunchedEffect(repoPath, logRef) { reload() }

    fun doCherryPick(c: DesktopRepoManager.CommitInfo) {
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { repoManager.cherryPick(repoPath, c.id) }
            busy = false
            confirmCherry = null
            r.fold(
                {
                    logRef = null // show current branch so the new commit is visible
                    reload()
                    onMessage("Cherry-picked ${c.shortId} onto current branch")
                },
                { onMessage("Cherry-pick failed: ${it.message}") }
            )
        }
    }

    fun doRevert(c: DesktopRepoManager.CommitInfo) {
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { repoManager.revertCommit(repoPath, c.id) }
            busy = false
            confirmRevert = null
            r.fold(
                { reload(); onMessage("Reverted ${c.shortId}") },
                { onMessage("Revert failed: ${it.message}") }
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Pick any local or remote-tracking branch to browse commits, then Cherry-pick onto your current branch. " +
                "Add forks under Branches → Add remote, Fetch, then select origin/… or fork/… here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        // Branch / remote-tracking ref picker
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = logRef == null,
                onClick = { logRef = null },
                label = { Text("Current branch") }
            )
            branches.forEach { b ->
                FilterChip(
                    selected = logRef == b.name,
                    onClick = { logRef = b.name },
                    label = {
                        Text(
                            if (b.isRemote) b.name else b.name,
                            maxLines = 1
                        )
                    }
                )
            }
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(commits, key = { it.id }) { c ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(c.message, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    c.shortId,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(c.author, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    dateFormat.format(Date(c.time)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { confirmCherry = c },
                                    enabled = !busy
                                ) { Text("Cherry-pick") }
                                OutlinedButton(
                                    onClick = { confirmRevert = c },
                                    enabled = !busy
                                ) { Text("Revert") }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    confirmCherry?.let { c ->
        AlertDialog(
            onDismissRequest = { if (!busy) confirmCherry = null },
            title = { Text("Cherry-pick ${c.shortId}?") },
            text = {
                Text("Apply this commit onto the current branch:\n\n${c.message}")
            },
            confirmButton = {
                TextButton(onClick = { doCherryPick(c) }, enabled = !busy) {
                    Text(if (busy) "Working…" else "Cherry-pick")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCherry = null }, enabled = !busy) { Text("Cancel") }
            }
        )
    }

    confirmRevert?.let { c ->
        AlertDialog(
            onDismissRequest = { if (!busy) confirmRevert = null },
            title = { Text("Revert ${c.shortId}?") },
            text = {
                Text("Create a new commit that undoes:\n\n${c.message}")
            },
            confirmButton = {
                TextButton(onClick = { doRevert(c) }, enabled = !busy) {
                    Text(if (busy) "Working…" else "Revert")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevert = null }, enabled = !busy) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun BranchesTab(repoPath: String, repoManager: DesktopRepoManager, onMessage: (String) -> Unit) {
    var branches by remember { mutableStateOf(emptyList<DesktopRepoManager.BranchInfo>()) }
    var remotes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var newBranchName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var showAddRemote by remember { mutableStateOf(false) }
    var remoteName by remember { mutableStateOf("") }
    var remoteUrl by remember { mutableStateOf("") }
    var remoteToDelete by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            loading = true
            branches = withContext(Dispatchers.IO) { repoManager.listBranches(repoPath) }
            remotes = withContext(Dispatchers.IO) { repoManager.listRemotes(repoPath) }
            loading = false
        }
    }
    LaunchedEffect(repoPath) { reload() }

    fun doCheckout(b: DesktopRepoManager.BranchInfo) {
        if (b.isCurrent) return
        scope.launch {
            val r = withContext(Dispatchers.IO) { repoManager.checkout(repoPath, b.name) }
            r.fold(
                {
                    reload()
                    val short = if (b.isRemote) b.name.substringAfter('/') else b.name
                    val trackNote = if (b.isRemote) " (tracking ${b.name})" else ""
                    onMessage("Checked out $short$trackNote")
                },
                { onMessage("Checkout failed: ${it.message}") }
            )
        }
    }

    fun doDelete(b: DesktopRepoManager.BranchInfo) {
        if (b.isRemote || b.isCurrent) return
        scope.launch {
            val r = withContext(Dispatchers.IO) { repoManager.deleteBranch(repoPath, b.name, force = false) }
            r.fold(
                { reload(); onMessage("Deleted branch ${b.name}") },
                { onMessage("Delete failed: ${it.message}") }
            )
        }
    }

    fun doFetchRemote(name: String) {
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { repoManager.fetchRemote(repoPath, name) }
            busy = false
            r.fold(
                { reload(); onMessage("Fetched $name") },
                { onMessage("Fetch $name failed: ${it.message}") }
            )
        }
    }

    fun doAddRemote() {
        val n = remoteName.trim()
        val u = remoteUrl.trim()
        if (n.isBlank() || u.isBlank()) return
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { repoManager.addOrSetRemote(repoPath, n, u) }
            busy = false
            r.fold(
                {
                    showAddRemote = false
                    remoteName = ""
                    remoteUrl = ""
                    reload()
                    onMessage("Remote '$n' saved — use Fetch to load its branches, then History to cherry-pick")
                },
                { onMessage("Add remote failed: ${it.message}") }
            )
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // ---- Remotes ----
        Text("Remotes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Add a fork URL, Fetch it, then open History and pick remote-tracking branches to cherry-pick commits.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (remotes.isEmpty()) {
            Text(
                "No remotes configured.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            remotes.forEach { (name, url) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, fontWeight = FontWeight.Medium)
                        Text(
                            url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { doFetchRemote(name) }, enabled = !busy) {
                        Text(if (busy) "…" else "Fetch")
                    }
                    TextButton(onClick = { remoteToDelete = name }, enabled = !busy) {
                        Text("Remove")
                    }
                }
            }
        }
        Row(
            Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { showAddRemote = true }) { Text("Add remote") }
            OutlinedButton(
                onClick = { doFetchRemote("origin") },
                enabled = !busy && remotes.containsKey("origin")
            ) { Text("Fetch origin") }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---- Branches ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newBranchName,
                onValueChange = { newBranchName = it },
                label = { Text("New branch name") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    val name = newBranchName.trim()
                    if (name.isEmpty()) return@Button
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            repoManager.createBranch(repoPath, name, checkout = true)
                        }
                        r.fold(
                            { newBranchName = ""; reload(); onMessage("Created & checked out $name") },
                            { onMessage("Create branch failed: ${it.message}") }
                        )
                    }
                },
                enabled = newBranchName.isNotBlank()
            ) { Text("Create") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Click a local branch to switch, or a remote branch to create a local tracking branch and switch to it. " +
                "Use History to cherry-pick commits from other branches or remotes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (loading) CircularProgressIndicator()
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(branches, key = { (if (it.isRemote) "r_" else "l_") + it.name }) { b ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !b.isCurrent) { doCheckout(b) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (b.isRemote) Icons.Default.Cloud else Icons.Default.Commit,
                        contentDescription = null,
                        tint = if (b.isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            b.name,
                            fontWeight = if (b.isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (b.isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        val subtitle = when {
                            b.isCurrent && !b.upstream.isNullOrBlank() -> "current · tracking ${b.upstream}"
                            b.isCurrent -> "current"
                            b.isRemote -> "remote — click to checkout & track"
                            !b.upstream.isNullOrBlank() -> "tracks ${b.upstream}"
                            else -> "local"
                        }
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (b.isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!b.isCurrent && !b.isRemote) {
                        TextButton(onClick = { doDelete(b) }) {
                            Text("Delete")
                        }
                    }
                    if (!b.isCurrent) {
                        TextButton(onClick = { doCheckout(b) }) {
                            Text(if (b.isRemote) "Checkout & track" else "Checkout")
                        }
                    }
                }
            }
        }
    }

    if (showAddRemote) {
        AlertDialog(
            onDismissRequest = { if (!busy) showAddRemote = false },
            title = { Text("Add remote") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = remoteName,
                        onValueChange = { remoteName = it },
                        label = { Text("Name (e.g. fork)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = remoteUrl,
                        onValueChange = { remoteUrl = it },
                        label = { Text("URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { doAddRemote() },
                    enabled = remoteName.isNotBlank() && remoteUrl.isNotBlank() && !busy
                ) { Text(if (busy) "Saving…" else "Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddRemote = false }, enabled = !busy) { Text("Cancel") }
            }
        )
    }

    remoteToDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { remoteToDelete = null },
            title = { Text("Remove remote '$name'?") },
            text = {
                Text("This only removes the remote configuration. Local branches and fetched refs under refs/remotes/$name/ are not deleted.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { repoManager.removeRemote(repoPath, name) }
                        remoteToDelete = null
                        r.fold(
                            { reload(); onMessage("Removed remote $name") },
                            { onMessage("Remove failed: ${it.message}") }
                        )
                    }
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { remoteToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

/** One entry in the repo file manager (folder or file in the current directory). */
private data class FsEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L
)

/** Language / type color for known source extensions. */
private fun languageColorFor(fileName: String): Color {
    val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (ext) {
        "kt", "kts" -> Color(0xFF7F52FF)          // Kotlin
        "java" -> Color(0xFFB07219)               // Java
        "py" -> Color(0xFF3572A5)                 // Python
        "js", "mjs", "cjs" -> Color(0xFFF1E05A)   // JavaScript
        "ts", "tsx" -> Color(0xFF3178C6)          // TypeScript
        "jsx" -> Color(0xFF61DAFB)                // React JSX
        "go" -> Color(0xFF00ADD8)                 // Go
        "rs" -> Color(0xFFDEA584)                 // Rust
        "c", "h" -> Color(0xFF555555)             // C
        "cpp", "cc", "cxx", "hpp", "hh" -> Color(0xFFF34B7D) // C++
        "cs" -> Color(0xFF178600)                 // C#
        "rb" -> Color(0xFF701516)                 // Ruby
        "php" -> Color(0xFF4F5D95)                // PHP
        "swift" -> Color(0xFFFA7343)              // Swift
        "scala" -> Color(0xFFC22D40)              // Scala
        "sh", "bash", "zsh" -> Color(0xFF89E051)  // Shell
        "html", "htm" -> Color(0xFFE34C26)        // HTML
        "css", "scss", "sass", "less" -> Color(0xFF563D7C) // CSS
        "json" -> Color(0xFF292929)               // JSON
        "xml", "xsl" -> Color(0xFF0060AC)         // XML
        "yml", "yaml" -> Color(0xFFCB171E)        // YAML
        "toml" -> Color(0xFF9C4221)               // TOML
        "md", "markdown" -> Color(0xFF083FA1)     // Markdown
        "gradle" -> Color(0xFF02303A)             // Gradle
        "sql" -> Color(0xFFE38C00)                // SQL
        "r" -> Color(0xFF198CE7)                  // R
        "dart" -> Color(0xFF00B4AB)               // Dart
        "lua" -> Color(0xFF000080)                // Lua
        "pl", "pm" -> Color(0xFF0298C3)           // Perl
        "hs" -> Color(0xFF5E5086)                 // Haskell
        "clj", "cljs" -> Color(0xFFDB5855)        // Clojure
        "ex", "exs" -> Color(0xFF6E4A7E)          // Elixir
        "vim" -> Color(0xFF199F4B)                // Vim
        "dockerfile" -> Color(0xFF384D54)
        "txt", "log" -> Color(0xFF6A737D)
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico" -> Color(0xFFA074C4)
        "pdf" -> Color(0xFFCB2431)
        "zip", "tar", "gz", "tgz", "7z", "jar", "apk" -> Color(0xFF8B6914)
        else -> Color(0xFF8B949E)
    }
}

private fun languageLabelFor(fileName: String): String {
    val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when (ext) {
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "py" -> "Python"
        "js", "mjs", "cjs" -> "JavaScript"
        "ts" -> "TypeScript"
        "tsx" -> "TSX"
        "jsx" -> "JSX"
        "go" -> "Go"
        "rs" -> "Rust"
        "c" -> "C"
        "h" -> "C header"
        "cpp", "cc", "cxx" -> "C++"
        "hpp", "hh" -> "C++ header"
        "cs" -> "C#"
        "rb" -> "Ruby"
        "php" -> "PHP"
        "swift" -> "Swift"
        "scala" -> "Scala"
        "sh", "bash", "zsh" -> "Shell"
        "html", "htm" -> "HTML"
        "css" -> "CSS"
        "scss", "sass" -> "Sass"
        "json" -> "JSON"
        "xml" -> "XML"
        "yml", "yaml" -> "YAML"
        "md", "markdown" -> "Markdown"
        "gradle" -> "Gradle"
        "sql" -> "SQL"
        "dart" -> "Dart"
        else -> ext.ifBlank { "file" }.uppercase()
    }
}

private fun isProbablyTextFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    if (ext.isEmpty()) return true
    val binary = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "pdf", "zip", "tar", "gz", "tgz",
        "7z", "rar", "jar", "apk", "so", "dll", "dylib", "exe", "class", "o", "a", "woff",
        "woff2", "ttf", "otf", "mp3", "mp4", "webm", "ogg", "wav"
    )
    return ext !in binary
}

/** Syntax highlight using the same palette/logic as the Android tablet app. */
private fun highlightSourceCode(source: String, fileName: String, dark: Boolean = true): AnnotatedString {
    val lang = SyntaxHighlight.languageFromPath(fileName)
    val palette = SyntaxHighlight.defaultPalette(
        base = if (dark) Color(0xFFE1E2E8) else Color(0xFF191C20),
        dark = dark
    )
    return SyntaxHighlight.highlight(source, lang, palette)
}

@Composable
fun FilesEditorTab(repoPath: String, repoManager: DesktopRepoManager, onMessage: (String) -> Unit) {
    // Relative path from repo root; empty = root
    var currentDir by remember(repoPath) { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf(true) }
    /** Conflict pairs (source → dest) waiting for overwrite confirmation. */
    var pendingUploadConflicts by remember { mutableStateOf<List<Pair<java.io.File, java.io.File>>>(emptyList()) }
    var entryToRename by remember { mutableStateOf<FsEntry?>(null) }
    var renameName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val root = remember(repoPath) { java.io.File(repoPath) }

    fun listDir(rel: String) {
        scope.launch {
            listing = true
            entries = withContext(Dispatchers.IO) {
                val dir = if (rel.isBlank()) root else java.io.File(root, rel)
                if (!dir.isDirectory) return@withContext emptyList()
                dir.listFiles()
                    ?.filter { it.name != ".git" && !it.name.startsWith(".git") }
                    ?.map { f ->
                        val childRel = if (rel.isBlank()) f.name else "$rel/${f.name}".replace('\\', '/')
                        FsEntry(
                            name = f.name,
                            relativePath = childRel.replace('\\', '/'),
                            isDirectory = f.isDirectory,
                            sizeBytes = if (f.isFile) f.length() else 0L
                        )
                    }
                    ?.sortedWith(compareBy<FsEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    ?: emptyList()
            }
            listing = false
        }
    }

    LaunchedEffect(repoPath, currentDir) {
        selectedFile = null
        content = ""
        original = ""
        listDir(currentDir)
    }

    fun goUp() {
        if (currentDir.isBlank()) return
        currentDir = currentDir
            .replace('\\', '/')
            .trimEnd('/')
            .substringBeforeLast('/', missingDelimiterValue = "")
    }

    fun openFile(rel: String) {
        selectedFile = rel
        scope.launch {
            loading = true
            val text = withContext(Dispatchers.IO) {
                val f = java.io.File(root, rel)
                if (!f.isFile) return@withContext ""
                if (!isProbablyTextFile(f.name)) return@withContext "(binary file — ${f.length()} bytes)"
                try {
                    f.readText()
                } catch (_: Exception) {
                    "(unable to read as text)"
                }
            }
            content = text
            original = text
            loading = false
        }
    }

    /** Merge [src] directory into [dest]; create dest if missing. Overwrite files when requested. */
    fun mergeCopyDirectory(src: java.io.File, dest: java.io.File, overwriteExistingFiles: Boolean): String {
        if (dest.isFile) throw IllegalStateException("A file named '${dest.name}' already exists")
        dest.mkdirs()
        var copied = 0
        var overwritten = 0
        var dirs = 0
        fun walk(from: java.io.File, to: java.io.File) {
            from.listFiles()?.forEach { child ->
                if (child.name == ".git") return@forEach
                val out = java.io.File(to, child.name)
                if (child.isDirectory) {
                    if (out.isFile) throw IllegalStateException("Cannot merge folder over file: ${child.name}")
                    if (!out.exists()) {
                        out.mkdirs()
                        dirs++
                    }
                    walk(child, out)
                } else if (child.isFile) {
                    if (out.isDirectory) throw IllegalStateException("Cannot overwrite folder with file: ${child.name}")
                    val existed = out.isFile
                    if (existed && !overwriteExistingFiles) return@forEach
                    out.parentFile?.mkdirs()
                    child.copyTo(out, overwrite = true)
                    if (existed) overwritten++ else copied++
                }
            }
        }
        walk(src, dest)
        val parts = mutableListOf<String>()
        if (copied > 0) parts += "$copied added"
        if (overwritten > 0) parts += "$overwritten overwritten"
        if (dirs > 0) parts += "$dirs folders"
        return if (parts.isEmpty()) {
            "Merged folder ${dest.name} (no file changes)"
        } else {
            "Imported folder ${dest.name}: ${parts.joinToString(", ")}"
        }
    }

    fun copyUpload(src: java.io.File, dest: java.io.File, overwrite: Boolean) {
        scope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    if (src.isDirectory) {
                        mergeCopyDirectory(src, dest, overwriteExistingFiles = overwrite)
                    } else {
                        if (dest.exists() && !overwrite) return@withContext null
                        if (dest.isDirectory) throw IllegalStateException("A folder named '${dest.name}' already exists")
                        dest.parentFile?.mkdirs()
                        src.copyTo(dest, overwrite = true)
                        if (overwrite) "Replaced ${dest.name}" else "Uploaded ${dest.name}"
                    }
                } ?: return@launch
                onMessage(summary)
                listDir(currentDir)
                if (src.isFile) {
                    val rel = dest.relativeTo(root).path.replace('\\', '/')
                    if (isProbablyTextFile(dest.name)) openFile(rel)
                }
            } catch (e: Exception) {
                onMessage("Upload failed: ${e.message}")
            }
        }
    }

    fun pickAndUpload() {
        scope.launch {
            val chosenList = withContext(Dispatchers.IO) {
                javax.swing.JFileChooser().apply {
                    dialogTitle = "Upload files or folders into repository"
                    fileSelectionMode = javax.swing.JFileChooser.FILES_AND_DIRECTORIES
                    isMultiSelectionEnabled = true
                }.let { chooser ->
                    val result = chooser.showOpenDialog(null)
                    if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
                        chooser.selectedFiles?.toList()?.filterNotNull().orEmpty()
                            .ifEmpty { listOfNotNull(chooser.selectedFile) }
                    } else {
                        emptyList()
                    }
                }
            }
            if (chosenList.isEmpty()) return@launch

            val destDir = if (currentDir.isBlank()) root else java.io.File(root, currentDir)
            val ready = mutableListOf<Pair<java.io.File, java.io.File>>()
            val conflicts = mutableListOf<Pair<java.io.File, java.io.File>>()
            for (chosen in chosenList) {
                val dest = java.io.File(destDir, chosen.name)
                if (dest.exists()) {
                    if (chosen.isDirectory && dest.isFile) {
                        onMessage("Skipped '${chosen.name}': a file with that name already exists")
                        continue
                    }
                    if (chosen.isFile && dest.isDirectory) {
                        onMessage("Skipped '${chosen.name}': a folder with that name already exists")
                        continue
                    }
                    conflicts += chosen to dest
                } else {
                    ready += chosen to dest
                }
            }
            // Upload non-conflicting items immediately
            for ((src, dest) in ready) {
                copyUpload(src, dest, overwrite = true)
            }
            if (conflicts.isNotEmpty()) {
                pendingUploadConflicts = conflicts
            } else if (ready.size > 1) {
                onMessage("Uploaded ${ready.size} items")
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        // ---- Browser pane ----
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            // Path bar + actions
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // "..." back one level
                IconButton(
                    onClick = { goUp() },
                    enabled = currentDir.isNotBlank()
                ) {
                    Text("…", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    if (currentDir.isBlank()) "/" else "/$currentDir",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { listDir(currentDir) }) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { pickAndUpload() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Upload, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Upload")
                }
            }
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            if (listing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                    // Parent "…" row when not at root
                    if (currentDir.isNotBlank()) {
                        item {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { goUp() }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("…", fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                                Text("..", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    items(entries, key = { it.relativePath }) { entry ->
                        // Neutral styling to match the Android file list (no selection
                        // highlight or language-tinted file names).
                        val iconTint = if (entry.isDirectory) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.isDirectory) {
                                        currentDir = entry.relativePath
                                    } else {
                                        openFile(entry.relativePath)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                contentDescription = null,
                                tint = iconTint,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (!entry.isDirectory) {
                                    Text(
                                        languageLabelFor(entry.name),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    entryToRename = entry
                                    renameName = entry.name
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.DriveFileRenameOutline,
                                    contentDescription = "Rename",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (entry.isDirectory) {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    null,
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        VerticalDivider()

        // ---- Editor / code reader pane ----
        Column(Modifier.weight(1f).padding(12.dp)) {
            if (selectedFile == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Open a folder, or select a file to view / edit.\nUse Upload to add local files or folders (multi-select supported; folders merge and overwrite matching files).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                val name = selectedFile!!.substringAfterLast('/')
                var editing by remember(selectedFile) { mutableStateOf(false) }
                val darkUi = androidx.compose.foundation.isSystemInDarkTheme()
                // Always-on syntax colouring (view + edit), matching the Android tablet editor.
                val codeStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = if (darkUi) Color(0xFFE1E2E8) else Color(0xFF191C20)
                )
                val highlightTransform = remember(name, darkUi) {
                    VisualTransformation { text ->
                        TransformedText(
                            highlightSourceCode(text.text, name, dark = darkUi),
                            OffsetMapping.Identity
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(selectedFile!!, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            languageLabelFor(name),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isProbablyTextFile(name)) {
                        TextButton(onClick = { editing = !editing }) {
                            Text(if (editing) "View" else "Edit")
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    java.io.File(root, selectedFile!!).writeText(content)
                                }
                                original = content
                                editing = false
                                onMessage("Saved $selectedFile")
                            }
                        },
                        enabled = content != original && isProbablyTextFile(name)
                    ) { Text("Save") }
                }
                Spacer(Modifier.height(8.dp))
                if (loading) {
                    CircularProgressIndicator()
                } else if (editing && isProbablyTextFile(name)) {
                    // Editable body with the same syntax colouring as the Android CodeEditor
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(if (darkUi) Color(0xFF111318) else Color(0xFFF8F9FF))
                            .horizontalScroll(rememberScrollState())
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        BasicTextField(
                            value = content,
                            onValueChange = { content = it },
                            textStyle = codeStyle,
                            cursorBrush = SolidColor(Color(0xFF7EE787)),
                            visualTransformation = highlightTransform,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    // Syntax-coloured code reader (identical palette to edit mode)
                    val highlighted = remember(content, name, darkUi) { highlightSourceCode(content, name, dark = darkUi) }
                    SelectionContainer(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(if (darkUi) Color(0xFF111318) else Color(0xFFF8F9FF))
                                .horizontalScroll(rememberScrollState())
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = highlighted,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }

    // Overwrite confirmation (supports multi-select conflicts)
    if (pendingUploadConflicts.isNotEmpty()) {
        val conflicts = pendingUploadConflicts
        val folderCount = conflicts.count { it.first.isDirectory }
        val fileCount = conflicts.size - folderCount
        val title = when {
            conflicts.size == 1 && folderCount == 1 -> "Merge folder?"
            conflicts.size == 1 -> "Replace existing file?"
            else -> "Replace ${conflicts.size} existing items?"
        }
        val body = buildString {
            if (conflicts.size == 1) {
                val (src, dest) = conflicts.first()
                if (src.isDirectory) {
                    append("\"${dest.name}\" already exists in this folder.\n\n")
                    append("Merge the selected folder into it? Existing files with the same name will be overwritten.")
                } else {
                    append("\"${dest.name}\" already exists in this folder.\n\nReplace it with the selected file?")
                }
            } else {
                append("These items already exist in this folder:\n\n")
                conflicts.take(12).forEach { (src, dest) ->
                    append("• ${dest.name}")
                    if (src.isDirectory) append(" (folder)")
                    append("\n")
                }
                if (conflicts.size > 12) append("… and ${conflicts.size - 12} more\n")
                append("\nReplace/merge all? Matching files inside folders will be overwritten.")
            }
        }
        val confirmLabel = when {
            conflicts.size == 1 && folderCount == 1 -> "Merge & overwrite"
            conflicts.size == 1 -> "Replace"
            else -> "Replace all"
        }
        AlertDialog(
            onDismissRequest = { pendingUploadConflicts = emptyList() },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        for ((src, dest) in conflicts) {
                            copyUpload(src, dest, overwrite = true)
                        }
                        pendingUploadConflicts = emptyList()
                    }
                ) { Text(confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUploadConflicts = emptyList() }) { Text("Cancel") }
            }
        )
    }

    entryToRename?.let { entry ->
        AlertDialog(
            onDismissRequest = { entryToRename = null },
            title = { Text(if (entry.isDirectory) "Rename folder" else "Rename file") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it.filter { c -> c != '/' && c != '\\' } },
                    label = { Text("New name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = renameName.trim()
                        if (name.isBlank() || name == entry.name) {
                            entryToRename = null
                            return@Button
                        }
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                repoManager.renameWorkingPath(repoPath, entry.relativePath, name)
                            }
                            entryToRename = null
                            result.fold(
                                onSuccess = { newRel ->
                                    onMessage("Renamed to ${newRel.substringAfterLast('/')}")
                                    if (selectedFile == entry.relativePath) {
                                        selectedFile = newRel
                                    } else if (selectedFile?.startsWith(entry.relativePath + "/") == true) {
                                        selectedFile = newRel + selectedFile!!.removePrefix(entry.relativePath)
                                    }
                                    if (entry.isDirectory && (currentDir == entry.relativePath || currentDir.startsWith(entry.relativePath + "/"))) {
                                        currentDir = if (currentDir == entry.relativePath) newRel
                                        else newRel + currentDir.removePrefix(entry.relativePath)
                                    }
                                    listDir(currentDir)
                                },
                                onFailure = { onMessage("Rename failed: ${it.message}") }
                            )
                        }
                    },
                    enabled = renameName.trim().isNotBlank() && renameName.trim() != entry.name
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { entryToRename = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun IssuesTab(
    repoPath: String,
    repoManager: DesktopRepoManager,
    credentialStore: DesktopCredentialStore,
    githubApi: GitHubApi,
    onMessage: (String) -> Unit
) {
    var issues by remember { mutableStateOf<List<Issue>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stateFilter by remember { mutableStateOf(IssueStateFilter.OPEN) }
    var ownerRepo by remember { mutableStateOf<GitHubApi.OwnerRepo?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var detailIssue by remember { mutableStateOf<Issue?>(null) }
    var comments by remember { mutableStateOf<List<PrComment>>(emptyList()) }
    var commentDraft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            loading = true
            error = null
            val remote = withContext(Dispatchers.IO) { repoManager.getRemoteUrl(repoPath) }
            val parsed = githubApi.parseOwnerRepo(remote)
            ownerRepo = parsed
            if (parsed == null) {
                error = "Not a GitHub remote (or no origin URL). Issues require a github.com remote and a token in Credentials."
                issues = emptyList()
                loading = false
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                githubApi.listIssues(parsed.owner, parsed.repo, stateFilter.apiValue)
            }
            result.fold(
                onSuccess = { issues = it },
                onFailure = { error = it.message; issues = emptyList() }
            )
            loading = false
        }
    }

    LaunchedEffect(repoPath, stateFilter) { reload() }

    fun loadDetail(issue: Issue) {
        detailIssue = issue
        commentDraft = ""
        val or = ownerRepo ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                githubApi.listComments(or.owner, or.repo, issue.number)
            }
            r.fold(onSuccess = { comments = it }, onFailure = { comments = emptyList() })
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IssueStateFilter.entries.forEach { f ->
                FilterChip(
                    selected = stateFilter == f,
                    onClick = { stateFilter = f },
                    label = { Text(f.label) }
                )
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { showCreate = true }, enabled = ownerRepo != null && !busy) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New issue")
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            issues.isEmpty() -> Text(
                "No ${stateFilter.label.lowercase()} issues",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(issues, key = { it.number }) { issue ->
                    Card(
                        Modifier.fillMaxWidth().clickable { loadDetail(issue) }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "#${issue.number} ${issue.title}",
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    issue.state,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (issue.state == "open") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "@${issue.authorLogin}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (issue.commentsCount > 0) {
                                    Text(
                                        "${issue.commentsCount} comments",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Row(
                                Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (issue.state == "open") {
                                    OutlinedButton(
                                        onClick = {
                                            val or = ownerRepo ?: return@OutlinedButton
                                            scope.launch {
                                                busy = true
                                                val r = withContext(Dispatchers.IO) {
                                                    githubApi.setIssueState(or.owner, or.repo, issue.number, open = false)
                                                }
                                                busy = false
                                                r.fold(
                                                    { onMessage("Closed #${issue.number}"); reload() },
                                                    { onMessage("Close failed: ${it.message}") }
                                                )
                                            }
                                        },
                                        enabled = !busy
                                    ) { Text("Close") }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            val or = ownerRepo ?: return@OutlinedButton
                                            scope.launch {
                                                busy = true
                                                val r = withContext(Dispatchers.IO) {
                                                    githubApi.setIssueState(or.owner, or.repo, issue.number, open = true)
                                                }
                                                busy = false
                                                r.fold(
                                                    { onMessage("Reopened #${issue.number}"); reload() },
                                                    { onMessage("Reopen failed: ${it.message}") }
                                                )
                                            }
                                        },
                                        enabled = !busy
                                    ) { Text("Reopen") }
                                }
                                TextButton(onClick = { loadDetail(issue) }) { Text("Details") }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showCreate) {
        var title by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) showCreate = false },
            title = { Text("New issue") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text("Body (optional)") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val or = ownerRepo ?: return@TextButton
                        val t = title.trim()
                        if (t.isBlank()) return@TextButton
                        scope.launch {
                            busy = true
                            val r = withContext(Dispatchers.IO) {
                                githubApi.createIssue(or.owner, or.repo, t, body.trim())
                            }
                            busy = false
                            r.fold(
                                {
                                    showCreate = false
                                    onMessage("Created issue #${it.number}")
                                    stateFilter = IssueStateFilter.OPEN
                                    reload()
                                },
                                { onMessage("Create failed: ${it.message}") }
                            )
                        }
                    },
                    enabled = title.isNotBlank() && !busy
                ) { Text(if (busy) "Creating…" else "Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }, enabled = !busy) { Text("Cancel") }
            }
        )
    }

    detailIssue?.let { issue ->
        AlertDialog(
            onDismissRequest = { detailIssue = null },
            title = { Text("#${issue.number} ${issue.title}") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "State: ${issue.state} · @${issue.authorLogin}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!issue.body.isNullOrBlank()) {
                        Text(issue.body!!, style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                    Text("Comments", fontWeight = FontWeight.SemiBold)
                    if (comments.isEmpty()) {
                        Text("No comments yet", style = MaterialTheme.typography.bodySmall)
                    } else {
                        comments.forEach { c ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    "@${c.authorLogin}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(c.body, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = commentDraft,
                        onValueChange = { commentDraft = it },
                        label = { Text("Add a comment") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val or = ownerRepo ?: return@TextButton
                        val body = commentDraft.trim()
                        if (body.isBlank()) return@TextButton
                        scope.launch {
                            busy = true
                            val r = withContext(Dispatchers.IO) {
                                githubApi.addComment(or.owner, or.repo, issue.number, body)
                            }
                            busy = false
                            r.fold(
                                {
                                    commentDraft = ""
                                    onMessage("Comment added")
                                    loadDetail(issue)
                                },
                                { onMessage("Comment failed: ${it.message}") }
                            )
                        }
                    },
                    enabled = commentDraft.isNotBlank() && !busy
                ) { Text("Comment") }
            },
            dismissButton = {
                TextButton(onClick = { detailIssue = null }) { Text("Close") }
            }
        )
    }
}

@Composable
fun PullRequestsTab(
    repoPath: String,
    repoManager: DesktopRepoManager,
    credentialStore: DesktopCredentialStore,
    githubApi: GitHubApi,
    onMessage: (String) -> Unit
) {
    var prs by remember { mutableStateOf<List<PullRequest>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stateFilter by remember { mutableStateOf(PrStateFilter.OPEN) }
    var ownerRepo by remember { mutableStateOf<GitHubApi.OwnerRepo?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var detailPr by remember { mutableStateOf<PullRequest?>(null) }
    var comments by remember { mutableStateOf<List<PrComment>>(emptyList()) }
    var commentDraft by remember { mutableStateOf("") }
    var mergeMethod by remember { mutableStateOf(MergeMethod.MERGE) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            loading = true
            error = null
            val remote = withContext(Dispatchers.IO) { repoManager.getRemoteUrl(repoPath) }
            val parsed = githubApi.parseOwnerRepo(remote)
            ownerRepo = parsed
            if (parsed == null) {
                error = "Not a GitHub remote. PRs require a github.com remote and a token in Credentials."
                prs = emptyList()
                loading = false
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                githubApi.listPullRequests(parsed.owner, parsed.repo, stateFilter.apiValue)
            }
            result.fold(
                onSuccess = { prs = it },
                onFailure = { error = it.message; prs = emptyList() }
            )
            loading = false
        }
    }

    LaunchedEffect(repoPath, stateFilter) { reload() }

    fun loadDetail(pr: PullRequest) {
        detailPr = pr
        commentDraft = ""
        val or = ownerRepo ?: return
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                githubApi.listComments(or.owner, or.repo, pr.number)
            }
            r.fold(onSuccess = { comments = it }, onFailure = { comments = emptyList() })
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PrStateFilter.entries.forEach { f ->
                FilterChip(
                    selected = stateFilter == f,
                    onClick = { stateFilter = f },
                    label = { Text(f.label) }
                )
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { showCreate = true }, enabled = ownerRepo != null && !busy) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New PR")
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            prs.isEmpty() -> Text(
                "No ${stateFilter.label.lowercase()} pull requests",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(prs, key = { it.number }) { pr ->
                    Card(Modifier.fillMaxWidth().clickable { loadDetail(pr) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "#${pr.number} ${pr.title}",
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                buildString {
                                    append(pr.state)
                                    if (pr.isDraft) append(" · draft")
                                    if (pr.merged) append(" · merged")
                                    append(" · ${pr.headRef} → ${pr.baseRef}")
                                    append(" · @${pr.authorLogin}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (pr.state == "open" && !pr.merged) {
                                    Button(
                                        onClick = {
                                            val or = ownerRepo ?: return@Button
                                            scope.launch {
                                                busy = true
                                                val r = withContext(Dispatchers.IO) {
                                                    githubApi.mergePullRequest(
                                                        or.owner, or.repo, pr.number, mergeMethod, null
                                                    )
                                                }
                                                busy = false
                                                r.fold(
                                                    { onMessage("Merged #${pr.number}"); reload() },
                                                    { onMessage("Merge failed: ${it.message}") }
                                                )
                                            }
                                        },
                                        enabled = !busy
                                    ) { Text("Merge") }
                                    OutlinedButton(
                                        onClick = {
                                            val or = ownerRepo ?: return@OutlinedButton
                                            scope.launch {
                                                busy = true
                                                val r = withContext(Dispatchers.IO) {
                                                    githubApi.setPullRequestState(
                                                        or.owner, or.repo, pr.number, open = false
                                                    )
                                                }
                                                busy = false
                                                r.fold(
                                                    { onMessage("Closed #${pr.number}"); reload() },
                                                    { onMessage("Close failed: ${it.message}") }
                                                )
                                            }
                                        },
                                        enabled = !busy
                                    ) { Text("Close") }
                                } else if (pr.state == "closed" && !pr.merged) {
                                    OutlinedButton(
                                        onClick = {
                                            val or = ownerRepo ?: return@OutlinedButton
                                            scope.launch {
                                                busy = true
                                                val r = withContext(Dispatchers.IO) {
                                                    githubApi.setPullRequestState(
                                                        or.owner, or.repo, pr.number, open = true
                                                    )
                                                }
                                                busy = false
                                                r.fold(
                                                    { onMessage("Reopened #${pr.number}"); reload() },
                                                    { onMessage("Reopen failed: ${it.message}") }
                                                )
                                            }
                                        },
                                        enabled = !busy
                                    ) { Text("Reopen") }
                                }
                                TextButton(onClick = { loadDetail(pr) }) { Text("Details") }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showCreate) {
        var title by remember { mutableStateOf("") }
        var body by remember { mutableStateOf("") }
        var head by remember { mutableStateOf("") }
        var base by remember { mutableStateOf("main") }
        var draft by remember { mutableStateOf(false) }
        var branchOptions by remember { mutableStateOf<List<String>>(emptyList()) }

        LaunchedEffect(Unit) {
            val or = ownerRepo
            if (or != null) {
                withContext(Dispatchers.IO) {
                    githubApi.listLocalAndRemoteBranches(or.owner, or.repo).onSuccess {
                        branchOptions = it
                        if (base !in it && it.isNotEmpty()) base = it.first()
                    }
                }
            }
            val current = withContext(Dispatchers.IO) { repoManager.currentBranch(repoPath) }
            if (!current.isNullOrBlank()) head = current
        }

        AlertDialog(
            onDismissRequest = { if (!busy) showCreate = false },
            title = { Text("New pull request") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text("Body (optional)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = head,
                        onValueChange = { head = it },
                        label = { Text("Head branch (yours)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = {
                            if (branchOptions.isNotEmpty()) {
                                Text("Remote branches: ${branchOptions.take(8).joinToString()}")
                            }
                        }
                    )
                    OutlinedTextField(
                        value = base,
                        onValueChange = { base = it },
                        label = { Text("Base branch") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = draft, onCheckedChange = { draft = it })
                        Text("Create as draft")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val or = ownerRepo ?: return@TextButton
                        val t = title.trim()
                        val h = head.trim()
                        val b = base.trim()
                        if (t.isBlank() || h.isBlank() || b.isBlank()) return@TextButton
                        scope.launch {
                            busy = true
                            val r = withContext(Dispatchers.IO) {
                                githubApi.createPullRequest(
                                    or.owner, or.repo, t, body.trim(), h, b, draft
                                )
                            }
                            busy = false
                            r.fold(
                                {
                                    showCreate = false
                                    onMessage("Created PR #${it.number}")
                                    stateFilter = PrStateFilter.OPEN
                                    reload()
                                },
                                { onMessage("Create PR failed: ${it.message}") }
                            )
                        }
                    },
                    enabled = title.isNotBlank() && head.isNotBlank() && base.isNotBlank() && !busy
                ) { Text(if (busy) "Creating…" else "Create PR") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }, enabled = !busy) { Text("Cancel") }
            }
        )
    }

    detailPr?.let { pr ->
        AlertDialog(
            onDismissRequest = { detailPr = null },
            title = { Text("#${pr.number} ${pr.title}") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "${pr.state}${if (pr.isDraft) " · draft" else ""}${if (pr.merged) " · merged" else ""} · ${pr.headRef} → ${pr.baseRef}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("@${pr.authorLogin}", style = MaterialTheme.typography.bodySmall)
                    if (!pr.body.isNullOrBlank()) {
                        Text(pr.body!!, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (pr.state == "open" && !pr.merged) {
                        Text("Merge method", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MergeMethod.entries.forEach { m ->
                                FilterChip(
                                    selected = mergeMethod == m,
                                    onClick = { mergeMethod = m },
                                    label = { Text(m.label) }
                                )
                            }
                        }
                        Button(
                            onClick = {
                                val or = ownerRepo ?: return@Button
                                scope.launch {
                                    busy = true
                                    val r = withContext(Dispatchers.IO) {
                                        githubApi.mergePullRequest(
                                            or.owner, or.repo, pr.number, mergeMethod, null
                                        )
                                    }
                                    busy = false
                                    r.fold(
                                        {
                                            detailPr = null
                                            onMessage("Merged #${pr.number}")
                                            reload()
                                        },
                                        { onMessage("Merge failed: ${it.message}") }
                                    )
                                }
                            },
                            enabled = !busy
                        ) { Text("Merge PR") }
                    }
                    HorizontalDivider()
                    Text("Comments", fontWeight = FontWeight.SemiBold)
                    if (comments.isEmpty()) {
                        Text("No comments yet", style = MaterialTheme.typography.bodySmall)
                    } else {
                        comments.forEach { c ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    "@${c.authorLogin}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(c.body, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = commentDraft,
                        onValueChange = { commentDraft = it },
                        label = { Text("Add a comment") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val or = ownerRepo ?: return@TextButton
                        val body = commentDraft.trim()
                        if (body.isBlank()) return@TextButton
                        scope.launch {
                            busy = true
                            val r = withContext(Dispatchers.IO) {
                                githubApi.addComment(or.owner, or.repo, pr.number, body)
                            }
                            busy = false
                            r.fold(
                                {
                                    commentDraft = ""
                                    onMessage("Comment added")
                                    loadDetail(pr)
                                },
                                { onMessage("Comment failed: ${it.message}") }
                            )
                        }
                    },
                    enabled = commentDraft.isNotBlank() && !busy
                ) { Text("Comment") }
            },
            dismissButton = {
                TextButton(onClick = { detailPr = null }) { Text("Close") }
            }
        )
    }
}


@Composable
fun WorkflowsTab(
    repoPath: String,
    repoManager: DesktopRepoManager,
    credentialStore: DesktopCredentialStore,
    githubApi: GitHubApi,
    onMessage: (String) -> Unit
) {
    var workflows by remember { mutableStateOf<List<Workflow>>(emptyList()) }
    var runs by remember { mutableStateOf<List<WorkflowRun>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(WorkflowRunFilter.ALL) }
    var selectedWorkflowId by remember { mutableStateOf<Long?>(null) }
    var ownerRepo by remember { mutableStateOf<GitHubApi.OwnerRepo?>(null) }
    var detailRun by remember { mutableStateOf<WorkflowRun?>(null) }
    var jobs by remember { mutableStateOf<List<WorkflowJob>>(emptyList()) }
    var detailLoading by remember { mutableStateOf(false) }
    var dispatchWorkflow by remember { mutableStateOf<Workflow?>(null) }
    var dispatchRef by remember { mutableStateOf("main") }
    var dispatchInputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var dispatchInputDefs by remember { mutableStateOf<List<WorkflowInput>>(emptyList()) }
    var dispatchBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload(showSpinner: Boolean = true) {
        scope.launch {
            if (showSpinner) loading = true
            error = null
            val remote = withContext(Dispatchers.IO) { repoManager.getRemoteUrl(repoPath) }
            val parsed = githubApi.parseOwnerRepo(remote)
            ownerRepo = parsed
            if (parsed == null) {
                error = "Not a GitHub remote. Actions require a github.com remote and a token in Credentials."
                workflows = emptyList()
                runs = emptyList()
                loading = false
                return@launch
            }
            val wfResult = withContext(Dispatchers.IO) {
                githubApi.listWorkflows(parsed.owner, parsed.repo)
            }
            wfResult.fold(
                onSuccess = { workflows = it },
                onFailure = { error = it.message; workflows = emptyList() }
            )
            val runResult = withContext(Dispatchers.IO) {
                githubApi.listWorkflowRuns(
                    parsed.owner, parsed.repo,
                    workflowId = selectedWorkflowId,
                    status = filter.apiValue
                )
            }
            runResult.fold(
                onSuccess = { runs = it },
                onFailure = { if (error == null) error = it.message; runs = emptyList() }
            )
            loading = false
        }
    }

    LaunchedEffect(repoPath, filter, selectedWorkflowId) { reload() }

    // Keep the runs list live while any run is still queued / in progress.
    LaunchedEffect(repoPath, filter, selectedWorkflowId, runs) {
        val active = runs.any { it.status == "queued" || it.status == "in_progress" || it.status == "waiting" || it.status == "requested" || it.status == "pending" }
        if (!active) return@LaunchedEffect
        while (true) {
            delay(3_000)
            reload(showSpinner = false)
        }
    }

    fun loadRunDetail(run: WorkflowRun, showSpinner: Boolean = true) {
        detailRun = run
        if (showSpinner) jobs = emptyList()
        val or = ownerRepo ?: return
        scope.launch {
            if (showSpinner) detailLoading = true
            val runResult = withContext(Dispatchers.IO) {
                githubApi.getWorkflowRun(or.owner, or.repo, run.id)
            }
            runResult.onSuccess { detailRun = it }
            val r = withContext(Dispatchers.IO) {
                githubApi.listJobsForRun(or.owner, or.repo, run.id)
            }
            r.fold(onSuccess = { jobs = it }, onFailure = { if (showSpinner) jobs = emptyList() })
            detailLoading = false
        }
    }

    // Poll job/step status while viewing an active run so completed steps appear without leaving.
    LaunchedEffect(detailRun?.id, detailRun?.status) {
        val run = detailRun ?: return@LaunchedEffect
        val active = run.status == "queued" || run.status == "in_progress" ||
            run.status == "waiting" || run.status == "requested" || run.status == "pending"
        if (!active) return@LaunchedEffect
        while (detailRun?.id == run.id) {
            delay(2_000)
            val current = detailRun ?: break
            if (current.id != run.id) break
            loadRunDetail(current, showSpinner = false)
        }
    }

    if (detailRun != null) {
        val run = detailRun!!
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { detailRun = null }) { Icon(Icons.Default.ArrowBack, "Back") }
                Text(
                    run.displayTitle.ifBlank { run.name },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val or = ownerRepo
                if (or != null && !busy) {
                    if (run.status == "in_progress" || run.status == "queued") {
                        OutlinedButton(onClick = {
                            scope.launch {
                                busy = true
                                val r = withContext(Dispatchers.IO) {
                                    githubApi.cancelWorkflowRun(or.owner, or.repo, run.id)
                                }
                                busy = false
                                r.fold(
                                    { onMessage("Cancel requested"); detailRun = null; reload() },
                                    { onMessage("Cancel failed: ${it.message}") }
                                )
                            }
                        }) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            busy = true
                            val r = withContext(Dispatchers.IO) {
                                githubApi.rerunWorkflowRun(or.owner, or.repo, run.id)
                            }
                            busy = false
                            r.fold(
                                { onMessage("Re-run requested"); detailRun = null; reload() },
                                { onMessage("Re-run failed: ${it.message}") }
                            )
                        }
                    }) { Text("Re-run") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${run.status}${run.conclusion?.let { " · $it" } ?: ""} · ${run.event} · ${run.headBranch ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (detailLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (jobs.isEmpty()) {
                Text("No jobs", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(jobs, key = { it.id }) { job ->
                        var logText by remember(job.id) { mutableStateOf<String?>(null) }
                        var logLoading by remember(job.id) { mutableStateOf(false) }
                        var showLog by remember(job.id) { mutableStateOf(false) }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .background(
                                                statusColor(job.status, job.conclusion),
                                                shape = MaterialTheme.shapes.extraSmall
                                            )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(job.name, fontWeight = FontWeight.Medium)
                                        Text(
                                            "${job.status}${job.conclusion?.let { " · $it" } ?: ""}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = statusColor(job.status, job.conclusion)
                                        )
                                    }
                                    val or = ownerRepo
                                    TextButton(
                                        onClick = {
                                            if (or == null) return@TextButton
                                            if (showLog && logText != null) {
                                                showLog = false
                                                return@TextButton
                                            }
                                            scope.launch {
                                                logLoading = true
                                                showLog = true
                                                val r = withContext(Dispatchers.IO) {
                                                    githubApi.getJobLogs(or.owner, or.repo, job.id)
                                                }
                                                logLoading = false
                                                r.fold(
                                                    onSuccess = { logText = it },
                                                    onFailure = {
                                                        logText = "Failed to load log: ${it.message}"
                                                        onMessage("Log: ${it.message}")
                                                    }
                                                )
                                            }
                                        },
                                        enabled = or != null && !logLoading
                                    ) {
                                        Text(
                                            when {
                                                logLoading -> "Loading…"
                                                showLog && logText != null -> "Hide log"
                                                else -> "View log"
                                            }
                                        )
                                    }
                                }
                                if (job.steps.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text("Steps", style = MaterialTheme.typography.labelMedium)
                                    Spacer(Modifier.height(4.dp))
                                    job.steps.forEach { step ->
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(8.dp)
                                                    .background(
                                                        statusColor(step.status, step.conclusion),
                                                        shape = MaterialTheme.shapes.extraSmall
                                                    )
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "${step.number}. ${step.name}",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                step.status + (step.conclusion?.let { " · $it" } ?: ""),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = statusColor(step.status, step.conclusion)
                                            )
                                        }
                                    }
                                }
                                if (showLog) {
                                    Spacer(Modifier.height(8.dp))
                                    if (logLoading) {
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        SelectionContainer {
                                            Text(
                                                logText ?: "",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = FontFamily.Monospace
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 320.dp)
                                                    .verticalScroll(rememberScrollState())
                                                    .background(Color(0xFF0D1117))
                                                    .padding(8.dp),
                                                color = Color(0xFFC9D1D9)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedWorkflowId == null,
                onClick = { selectedWorkflowId = null },
                label = { Text("All workflows") }
            )
            workflows.forEach { wf ->
                FilterChip(
                    selected = selectedWorkflowId == wf.id,
                    onClick = { selectedWorkflowId = wf.id },
                    label = { Text(wf.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WorkflowRunFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.label) }
                )
            }
            Spacer(Modifier.weight(1f))
            val selectedWf = workflows.firstOrNull { it.id == selectedWorkflowId }
            Button(
                onClick = {
                    val wf = selectedWf ?: workflows.firstOrNull()
                    if (wf == null) {
                        onMessage("No workflow selected")
                        return@Button
                    }
                    val or = ownerRepo
                    if (or == null) {
                        onMessage("Not a GitHub remote")
                        return@Button
                    }
                    dispatchWorkflow = wf
                    dispatchRef = "main"
                    scope.launch {
                        dispatchBusy = true
                        val defs = withContext(Dispatchers.IO) {
                            githubApi.listWorkflowDispatchInputs(or.owner, or.repo, wf.path)
                                .getOrElse { emptyList() }
                        }
                        dispatchInputDefs = defs
                        dispatchInputs = defs.associate { it.name to (it.default.orEmpty()) }
                        dispatchBusy = false
                    }
                },
                enabled = !loading && workflows.isNotEmpty()
            ) { Text("Run workflow…") }
            IconButton(onClick = { reload() }, enabled = !loading) {
                Icon(Icons.Default.Refresh, "Refresh")
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            runs.isEmpty() -> Text(
                "No workflow runs",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(runs, key = { it.id }) { run ->
                    Card(
                        Modifier.fillMaxWidth().clickable { loadRunDetail(run) }
                    ) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Status colour bar
                            Box(
                                Modifier
                                    .width(4.dp)
                                    .height(48.dp)
                                    .background(
                                        statusColor(run.status, run.conclusion),
                                        shape = MaterialTheme.shapes.extraSmall
                                    )
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    run.displayTitle.ifBlank { run.name },
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        run.status + (run.conclusion?.let { " · $it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = statusColor(run.status, run.conclusion)
                                    )
                                    if (!run.headBranch.isNullOrBlank()) {
                                        Text(
                                            run.headBranch!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        run.event,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            TextButton(onClick = { loadRunDetail(run) }) { Text("Open") }
                        }
                    }
                }
            }
        }
    }


    dispatchWorkflow?.let { wf ->
        AlertDialog(
            onDismissRequest = { dispatchWorkflow = null },
            title = { Text("Run “${wf.name}”") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Triggers workflow_dispatch. Set the ref and any inputs defined in the workflow YAML.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = dispatchRef,
                        onValueChange = { dispatchRef = it },
                        label = { Text("Ref (branch / tag / SHA)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (dispatchInputDefs.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Inputs", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        dispatchInputDefs.forEach { def ->
                            OutlinedTextField(
                                value = dispatchInputs[def.name].orEmpty(),
                                onValueChange = { v ->
                                    dispatchInputs = dispatchInputs + (def.name to v)
                                },
                                label = {
                                    Text(if (def.required) "${def.name} *" else def.name)
                                },
                                supportingText = {
                                    if (!def.description.isNullOrBlank()) Text(def.description!!)
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            )
                        }
                    } else if (dispatchBusy) {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            },
            confirmButton = {
                val requiredOk = dispatchInputDefs.none {
                    it.required && dispatchInputs[it.name].isNullOrBlank()
                }
                Button(
                    onClick = {
                        val or = ownerRepo ?: return@Button
                        scope.launch {
                            dispatchBusy = true
                            val r = withContext(Dispatchers.IO) {
                                githubApi.dispatchWorkflow(
                                    or.owner,
                                    or.repo,
                                    wf.id,
                                    dispatchRef.trim(),
                                    dispatchInputs.filter { it.value.isNotEmpty() ||
                                        dispatchInputDefs.any { d -> d.name == it.key && d.required } }
                                )
                            }
                            dispatchBusy = false
                            r.fold(
                                {
                                    onMessage("Workflow dispatched")
                                    dispatchWorkflow = null
                                    reload()
                                },
                                { onMessage("Dispatch failed: ${it.message}") }
                            )
                        }
                    },
                    enabled = !dispatchBusy && dispatchRef.isNotBlank() && requiredOk
                ) {
                    if (dispatchBusy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Run workflow")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { dispatchWorkflow = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun ReleasesTab(
    repoPath: String,
    repoManager: DesktopRepoManager,
    credentialStore: DesktopCredentialStore,
    githubApi: GitHubApi,
    onMessage: (String) -> Unit
) {
    var releases by remember { mutableStateOf<List<Release>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var ownerRepo by remember { mutableStateOf<GitHubApi.OwnerRepo?>(null) }
    var detail by remember { mutableStateOf<Release?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            loading = true
            error = null
            val remote = withContext(Dispatchers.IO) { repoManager.getRemoteUrl(repoPath) }
            val parsed = githubApi.parseOwnerRepo(remote)
            ownerRepo = parsed
            if (parsed == null) {
                error = "Not a GitHub remote. Releases require a github.com remote and a token in Credentials."
                releases = emptyList()
                loading = false
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                githubApi.listReleases(parsed.owner, parsed.repo)
            }
            result.fold(
                onSuccess = { releases = it },
                onFailure = { error = it.message; releases = emptyList() }
            )
            loading = false
        }
    }

    LaunchedEffect(repoPath) { reload() }

    fun openDetail(release: Release) {
        detail = release
        val or = ownerRepo ?: return
        scope.launch {
            detailLoading = true
            val r = withContext(Dispatchers.IO) {
                githubApi.getRelease(or.owner, or.repo, release.id)
            }
            r.fold(onSuccess = { detail = it }, onFailure = { /* keep list item */ })
            detailLoading = false
        }
    }

    if (detail != null) {
        val rel = detail!!
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { detail = null }) { Icon(Icons.Default.ArrowBack, "Back") }
                Text(
                    rel.name.ifBlank { rel.tagName },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { reload(); detail = null }) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${rel.tagName}${if (rel.prerelease) " · pre-release" else ""}${if (rel.draft) " · draft" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            rel.publishedAt?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            if (detailLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (!rel.body.isNullOrBlank()) {
                        Text(rel.body!!, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                    }
                    if (rel.assets.isNotEmpty()) {
                        Text("Assets", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        rel.assets.forEach { asset ->
                            var downloading by remember(asset.id) { mutableStateOf(false) }
                            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(
                                    Modifier.padding(12.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(asset.name, fontWeight = FontWeight.Medium)
                                        Text(
                                            formatBytes(asset.size) + " · ${asset.downloadCount} downloads",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    val or = ownerRepo
                                    Button(
                                        onClick = {
                                            if (or == null) {
                                                onMessage("No owner/repo")
                                                return@Button
                                            }
                                            scope.launch {
                                                downloading = true
                                                val destDir = java.io.File(
                                                    System.getProperty("user.home"),
                                                    "Downloads/QuickGit"
                                                )
                                                destDir.mkdirs()
                                                val dest = java.io.File(destDir, asset.name)
                                                val r = withContext(Dispatchers.IO) {
                                                    githubApi.downloadReleaseAsset(
                                                        or.owner, or.repo, asset, dest
                                                    )
                                                }
                                                downloading = false
                                                r.fold(
                                                    { onMessage("Saved to ${it.absolutePath}") },
                                                    { onMessage("Download failed: ${it.message}") }
                                                )
                                            }
                                        },
                                        enabled = !downloading && or != null
                                    ) {
                                        if (downloading) {
                                            CircularProgressIndicator(
                                                Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        } else {
                                            Text("Download")
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text("No assets", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Releases", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { reload() }, enabled = !loading) {
                Icon(Icons.Default.Refresh, "Refresh")
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            releases.isEmpty() -> Text(
                "No releases",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(releases, key = { it.id }) { rel ->
                    Card(
                        Modifier.fillMaxWidth().clickable { openDetail(rel) }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                rel.name.ifBlank { rel.tagName },
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    rel.tagName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (rel.prerelease) {
                                    Text(
                                        "pre-release",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                if (rel.draft) {
                                    Text(
                                        "draft",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                rel.publishedAt?.let {
                                    Text(
                                        it.take(10),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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
fun CloneScreen(
    repoManager: DesktopRepoManager,
    credentialStore: DesktopCredentialStore,
    githubApi: GitHubApi,
    onCloned: (String) -> Unit,
    onMessage: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val gerrit = remember { com.quickgit.desktop.data.GerritAccountManager(credentialStore) }
    var showGerrit by remember { mutableStateOf(false) }
    var gerritProjects by remember { mutableStateOf<List<com.quickgit.desktop.data.models.GerritProject>>(emptyList()) }
    var gerritLoading by remember { mutableStateOf(false) }
    var gerritError by remember { mutableStateOf<String?>(null) }
    val gerritHost = gerrit.host
    val gerritConnected = gerrit.isConnected()

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Clone a repository", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Repository URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://github.com/… or https://gerrit…/a/project") }
        )
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Folder name (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("Clones into: ${repoManager.getReposRoot().absolutePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (!gerrit.isConnected()) {
                        onMessage("Connect a Gerrit host under Credentials first")
                        return@OutlinedButton
                    }
                    showGerrit = true
                    scope.launch {
                        gerritLoading = true
                        gerritError = null
                        val r = withContext(Dispatchers.IO) { gerrit.listProjects() }
                        gerritLoading = false
                        r.fold(
                            onSuccess = { gerritProjects = it },
                            onFailure = { gerritError = it.message; gerritProjects = emptyList() }
                        )
                    }
                }
            ) {
                Text(
                    if (gerritConnected) "Browse Gerrit projects ($gerritHost)"
                    else "Browse Gerrit projects"
                )
            }
        }
        Text(
            "Gerrit HTTPS clones use https://host/a/project/path. Credentials saved under Creds are applied automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (progress != null) {
            Text(progress!!)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Button(
            onClick = {
                if (url.isNotBlank()) scope.launch {
                    busy = true
                    progress = "Starting…"
                    val result = withContext(Dispatchers.IO) {
                        repoManager.cloneRepo(url, name.ifBlank { null }) { p ->
                            progress = "${p.task} ${if (p.total > 0) "${p.completed}/${p.total}" else ""}"
                        }
                    }
                    busy = false
                    progress = null
                    result.fold(onSuccess = { onCloned(it.absolutePath) }, onFailure = { onMessage("Clone failed: ${it.message}") })
                }
            },
            enabled = url.isNotBlank() && !busy,
            modifier = Modifier.align(Alignment.End)
        ) { Text(if (busy) "Cloning…" else "Clone") }
        Text("Tip: use Browse for GitHub, or Gerrit projects after connecting under Credentials.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (showGerrit) {
        AlertDialog(
            onDismissRequest = { showGerrit = false },
            title = { Text("Gerrit projects") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    when {
                        gerritLoading -> CircularProgressIndicator()
                        gerritError != null -> Text(gerritError!!, color = MaterialTheme.colorScheme.error)
                        gerritProjects.isEmpty() -> Text("No projects found.")
                        else -> gerritProjects.forEach { project ->
                            TextButton(
                                onClick = {
                                    gerrit.cloneUrl(project.name)?.let { cloneUrl ->
                                        url = cloneUrl
                                        if (name.isBlank()) name = project.name.substringAfterLast('/')
                                    }
                                    showGerrit = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(project.name)
                                    project.description?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showGerrit = false }) { Text("Close") }
            }
        )
    }
}

private enum class BrowseDesktopTab { GITHUB, GITLAB, GERRIT }

@Composable
fun BrowseAccountScreen(
    credentialStore: DesktopCredentialStore,
    githubApi: GitHubApi,
    repoManager: DesktopRepoManager,
    onCloned: (String) -> Unit,
    onMessage: (String) -> Unit
) {
    val gerritMgr = remember { GerritAccountManager(credentialStore) }
    val githubToken = credentialStore.getGithubToken() ?: credentialStore.getHttpsToken("github.com")
    val githubConnected = !githubToken.isNullOrBlank()
    // Use preferred host from Credentials (self-hosted or gitlab.com). Fallback gitlab.com.
    val gitlabHost = remember {
        val preferred = credentialStore.getPreferredGitlabHost()
        when {
            !preferred.isNullOrBlank() -> preferred
            !credentialStore.getHttpsToken("gitlab.com").isNullOrBlank() -> "gitlab.com"
            else -> "gitlab.com"
        }
    }
    val gitlabToken = credentialStore.getHttpsToken(gitlabHost)
        ?: credentialStore.getGitlabToken()
    val gitlabConnected = !gitlabToken.isNullOrBlank()
    val gerritHost = gerritMgr.host.takeIf { it.isNotBlank() } ?: credentialStore.getPreferredGerritHost().orEmpty()
    val gerritConnected = gerritHost.isNotBlank() && gerritMgr.isConnected(gerritHost)

    var selectedTab by remember {
        mutableStateOf(
            when {
                githubConnected -> BrowseDesktopTab.GITHUB
                gitlabConnected -> BrowseDesktopTab.GITLAB
                gerritConnected -> BrowseDesktopTab.GERRIT
                else -> BrowseDesktopTab.GITHUB
            }
        )
    }
    var query by remember { mutableStateOf("") }
    var cloning by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // ---- GitHub state ----
    var ghRepos by remember { mutableStateOf<List<GitHubRemoteRepo>>(emptyList()) }
    var orgs by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedOrg by remember { mutableStateOf<String?>(null) }
    var ghLoading by remember { mutableStateOf(false) }
    var ghLoadingMore by remember { mutableStateOf(false) }
    var ghPage by remember { mutableStateOf(1) }
    var ghHasMore by remember { mutableStateOf(false) }
    var ghLoaded by remember { mutableStateOf(false) }
    val orgScroll = rememberScrollState()
    val pageSize = 100

    // ---- GitLab state ----
    var glProjects by remember { mutableStateOf<List<GitLabProject>>(emptyList()) }
    var glLoading by remember { mutableStateOf(false) }
    var glLoadingMore by remember { mutableStateOf(false) }
    var glPage by remember { mutableStateOf(1) }
    var glHasMore by remember { mutableStateOf(false) }
    var glLoaded by remember { mutableStateOf(false) }

    // ---- Gerrit state ----
    var grProjects by remember { mutableStateOf<List<GerritProject>>(emptyList()) }
    var grLoading by remember { mutableStateOf(false) }
    var grLoadingMore by remember { mutableStateOf(false) }
    var grLoaded by remember { mutableStateOf(false) }
    var grHasMore by remember { mutableStateOf(false) }
    var grStart by remember { mutableStateOf(0) }

    fun loadGitHubRepos(org: String?, reset: Boolean = true) {
        if (!githubConnected) return
        scope.launch {
            if (reset) {
                ghLoading = true
                ghPage = 1
            } else {
                ghLoadingMore = true
            }
            val page = if (reset) 1 else ghPage + 1
            val result = withContext(Dispatchers.IO) {
                if (org == null) githubApi.listUserRepos(
                    affiliation = "owner,collaborator,organization_member",
                    perPage = pageSize,
                    page = page
                )
                else githubApi.listOrgRepos(org, perPage = pageSize, page = page)
            }
            result.fold(
                onSuccess = { batch ->
                    ghRepos = if (reset) batch else ghRepos + batch
                    ghPage = page
                    ghHasMore = batch.size >= pageSize
                    ghLoaded = true
                },
                onFailure = { onMessage("Failed to list GitHub repos: ${it.message}") }
            )
            ghLoading = false
            ghLoadingMore = false
        }
    }

    suspend fun loadAllOrganizations(): List<String> {
        val all = mutableListOf<String>()
        var page = 1
        while (true) {
            val batch = withContext(Dispatchers.IO) {
                githubApi.listUserOrganizations(perPage = 100, page = page).getOrElse { emptyList() }
            }
            if (batch.isEmpty()) break
            all += batch
            if (batch.size < 100) break
            page++
            if (page > 20) break
        }
        return all.distinct().sortedBy { it.lowercase() }
    }

    fun loadGitLabProjects(reset: Boolean = true) {
        if (!gitlabConnected) return
        val token = gitlabToken ?: return
        scope.launch {
            if (reset) {
                glLoading = true
                glPage = 1
            } else {
                glLoadingMore = true
            }
            val page = if (reset) 1 else glPage + 1
            val api = GitLabApi(gitlabHost, token)
            val result = withContext(Dispatchers.IO) {
                if (query.isBlank()) api.listProjects(membership = true, perPage = pageSize, page = page)
                else api.searchProjects(query, perPage = pageSize, page = page)
            }
            result.fold(
                onSuccess = { batch ->
                    glProjects = if (reset) batch else glProjects + batch
                    glPage = page
                    glHasMore = batch.size >= pageSize
                    glLoaded = true
                },
                onFailure = { onMessage("Failed to list GitLab projects: ${it.message}") }
            )
            glLoading = false
            glLoadingMore = false
        }
    }

    fun loadGerritProjects(reset: Boolean = true) {
        if (!gerritConnected) return
        scope.launch {
            if (reset) {
                grLoading = true
                grStart = 0
                grProjects = emptyList()
            } else {
                grLoadingMore = true
            }
            val start = if (reset) 0 else grStart
            val q = if (query.isBlank()) "state:ACTIVE" else "state:ACTIVE $query"
            val result = withContext(Dispatchers.IO) {
                gerritMgr.listProjects(gerritHost, q, limit = pageSize, start = start)
            }
            result.fold(
                onSuccess = { list ->
                    val batch = if (query.isBlank()) list
                    else list.filter {
                        it.name.contains(query, true) ||
                            (it.description?.contains(query, true) == true)
                    }
                    grProjects = if (reset) batch else grProjects + batch
                    grStart = start + list.size
                    grHasMore = list.size >= pageSize
                    grLoaded = true
                },
                onFailure = { onMessage("Failed to list Gerrit projects: ${it.message}") }
            )
            grLoading = false
            grLoadingMore = false
        }
    }

    fun ensureTabLoaded(tab: BrowseDesktopTab) {
        when (tab) {
            BrowseDesktopTab.GITHUB -> if (!ghLoaded && githubConnected) {
                scope.launch {
                    orgs = loadAllOrganizations()
                    loadGitHubRepos(null)
                }
            }
            BrowseDesktopTab.GITLAB -> if (!glLoaded && gitlabConnected) loadGitLabProjects(true)
            BrowseDesktopTab.GERRIT -> if (!grLoaded && gerritConnected) loadGerritProjects(true)
        }
    }

    LaunchedEffect(Unit) {
        if (!githubConnected && !gitlabConnected && !gerritConnected) {
            onMessage("Add GitHub, GitLab, or Gerrit credentials under Credentials first")
            return@LaunchedEffect
        }
        ensureTabLoaded(selectedTab)
    }

    val tabs = listOf(
        BrowseDesktopTab.GITHUB to "GitHub",
        BrowseDesktopTab.GITLAB to "GitLab",
        BrowseDesktopTab.GERRIT to "Gerrit"
    )
    val selectedIndex = tabs.indexOfFirst { it.first == selectedTab }.coerceAtLeast(0)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Browse repositories", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        TabRow(selectedTabIndex = selectedIndex) {
            tabs.forEachIndexed { index, (tab, label) ->
                val connected = when (tab) {
                    BrowseDesktopTab.GITHUB -> githubConnected
                    BrowseDesktopTab.GITLAB -> gitlabConnected
                    BrowseDesktopTab.GERRIT -> gerritConnected
                }
                Tab(
                    selected = selectedIndex == index,
                    onClick = {
                        selectedTab = tab
                        query = ""
                        ensureTabLoaded(tab)
                    },
                    text = { Text(if (connected) label else "$label · off") },
                    enabled = connected || tab == selectedTab
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // GitHub org chips
        if (selectedTab == BrowseDesktopTab.GITHUB && githubConnected) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val canScrollLeft = orgScroll.value > 0
                val canScrollRight = orgScroll.value < orgScroll.maxValue
                IconButton(
                    onClick = {
                        scope.launch {
                            orgScroll.scrollTo((orgScroll.value - 240).coerceAtLeast(0))
                        }
                    },
                    enabled = canScrollLeft
                ) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Scroll organizations left")
                }
                Row(
                    Modifier.weight(1f).horizontalScroll(orgScroll),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedOrg == null,
                        onClick = { selectedOrg = null; loadGitHubRepos(null) },
                        label = { Text("Personal") }
                    )
                    orgs.forEach { org ->
                        FilterChip(
                            selected = selectedOrg == org,
                            onClick = { selectedOrg = org; loadGitHubRepos(org) },
                            label = { Text(org) }
                        )
                    }
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            orgScroll.scrollTo((orgScroll.value + 240).coerceAtMost(orgScroll.maxValue))
                        }
                    },
                    enabled = canScrollRight
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Scroll organizations right")
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                when (selectedTab) {
                    BrowseDesktopTab.GITHUB -> { /* client-side filter */ }
                    BrowseDesktopTab.GITLAB -> {
                        glLoaded = false
                        loadGitLabProjects(true)
                    }
                    BrowseDesktopTab.GERRIT -> {
                        grLoaded = false
                        loadGerritProjects(true)
                    }
                }
            },
            label = {
                Text(
                    when (selectedTab) {
                        BrowseDesktopTab.GITHUB -> "Filter repositories"
                        BrowseDesktopTab.GITLAB -> "Search GitLab projects"
                        BrowseDesktopTab.GERRIT -> "Search Gerrit projects"
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        when (selectedTab) {
            BrowseDesktopTab.GITHUB -> {
                if (!githubConnected) {
                    Text(
                        "Add a GitHub token in Credentials to browse your repos.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        if (ghLoading) "Loading…" else "${ghRepos.size} repositories" +
                            if (query.isNotBlank()) " (filtered)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    if (ghLoading && ghRepos.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val filtered = ghRepos.filter {
                            query.isBlank() || it.fullName.contains(query, true) || it.name.contains(query, true)
                        }
                        LazyColumn(
                            Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filtered, key = { it.id }) { repo ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.padding(12.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(repo.fullName, fontWeight = FontWeight.Medium)
                                            repo.description?.let {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                buildString {
                                                    if (repo.isPrivate) append("private · ")
                                                    append(repo.defaultBranch ?: "main")
                                                    repo.language?.let { append(" · $it") }
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                val cloneUrl = repo.cloneUrl
                                                    ?: "https://github.com/${repo.fullName}.git"
                                                scope.launch {
                                                    cloning = repo.fullName
                                                    val result = withContext(Dispatchers.IO) {
                                                        repoManager.cloneRepo(cloneUrl, repo.name)
                                                    }
                                                    cloning = null
                                                    result.fold(
                                                        onSuccess = { onCloned(it.absolutePath) },
                                                        onFailure = { onMessage("Clone failed: ${it.message}") }
                                                    )
                                                }
                                            },
                                            enabled = cloning == null
                                        ) {
                                            if (cloning == repo.fullName) {
                                                CircularProgressIndicator(
                                                    Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            } else {
                                                Text("Clone")
                                            }
                                        }
                                    }
                                }
                            }
                            if (ghHasMore && query.isBlank()) {
                                item {
                                    Box(
                                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (ghLoadingMore) {
                                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                        } else {
                                            OutlinedButton(
                                                onClick = { loadGitHubRepos(selectedOrg, reset = false) },
                                                enabled = !ghLoadingMore
                                            ) { Text("Load more") }
                                        }
                                    }
                                }
                            }
                            if (filtered.isEmpty() && !ghLoading) {
                                item {
                                    Text(
                                        if (query.isNotBlank()) "No repositories match \"$query\""
                                        else "No repositories found",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            BrowseDesktopTab.GITLAB -> {
                if (!gitlabConnected) {
                    Text(
                        "Add a GitLab host + personal access token in Credentials to browse projects.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        if (glLoading) "Loading…" else "${glProjects.size} projects · $gitlabHost",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    if (glLoading && glProjects.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(glProjects, key = { it.id }) { project ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.padding(12.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(project.pathWithNamespace, fontWeight = FontWeight.Medium)
                                            project.description?.let {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                buildString {
                                                    if (project.isPrivate) append("private · ")
                                                    append(project.defaultBranch)
                                                    if (project.isFork) append(" · fork")
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                val cloneUrl = project.httpUrlToRepo
                                                val folder = project.pathWithNamespace.substringAfterLast('/')
                                                scope.launch {
                                                    cloning = "gl:${project.id}"
                                                    val result = withContext(Dispatchers.IO) {
                                                        repoManager.cloneRepo(cloneUrl, folder)
                                                    }
                                                    cloning = null
                                                    result.fold(
                                                        onSuccess = { onCloned(it.absolutePath) },
                                                        onFailure = { onMessage("Clone failed: ${it.message}") }
                                                    )
                                                }
                                            },
                                            enabled = cloning == null
                                        ) {
                                            if (cloning == "gl:${project.id}") {
                                                CircularProgressIndicator(
                                                    Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            } else {
                                                Text("Clone")
                                            }
                                        }
                                    }
                                }
                            }
                            if (glHasMore) {
                                item {
                                    Box(
                                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (glLoadingMore) {
                                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                        } else {
                                            OutlinedButton(
                                                onClick = { loadGitLabProjects(reset = false) },
                                                enabled = !glLoadingMore
                                            ) { Text("Load more") }
                                        }
                                    }
                                }
                            }
                            if (glProjects.isEmpty() && !glLoading) {
                                item {
                                    Text(
                                        if (query.isNotBlank()) "No projects match \"$query\""
                                        else "No projects found",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            BrowseDesktopTab.GERRIT -> {
                if (!gerritConnected) {
                    Text(
                        "Connect a Gerrit host under Credentials to browse and clone projects.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        if (grLoading) "Loading…" else "${grProjects.size} projects · $gerritHost",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    if (grLoading && grProjects.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(grProjects, key = { it.id }) { project ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.padding(12.dp).fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(project.name, fontWeight = FontWeight.Medium)
                                            project.description?.let {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            project.state?.let {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Button(
                                            onClick = {
                                                val cloneUrl = gerritMgr.cloneUrl(project.name, gerritHost)
                                                if (cloneUrl == null) {
                                                    onMessage("Could not build Gerrit clone URL")
                                                    return@Button
                                                }
                                                val folder = project.name.substringAfterLast('/')
                                                    .ifBlank { project.name }
                                                scope.launch {
                                                    cloning = "gr:${project.id}"
                                                    val result = withContext(Dispatchers.IO) {
                                                        repoManager.cloneRepo(cloneUrl, folder)
                                                    }
                                                    cloning = null
                                                    result.fold(
                                                        onSuccess = { onCloned(it.absolutePath) },
                                                        onFailure = { onMessage("Clone failed: ${it.message}") }
                                                    )
                                                }
                                            },
                                            enabled = cloning == null
                                        ) {
                                            if (cloning == "gr:${project.id}") {
                                                CircularProgressIndicator(
                                                    Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            } else {
                                                Text("Clone")
                                            }
                                        }
                                    }
                                }
                            }
                            if (grProjects.isEmpty() && !grLoading) {
                                item {
                                    Text(
                                        if (query.isNotBlank()) "No projects match \"$query\""
                                        else "No projects found",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                            if (grHasMore) {
                                item {
                                    Box(
                                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (grLoadingMore) {
                                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                        } else {
                                            OutlinedButton(
                                                onClick = { loadGerritProjects(reset = false) },
                                                enabled = !grLoadingMore && !grLoading
                                            ) { Text("Load more") }
                                        }
                                    }
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
private fun RemoteRepoCard(
    repo: GitHubRemoteRepo,
    busy: Boolean,
    onClone: () -> Unit,
    onFork: () -> Unit,
    onBrowse: () -> Unit,
    showFork: Boolean = true
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(repo.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (repo.isPrivate) {
                    Text("private", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                }
                if (repo.isFork) {
                    Text("fork", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            repo.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                buildString {
                    repo.language?.let { append(it); append(" · ") }
                    append(repo.fullName)
                    append(" · ")
                    append(repo.defaultBranch)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onClone, enabled = !busy) { Text("Clone") }
                if (showFork) {
                    OutlinedButton(onClick = onFork, enabled = !busy) { Text("Fork") }
                }
                TextButton(onClick = onBrowse, enabled = !busy) { Text("Code") }
            }
        }
    }
}

/** Browse a remote GitHub repo: folders, files, syntax-coloured code reader. */
@Composable
fun RemoteCodeReaderDialog(
    owner: String,
    repo: String,
    defaultBranch: String,
    githubApi: GitHubApi,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit
) {
    var path by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf(defaultBranch.ifBlank { "main" }) }
    var entries by remember { mutableStateOf<List<GitHubApi.RemoteEntry>>(emptyList()) }
    var fileContent by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun loadDir(p: String) {
        scope.launch {
            loading = true
            fileContent = null
            fileName = null
            val r = withContext(Dispatchers.IO) { githubApi.getRepoContents(owner, repo, p, ref) }
            r.fold(
                onSuccess = {
                    entries = it.sortedWith(compareBy<GitHubApi.RemoteEntry> { e -> e.type != "dir" }.thenBy { e -> e.name.lowercase() })
                },
                onFailure = {
                    entries = emptyList()
                    onMessage("Browse failed: ${it.message}")
                }
            )
            loading = false
        }
    }

    fun openFile(entry: GitHubApi.RemoteEntry) {
        scope.launch {
            loading = true
            val r = withContext(Dispatchers.IO) {
                githubApi.getFileContent(owner, repo, entry.path, ref)
            }
            r.fold(
                onSuccess = {
                    fileContent = it
                    fileName = entry.name
                },
                onFailure = { onMessage("Read failed: ${it.message}") }
            )
            loading = false
        }
    }

    LaunchedEffect(owner, repo, ref) { loadDir("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("$owner/$repo", fontWeight = FontWeight.SemiBold)
                Text(
                    if (fileName != null) "${if (path.isBlank()) "" else "$path/"}$fileName"
                    else if (path.isBlank()) "/" else "/$path",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 520.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            when {
                                fileContent != null -> {
                                    fileContent = null
                                    fileName = null
                                }
                                path.isNotBlank() -> {
                                    path = path.substringBeforeLast('/', missingDelimiterValue = "")
                                    loadDir(path)
                                }
                            }
                        },
                        enabled = fileContent != null || path.isNotBlank()
                    ) { Text("…", fontWeight = FontWeight.Bold) }
                    OutlinedTextField(
                        value = ref,
                        onValueChange = { ref = it },
                        label = { Text("Branch / ref") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { loadDir(path) }) { Text("Go") }
                }
                Spacer(Modifier.height(8.dp))
                if (loading) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (fileContent != null && fileName != null) {
                    val darkUi = androidx.compose.foundation.isSystemInDarkTheme()
                    val highlighted = remember(fileContent, fileName, darkUi) {
                        highlightSourceCode(fileContent!!, fileName!!, dark = darkUi)
                    }
                    SelectionContainer(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(if (darkUi) Color(0xFF111318) else Color(0xFFF8F9FF))
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .horizontalScroll(rememberScrollState())
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = highlighted,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                softWrap = false
                            )
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(entries, key = { it.path }) { entry ->
                            val isDir = entry.type == "dir"
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isDir) {
                                            path = entry.path
                                            loadDir(entry.path)
                                        } else {
                                            openFile(entry)
                                        }
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isDir) Icons.Default.Folder else Icons.Default.Description,
                                    null,
                                    tint = if (isDir) MaterialTheme.colorScheme.primary else languageColorFor(entry.name),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    entry.name,
                                    color = if (isDir) MaterialTheme.colorScheme.onSurface else languageColorFor(entry.name)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private enum class ProfileProviderTab { GITHUB, GITLAB }

private data class ProfileHeader(
    val login: String,
    val name: String?,
    val bio: String? = null,
    val publicRepos: Int = 0,
    val followers: Int = 0,
    val following: Int = 0,
    val webUrl: String = ""
)

private fun GitLabProject.toProfileRemoteRepo(): GitHubRemoteRepo = GitHubRemoteRepo(
    id = id,
    name = name,
    fullName = pathWithNamespace,
    description = description,
    htmlUrl = webUrl,
    cloneUrl = httpUrlToRepo,
    sshUrl = sshUrlToRepo,
    isPrivate = isPrivate,
    isFork = isFork,
    ownerLogin = pathWithNamespace.substringBeforeLast('/', pathWithNamespace),
    defaultBranch = defaultBranch,
    updatedAt = updatedAt,
    language = null
)

@Composable
fun ProfileScreen(
    credentialStore: DesktopCredentialStore,
    githubApi: GitHubApi,
    repoManager: DesktopRepoManager,
    onCloned: (String) -> Unit,
    onMessage: (String) -> Unit
) {
    val githubToken = credentialStore.getGithubToken() ?: credentialStore.getHttpsToken("github.com")
    val githubConnected = !githubToken.isNullOrBlank()
    val gitlabHost = remember {
        credentialStore.getPreferredGitlabHost()
            ?: "gitlab.com".takeIf { !credentialStore.getHttpsToken("gitlab.com").isNullOrBlank() }
            ?: "gitlab.com"
    }
    val gitlabToken = credentialStore.getHttpsToken(gitlabHost)
        ?: credentialStore.getGitlabToken()
    val gitlabConnected = !gitlabToken.isNullOrBlank()

    var selectedTab by remember {
        mutableStateOf(
            when {
                githubConnected -> ProfileProviderTab.GITHUB
                gitlabConnected -> ProfileProviderTab.GITLAB
                else -> ProfileProviderTab.GITHUB
            }
        )
    }
    var header by remember { mutableStateOf<ProfileHeader?>(null) }
    var repos by remember { mutableStateOf<List<GitHubRemoteRepo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var repoPage by remember { mutableStateOf(1) }
    var hasMoreRepos by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var browseRepo by remember { mutableStateOf<GitHubRemoteRepo?>(null) }
    val scope = rememberCoroutineScope()
    val pageSize = 100

    fun loadGitHub(reset: Boolean = true) {
        if (!githubConnected) {
            error = "No GitHub token — add one in Credentials"
            loading = false
            return
        }
        scope.launch {
            if (reset) {
                loading = true
                error = null
                header = null
                repos = emptyList()
                repoPage = 1
            } else {
                loadingMore = true
            }
            val page = if (reset) 1 else repoPage + 1
            if (reset) {
                val u = withContext(Dispatchers.IO) { githubApi.getAuthenticatedUser() }
                u.fold(
                    onSuccess = {
                        header = ProfileHeader(
                            login = it.login,
                            name = it.name,
                            bio = it.bio,
                            publicRepos = it.publicRepos,
                            followers = it.followers,
                            following = it.following,
                            webUrl = it.htmlUrl
                        )
                    },
                    onFailure = { error = it.message }
                )
            }
            val r = withContext(Dispatchers.IO) {
                githubApi.listUserRepos(
                    affiliation = "owner,collaborator,organization_member",
                    perPage = pageSize,
                    page = page
                )
            }
            r.fold(
                onSuccess = { batch ->
                    repos = if (reset) batch else repos + batch
                    repoPage = page
                    hasMoreRepos = batch.size >= pageSize
                },
                onFailure = { onMessage("GitHub repos: ${it.message}") }
            )
            loading = false
            loadingMore = false
        }
    }

    fun loadGitLab(reset: Boolean = true) {
        if (!gitlabConnected) {
            error = "No GitLab token — add host + PAT in Credentials"
            loading = false
            return
        }
        val token = gitlabToken ?: return
        scope.launch {
            if (reset) {
                loading = true
                error = null
                header = null
                repos = emptyList()
                repoPage = 1
            } else {
                loadingMore = true
            }
            val page = if (reset) 1 else repoPage + 1
            val api = GitLabApi(gitlabHost, token)
            if (reset) {
                val u = withContext(Dispatchers.IO) { api.getAuthenticatedUser() }
                u.fold(
                    onSuccess = {
                        header = ProfileHeader(
                            login = it.username,
                            name = it.name,
                            bio = null,
                            publicRepos = 0,
                            webUrl = it.webUrl
                        )
                    },
                    onFailure = { error = it.message }
                )
            }
            val r = withContext(Dispatchers.IO) {
                api.listProjects(membership = true, perPage = pageSize, page = page)
            }
            r.fold(
                onSuccess = { batch ->
                    val mapped = batch.map { it.toProfileRemoteRepo() }
                    repos = if (reset) mapped else repos + mapped
                    repoPage = page
                    hasMoreRepos = batch.size >= pageSize
                    if (reset && header != null) {
                        header = header!!.copy(publicRepos = repos.size)
                    }
                },
                onFailure = { onMessage("GitLab projects: ${it.message}") }
            )
            loading = false
            loadingMore = false
        }
    }

    fun reload(reset: Boolean = true) {
        when (selectedTab) {
            ProfileProviderTab.GITHUB -> loadGitHub(reset)
            ProfileProviderTab.GITLAB -> loadGitLab(reset)
        }
    }

    LaunchedEffect(selectedTab) { reload(reset = true) }

    fun doClone(repo: GitHubRemoteRepo) {
        scope.launch {
            busy = true
            val url = repo.cloneUrl.ifBlank {
                when (selectedTab) {
                    ProfileProviderTab.GITHUB -> "https://github.com/${repo.fullName}.git"
                    ProfileProviderTab.GITLAB -> "https://$gitlabHost/${repo.fullName}.git"
                }
            }
            val result = withContext(Dispatchers.IO) { repoManager.cloneRepo(url, repo.name) { } }
            busy = false
            result.fold(
                onSuccess = { onCloned(it.absolutePath) },
                onFailure = { onMessage("Clone failed: ${it.message}") }
            )
        }
    }

    fun doFork(repo: GitHubRemoteRepo) {
        if (selectedTab != ProfileProviderTab.GITHUB) {
            onMessage("Fork is only available for GitHub")
            return
        }
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                githubApi.forkRepo(repo.ownerLogin, repo.name)
            }
            busy = false
            result.fold(
                onSuccess = {
                    onMessage("Forked to ${it.fullName}")
                    loadGitHub(reset = true)
                },
                onFailure = { onMessage("Fork failed: ${it.message}") }
            )
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            when (selectedTab) {
                ProfileProviderTab.GITHUB -> "GitHub profile"
                ProfileProviderTab.GITLAB -> "GitLab profile"
            },
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(8.dp))

        // Provider switcher when both (or either) are configured
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedTab == ProfileProviderTab.GITHUB,
                onClick = { selectedTab = ProfileProviderTab.GITHUB },
                enabled = githubConnected,
                label = { Text("GitHub") }
            )
            FilterChip(
                selected = selectedTab == ProfileProviderTab.GITLAB,
                onClick = { selectedTab = ProfileProviderTab.GITLAB },
                enabled = gitlabConnected,
                label = { Text("GitLab") }
            )
            if (selectedTab == ProfileProviderTab.GITLAB && gitlabConnected) {
                Text(
                    gitlabHost,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            !githubConnected && !gitlabConnected -> {
                Text(
                    "Add a GitHub or GitLab token in Credentials to view your profile and repositories.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            loading && header == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null && header == null -> {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            header != null -> {
                val u = header!!
                Text(u.name ?: u.login, style = MaterialTheme.typography.headlineSmall)
                Text("@${u.login}", color = MaterialTheme.colorScheme.primary)
                u.bio?.let { Text(it) }
                Text(
                    when (selectedTab) {
                        ProfileProviderTab.GITHUB ->
                            "Repos: ${u.publicRepos} public · Followers: ${u.followers} · Following: ${u.following}"
                        ProfileProviderTab.GITLAB ->
                            "Projects loaded: ${repos.size}" + if (u.webUrl.isNotBlank()) " · ${u.webUrl}" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    when (selectedTab) {
                        ProfileProviderTab.GITHUB -> "Your repositories (${repos.size})"
                        ProfileProviderTab.GITLAB -> "Your projects (${repos.size})"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                if (loading && repos.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                } else if (repos.isEmpty()) {
                    Text(
                        if (selectedTab == ProfileProviderTab.GITLAB) "No projects found"
                        else "No repositories found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(repos, key = { it.id }) { repo ->
                            RemoteRepoCard(
                                repo = repo,
                                busy = busy,
                                showFork = selectedTab == ProfileProviderTab.GITHUB,
                                onClone = { doClone(repo) },
                                onFork = { doFork(repo) },
                                onBrowse = {
                                    if (selectedTab == ProfileProviderTab.GITHUB) {
                                        browseRepo = repo
                                    } else {
                                        onMessage("Open ${repo.htmlUrl} in a browser to view GitLab code")
                                    }
                                }
                            )
                        }
                        if (hasMoreRepos) {
                            item {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (loadingMore) {
                                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else {
                                        OutlinedButton(
                                            onClick = { reload(reset = false) },
                                            enabled = !loadingMore
                                        ) { Text("Load more") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    browseRepo?.let { repo ->
        RemoteCodeReaderDialog(
            owner = repo.ownerLogin,
            repo = repo.name,
            defaultBranch = repo.defaultBranch,
            githubApi = githubApi,
            onDismiss = { browseRepo = null },
            onMessage = onMessage
        )
    }
}

@Composable
fun UserSearchScreen(
    githubApi: GitHubApi,
    repoManager: DesktopRepoManager,
    onCloned: (String) -> Unit,
    onMessage: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GitHubApi.GitHubUserSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var selectedLogin by remember { mutableStateOf<String?>(null) }
    var profile by remember { mutableStateOf<GitHubApi.GitHubUser?>(null) }
    var repos by remember { mutableStateOf<List<GitHubRemoteRepo>>(emptyList()) }
    var profileLoading by remember { mutableStateOf(false) }
    var loadingMoreRepos by remember { mutableStateOf(false) }
    var repoPage by remember { mutableStateOf(1) }
    var hasMoreRepos by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf<String?>(null) }
    var reposError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var browseRepo by remember { mutableStateOf<GitHubRemoteRepo?>(null) }
    val scope = rememberCoroutineScope()
    val pageSize = 100

    fun loadUserRepos(login: String, reset: Boolean = true) {
        scope.launch {
            if (reset) {
                // keep profileLoading for full open; only for load-more use loadingMoreRepos
            } else {
                loadingMoreRepos = true
            }
            val page = if (reset) 1 else repoPage + 1
            val r = withContext(Dispatchers.IO) {
                githubApi.listPublicRepos(login, perPage = pageSize, page = page)
            }
            r.fold(
                onSuccess = { batch ->
                    repos = if (reset) batch else repos + batch
                    repoPage = page
                    hasMoreRepos = batch.size >= pageSize
                    if (batch.isEmpty() && reset) reposError = null
                },
                onFailure = {
                    if (reset) {
                        repos = emptyList()
                        reposError = it.message ?: "Failed to load repositories"
                    }
                    onMessage("Repos: ${it.message}")
                }
            )
            loadingMoreRepos = false
        }
    }

    fun openUser(login: String) {
        val clean = login.trim().trimStart('@')
        if (clean.isEmpty()) return
        selectedLogin = clean
        profile = null
        repos = emptyList()
        profileError = null
        reposError = null
        repoPage = 1
        hasMoreRepos = false
        scope.launch {
            profileLoading = true
            // 1) Profile
            val u = withContext(Dispatchers.IO) { githubApi.getUser(clean) }
            u.fold(
                onSuccess = { profile = it },
                onFailure = {
                    profileError = it.message ?: "Failed to load profile"
                    onMessage("Profile: ${it.message}")
                }
            )
            // 2) Repos (always attempt, even if profile failed)
            val r = withContext(Dispatchers.IO) {
                githubApi.listPublicRepos(clean, perPage = pageSize, page = 1)
            }
            r.fold(
                onSuccess = { batch ->
                    repos = batch
                    repoPage = 1
                    hasMoreRepos = batch.size >= pageSize
                    if (batch.isEmpty()) reposError = null
                },
                onFailure = {
                    repos = emptyList()
                    reposError = it.message ?: "Failed to load repositories"
                    onMessage("Repos: ${it.message}")
                }
            )
            profileLoading = false
        }
    }

    fun doClone(repo: GitHubRemoteRepo) {
        scope.launch {
            busy = true
            val url = repo.cloneUrl.ifBlank { "https://github.com/${repo.fullName}.git" }
            val result = withContext(Dispatchers.IO) { repoManager.cloneRepo(url, repo.name) { } }
            busy = false
            result.fold(
                onSuccess = { onCloned(it.absolutePath) },
                onFailure = { onMessage("Clone failed: ${it.message}") }
            )
        }
    }

    fun doFork(repo: GitHubRemoteRepo) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                githubApi.forkRepo(repo.ownerLogin, repo.name)
            }
            busy = false
            result.fold(
                onSuccess = { onMessage("Forked to ${it.fullName}") },
                onFailure = { onMessage("Fork failed: ${it.message}") }
            )
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (selectedLogin == null) {
            Text("Search GitHub users", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Username or name") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !loading
                )
                Button(
                    onClick = {
                        val q = query.trim()
                        if (q.isBlank()) return@Button
                        // Exact login: open profile directly
                        if (!q.contains(' ') && !q.contains(',')) {
                            // Still run search, but also allow Enter-style direct open via View
                        }
                        scope.launch {
                            loading = true
                            searchError = null
                            results = emptyList()
                            val r = withContext(Dispatchers.IO) { githubApi.searchUsers(q) }
                            r.fold(
                                onSuccess = {
                                    results = it
                                    if (it.isEmpty()) searchError = "No users found for \"$q\""
                                },
                                onFailure = {
                                    searchError = it.message ?: "Search failed"
                                    onMessage("Search failed: ${it.message}")
                                }
                            )
                            loading = false
                        }
                    },
                    enabled = query.isNotBlank() && !loading
                ) { Text(if (loading) "…" else "Search") }
                // Open exact username without waiting for search results
                OutlinedButton(
                    onClick = {
                        val q = query.trim().trimStart('@')
                        if (q.isNotBlank()) openUser(q)
                    },
                    enabled = query.isNotBlank() && !loading
                ) { Text("Open") }
            }
            Spacer(Modifier.height(12.dp))
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                searchError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                if (results.isEmpty() && searchError == null) {
                    Text(
                        "Search for a user, or type an exact username and press Open.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results, key = { it.login }) { u ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(u.login, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        u.type,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                // Explicit button — more reliable on desktop than Card click alone
                                Button(onClick = { openUser(u.login) }) {
                                    Text("View profile")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ---- User profile detail ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    selectedLogin = null
                    profile = null
                    repos = emptyList()
                    profileError = null
                    reposError = null
                }) { Icon(Icons.Default.ArrowBack, "Back") }
                Text(
                    "User · @$selectedLogin",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { openUser(selectedLogin!!) },
                    enabled = !profileLoading
                ) { Text("Refresh") }
            }
            Spacer(Modifier.height(8.dp))
            if (profileLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // Header (fixed height)
                profileError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
                profile?.let { u ->
                    Text(u.name ?: u.login, style = MaterialTheme.typography.headlineSmall)
                    Text("@${u.login}", color = MaterialTheme.colorScheme.primary)
                    u.bio?.let { Text(it) }
                    Text(
                        "Repos: ${u.publicRepos} · Followers: ${u.followers} · Following: ${u.following}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                } ?: run {
                    if (profileError == null) {
                        Text("@$selectedLogin", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                    }
                }

                Text("Public repositories (${repos.size})", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                reposError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { openUser(selectedLogin!!) }) { Text("Retry") }
                    Spacer(Modifier.height(8.dp))
                }
                if (repos.isEmpty() && reposError == null) {
                    Text(
                        "No public repositories for this user.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // weight(1f) so LazyColumn gets a bounded height (was broken before)
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(repos, key = { it.id }) { repo ->
                            RemoteRepoCard(
                                repo = repo,
                                busy = busy,
                                onClone = { doClone(repo) },
                                onFork = { doFork(repo) },
                                onBrowse = { browseRepo = repo }
                            )
                        }
                        if (hasMoreRepos) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                    if (loadingMoreRepos) {
                                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else {
                                        OutlinedButton(
                                            onClick = { loadUserRepos(selectedLogin!!, reset = false) },
                                            enabled = !loadingMoreRepos
                                        ) { Text("Load more") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    browseRepo?.let { repo ->
        RemoteCodeReaderDialog(
            owner = repo.ownerLogin,
            repo = repo.name,
            defaultBranch = repo.defaultBranch,
            githubApi = githubApi,
            onDismiss = { browseRepo = null },
            onMessage = onMessage
        )
    }
}

@Composable
fun SettingsScreen(
    repoManager: DesktopRepoManager,
    credentialStore: DesktopCredentialStore,
    updateManager: DesktopAppUpdateManager,
    onMessage: (String) -> Unit
) {
    var reposRoot by remember { mutableStateOf(repoManager.getReposRoot().absolutePath) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Repositories root", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = reposRoot, onValueChange = { reposRoot = it }, label = { Text("Path") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { repoManager.setReposRoot(reposRoot); onMessage("Root updated") }) { Text("Save root") }

        HorizontalDivider()
        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Text("Current version: ${DesktopAppUpdateConfig.currentVersionName}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    checkingUpdate = true
                    updateStatus = null
                    updateManager.clearSuppression()
                    val result = withContext(Dispatchers.IO) { updateManager.checkForUpdate() }
                    checkingUpdate = false
                    when (result) {
                        is DesktopUpdateCheckResult.UpToDate ->
                            updateStatus = "Up to date (${result.current.versionName})"
                        is DesktopUpdateCheckResult.Available ->
                            updateStatus =
                                "QuickGit ${result.latest.versionName} is available — open Releases to download."
                        is DesktopUpdateCheckResult.Error ->
                            updateStatus = "Check failed: ${result.message}"
                    }
                }
            }, enabled = !checkingUpdate) { Text(if (checkingUpdate) "Checking…" else "Check for updates") }
            TextButton(onClick = { updateManager.openReleasePage() }) { Text("Open releases") }
        }
        updateStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        HorizontalDivider()
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Text("Dark mode is always on for the desktop app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CredentialsScreen(
    repoManager: DesktopRepoManager,
    credentialStore: DesktopCredentialStore,
    onMessage: (String) -> Unit
) {
    var authorName by remember { mutableStateOf(repoManager.getCommitAuthorName()) }
    var authorEmail by remember { mutableStateOf(repoManager.getCommitAuthorEmail()) }
    var githubToken by remember { mutableStateOf(credentialStore.getGithubToken() ?: "") }
    var githubUsername by remember { mutableStateOf(credentialStore.getHttpsUsername("github.com") ?: "") }
    var httpsHost by remember { mutableStateOf("github.com") }
    var httpsUsername by remember { mutableStateOf(credentialStore.getHttpsUsername("github.com") ?: "") }
    var httpsToken by remember { mutableStateOf(credentialStore.getHttpsToken("github.com") ?: "") }
    var gitlabHost by remember {
        mutableStateOf(credentialStore.getPreferredGitlabHost() ?: "gitlab.com")
    }
    var gitlabToken by remember {
        val h = credentialStore.getPreferredGitlabHost() ?: "gitlab.com"
        mutableStateOf(credentialStore.getHttpsToken(h) ?: credentialStore.getGitlabToken() ?: "")
    }
    var gitlabUsername by remember {
        val h = credentialStore.getPreferredGitlabHost() ?: "gitlab.com"
        mutableStateOf(credentialStore.getHttpsUsername(h) ?: "")
    }
    var sshKey by remember { mutableStateOf(credentialStore.getSshPrivateKey() ?: "") }
    var sshPass by remember { mutableStateOf(credentialStore.getSshPassphrase() ?: "") }
    var gpgSign by remember { mutableStateOf(repoManager.isGpgSigningEnabled()) }
    var gpgKey by remember { mutableStateOf(credentialStore.getGpgSecretKey() ?: "") }
    var gpgPass by remember { mutableStateOf(credentialStore.getGpgPassphrase() ?: "") }

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Commit identity", style = MaterialTheme.typography.titleMedium)
        Text(
            "Used as the author name and email on local commits.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = authorName,
            onValueChange = { authorName = it },
            label = { Text("Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = authorEmail,
            onValueChange = { authorEmail = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(onClick = {
            repoManager.setCommitAuthor(authorName, authorEmail)
            onMessage("Author saved")
        }) { Text("Save author") }

        HorizontalDivider()
        Text("Signing", style = MaterialTheme.typography.titleMedium)
        Text(
            "GPG commit signing uses the secret key and passphrase stored here. " +
                "The author email should match a UID on the key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = gpgSign,
                onCheckedChange = {
                    gpgSign = it
                    repoManager.setGpgSigningEnabled(it)
                }
            )
            Text("Sign commits with GPG")
        }
        OutlinedTextField(
            value = gpgKey,
            onValueChange = { gpgKey = it },
            label = { Text("GPG secret key (ASCII-armored)") },
            modifier = Modifier.fillMaxWidth().height(140.dp),
            maxLines = 8
        )
        OutlinedTextField(
            value = gpgPass,
            onValueChange = { gpgPass = it },
            label = { Text("GPG passphrase") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(onClick = {
            credentialStore.setGpgSecretKey(gpgKey.ifBlank { null })
            credentialStore.setGpgPassphrase(gpgPass.ifBlank { null })
            repoManager.setGpgSigningEnabled(gpgSign)
            onMessage("GPG signing settings saved")
        }) { Text("Save GPG signing") }

        HorizontalDivider()
        Text("GitHub account", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = githubUsername, onValueChange = { githubUsername = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = githubToken, onValueChange = { githubToken = it }, label = { Text("Personal access token") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = {
            credentialStore.setGithubToken(githubToken.ifBlank { null })
            if (githubUsername.isNotBlank()) credentialStore.setHttpsUsername("github.com", githubUsername)
            if (githubToken.isNotBlank()) credentialStore.setHttpsToken("github.com", githubToken)
            onMessage("GitHub credentials saved")
        }) { Text("Save GitHub credentials") }

        HorizontalDivider()
        Text("GitLab", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = gitlabHost, onValueChange = { gitlabHost = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = gitlabUsername, onValueChange = { gitlabUsername = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = gitlabToken, onValueChange = { gitlabToken = it }, label = { Text("Personal access token") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = {
            val host = gitlabHost.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
                .ifBlank { "gitlab.com" }
            credentialStore.setPreferredGitlabHost(host)
            credentialStore.setHttpsUsername(host, gitlabUsername.ifBlank { null })
            credentialStore.setHttpsToken(host, gitlabToken.ifBlank { null })
            credentialStore.setGitlabToken(gitlabToken.ifBlank { null })
            onMessage("GitLab credentials saved for $host")
        }) { Text("Save GitLab credentials") }

        HorizontalDivider()
        Text("Gerrit", style = MaterialTheme.typography.titleMedium)
        Text(
            "Use your Gerrit username and HTTP password (Settings → HTTP Credentials on the Gerrit web UI).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var gerritHost by remember {
            mutableStateOf(credentialStore.getPreferredGerritHost().orEmpty())
        }
        var gerritUser by remember {
            mutableStateOf(
                credentialStore.getPreferredGerritHost()?.let { credentialStore.getHttpsUsername(it) }.orEmpty()
            )
        }
        var gerritPassword by remember { mutableStateOf("") }
        var gerritBusy by remember { mutableStateOf(false) }
        val gerritScope = rememberCoroutineScope()
        OutlinedTextField(
            value = gerritHost,
            onValueChange = { gerritHost = it },
            label = { Text("Host") },
            placeholder = { Text("gerrit.example.com") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = gerritUser,
            onValueChange = { gerritUser = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = gerritPassword,
            onValueChange = { gerritPassword = it },
            label = { Text("HTTP password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    gerritScope.launch {
                        gerritBusy = true
                        val mgr = com.quickgit.desktop.data.GerritAccountManager(credentialStore)
                        val r = withContext(Dispatchers.IO) {
                            mgr.connect(gerritHost, gerritUser, gerritPassword)
                        }
                        gerritBusy = false
                        r.fold(
                            onSuccess = {
                                gerritPassword = ""
                                onMessage("Connected to Gerrit as ${it.username} on ${it.host}")
                            },
                            onFailure = { onMessage("Gerrit connect failed: ${it.message}") }
                        )
                    }
                },
                enabled = gerritHost.isNotBlank() && gerritUser.isNotBlank() &&
                    gerritPassword.isNotBlank() && !gerritBusy
            ) { Text(if (gerritBusy) "Connecting…" else "Connect Gerrit") }
            OutlinedButton(
                onClick = {
                    val mgr = com.quickgit.desktop.data.GerritAccountManager(credentialStore)
                    val h = gerritHost.ifBlank { mgr.host }
                    mgr.disconnect(h)
                    gerritPassword = ""
                    onMessage("Disconnected Gerrit")
                }
            ) { Text("Disconnect") }
        }

        HorizontalDivider()
        Text("Per-host HTTPS", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = httpsHost, onValueChange = {
            httpsHost = it
            httpsUsername = credentialStore.getHttpsUsername(it) ?: ""
            httpsToken = credentialStore.getHttpsToken(it) ?: ""
        }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = httpsUsername, onValueChange = { httpsUsername = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = httpsToken, onValueChange = { httpsToken = it }, label = { Text("Token / password") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = {
            credentialStore.saveHttpsCredential(httpsHost, httpsUsername, httpsToken)
            onMessage("Saved credentials for $httpsHost")
        }) { Text("Save host credentials") }

        HorizontalDivider()
        Text("SSH private key", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = sshKey, onValueChange = { sshKey = it }, label = { Text("Private key") }, modifier = Modifier.fillMaxWidth().height(140.dp), maxLines = 8)
        OutlinedTextField(value = sshPass, onValueChange = { sshPass = it }, label = { Text("Passphrase") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(onClick = {
            credentialStore.setSshPrivateKey(sshKey.ifBlank { null })
            credentialStore.setSshPassphrase(sshPass.ifBlank { null })
            onMessage("SSH key saved")
        }) { Text("Save SSH key") }

        HorizontalDivider()
        TextButton(onClick = {
            credentialStore.clearAll()
            githubToken = ""; githubUsername = ""; httpsToken = ""; httpsUsername = ""
            gitlabToken = ""; gitlabUsername = ""; sshKey = ""; sshPass = ""
            gpgKey = ""; gpgPass = ""; gpgSign = false
            onMessage("All credentials cleared")
        }) { Text("Clear all credentials") }
    }
}


@Composable
fun LogsScreen(onMessage: (String) -> Unit) {
    val entries by AppLog.entries.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }

    val filtered = remember(entries, filter) {
        if (filter.isBlank()) entries
        else entries.filter {
            it.message.contains(filter, ignoreCase = true) ||
                it.tag.contains(filter, ignoreCase = true) ||
                it.level.name.contains(filter, ignoreCase = true)
        }
    }

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.animateScrollToItem(filtered.lastIndex)
        }
    }

    fun copyToClipboard() {
        try {
            val text = if (filter.isBlank()) AppLog.dumpText()
            else filtered.joinToString("\n") { it.formattedLine }
            val clip = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clip.setContents(java.awt.datatransfer.StringSelection(text), null)
            onMessage("Copied ${filtered.size} log lines")
        } catch (e: Exception) {
            onMessage("Copy failed: ${e.message}")
        }
    }

    fun saveToFile() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val chooser = javax.swing.JFileChooser().apply {
                    dialogTitle = "Save QuickGit logs"
                    selectedFile = java.io.File(
                        System.getProperty("user.home"),
                        "quickgit-logs-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())}.txt"
                    )
                }
                val res = chooser.showSaveDialog(null)
                if (res != javax.swing.JFileChooser.APPROVE_OPTION) {
                    return@withContext null
                }
                val file = chooser.selectedFile
                AppLog.saveToFile(file)
            }
            when {
                result == null -> { /* cancelled */ }
                result.isSuccess -> onMessage("Saved to ${result.getOrNull()?.absolutePath}")
                else -> onMessage("Save failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Logs", style = MaterialTheme.typography.titleLarge)
            Text(
                "${entries.size} entries · kept until cleared",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                label = { Text("Filter") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("tag, level, or text") }
            )
            FilterChip(
                selected = autoScroll,
                onClick = { autoScroll = !autoScroll },
                label = { Text("Auto-scroll") }
            )
            OutlinedButton(onClick = { copyToClipboard() }) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy")
            }
            OutlinedButton(onClick = { saveToFile() }) {
                Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Save")
            }
            OutlinedButton(onClick = {
                AppLog.clear()
                onMessage("Logs cleared")
            }) {
                Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Also written to ~/.config/quickgit/app.log",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxSize(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
        ) {
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (entries.isEmpty()) "No log entries yet — git and network actions will appear here"
                        else "No entries match filter",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        val color = when (entry.level) {
                            LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                            LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                            LogLevel.WARN -> Color(0xFFFFB74D)
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                        }
                        Text(
                            entry.formattedLine,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = color
                        )
                    }
                }
            }
        }
    }
}
