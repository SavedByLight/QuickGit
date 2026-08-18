package com.quickgit.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.models.BranchInfo
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.ui.adaptive.AdaptiveContent
import com.quickgit.app.ui.components.PullToRefreshBox
import com.quickgit.app.viewmodel.BranchesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchesScreen(repoPath: String, vm: BranchesViewModel, onBack: (() -> Unit)? = null) {
    LaunchedEffect(repoPath) { vm.init(repoPath) }
    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }
    var showAddRemote by remember { mutableStateOf(false) }
    var branchToDelete by remember { mutableStateOf<BranchInfo?>(null) }
    var remoteToDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.lastResult, state.statusMessage) {
        state.statusMessage?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeResult()
        }
        when (val r = state.lastResult) {
            is GitOpResult.Error -> {
                snackbarHost.showSnackbar(r.message)
                vm.consumeResult()
            }
            is GitOpResult.AuthRequired -> {
                snackbarHost.showSnackbar("Auth required for ${r.remoteUrl}")
                vm.consumeResult()
            }
            is GitOpResult.Conflict -> {
                snackbarHost.showSnackbar("Conflict: ${r.paths.joinToString()}")
                vm.consumeResult()
            }
            is GitOpResult.Success, is GitOpResult.UpToDate -> vm.consumeResult()
            null -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Branches & remotes") },
                navigationIcon = {
                    onBack?.let { back ->
                        IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Back") }
                    }
                },
                actions = {
                    IconButton(onClick = { showAddRemote = true }) {
                        Icon(Icons.Default.CloudUpload, "Add remote")
                    }
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Default.Add, "New branch")
                    }
                }
            )
        }
    ) { padding ->
        AdaptiveContent(Modifier.padding(padding)) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize()
        ) {
            if ((state.busy || state.refreshing) && state.branches.isEmpty() && state.remotes.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    // ---- Remotes ----
                    item {
                        Text(
                            "Remotes",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                    if (state.remotes.isEmpty()) {
                        item {
                            Text(
                                "No remotes configured. Add a fork URL to fetch and cherry-pick from it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }
                    } else {
                        items(state.remotes.entries.toList(), key = { it.key }) { (name, url) ->
                            RemoteRow(
                                name = name,
                                url = url,
                                busy = state.busy,
                                onFetch = { vm.fetchRemote(name) },
                                onDelete = { remoteToDelete = name }
                            )
                            HorizontalDivider()
                        }
                    }
                    item {
                        TextButton(
                            onClick = { showAddRemote = true },
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add remote (e.g. a fork)")
                        }
                    }

                    // ---- Branches ----
                    item {
                        Text(
                            "Branches",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                    items(state.branches, key = { (if (it.isRemote) "r_" else "l_") + it.name }) { b ->
                        BranchRow(
                            b,
                            onCheckout = { vm.checkout(b.name) },
                            onDelete = if (!b.isRemote && !b.isCurrent) ({ branchToDelete = b }) else null
                        )
                        HorizontalDivider()
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
        } // AdaptiveContent
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New local branch") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Branch name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Created only on this device. It won't appear on any remote until you push it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.createBranch(name.trim(), checkout = true)
                        showCreate = false
                    },
                    enabled = name.isNotBlank()
                ) { Text("Create & checkout") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } }
        )
    }

    if (showAddRemote) {
        var remoteName by remember { mutableStateOf("") }
        var remoteUrl by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddRemote = false },
            title = { Text("Add remote") },
            text = {
                Column {
                    Text(
                        "Point at a fork to fetch its branches, then cherry-pick commits from History.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = remoteName,
                        onValueChange = { remoteName = it },
                        label = { Text("Name (e.g. fork)") },
                        placeholder = { Text("fork") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = remoteUrl,
                        onValueChange = { remoteUrl = it },
                        label = { Text("URL") },
                        placeholder = { Text("https://github.com/user/repo.git") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.addRemote(remoteName.trim(), remoteUrl.trim())
                        showAddRemote = false
                    },
                    enabled = remoteName.isNotBlank() && remoteUrl.isNotBlank() && !state.busy
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddRemote = false }) { Text("Cancel") } }
        )
    }

    branchToDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { branchToDelete = null },
            title = { Text("Delete '${b.name}'?") },
            confirmButton = {
                TextButton(onClick = { vm.delete(b.name); branchToDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { branchToDelete = null }) { Text("Cancel") } }
        )
    }

    remoteToDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { remoteToDelete = null },
            title = { Text("Remove remote '$name'?") },
            text = {
                Text("This only removes the remote configuration. Local branches are kept.")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeRemote(name)
                    remoteToDelete = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { remoteToDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RemoteRow(
    name: String,
    url: String,
    busy: Boolean,
    onFetch: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium)
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
        TextButton(onClick = onFetch, enabled = !busy) { Text("Fetch") }
        IconButton(onClick = onDelete, enabled = !busy) {
            Icon(Icons.Default.Delete, "Remove remote")
        }
    }
}

@Composable
private fun BranchRow(b: BranchInfo, onCheckout: () -> Unit, onDelete: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (b.isRemote) Icons.Default.Cloud else Icons.Default.AccountTree,
            null,
            tint = if (b.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.name, fontWeight = if (b.isCurrent) FontWeight.Bold else FontWeight.Normal)
            val subtitle = when {
                b.isCurrent && !b.upstream.isNullOrBlank() -> "current · tracking ${b.upstream}"
                b.isCurrent -> "current"
                b.isRemote -> "remote — checkout creates local tracking branch"
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
        if (!b.isCurrent) {
            TextButton(onClick = onCheckout) {
                Text(if (b.isRemote) "Checkout & track" else "Checkout")
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete branch") }
        }
    }
}
