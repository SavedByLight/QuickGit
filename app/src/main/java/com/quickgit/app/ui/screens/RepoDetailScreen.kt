package com.quickgit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
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
import com.quickgit.app.ui.adaptive.AdaptiveContent
import com.quickgit.app.ui.adaptive.LocalWindowSizeClass
import com.quickgit.app.ui.adaptive.isCompactHeight
import com.quickgit.app.ui.adaptive.isTabletOrWider
import com.quickgit.app.ui.components.PullToRefreshBox
import com.quickgit.app.ui.theme.GitAmber
import com.quickgit.app.ui.theme.GitGreen
import com.quickgit.app.ui.theme.GitRed
import com.quickgit.app.viewmodel.RepoDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(
    repoPath: String,
    repoName: String,
    vm: RepoDetailViewModel,
    onBack: () -> Unit,
    onOpenDiff: (filePath: String, mode: String) -> Unit,
    onOpenFile: (filePath: String) -> Unit = {},
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
    // Tablet / Chromebook / desktop-window sized: use the same single-screen,
    // tabbed layout as the Linux/Mac desktop app instead of navigating to
    // separate screens.
    if (LocalWindowSizeClass.current.isTabletOrWider) {
        RepoDetailScreenDesktop(
            repoPath = repoPath,
            repoName = repoName,
            vm = vm,
            onBack = onBack,
            onOpenDiff = onOpenDiff,
            onOpenFile = onOpenFile,
            onConflicts = onConflicts,
            onNeedsAuth = onNeedsAuth
        )
        return
    }

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
        AdaptiveContent(Modifier.padding(padding)) {
        Column(Modifier.fillMaxSize()) {

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

            // Sub-page navigation under the name — centered on phones
            androidx.compose.foundation.lazy.LazyRow(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                item { SubPageButton(Icons.Default.FolderOpen, "Files", onOpenFiles) }
                item { Spacer(Modifier.width(4.dp)) }
                item { SubPageButton(Icons.Default.AccountTree, "Branches", onOpenBranches) }
                item { Spacer(Modifier.width(4.dp)) }
                // GitLab: MRs / Issues board / Build. GitHub: PRs / Issues / Actions.
                val prLabel = if (state.isGitLabRemote) "MRs" else "PRs"
                val issuesLabel = if (state.isGitLabRemote) "Board" else "Issues"
                val ciLabel = if (state.isGitLabRemote) "Build" else "Actions"
                item { SubPageButton(Icons.Default.CallMerge, prLabel, onOpenPullRequests) }
                item { Spacer(Modifier.width(4.dp)) }
                item { SubPageButton(Icons.Default.BugReport, issuesLabel, onOpenIssues) }
                item { Spacer(Modifier.width(4.dp)) }
                item { SubPageButton(Icons.Default.PlayCircle, ciLabel, onOpenWorkflows) }
                item { Spacer(Modifier.width(4.dp)) }
                item { SubPageButton(Icons.Default.NewReleases, "Releases", onOpenReleases) }
            }

            var showLfsTrack by remember { mutableStateOf(false) }
            var lfsTrackPattern by remember { mutableStateOf("*.psd") }
            var showForcePushConfirm by remember { mutableStateOf(false) }
            // true = force-with-lease (safer default), false = unconditional force
            var forcePushUseLease by remember { mutableStateOf(true) }
            var showPullOptions by remember { mutableStateOf(false) }
            var showPushOptions by remember { mutableStateOf(false) }
            var showStatusOptions by remember { mutableStateOf(false) }
            var showLfsOptions by remember { mutableStateOf(false) }

            // Phones: single centered row of icon actions (Pull / Push / Status / LFS).
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { showPullOptions = true },
                    enabled = !state.busy
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Pull")
                }
                IconButton(
                    onClick = { showPushOptions = true },
                    enabled = !state.busy
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Push")
                }
                IconButton(
                    onClick = { showStatusOptions = true },
                    enabled = !state.busy
                ) {
                    Icon(Icons.Default.Info, contentDescription = "Status")
                }
                IconButton(
                    onClick = { showLfsOptions = true },
                    enabled = !state.busy
                ) {
                    Icon(Icons.Default.Storage, contentDescription = "LFS")
                }
            }

            if (showPullOptions) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { showPullOptions = false }) {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            tonalElevation = 6.dp,
                            modifier = Modifier
                                .widthIn(max = 400.dp)
                                .fillMaxWidth()
                        ) {
                            Column(
                                Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Pull", style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.height(12.dp))
                                TextButton(
                                    onClick = { showPullOptions = false; vm.pull() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Git pull") }
                                TextButton(
                                    onClick = { showPullOptions = false; vm.pullRebase() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Pull with rebase") }
                                TextButton(
                                    onClick = { showPullOptions = false; vm.fetchLfs() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("LFS pull only") }
                                TextButton(
                                    onClick = { showPullOptions = false; vm.pullWithLfs() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Git pull + LFS") }
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { showPullOptions = false }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }

            if (showPushOptions) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { showPushOptions = false }) {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            tonalElevation = 6.dp,
                            modifier = Modifier
                                .widthIn(max = 400.dp)
                                .fillMaxWidth()
                        ) {
                            Column(
                                Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Push", style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.height(12.dp))
                                TextButton(
                                    onClick = { showPushOptions = false; vm.push(force = false) },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Git push") }
                                TextButton(
                                    onClick = { showPushOptions = false; vm.pushRebase() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Push with rebase") }
                                if (state.isGerritRemote) {
                                    TextButton(
                                        onClick = { showPushOptions = false; vm.pushForReview() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Push for review") }
                                }
                                TextButton(
                                    onClick = { showPushOptions = false; vm.pushLfs() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("LFS push only") }
                                TextButton(
                                    onClick = { showPushOptions = false; vm.pushWithLfs() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Git push + LFS") }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Force push",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = GitRed
                                )
                                Spacer(Modifier.height(4.dp))
                                TextButton(
                                    onClick = {
                                        showPushOptions = false
                                        forcePushUseLease = true
                                        showForcePushConfirm = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.textButtonColors(contentColor = GitRed)
                                ) { Text("Force with lease…") }
                                TextButton(
                                    onClick = {
                                        showPushOptions = false
                                        forcePushUseLease = false
                                        showForcePushConfirm = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.textButtonColors(contentColor = GitRed)
                                ) { Text("Force (no lease)…") }
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { showPushOptions = false }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }

            if (showLfsOptions) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { showLfsOptions = false }) {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            tonalElevation = 6.dp,
                            modifier = Modifier
                                .widthIn(max = 400.dp)
                                .fillMaxWidth()
                        ) {
                            Column(
                                Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Git LFS", style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.height(12.dp))
                                TextButton(
                                    onClick = { showLfsOptions = false; vm.lfsInstall() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("LFS install") }
                                TextButton(
                                    onClick = { showLfsOptions = false; showLfsTrack = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("LFS track…") }
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { showLfsOptions = false }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }

            // Full status page (colorized) instead of a short snackbar
            state.statusDetail?.let { detail ->
                AlertDialog(
                    onDismissRequest = { vm.dismissStatusDetail() },
                    title = { Text("Git status") },
                    text = {
                        SelectionContainer {
                            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 480.dp)) {
                                detail.lines().forEach { line ->
                                    val color = when {
                                        line.startsWith("  new file:") || line.trimStart().startsWith("new file") -> GitGreen
                                        line.contains("modified:") -> GitAmber
                                        line.contains("deleted:") || line.contains("both modified") -> GitRed
                                        line.startsWith("  ") && line.trim().isNotEmpty() &&
                                            !line.contains(":") -> Color.Gray // untracked
                                        line.startsWith("Changes to be committed") -> GitGreen
                                        line.startsWith("Changes not staged") -> GitAmber
                                        line.startsWith("Untracked") -> Color.Gray
                                        line.startsWith("Unmerged") -> GitRed
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                    Text(
                                        line.ifEmpty { " " },
                                        color = color,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { vm.dismissStatusDetail() }) { Text("Close") }
                    }
                )
            }

            if (showStatusOptions) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { showStatusOptions = false }) {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            tonalElevation = 6.dp,
                            modifier = Modifier
                                .widthIn(max = 400.dp)
                                .fillMaxWidth()
                        ) {
                            Column(
                                Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Status", style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.height(12.dp))
                                TextButton(
                                    onClick = { showStatusOptions = false; vm.gitStatus() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Git status") }
                                TextButton(
                                    onClick = { showStatusOptions = false; vm.lfsStatus() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("LFS status") }
                                TextButton(
                                    onClick = { showStatusOptions = false; vm.fullStatus() },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Git + LFS status") }
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { showStatusOptions = false }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
            }


            if (showLfsTrack) {
                AlertDialog(
                    onDismissRequest = { showLfsTrack = false },
                    title = { Text("Track with Git LFS") },
                    text = {
                        Column {
                            Text(
                                "Files matching this pattern will be stored in LFS on GitHub/GitLab when staged.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = lfsTrackPattern,
                                onValueChange = { lfsTrackPattern = it },
                                label = { Text("Pattern") },
                                placeholder = { Text("*.psd") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showLfsTrack = false
                                vm.lfsTrack(lfsTrackPattern)
                            }
                        ) { Text("Track") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLfsTrack = false }) { Text("Cancel") }
                    }
                )
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
                            // Extra scroll room for the commit bar; less on short (landscape) heights.
                            item {
                                val spacerH = if (LocalWindowSizeClass.current.isCompactHeight) 72.dp else 140.dp
                                Spacer(Modifier.height(spacerH))
                            }
                        }

                        CommitBar(
                            message = state.commitMessage,
                            onMessageChange = vm::setCommitMessage,
                            suggestedMessage = state.suggestedCommitMessage,
                            onUseSuggested = vm::applySuggestedCommitMessage,
                            signOff = state.signOff,
                            onSignOffChange = vm::setSignOff,
                            amend = state.amend,
                            onAmendChange = vm::setAmend,
                            authorName = state.authorName,
                            authorEmail = state.authorEmail,
                            enabled = (status.staged.isNotEmpty() || state.amend) && !state.busy,
                            onCommit = vm::commit
                        )
                    }
                }
            }
        }
        } // AdaptiveContent
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

internal fun ChangeType.badge() = when (this) {
    ChangeType.ADDED -> "A"
    ChangeType.MODIFIED -> "M"
    ChangeType.DELETED -> "D"
    ChangeType.RENAMED -> "R"
    ChangeType.CONFLICTING -> "!"
    ChangeType.UNTRACKED -> "U"
}

internal fun ChangeType.color() = when (this) {
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
    suggestedMessage: String = "",
    onUseSuggested: () -> Unit = {},
    signOff: Boolean,
    onSignOffChange: (Boolean) -> Unit,
    amend: Boolean = false,
    onAmendChange: (Boolean) -> Unit = {},
    authorName: String,
    authorEmail: String,
    enabled: Boolean,
    onCommit: () -> Unit
) {
    Surface(
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                label = { Text("Commit message") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
            if (suggestedMessage.isNotBlank() && message.isBlank()) {
                TextButton(
                    onClick = onUseSuggested,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Use suggested: $suggestedMessage", maxLines = 1)
                }
            } else if (suggestedMessage.isNotBlank()) {
                TextButton(
                    onClick = onUseSuggested,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Replace with suggested message")
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = signOff, onCheckedChange = onSignOffChange)
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
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = amend, onCheckedChange = onAmendChange)
                Column(Modifier.weight(1f).clickableSimple { onAmendChange(!amend) }) {
                    Text("Amend previous commit", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Rewrites the last commit (git commit --amend)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onCommit,
                enabled = enabled && message.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                val label = when {
                    amend && signOff -> "Amend (signed-off)"
                    amend -> "Amend previous commit"
                    signOff -> "Commit (signed-off)"
                    else -> "Commit to current branch"
                }
                Text(label)
            }
        }
    }
}

private fun Modifier.clickableSimple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
