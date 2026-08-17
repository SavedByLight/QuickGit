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
    onSelect: (String) -> Unit,
    onMessage: (String) -> Unit
) {
    var repos by remember { mutableStateOf(emptyList<DesktopRepoManager.LocalRepo>()) }
    var loading by remember { mutableStateOf(true) }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Local repositories", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { reload() }) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Refresh")
            }
        }
        Text("Root: ${repoManager.getReposRoot().absolutePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (repos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No local repos — clone one or browse your GitHub account", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(repos) { repo ->
                    Card(Modifier.fillMaxWidth().clickable { onSelect(repo.path) }, elevation = CardDefaults.cardElevation(2.dp)) {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(repo.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(repo.branch ?: "(no branch)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                repo.remoteUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            if (repo.isDirty) {
                                Box(Modifier.size(10.dp).background(Color(0xFFFF9800), shape = MaterialTheme.shapes.small))
                            }
                        }
                    }
                }
            }
        }
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
    val tabs = listOf("Changes", "History", "Branches", "Files", "Issues", "PRs")
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
            1 -> HistoryTab(repoPath, repoManager)
            2 -> BranchesTab(repoPath, repoManager, onMessage)
            3 -> FilesEditorTab(repoPath, repoManager, onMessage)
            4 -> IssuesTab(repoPath, repoManager, credentialStore, githubApi, onMessage)
            5 -> PullRequestsTab(repoPath, repoManager, credentialStore, githubApi, onMessage)
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
fun HistoryTab(repoPath: String, repoManager: DesktopRepoManager) {
    var commits by remember { mutableStateOf(emptyList<DesktopRepoManager.CommitInfo>()) }
    var loading by remember { mutableStateOf(true) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    LaunchedEffect(repoPath) {
        loading = true
        commits = withContext(Dispatchers.IO) { repoManager.getHistory(repoPath, 200) }
        loading = false
    }
    if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(commits) { c ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(c.message, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(c.shortId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text(c.author, style = MaterialTheme.typography.bodySmall)
                        Text(dateFormat.format(Date(c.time)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
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

@Composable
fun FilesEditorTab(repoPath: String, repoManager: DesktopRepoManager, onMessage: (String) -> Unit) {
    var files by remember { mutableStateOf(listOf<String>()) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var content by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(repoPath) {
        withContext(Dispatchers.IO) {
            val root = java.io.File(repoPath)
            files = root.walkTopDown()
                .filter { it.isFile && !it.path.contains("${java.io.File.separator}.git${java.io.File.separator}") }
                .map { it.relativeTo(root).path }
                .sorted()
                .take(500)
                .toList()
        }
    }

    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(260.dp).fillMaxHeight().padding(8.dp)) {
            items(files) { path ->
                Text(
                    path,
                    modifier = Modifier.fillMaxWidth().clickable {
                        selectedFile = path
                        scope.launch {
                            loading = true
                            val text = withContext(Dispatchers.IO) {
                                try { java.io.File(repoPath, path).readText() } catch (_: Exception) { "" }
                            }
                            content = text
                            original = text
                            loading = false
                        }
                    }.background(if (selectedFile == path) MaterialTheme.colorScheme.primaryContainer.copy(0.4f) else Color.Transparent).padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        VerticalDivider()
        Column(Modifier.weight(1f).padding(12.dp)) {
            if (selectedFile == null) {
                Text("Select a file to view / edit", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedFile!!, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    java.io.File(repoPath, selectedFile!!).writeText(content)
                                }
                                original = content
                                onMessage("Saved $selectedFile")
                            }
                        },
                        enabled = content != original
                    ) { Text("Save") }
                }
                Spacer(Modifier.height(8.dp))
                if (loading) CircularProgressIndicator()
                else OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }
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
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(repoPath) {
        loading = true
        error = null
        val remote = withContext(Dispatchers.IO) { repoManager.getRemoteUrl(repoPath) }
        val parsed = githubApi.parseOwnerRepo(remote)
        if (parsed == null) {
            error = "Not a GitHub remote (or no origin URL)"
            loading = false
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { githubApi.listIssues(parsed.owner, parsed.repo, "open") }
        result.fold(
            onSuccess = { issues = it },
            onFailure = { error = it.message }
        )
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        when {
            loading -> CircularProgressIndicator()
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            issues.isEmpty() -> Text("No open issues", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(issues) { issue ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("#${issue.number} ${issue.title}", fontWeight = FontWeight.Medium)
                            Text(issue.state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
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
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repoPath) {
        loading = true
        error = null
        val remote = withContext(Dispatchers.IO) { repoManager.getRemoteUrl(repoPath) }
        val parsed = githubApi.parseOwnerRepo(remote)
        if (parsed == null) {
            error = "Not a GitHub remote"
            loading = false
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { githubApi.listPullRequests(parsed.owner, parsed.repo, "open") }
        result.fold(onSuccess = { prs = it }, onFailure = { error = it.message })
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        when {
            loading -> CircularProgressIndicator()
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            prs.isEmpty() -> Text("No open pull requests", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(prs) { pr ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("#${pr.number} ${pr.title}", fontWeight = FontWeight.Medium)
                            Text("${pr.state}${if (pr.isDraft) " · draft" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
