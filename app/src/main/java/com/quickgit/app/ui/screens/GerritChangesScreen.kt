package com.quickgit.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickgit.app.data.models.DiffLineType
import com.quickgit.app.data.models.GerritChange
import com.quickgit.app.data.models.GerritFileChange
import com.quickgit.app.ui.components.PullToRefreshBox
import com.quickgit.app.ui.theme.GitAmber
import com.quickgit.app.ui.theme.GitGreen
import com.quickgit.app.ui.theme.GitRed
import com.quickgit.app.viewmodel.GerritChangeFilter
import com.quickgit.app.viewmodel.GerritChangesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GerritChangesScreen(
    vm: GerritChangesViewModel,
    onBack: () -> Unit,
    onNeedsAuth: () -> Unit
) {
    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage, state.statusMessage, state.authRequired) {
        state.errorMessage?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeMessages()
        }
        state.statusMessage?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeMessages()
        }
        if (state.authRequired && !state.connected) {
            // Stay on screen but show empty state; user can go to Settings via top bar action
        }
    }

    // File diff overlay
    if (state.selectedFile != null) {
        BackHandler { vm.closeFileDiff() }
        GerritFileDiffContent(
            file = state.selectedFile!!,
            diff = state.fileDiff,
            loading = state.diffLoading,
            onBack = { vm.closeFileDiff() },
            snackbarHost = snackbarHost
        )
        return
    }

    // Change detail (files list)
    if (state.selected != null) {
        BackHandler { vm.closeDetail() }
        GerritChangeDetailContent(
            change = state.selected!!,
            files = state.files,
            loading = state.detailLoading,
            onBack = { vm.closeDetail() },
            onOpenFile = { vm.openFileDiff(it) },
            snackbarHost = snackbarHost
        )
        return
    }

    // List
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Gerrit changes")
                        if (!state.host.isNullOrBlank()) {
                            Text(
                                state.host!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.authRequired || !state.connected) {
                        TextButton(onClick = onNeedsAuth) { Text("Connect") }
                    }
                }
            )
        }
    ) { padding ->
        if (state.authRequired || !state.connected) {
            Box(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Connect a Gerrit account in Settings to browse open changes across all projects.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onNeedsAuth) { Text("Open Settings") }
                }
            }
            return@Scaffold
        }

        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp)
            ) {
                GerritChangeFilter.entries.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = state.filter == f,
                        onClick = { vm.setFilter(f) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = i,
                            count = GerritChangeFilter.entries.size
                        )
                    ) { Text(f.label) }
                }
            }

            PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    state.loading && state.changes.isEmpty() -> {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    state.changes.isEmpty() -> {
                        Box(
                            Modifier.fillMaxSize().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No ${state.filter.label.lowercase()} changes",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.changes, key = { it.id.ifBlank { it.number.toString() } }) { change ->
                                GerritChangeRow(change, onClick = { vm.openChange(change) })
                                HorizontalDivider()
                            }
                            item { Spacer(Modifier.height(24.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GerritChangeRow(change: GerritChange, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp, 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#${change.number}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                GerritStatusChip(change.status)
                if (change.unresolvedCommentCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${change.unresolvedCommentCount} unresolved",
                        style = MaterialTheme.typography.labelSmall,
                        color = GitAmber
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                change.subject,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${change.project} · ${change.branch}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    change.ownerName.ifBlank { "unknown" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (change.insertions > 0 || change.deletions > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "+${change.insertions}",
                        style = MaterialTheme.typography.labelSmall,
                        color = GitGreen
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "-${change.deletions}",
                        style = MaterialTheme.typography.labelSmall,
                        color = GitRed
                    )
                }
                // Labels summary
                change.labels.forEach { (name, info) ->
                    if (info.approved || info.rejected || info.value != null) {
                        Spacer(Modifier.width(6.dp))
                        val color = when {
                            info.rejected -> GitRed
                            info.approved -> GitGreen
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val v = info.value?.let { if (it > 0) "+$it" else "$it" } ?: ""
                        Text(
                            "$name$v",
                            style = MaterialTheme.typography.labelSmall,
                            color = color
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GerritStatusChip(status: String) {
    val (label, color) = when (status.uppercase()) {
        "NEW", "OPEN" -> "Open" to GitGreen
        "MERGED" -> "Merged" to MaterialTheme.colorScheme.primary
        "ABANDONED" -> "Abandoned" to GitRed
        else -> status to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GerritChangeDetailContent(
    change: GerritChange,
    files: List<GerritFileChange>,
    loading: Boolean,
    onBack: () -> Unit,
    onOpenFile: (GerritFileChange) -> Unit,
    snackbarHost: SnackbarHostState
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Change #${change.number}", maxLines = 1)
                        Text(
                            change.project,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (loading && files.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        change.subject,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${change.ownerName} · ${change.branch} · ${change.status}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (change.insertions > 0 || change.deletions > 0) {
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Text("+${change.insertions}", color = GitGreen, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(8.dp))
                            Text("-${change.deletions}", color = GitRed, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (change.labels.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            change.labels.forEach { (name, info) ->
                                val color = when {
                                    info.rejected -> GitRed
                                    info.approved -> GitGreen
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                val v = info.value?.let { if (it > 0) "+$it" else "$it" }.orEmpty()
                                Surface(
                                    color = color.copy(alpha = 0.12f),
                                    shape = MaterialTheme.shapes.extraSmall
                                ) {
                                    Text(
                                        "$name $v".trim(),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = color
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Files (${files.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (files.isEmpty() && !loading) {
                item {
                    Text(
                        "No file changes returned for this revision. " +
                            "If this is unexpected, go back and reopen the change, or check your Gerrit permissions.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(files, key = { it.path }) { file ->
                    GerritFileRow(file, onClick = { onOpenFile(file) })
                    HorizontalDivider()
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun GerritFileRow(file: GerritFileChange, onClick: () -> Unit) {
    val statusColor = when {
        file.isAdded -> GitGreen
        file.isDeleted -> GitRed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = when {
        file.isAdded -> "A"
        file.isDeleted -> "D"
        file.status.equals("R", ignoreCase = true) -> "R"
        else -> "M"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = statusColor.copy(alpha = 0.15f),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.path,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (file.oldPath != null) {
                Text(
                    "from ${file.oldPath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (file.linesInserted > 0 || file.linesDeleted > 0) {
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (file.linesInserted > 0) {
                    Text("+${file.linesInserted}", style = MaterialTheme.typography.labelSmall, color = GitGreen)
                }
                if (file.linesDeleted > 0) {
                    Text("-${file.linesDeleted}", style = MaterialTheme.typography.labelSmall, color = GitRed)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GerritFileDiffContent(
    file: GerritFileChange,
    diff: com.quickgit.app.data.models.FileDiff?,
    loading: Boolean,
    onBack: () -> Unit,
    snackbarHost: SnackbarHostState
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        file.path.substringAfterLast('/'),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                diff == null -> {
                    Text(
                        "Failed to load diff",
                        Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                diff.isBinary -> {
                    Text(
                        "Binary file — diff not shown",
                        Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                diff.lines.isEmpty() -> {
                    Text(
                        "No changes to show",
                        Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        items(diff.lines) { line ->
                            val (bg, fg) = when (line.type) {
                                DiffLineType.ADDED -> GitGreen.copy(alpha = 0.15f) to GitGreen
                                DiffLineType.REMOVED -> GitRed.copy(alpha = 0.15f) to GitRed
                                DiffLineType.HEADER ->
                                    MaterialTheme.colorScheme.surfaceVariant to
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                DiffLineType.CONTEXT ->
                                    MaterialTheme.colorScheme.surface to
                                        MaterialTheme.colorScheme.onSurface
                            }
                            Text(
                                line.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = fg,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bg)
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
