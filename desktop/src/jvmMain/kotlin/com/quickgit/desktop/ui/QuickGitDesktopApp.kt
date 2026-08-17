package com.quickgit.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickgit.desktop.data.DesktopAppUpdateManager
import com.quickgit.desktop.data.DesktopAppUpdateConfig
import com.quickgit.desktop.data.DesktopCredentialStore
import com.quickgit.desktop.data.DesktopRepoManager
import com.quickgit.desktop.data.DesktopUpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

enum class DesktopScreen {
    RepoList, RepoDetail, Clone, Settings, Credentials
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
    var snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun showMessage(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (currentScreen) {
                            DesktopScreen.RepoList -> "QuickGit"
                            DesktopScreen.RepoDetail -> selectedRepoPath?.substringAfterLast('/') ?: "Repository"
                            DesktopScreen.Clone -> "Clone Repository"
                            DesktopScreen.Settings -> "Settings"
                            DesktopScreen.Credentials -> "Credentials"
                        }
                    )
                },
                navigationIcon = {
                    if (currentScreen != DesktopScreen.RepoList) {
                        IconButton(onClick = {
                            currentScreen = when (currentScreen) {
                                DesktopScreen.RepoDetail -> DesktopScreen.RepoList
                                else -> if (selectedRepoPath != null) DesktopScreen.RepoDetail else DesktopScreen.RepoList
                            }
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (currentScreen == DesktopScreen.RepoList) {
                        IconButton(onClick = { currentScreen = DesktopScreen.Clone }) {
                            Icon(Icons.Default.Add, contentDescription = "Clone")
                        }
                        IconButton(onClick = { currentScreen = DesktopScreen.Credentials }) {
                            Icon(Icons.Default.Key, contentDescription = "Credentials")
                        }
                        IconButton(onClick = { currentScreen = DesktopScreen.Settings }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (currentScreen) {
                DesktopScreen.RepoList -> RepoListScreen(
                    repoManager = repoManager,
                    onSelect = { path ->
                        selectedRepoPath = path
                        currentScreen = DesktopScreen.RepoDetail
                    },
                    onRefresh = { },
                    onMessage = ::showMessage
                )
                DesktopScreen.RepoDetail -> selectedRepoPath?.let { path ->
                    RepoDetailScreen(
                        repoPath = path,
                        repoManager = repoManager,
                        onMessage = ::showMessage
                    )
                }
                DesktopScreen.Clone -> CloneScreen(
                    repoManager = repoManager,
                    onCloned = { path ->
                        selectedRepoPath = path
                        currentScreen = DesktopScreen.RepoDetail
                        showMessage("Cloned successfully")
                    },
                    onMessage = ::showMessage
                )
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

@Composable
fun RepoListScreen(
    repoManager: DesktopRepoManager,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Local repositories",
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = { reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Refresh")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Root: ${repoManager.getReposRoot().absolutePath}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (repos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOff, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text("No repositories yet", style = MaterialTheme.typography.titleMedium)
                    Text("Clone a repo or put git repositories under the root folder", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(repos) { repo ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(repo.path) },
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Row(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(repo.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    repo.branch ?: "(no branch)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                repo.remoteUrl?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(
    repoPath: String,
    repoManager: DesktopRepoManager,
    onMessage: (String) -> Unit
) {
    var tabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Changes", "History", "Branches")
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // Action bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        val result = withContext(Dispatchers.IO) { repoManager.pull(repoPath) }
                        busy = false
                        result.fold(
                            onSuccess = { onMessage("Pull succeeded") },
                            onFailure = { onMessage("Pull failed: ${it.message}") }
                        )
                    }
                },
                enabled = !busy
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Pull")
            }
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        val result = withContext(Dispatchers.IO) { repoManager.push(repoPath) }
                        busy = false
                        result.fold(
                            onSuccess = { onMessage("Push succeeded") },
                            onFailure = { onMessage("Push failed: ${it.message}") }
                        )
                    }
                },
                enabled = !busy
            ) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Push")
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        val result = withContext(Dispatchers.IO) { repoManager.fetch(repoPath) }
                        busy = false
                        result.fold(
                            onSuccess = { onMessage("Fetch succeeded") },
                            onFailure = { onMessage("Fetch failed: ${it.message}") }
                        )
                    }
                },
                enabled = !busy
            ) {
                Text("Fetch")
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }

        TabRow(selectedTabIndex = tabIndex) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = tabIndex == i, onClick = { tabIndex = i }, text = { Text(title) })
            }
        }

        when (tabIndex) {
            0 -> ChangesTab(repoPath, repoManager, onMessage)
            1 -> HistoryTab(repoPath, repoManager)
            2 -> BranchesTab(repoPath, repoManager, onMessage)
        }
    }
}

@Composable
fun ChangesTab(
    repoPath: String,
    repoManager: DesktopRepoManager,
    onMessage: (String) -> Unit
) {
    var changes by remember { mutableStateOf(emptyList<DesktopRepoManager.FileChange>()) }
    var commitMessage by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            loading = true
            changes = withContext(Dispatchers.IO) { repoManager.getStatus(repoPath) }
            loading = false
        }
    }

    LaunchedEffect(repoPath) { reload() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { repoManager.stageAll(repoPath) }
                    reload()
                    onMessage("Staged all")
                }
            }) { Text("Stage all") }
            OutlinedButton(onClick = {
                if (selected.isNotEmpty()) {
                    scope.launch {
                        withContext(Dispatchers.IO) { repoManager.stage(repoPath, selected.toList()) }
                        selected = emptySet()
                        reload()
                    }
                }
            }, enabled = selected.isNotEmpty()) { Text("Stage selected") }
            OutlinedButton(onClick = {
                if (selected.isNotEmpty()) {
                    scope.launch {
                        withContext(Dispatchers.IO) { repoManager.unstage(repoPath, selected.toList()) }
                        selected = emptySet()
                        reload()
                    }
                }
            }, enabled = selected.isNotEmpty()) { Text("Unstage") }
            OutlinedButton(onClick = {
                if (selected.isNotEmpty()) {
                    scope.launch {
                        withContext(Dispatchers.IO) { repoManager.discard(repoPath, selected.toList()) }
                        selected = emptySet()
                        reload()
                        onMessage("Discarded")
                    }
                }
            }, enabled = selected.isNotEmpty()) { Text("Discard") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { reload() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        Spacer(Modifier.height(12.dp))

        if (loading) {
            CircularProgressIndicator()
        } else if (changes.isEmpty()) {
            Text("Working tree clean", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(changes) { change ->
                    val isSelected = change.path in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (isSelected) selected - change.path else selected + change.path
                            }
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = isSelected, onCheckedChange = {
                            selected = if (it) selected + change.path else selected - change.path
                        })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (change.status) {
                                DesktopRepoManager.ChangeStatus.STAGED -> "S"
                                DesktopRepoManager.ChangeStatus.UNSTAGED -> "M"
                                DesktopRepoManager.ChangeStatus.UNTRACKED -> "?"
                                DesktopRepoManager.ChangeStatus.CONFLICT -> "C"
                            },
                            fontWeight = FontWeight.Bold,
                            color = when (change.status) {
                                DesktopRepoManager.ChangeStatus.STAGED -> Color(0xFF4CAF50)
                                DesktopRepoManager.ChangeStatus.UNSTAGED -> Color(0xFFFF9800)
                                DesktopRepoManager.ChangeStatus.UNTRACKED -> Color(0xFF2196F3)
                                DesktopRepoManager.ChangeStatus.CONFLICT -> Color(0xFFF44336)
                            },
                            modifier = Modifier.width(24.dp)
                        )
                        Text(change.path, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = commitMessage,
            onValueChange = { commitMessage = it },
            label = { Text("Commit message") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (commitMessage.isNotBlank()) {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            repoManager.commit(repoPath, commitMessage)
                        }
                        result.fold(
                            onSuccess = {
                                commitMessage = ""
                                reload()
                                onMessage("Committed ${it.take(7)}")
                            },
                            onFailure = { onMessage("Commit failed: ${it.message}") }
                        )
                    }
                }
            },
            enabled = commitMessage.isNotBlank(),
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Commit")
        }
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

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(commits) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.message, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
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
}

@Composable
fun BranchesTab(
    repoPath: String,
    repoManager: DesktopRepoManager,
    onMessage: (String) -> Unit
) {
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

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newBranchName,
                onValueChange = { newBranchName = it },
                label = { Text("New branch name") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(
                onClick = {
                    if (newBranchName.isNotBlank()) {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                repoManager.createBranch(repoPath, newBranchName, checkout = true)
                            }
                            result.fold(
                                onSuccess = {
                                    newBranchName = ""
                                    reload()
                                    onMessage("Branch created and checked out")
                                },
                                onFailure = { onMessage("Failed: ${it.message}") }
                            )
                        }
                    }
                },
                enabled = newBranchName.isNotBlank()
            ) { Text("Create") }
        }
        Spacer(Modifier.height(16.dp))
        if (loading) {
            CircularProgressIndicator()
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(branches) { b ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!b.isCurrent && !b.isRemote) {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            repoManager.checkout(repoPath, b.name)
                                        }
                                        result.fold(
                                            onSuccess = { reload(); onMessage("Checked out ${b.name}") },
                                            onFailure = { onMessage("Checkout failed: ${it.message}") }
                                        )
                                    }
                                }
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (b.isRemote) Icons.Default.Cloud else Icons.Default.Commit,
                            contentDescription = null,
                            tint = if (b.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            b.name,
                            fontWeight = if (b.isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (b.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        if (b.isCurrent) {
                            Spacer(Modifier.width(8.dp))
                            Text("(current)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Repository URL (HTTPS or SSH)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://github.com/user/repo.git") }
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Folder name (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Text(
            "Will be cloned into: ${repoManager.getReposRoot().absolutePath}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (progress != null) {
            Text(progress!!, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Button(
            onClick = {
                if (url.isNotBlank()) {
                    scope.launch {
                        busy = true
                        progress = "Starting…"
                        val result = withContext(Dispatchers.IO) {
                            repoManager.cloneRepo(url, name.ifBlank { null }) { p ->
                                progress = "${p.task} ${if (p.total > 0) "${p.completed}/${p.total}" else ""}"
                            }
                        }
                        busy = false
                        progress = null
                        result.fold(
                            onSuccess = { onCloned(it.absolutePath) },
                            onFailure = { onMessage("Clone failed: ${it.message}") }
                        )
                    }
                }
            },
            enabled = url.isNotBlank() && !busy,
            modifier = Modifier.align(Alignment.End)
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "Cloning…" else "Clone")
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

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Commit identity", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = authorName, onValueChange = { authorName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = authorEmail, onValueChange = { authorEmail = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            repoManager.setCommitAuthor(authorName, authorEmail)
            onMessage("Author saved")
        }) { Text("Save author") }

        Divider()
        Text("Repositories root", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = reposRoot, onValueChange = { reposRoot = it }, label = { Text("Path") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            repoManager.setReposRoot(reposRoot)
            onMessage("Root updated")
        }) { Text("Save root") }

        Divider()
        Text("Signing", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = gpgSign, onCheckedChange = {
                gpgSign = it
                repoManager.setGpgSigningEnabled(it)
            })
            Text("Sign commits with GPG (key must be imported in Credentials)")
        }

        Divider()
        Text("Updates", style = MaterialTheme.typography.titleMedium)
        Text(
            "Current version: ${DesktopAppUpdateConfig.currentVersionName}",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        checkingUpdate = true
                        updateStatus = null
                        updateManager.clearSuppression()
                        val result = withContext(Dispatchers.IO) { updateManager.checkForUpdate() }
                        checkingUpdate = false
                        when (result) {
                            is DesktopUpdateCheckResult.UpToDate ->
                                updateStatus = "You are up to date (${result.current.versionName})"
                            is DesktopUpdateCheckResult.Available -> {
                                updateStatus = "Update ${result.latest.versionName} available — downloading…"
                                val dl = withContext(Dispatchers.IO) {
                                    updateManager.downloadUpdate(result.downloadUrl, result.assetName)
                                }
                                dl.fold(
                                    onSuccess = { file ->
                                        updateStatus = "Downloaded to ${file.absolutePath}"
                                        updateManager.openFile(file)
                                        onMessage("Update downloaded")
                                    },
                                    onFailure = {
                                        updateStatus = "Download failed: ${it.message}"
                                        updateManager.openReleasePage()
                                    }
                                )
                            }
                            is DesktopUpdateCheckResult.Error ->
                                updateStatus = "Check failed: ${result.message}"
                        }
                    }
                },
                enabled = !checkingUpdate
            ) {
                if (checkingUpdate) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (checkingUpdate) "Checking…" else "Check for updates")
            }
            TextButton(onClick = { updateManager.openReleasePage() }) {
                Text("Open releases page")
            }
        }
        updateStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CredentialsScreen(
    credentialStore: DesktopCredentialStore,
    onMessage: (String) -> Unit
) {
    var githubToken by remember { mutableStateOf(credentialStore.getGithubToken() ?: "") }
    var githubUsername by remember {
        mutableStateOf(credentialStore.getHttpsUsername("github.com") ?: "")
    }
    var httpsHost by remember { mutableStateOf("github.com") }
    var httpsUsername by remember {
        mutableStateOf(credentialStore.getHttpsUsername("github.com") ?: "")
    }
    var httpsToken by remember {
        mutableStateOf(credentialStore.getHttpsToken("github.com") ?: "")
    }
    var sshKey by remember { mutableStateOf(credentialStore.getSshPrivateKey() ?: "") }
    var sshPass by remember { mutableStateOf(credentialStore.getSshPassphrase() ?: "") }

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("GitHub account", style = MaterialTheme.typography.titleMedium)
        Text(
            "Username is required for HTTPS remotes. For a personal access token, use your GitHub username " +
                "(or leave blank to send x-access-token). Token needs the repo scope.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = githubUsername,
            onValueChange = { githubUsername = it },
            label = { Text("Username") },
            placeholder = { Text("your-github-username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = githubToken,
            onValueChange = { githubToken = it },
            label = { Text("Personal access token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(onClick = {
            credentialStore.setGithubToken(githubToken.ifBlank { null })
            if (githubUsername.isNotBlank()) {
                credentialStore.setHttpsUsername("github.com", githubUsername)
            }
            if (githubToken.isNotBlank()) {
                credentialStore.setHttpsToken("github.com", githubToken)
            }
            onMessage("GitHub credentials saved")
        }) { Text("Save GitHub credentials") }

        Divider()
        Text("Per-host HTTPS credentials", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = httpsHost,
            onValueChange = {
                httpsHost = it
                httpsUsername = credentialStore.getHttpsUsername(it) ?: ""
                httpsToken = credentialStore.getHttpsToken(it) ?: ""
            },
            label = { Text("Host") },
            placeholder = { Text("github.com") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = httpsUsername,
            onValueChange = { httpsUsername = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = httpsToken,
            onValueChange = { httpsToken = it },
            label = { Text("Personal access token / password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(onClick = {
            if (httpsHost.isBlank()) {
                onMessage("Host is required")
                return@Button
            }
            credentialStore.saveHttpsCredential(
                httpsHost,
                httpsUsername,
                httpsToken
            )
            onMessage("Credentials for $httpsHost saved")
        }) { Text("Save host credentials") }

        Divider()
        Text("SSH private key (PEM / OpenSSH format)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = sshKey,
            onValueChange = { sshKey = it },
            label = { Text("Private key") },
            modifier = Modifier.fillMaxWidth().height(160.dp),
            maxLines = 10
        )
        OutlinedTextField(
            value = sshPass,
            onValueChange = { sshPass = it },
            label = { Text("Passphrase (if any)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(onClick = {
            credentialStore.setSshPrivateKey(sshKey.ifBlank { null })
            credentialStore.setSshPassphrase(sshPass.ifBlank { null })
            onMessage("SSH key saved")
        }) { Text("Save SSH key") }

        Divider()
        TextButton(onClick = {
            credentialStore.clearAll()
            githubToken = ""
            githubUsername = ""
            httpsUsername = ""
            httpsToken = ""
            sshKey = ""
            sshPass = ""
            onMessage("All credentials cleared")
        }) { Text("Clear all credentials") }
    }
}
