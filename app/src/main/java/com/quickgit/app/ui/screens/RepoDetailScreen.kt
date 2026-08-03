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
    onConflicts: () -> Unit,
    onNeedsAuth: (String) -> Unit
) {
    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.lastResult) {
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
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Column { Text(repoName); Text(state.branch, style = MaterialTheme.typography.bodySmall) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onOpenFiles) { Icon(Icons.Default.FolderOpen, "Files") }
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Default.History, "History") }
                    IconButton(onClick = onOpenBranches) { Icon(Icons.Default.AccountTree, "Branches") }
                }
            )
        }
    ) { padding ->
        val status = state.status
        Column(Modifier.padding(padding).fillMaxSize()) {

            Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                OutlinedButton(onClick = { vm.pull() }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ArrowDownward, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pull")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.push() }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ArrowUpward, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Push")
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

            if (state.busy && status == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (status != null) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (status.staged.isNotEmpty()) {
                        item { SectionHeader("Staged changes (${status.staged.size})") }
                        items(status.staged, key = { "s_" + it.path }) { fc ->
                            ChangeRow(fc, onToggle = { vm.toggleStage(fc.path, true) }, onClick = { onOpenDiff(fc.path, "staged") })
                        }
                    }
                    val unstagedAll = status.unstaged + status.untracked
                    if (unstagedAll.isNotEmpty()) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp, 16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Changes (${unstagedAll.size})", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
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
                    enabled = status.staged.isNotEmpty() && !state.busy,
                    onCommit = vm::commit
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp))
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
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCommit, enabled = enabled && message.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Commit to current branch")
            }
        }
    }
}

private fun Modifier.clickableSimple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
