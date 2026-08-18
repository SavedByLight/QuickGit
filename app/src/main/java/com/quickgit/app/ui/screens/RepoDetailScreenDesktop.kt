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
import com.quickgit.app.viewmodel.RepoDetailViewModel
import com.quickgit.app.viewmodel.ViewModelFactory

/**
 * Repo detail screen for tablet / Chromebook / desktop-window sized Android windows.
 *
 * Structured identically to [com.quickgit.desktop.ui.RepoDetailScreen] in the Linux/Mac
 * desktop app: a single screen with a top bar (back, repo name, plain Pull/Push) and a
 * [TabRow] of Changes / Files / Branches / Issues / PRs / History, instead of the phone
 * layout's separate screens and Pull/Push option sheets. To match the desktop app exactly,
 * this intentionally drops the phone-only extras (LFS menu, force-push confirmation,
 * commit sign-off/amend, suggested commit messages, status detail sheet).
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
    val historyVm: HistoryViewModel = viewModel(factory = factory)

    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

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

    // Changes first, then Files, Branches, Issues, PRs, History last — same order as desktop.
    val tabs = listOf("Changes", "Files", "Branches", "Issues", "PRs", "History")
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
                Button(onClick = { vm.push() }, enabled = !state.busy) { Text("Push") }
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
                    5 -> HistoryScreen(repoPath = repoPath, vm = historyVm, onBack = null)
                }
            }
        }
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
