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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quickgit.desktop.data.*
import com.quickgit.desktop.data.github.GitHubApi
import com.quickgit.desktop.data.gitlab.GitLabApi
import com.quickgit.desktop.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

enum class DesktopScreen {
    RepoList, RepoDetail, Clone, BrowseAccount, Profile, UserSearch, Logs, Settings, Credentials
}

@OptIn(ExperimentalMaterial3Api::class)
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
                        onMessage = ::showMessage
                    )
                    DesktopScreen.UserSearch -> UserSearchScreen(
                        githubApi = githubApi(),
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
                        Modifier.fillMaxWidth().clickable { onSelect(repo.path) },
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Row(
                            Modifier.padding(16.dp).fillMaxWidth(),
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
                            }
                        }
                    }
                }
            }
        }
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
    // Changes first, then Files (file manager), Branches, Issues, PRs, History last
    val tabs = listOf("Changes", "Files", "Branches", "Issues", "PRs", "History")
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text(repoPath.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = {
                scope.launch {
                    busy = true
                    val r = withContext(Dispatchers.IO) { repoManager.pull(repoPath) }
                    busy = false
                    r.fold({ onMessage("Pull OK") }, { onMessage("Pull failed: ${it.message}") })
                }
            }, enabled = !busy) { Text("Pull") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    busy = true
                    val r = withContext(Dispatchers.IO) { repoManager.push(repoPath) }
                    busy = false
                    r.fold({ onMessage("Push OK") }, { onMessage("Push failed: ${it.message}") })
                }
            }, enabled = !busy) { Text("Push") }
            if (busy) { Spacer(Modifier.width(8.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
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
            5 -> HistoryTab(repoPath, repoManager, onMessage)
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
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirmCherry by remember { mutableStateOf<DesktopRepoManager.CommitInfo?>(null) }
    var confirmRevert by remember { mutableStateOf<DesktopRepoManager.CommitInfo?>(null) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            loading = true
            commits = withContext(Dispatchers.IO) { repoManager.getHistory(repoPath, 200) }
            loading = false
        }
    }
    LaunchedEffect(repoPath) { reload() }

    fun doCherryPick(c: DesktopRepoManager.CommitInfo) {
        scope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { repoManager.cherryPick(repoPath, c.id) }
            busy = false
            confirmCherry = null
            r.fold(
                { reload(); onMessage("Cherry-picked ${c.shortId}") },
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
            "Cherry-pick applies a commit onto the current branch. Revert creates an inverse commit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
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
    var newBranchName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun reload() {
        scope.launch {
            loading = true
            branches = withContext(Dispatchers.IO) { repoManager.listBranches(repoPath) }
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

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newBranchName,
                onValueChange = { newBranchName = it },
                label = { Text("New local branch") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(
                onClick = {
                    val name = newBranchName.trim()
                    if (name.isNotBlank()) scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            repoManager.createBranch(repoPath, name, checkout = true)
                        }
                        r.fold(
                            { newBranchName = ""; reload(); onMessage("Created & checked out $name") },
                            { onMessage("Failed: ${it.message}") }
                        )
                    }
                },
                enabled = newBranchName.isNotBlank()
            ) { Text("Create & checkout") }
        }
        Text(
            "Click a local branch to switch, or a remote branch to create a local tracking branch and switch to it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
        Spacer(Modifier.height(8.dp))
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
    var overwriteTarget by remember { mutableStateOf<java.io.File?>(null) }
    var pendingUpload by remember { mutableStateOf<java.io.File?>(null) }
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

    fun copyUpload(src: java.io.File, dest: java.io.File, overwrite: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (dest.exists() && !overwrite) return@withContext
                dest.parentFile?.mkdirs()
                src.copyTo(dest, overwrite = true)
            }
            onMessage(
                if (overwrite && dest.exists()) "Replaced ${dest.name}"
                else "Uploaded ${dest.name}"
            )
            listDir(currentDir)
            val rel = dest.relativeTo(root).path.replace('\\', '/')
            if (isProbablyTextFile(dest.name)) openFile(rel)
        }
    }

    fun pickAndUpload() {
        scope.launch {
            val chosen = withContext(Dispatchers.IO) {
                javax.swing.JFileChooser().apply {
                    dialogTitle = "Upload file into repository"
                    fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
                    isMultiSelectionEnabled = false
                }.let { chooser ->
                    val result = chooser.showOpenDialog(null)
                    if (result == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
                }
            } ?: return@launch

            val destDir = if (currentDir.isBlank()) root else java.io.File(root, currentDir)
            val dest = java.io.File(destDir, chosen.name)
            if (dest.exists()) {
                pendingUpload = chosen
                overwriteTarget = dest
            } else {
                copyUpload(chosen, dest, overwrite = false)
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
                        val langColor = if (entry.isDirectory) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            languageColorFor(entry.name)
                        }
                        val selected = selectedFile == entry.relativePath
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                    else Color.Transparent
                                )
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
                            // Color chip
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(langColor, shape = MaterialTheme.shapes.small)
                            )
                            Spacer(Modifier.width(10.dp))
                            Icon(
                                if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                contentDescription = null,
                                tint = langColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (entry.isDirectory) MaterialTheme.colorScheme.onSurface
                                    else langColor
                                )
                                if (!entry.isDirectory) {
                                    Text(
                                        languageLabelFor(entry.name),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
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

        // ---- Editor pane ----
        Column(Modifier.weight(1f).padding(12.dp)) {
            if (selectedFile == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Open a folder, or select a file to view / edit.\nUse Upload to add a local file into this folder.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                val name = selectedFile!!.substringAfterLast('/')
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(languageColorFor(name), shape = MaterialTheme.shapes.small)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(selectedFile!!, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            languageLabelFor(name),
                            style = MaterialTheme.typography.labelSmall,
                            color = languageColorFor(name)
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    java.io.File(root, selectedFile!!).writeText(content)
                                }
                                original = content
                                onMessage("Saved $selectedFile")
                            }
                        },
                        enabled = content != original && isProbablyTextFile(name)
                    ) { Text("Save") }
                }
                Spacer(Modifier.height(8.dp))
                if (loading) {
                    CircularProgressIndicator()
                } else {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        readOnly = !isProbablyTextFile(name)
                    )
                }
            }
        }
    }

    // Overwrite confirmation
    overwriteTarget?.let { dest ->
        val src = pendingUpload
        AlertDialog(
            onDismissRequest = {
                overwriteTarget = null
                pendingUpload = null
            },
            title = { Text("Replace existing file?") },
            text = {
                Text(
                    "\"${dest.name}\" already exists in this folder.\n\nReplace it with the selected file?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (src != null) copyUpload(src, dest, overwrite = true)
                        overwriteTarget = null
                        pendingUpload = null
                    }
                ) { Text("Replace") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        overwriteTarget = null
                        pendingUpload = null
                    }
                ) { Text("Cancel") }
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

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Clone a repository", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Repository URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("https://github.com/user/repo.git") })
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Folder name (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("Clones into: ${repoManager.getReposRoot().absolutePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text("Tip: use Browse to clone from your GitHub account and organizations.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BrowseAccountScreen(
    credentialStore: DesktopCredentialStore,
    githubApi: GitHubApi,
    repoManager: DesktopRepoManager,
    onCloned: (String) -> Unit,
    onMessage: (String) -> Unit
) {
    var repos by remember { mutableStateOf<List<GitHubRemoteRepo>>(emptyList()) }
    var orgs by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedOrg by remember { mutableStateOf<String?>(null) } // null = personal
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var cloning by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadRepos(org: String?) {
        scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) {
                if (org == null) githubApi.listUserRepos(affiliation = "owner,collaborator,organization_member", perPage = 100)
                else githubApi.listOrgRepos(org, perPage = 100)
            }
            result.fold(onSuccess = { repos = it }, onFailure = { onMessage("Failed to list repos: ${it.message}") })
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        val token = credentialStore.getGithubToken() ?: credentialStore.getHttpsToken("github.com")
        if (token.isNullOrBlank()) {
            onMessage("Add a GitHub token in Credentials first")
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            githubApi.listUserOrganizations().onSuccess { orgs = it }
        }
        loadRepos(null)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Your GitHub repositories", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        // Org chips
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selectedOrg == null, onClick = { selectedOrg = null; loadRepos(null) }, label = { Text("Personal") })
            orgs.forEach { org ->
                FilterChip(selected = selectedOrg == org, onClick = { selectedOrg = org; loadRepos(org) }, label = { Text(org) })
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Filter") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(8.dp))
        if (loading) CircularProgressIndicator()
        else {
            val filtered = repos.filter {
                query.isBlank() || it.fullName.contains(query, true) || it.name.contains(query, true)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered) { repo ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(repo.fullName, fontWeight = FontWeight.Medium)
                                repo.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                                    val cloneUrl = repo.cloneUrl ?: "https://github.com/${repo.fullName}.git"
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
                                if (cloning == repo.fullName) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                else Text("Clone")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(
    credentialStore: DesktopCredentialStore,
    githubApi: GitHubApi,
    onMessage: (String) -> Unit
) {
    var user by remember { mutableStateOf<GitHubApi.GitHubUser?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        val token = credentialStore.getGithubToken() ?: credentialStore.getHttpsToken("github.com")
        if (token.isNullOrBlank()) {
            error = "No GitHub token — add one in Credentials"
            loading = false
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { githubApi.getAuthenticatedUser() }
        result.fold(onSuccess = { user = it }, onFailure = { error = it.message })
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("GitHub profile", style = MaterialTheme.typography.titleLarge)
        when {
            loading -> CircularProgressIndicator()
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            user != null -> {
                val u = user!!
                Text(u.name ?: u.login, style = MaterialTheme.typography.headlineSmall)
                Text("@${u.login}", color = MaterialTheme.colorScheme.primary)
                u.bio?.let { Text(it) }
                Spacer(Modifier.height(8.dp))
                Text("Repos: ${u.publicRepos} public · Followers: ${u.followers} · Following: ${u.following}")
                u.company?.let { Text("Company: $it") }
                u.location?.let { Text("Location: $it") }
                u.email?.let { Text("Email: $it") }
                u.blog?.takeIf { it.isNotBlank() }?.let { Text("Blog: $it") }
            }
        }
    }
}

@Composable
fun UserSearchScreen(githubApi: GitHubApi, onMessage: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GitHubApi.GitHubUserSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Search GitHub users", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Username or name") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(
                onClick = {
                    if (query.isBlank()) return@Button
                    scope.launch {
                        loading = true
                        val r = withContext(Dispatchers.IO) { githubApi.searchUsers(query) }
                        r.fold(onSuccess = { results = it }, onFailure = { onMessage("Search failed: ${it.message}") })
                        loading = false
                    }
                },
                enabled = query.isNotBlank() && !loading
            ) { Text("Search") }
        }
        Spacer(Modifier.height(12.dp))
        if (loading) CircularProgressIndicator()
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results) { u ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(u.login, fontWeight = FontWeight.Medium)
                            Text(u.htmlUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    repoManager: DesktopRepoManager,
    credentialStore: DesktopCredentialStore,
    updateManager: DesktopAppUpdateManager,
    onMessage: (String) -> Unit
) {
    var authorName by remember { mutableStateOf(repoManager.getCommitAuthorName()) }
    var authorEmail by remember { mutableStateOf(repoManager.getCommitAuthorEmail()) }
    var reposRoot by remember { mutableStateOf(repoManager.getReposRoot().absolutePath) }
    var gpgSign by remember { mutableStateOf(repoManager.isGpgSigningEnabled()) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Commit identity", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = authorName, onValueChange = { authorName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = authorEmail, onValueChange = { authorEmail = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { repoManager.setCommitAuthor(authorName, authorEmail); onMessage("Author saved") }) { Text("Save author") }

        HorizontalDivider()
        Text("Repositories root", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = reposRoot, onValueChange = { reposRoot = it }, label = { Text("Path") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { repoManager.setReposRoot(reposRoot); onMessage("Root updated") }) { Text("Save root") }

        HorizontalDivider()
        Text("Signing", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = gpgSign, onCheckedChange = { gpgSign = it; repoManager.setGpgSigningEnabled(it) })
            Text("Sign commits with GPG")
        }

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
                        is DesktopUpdateCheckResult.UpToDate -> updateStatus = "Up to date (${result.current.versionName})"
                        is DesktopUpdateCheckResult.Available -> {
                            updateStatus = "Downloading ${result.latest.versionName}…"
                            val dl = withContext(Dispatchers.IO) { updateManager.downloadUpdate(result.downloadUrl, result.assetName) }
                            dl.fold(
                                onSuccess = { f -> updateStatus = "Saved ${f.absolutePath}"; updateManager.openFile(f) },
                                onFailure = { updateStatus = "Download failed: ${it.message}"; updateManager.openReleasePage() }
                            )
                        }
                        is DesktopUpdateCheckResult.Error -> updateStatus = "Check failed: ${result.message}"
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
fun CredentialsScreen(credentialStore: DesktopCredentialStore, onMessage: (String) -> Unit) {
    var githubToken by remember { mutableStateOf(credentialStore.getGithubToken() ?: "") }
    var githubUsername by remember { mutableStateOf(credentialStore.getHttpsUsername("github.com") ?: "") }
    var httpsHost by remember { mutableStateOf("github.com") }
    var httpsUsername by remember { mutableStateOf(credentialStore.getHttpsUsername("github.com") ?: "") }
    var httpsToken by remember { mutableStateOf(credentialStore.getHttpsToken("github.com") ?: "") }
    var gitlabHost by remember { mutableStateOf("gitlab.com") }
    var gitlabToken by remember { mutableStateOf(credentialStore.getHttpsToken("gitlab.com") ?: "") }
    var gitlabUsername by remember { mutableStateOf(credentialStore.getHttpsUsername("gitlab.com") ?: "") }
    var sshKey by remember { mutableStateOf(credentialStore.getSshPrivateKey() ?: "") }
    var sshPass by remember { mutableStateOf(credentialStore.getSshPassphrase() ?: "") }

    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            credentialStore.setHttpsUsername(gitlabHost, gitlabUsername.ifBlank { null })
            credentialStore.setHttpsToken(gitlabHost, gitlabToken.ifBlank { null })
            credentialStore.setGitlabToken(gitlabToken.ifBlank { null })
            onMessage("GitLab credentials saved for $gitlabHost")
        }) { Text("Save GitLab credentials") }

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
