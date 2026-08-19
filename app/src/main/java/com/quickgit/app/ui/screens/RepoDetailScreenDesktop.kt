package com.quickgit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quickgit.app.data.models.FileChange
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.viewmodel.BranchesViewModel
import com.quickgit.app.viewmodel.FilesViewModel
import com.quickgit.app.viewmodel.HistoryViewModel
import com.quickgit.app.viewmodel.IssuesViewModel
import com.quickgit.app.viewmodel.PullRequestsViewModel
import com.quickgit.app.viewmodel.ReleasesViewModel
import com.quickgit.app.viewmodel.RepoDetailViewModel
import com.quickgit.app.viewmodel.ViewModelFactory
import com.quickgit.app.viewmodel.WorkflowsViewModel

/**
 * Repo detail screen for tablet / Chromebook / desktop-window sized Android windows.
 *
 * Structured identically to the Linux/Mac desktop app: top bar (back, repo name, Pull/Push
 * with force-push options) and a [TabRow] of Changes / Files / Branches / Issues / PRs /
 * Actions / Releases / History. Force push (with lease / no lease) matches the phone app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreenDesktop(
    repoPath: String,
    repoName: String,
    vm: RepoDetailViewModel,
    onBack: () -> Unit,
    onOpenDiff: (filePath: String, mode: String) -> Unit,
    onOpenFile: (filePath: String) -> Unit,
    onConflicts: () -> Unit,
    onNeedsAuth: (String) -> Unit
) {
    val context = LocalContext.current
    val factory = remember { ViewModelFactory(context.applicationContext as android.app.Application) }

    val branchesVm: BranchesViewModel = viewModel(factory = factory)
    val filesVm: FilesViewModel = viewModel(factory = factory)
    val issuesVm: IssuesViewModel = viewModel(factory = factory)
    val pullRequestsVm: PullRequestsViewModel = viewModel(factory = factory)
    val workflowsVm: WorkflowsViewModel = viewModel(factory = factory)
    val releasesVm: ReleasesViewModel = viewModel(factory = factory)
    val historyVm: HistoryViewModel = viewModel(factory = factory)

    LaunchedEffect(repoPath) {
        issuesVm.init(repoPath)
        pullRequestsVm.init(repoPath)
        workflowsVm.init(repoPath)
        releasesVm.init(repoPath)
    }

    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var pushMenuExpanded by remember { mutableStateOf(false) }
    var showForcePushConfirm by remember { mutableStateOf(false) }
    var forcePushUseLease by remember { mutableStateOf(true) }

    LaunchedEffect(state.lastResult, state.statusMessage) {
        state.statusMessage?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeResult()
            return@LaunchedEffect
        }
        when (val r = state.lastResult) {
            is GitOpResult.Success -> { snackbarHost.showSnackbar("Done"); vm.consumeResult() }
            is GitOpResult.UpToDate -> { snackbarHost.showSnackbar(r.message); vm.consumeResult() }
            is GitOpResult.Error -> { snackbarHost.showSnackbar(r.message); vm.consumeResult() }
            is GitOpResult.AuthRequired -> { vm.consumeResult(); onNeedsAuth(r.remoteUrl) }
            is GitOpResult.Conflict -> { vm.consumeResult(); onConflicts() }
            null -> {}
        }
    }

    // Changes first, then Files, Branches, Issues, PRs, Actions, Releases, History last — same order as desktop.
    val tabs = listOf("Changes", "Files", "Branches", "Issues", "PRs", "Actions", "Releases", "History")
    var tabIndex by remember { mutableStateOf(0) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                Text(
                    repoName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { vm.pull() }, enabled = !state.busy) { Text("Pull") }
                Spacer(Modifier.width(8.dp))
                Box {
                    Button(onClick = { pushMenuExpanded = true }, enabled = !state.busy) {
                        Text("Push ▾")
                    }
                    DropdownMenu(
                        expanded = pushMenuExpanded,
                        onDismissRequest = { pushMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Git push") },
                            onClick = {
                                pushMenuExpanded = false
                                vm.push(force = false)
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Force with lease…",
                                    color = com.quickgit.app.ui.theme.GitRed
                                )
                            },
                            onClick = {
                                pushMenuExpanded = false
                                forcePushUseLease = true
                                showForcePushConfirm = true
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Force (no lease)…",
                                    color = com.quickgit.app.ui.theme.GitRed
                                )
                            },
                            onClick = {
                                pushMenuExpanded = false
                                forcePushUseLease = false
                                showForcePushConfirm = true
                            }
                        )
                    }
                }
                if (state.busy) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            TabRow(selectedTabIndex = tabIndex) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = tabIndex == i, onClick = { tabIndex = i }, text = { Text(title) })
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                when (tabIndex) {
                    0 -> ChangesTabDesktop(vm)
                    1 -> FilesScreen(repoPath = repoPath, vm = filesVm, onBack = null, onOpenFile = onOpenFile)
                    2 -> BranchesScreen(repoPath = repoPath, vm = branchesVm, onBack = null)
                    3 -> IssuesScreen(vm = issuesVm, onBack = null, onNeedsAuth = onNeedsAuth)
                    4 -> PullRequestsScreen(vm = pullRequestsVm, onBack = null, onNeedsAuth = onNeedsAuth)
                    5 -> WorkflowsScreen(vm = workflowsVm, onBack = null, onNeedsAuth = onNeedsAuth)
                    6 -> ReleasesScreen(vm = releasesVm, onBack = null, onNeedsAuth = onNeedsAuth)
                    7 -> HistoryScreen(repoPath = repoPath, vm = historyVm, onBack = null)
                }
            }
        }
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
                    Surface(
                        onClick = { forcePushUseLease = true },
                        shape = MaterialTheme.shapes.medium,
                        color = if (forcePushUseLease) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = forcePushUseLease,
                                onClick = { forcePushUseLease = true }
                            )
                            Column(Modifier.weight(1f)) {
                                Text("Force with lease", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Only if remote still matches your last fetch (safer)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = { forcePushUseLease = false },
                        shape = MaterialTheme.shapes.medium,
                        color = if (!forcePushUseLease) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = !forcePushUseLease,
                                onClick = { forcePushUseLease = false }
                            )
                            Column(Modifier.weight(1f)) {
                                Text("Force (no lease)", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Always overwrite — can discard others’ commits",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showForcePushConfirm = false
                        if (forcePushUseLease) {
                            vm.push(forceWithLease = true)
                        } else {
                            vm.push(force = true)
                        }
                    },
                    colors = if (forcePushUseLease) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = com.quickgit.app.ui.theme.GitRed
                        )
                    }
                ) {
                    Text(if (forcePushUseLease) "Force with lease" else "Force push")
                }
            },
            dismissButton = {
                TextButton(onClick = { showForcePushConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Changes tab matching the desktop app's [com.quickgit.desktop.ui.ChangesTab]: one flat
 * selectable list of changed files (no separate staged/unstaged sections), Stage
 * all / Stage / Unstage / Discard acting on the selection, and a plain commit message
 * field with a single Commit button — no sign-off, amend, or suggested message.
 */
@Composable
private fun ChangesTabDesktop(vm: RepoDetailViewModel) {
    val state by vm.state.collectAsState()
    val status = state.status
    var selected by remember { mutableStateOf(setOf<String>()) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.stageAll() }, enabled = !state.busy) { Text("Stage all") }
            OutlinedButton(
                onClick = {
                    selected.forEach { path ->
                        val staged = status?.staged?.any { it.path == path } == true
                        if (!staged) vm.toggleStage(path, false)
                    }
                    selected = emptySet()
                },
                enabled = selected.isNotEmpty() && !state.busy
            ) { Text("Stage") }
            OutlinedButton(
                onClick = {
                    selected.forEach { path ->
                        val staged = status?.staged?.any { it.path == path } == true
                        if (staged) vm.toggleStage(path, true)
                    }
                    selected = emptySet()
                },
                enabled = selected.isNotEmpty() && !state.busy
            ) { Text("Unstage") }
            OutlinedButton(
                onClick = {
                    selected.forEach { path -> vm.discard(path) }
                    selected = emptySet()
                },
                enabled = selected.isNotEmpty() && !state.busy
            ) { Text("Discard") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, null) }
        }
        Spacer(Modifier.height(8.dp))

        val allChanges: List<FileChange> = remember(status) {
            (status?.staged.orEmpty() + status?.unstaged.orEmpty() +
                status?.untracked.orEmpty() + status?.conflicting.orEmpty())
        }

        if (state.busy && status == null) {
            CircularProgressIndicator()
        } else if (allChanges.isEmpty()) {
            Text("Working tree clean", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(allChanges, key = { it.path }) { fc ->
                    val isSelected = fc.path in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = if (isSelected) selected - fc.path else selected + fc.path }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { selected = if (it) selected + fc.path else selected - fc.path }
                        )
                        Text(
                            fc.type.badge(),
                            color = fc.type.color(),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(fc.path, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.commitMessage,
            onValueChange = vm::setCommitMessage,
            label = { Text("Commit message") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )
        Button(
            onClick = vm::commit,
            enabled = state.commitMessage.isNotBlank() && status?.staged?.isNotEmpty() == true && !state.busy,
            modifier = Modifier.align(Alignment.End)
        ) { Text("Commit") }
    }
}
