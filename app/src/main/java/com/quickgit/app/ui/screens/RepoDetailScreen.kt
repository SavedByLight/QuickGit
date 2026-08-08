package com.quickgit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.models.ChangeType
import com.quickgit.app.data.models.FileChange
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.ui.components.PullToRefreshBox
import com.quickgit.app.ui.theme.GitAmber
import com.quickgit.app.ui.theme.GitGreen
import com.quickgit.app.ui.theme.GitRed
import com.quickgit.app.viewmodel.RepoDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(
    repoName: String,
    vm: RepoDetailViewModel,
    onBack: () -> Unit,
    onOpenDiff: (filePath: String, mode: String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBranches: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenPullRequests: () -> Unit = {},
    onOpenIssues: () -> Unit = {},
    onOpenWorkflows: () -> Unit = {},
    onOpenReleases: () -> Unit = {},
    onConflicts: () -> Unit,
    onNeedsAuth: (String) -> Unit
) {
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        val status = state.status
        Column(Modifier.padding(padding).fillMaxSize()) {

            // Back (left) + centered repo name / branch + History (right, icon only)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 2.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(Icons.Default.ArrowBack, "Back")
                }
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        repoName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        state.branch,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onOpenHistory,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(Icons.Default.History, "History")
                }
            }

            // Sub-page navigation under the name
            androidx.compose.foundation.lazy.LazyRow(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item { SubPageButton(Icons.Default.FolderOpen, "Files", onOpenFiles) }
                item { SubPageButton(Icons.Default.AccountTree, "Branches", onOpenBranches) }
                if (!state.isGerritRemote) {
                    // GitLab: MRs / Issues board / Build. GitHub: PRs / Issues / Actions.
                    // Gerrit hides this row entirely.
                    val prLabel = if (state.isGitLabRemote) "MRs" else "PRs"
                    val issuesLabel = if (state.isGitLabRemote) "Board" else "Issues"
                    val ciLabel = if (state.isGitLabRemote) "Build" else "Actions"
                    item { SubPageButton(Icons.Default.CallMerge, prLabel, onOpenPullRequests) }
                    item { SubPageButton(Icons.Default.BugReport, issuesLabel, onOpenIssues) }
                    item { SubPageButton(Icons.Default.PlayCircle, ciLabel, onOpenWorkflows) }
                    item { SubPageButton(Icons.Default.NewReleases, "Releases", onOpenReleases) }
                }
            }

            var showForcePushConfirm by remember { mutableStateOf(false) }
            // true = force-with-lease (safer default), false = unconditional force
            var forcePushUseLease by remember { mutableStateOf(true) }
            var showPushModeDialog by remember { mutableStateOf(false) }
            // true = refs/for/<branch> review; false = normal branch push
            var pushForReview by remember { mutableStateOf(true) }

            Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
                OutlinedButton(onClick = { vm.pull() }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ArrowDownward, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pull")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (state.isGerritRemote) {
                            pushForReview = true
                            showPushModeDialog = true
                        } else {
                            vm.push(force = false)
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ArrowUpward, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Push")
                }
            }

            if (showPushModeDialog) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { showPushModeDialog = false }) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        tonalElevation = 6.dp,
                        modifier = Modifier.fillMaxWidth().padding(24.dp)
                    ) {
                        Column(Modifier.padding(24.dp)) {
                            Text("Push", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Choose how to push to Gerrit:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))

                            ForcePushModeOption(
                                title = "Push for review",
                                subtitle = "Upload to refs/for/${state.branch.ifBlank { "branch" }} for code review",
                                selected = pushForReview,
                                onSelect = { pushForReview = true }
                            )
                            Spacer(Modifier.height(8.dp))
                            ForcePushModeOption(
                                title = "Push to branch",
                                subtitle = "Update the remote branch tip directly (no review)",
                                selected = !pushForReview,
                                onSelect = { pushForReview = false }
                            )

                            Spacer(Modifier.height(20.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showPushModeDialog = false }) {
                                    Text("Cancel")
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        showPushModeDialog = false
                                        if (pushForReview) vm.pushForReview()
                                        else vm.push(force = false)
                                    }
                                ) {
                                    Text(if (pushForReview) "Push for review" else "Push")
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 4.dp)) {
                OutlinedButton(
                    onClick = {
                        forcePushUseLease = true
                        showForcePushConfirm = true
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GitRed)
                ) {
                    Text("Force push")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.fetchLfs() }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text("LFS pull")
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 4.dp)) {
                OutlinedButton(onClick = { vm.pushLfs() }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text("LFS push")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.lfsInstall() }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text("LFS install")
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 4.dp)) {
                OutlinedButton(onClick = { showLfsTrack = true }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text("LFS track")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.lfsStatus() }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Text("LFS status")
                }
            }

            if (showForcePushConfirm) {
                // Custom dialog so options are never clipped (M3 AlertDialog text slot often truncates)
                androidx.compose.ui.window.Dialog(onDismissRequest = { showForcePushConfirm = false }) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        tonalElevation = 6.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(24.dp)) {
                            Text(
                                "Force push",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Choose how to overwrite the remote branch:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(16.dp))

                            ForcePushModeOption(
                                title = "Force with lease",
                                subtitle = "Only if remote still matches your last fetch (safer)",
                                selected = forcePushUseLease,
                                onSelect = { forcePushUseLease = true }
                            )
                            Spacer(Modifier.height(8.dp))
                            ForcePushModeOption(
                                title = "Force (no lease)",
                                subtitle = "Always overwrite — can discard others’ commits",
                                selected = !forcePushUseLease,
                                onSelect = { forcePushUseLease = false }
                            )

                            Spacer(Modifier.height(20.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showForcePushConfirm = false }) {
                                    Text("Cancel")
                                }
                                Spacer(Modifier.width(8.dp))
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
                                        ButtonDefaults.buttonColors(containerColor = GitRed)
                                    }
                                ) {
                                    Text(if (forcePushUseLease) "Force with lease" else "Force push")
                                }
                            }
                        }
                    }
                }
            }

            if (status != null && status.conflicting.isNotEmpty()) {
                Surface(color = GitAmber.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = GitAmber)
                        Spacer(Modifier.width(8.dp))
                        Text("${status.conflicting.size} conflicting file(s)", Modifier.weight(1f))
                        TextButton(onClick = onConflicts) { Text("Resolve") }
                    }
                }
            }

            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                if ((state.busy || state.refreshing) && status == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (status != null) {
                    Column(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            if (status.staged.isNotEmpty()) {
                                item {
                                    Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp, 16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Staged changes (${status.staged.size})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { vm.unstageAll() }, enabled = !state.busy) { Text("Unstage all") }
                                    }
                                }
                                items(status.staged, key = { "s_" + it.path }) { fc ->
                                    ChangeRow(fc, onToggle = { vm.toggleStage(fc.path, true) }, onClick = { onOpenDiff(fc.path, "staged") })
                                }
                            }
                            val unstagedAll = status.unstaged + status.untracked
                            if (unstagedAll.isNotEmpty()) {
                                item {
                                    Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp, 16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Changes (${unstagedAll.size})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { vm.discardAll() }, enabled = !state.busy) { Text("Revert all") }
                                        TextButton(onClick = { vm.stageAll() }) { Text("Stage all") }
                                    }
                                }
                                items(unstagedAll, key = { "u_" + it.path }) { fc ->
                                    ChangeRow(
                                        fc,
                                        onToggle = { vm.toggleStage(fc.path, false) },
                                        onClick = { onOpenDiff(fc.path, "working") },
                                        onDiscard = { vm.discard(fc.path) }
                                    )
                                }
                            }
                            if (status.isClean) {
                                item {
                                    Box(Modifier.fillParentMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                        Text("Nothing to commit — working tree clean", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(140.dp)) }
                        }

                        CommitBar(
                            message = state.commitMessage,
                            onMessageChange = vm::setCommitMessage,
                            signOff = state.signOff,
                            onSignOffChange = vm::setSignOff,
                            authorName = state.authorName,
                            authorEmail = state.authorEmail,
                            enabled = status.staged.isNotEmpty() && !state.busy,
                            onCommit = vm::commit
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ForcePushModeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        onClick = onSelect,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
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
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SubPageButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = 56.dp)
            .clickableSimple(onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun ChangeRow(
    fc: FileChange,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onDiscard: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            fc.type.badge(),
            color = fc.type.color(),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp)
        )
        Text(
            fc.path,
            modifier = Modifier.weight(1f).clickableSimple(onClick),
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium
        )
        if (onDiscard != null) {
            IconButton(onClick = onDiscard) { Icon(Icons.Default.Undo, "Discard", modifier = Modifier.size(18.dp)) }
        }
        IconButton(onClick = onToggle) {
            Icon(if (fc.staged) Icons.Default.RemoveCircleOutline else Icons.Default.AddCircleOutline, "Toggle stage")
        }
    }
}

private fun ChangeType.badge() = when (this) {
    ChangeType.ADDED -> "A"
    ChangeType.MODIFIED -> "M"
    ChangeType.DELETED -> "D"
    ChangeType.RENAMED -> "R"
    ChangeType.CONFLICTING -> "!"
    ChangeType.UNTRACKED -> "U"
}

private fun ChangeType.color() = when (this) {
    ChangeType.ADDED -> GitGreen
    ChangeType.MODIFIED -> GitAmber
    ChangeType.DELETED -> GitRed
    ChangeType.RENAMED -> Color(0xFF8250DF)
    ChangeType.CONFLICTING -> GitRed
    ChangeType.UNTRACKED -> Color.Gray
}

@Composable
private fun CommitBar(
    message: String,
    onMessageChange: (String) -> Unit,
    signOff: Boolean,
    onSignOffChange: (Boolean) -> Unit,
    authorName: String,
    authorEmail: String,
    enabled: Boolean,
    onCommit: () -> Unit
) {
    Surface(shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                label = { Text("Commit message") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = signOff,
                    onCheckedChange = onSignOffChange
                )
                Column(Modifier.weight(1f).clickableSimple { onSignOffChange(!signOff) }) {
                    Text("Sign-off", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Signed-off-by: $authorName <$authorEmail>",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCommit, enabled = enabled && message.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (signOff) "Commit (signed-off)" else "Commit to current branch")
            }
        }
    }
}

private fun Modifier.clickableSimple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
