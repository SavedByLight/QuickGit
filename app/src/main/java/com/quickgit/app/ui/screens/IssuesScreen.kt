package com.quickgit.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.models.Issue
import com.quickgit.app.data.models.IssueStateFilter
import com.quickgit.app.data.models.PrComment
import com.quickgit.app.ui.theme.GitGreen
import com.quickgit.app.ui.theme.GitRed
import com.quickgit.app.viewmodel.IssuesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssuesScreen(
    vm: IssuesViewModel,
    onBack: (() -> Unit)? = null,
    onNeedsAuth: (String) -> Unit
) {
    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.errorMessage, state.statusMessage, state.authRequiredHost) {
        state.errorMessage?.let { snackbarHost.showSnackbar(it); vm.consumeMessages() }
        state.statusMessage?.let { snackbarHost.showSnackbar(it); vm.consumeMessages() }
        state.authRequiredHost?.let { onNeedsAuth("https://$it/"); vm.consumeMessages() }
    }

    if (state.selected != null) {
        BackHandler { vm.closeDetail() }
        IssueDetailContent(vm = vm, onBack = { vm.closeDetail() }, snackbarHost = snackbarHost)
        return
    }

    if (!state.supported) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Issues") },
                    navigationIcon = {
                        onBack?.let { back ->
                            IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Back") }
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Issue management supports GitHub and GitLab repositories. " +
                        "This repo's origin isn't recognized as either.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Issues") },
                navigationIcon = {
                    onBack?.let { back ->
                        IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Back") }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New issue") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp)) {
                IssueStateFilter.entries.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = state.filter == f,
                        onClick = { vm.setFilter(f) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = IssueStateFilter.entries.size)
                    ) { Text(f.label) }
                }
            }

            if (state.loading && state.issues.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (state.issues.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No ${state.filter.label.lowercase()} issues",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.issues, key = { it.number }) { issue ->
                        IssueRow(issue, onClick = { vm.openDetail(issue.number) })
                        HorizontalDivider()
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateIssueDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { title, body ->
                vm.createIssue(title, body)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun IssueRow(issue: Issue, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#${issue.number}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                IssueStatusChip(issue)
            }
            Spacer(Modifier.height(2.dp))
            Text(issue.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            val meta = buildString {
                append(issue.authorLogin)
                if (issue.labels.isNotEmpty()) {
                    append(" · ")
                    append(issue.labels.take(3).joinToString(", "))
                }
                if (issue.commentsCount > 0) {
                    append(" · ")
                    append("${issue.commentsCount} comment${if (issue.commentsCount == 1) "" else "s"}")
                }
            }
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IssueStatusChip(issue: Issue) {
    val (label, color) = if (issue.state == "closed") {
        "Closed" to GitRed
    } else {
        "Open" to GitGreen
    }
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(6.dp, 2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IssueDetailContent(
    vm: IssuesViewModel,
    onBack: () -> Unit,
    snackbarHost: SnackbarHostState
) {
    val state by vm.state.collectAsState()
    val issue = state.selected
    var commentText by remember { mutableStateOf("") }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(if (issue != null) "Issue #${issue.number}" else "Issue") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        if (state.detailLoading || issue == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(16.dp)) {
                IssueStatusChip(issue)
                Spacer(Modifier.height(8.dp))
                Text(issue.title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Opened by ${issue.authorLogin}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (issue.labels.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        issue.labels.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (!issue.body.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(issue.body, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            HorizontalDivider()

            Column(Modifier.padding(16.dp)) {
                if (issue.state == "open") {
                    OutlinedButton(
                        onClick = { vm.setOpen(false) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Close issue") }
                } else {
                    Button(
                        onClick = { vm.setOpen(true) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Reopen issue") }
                }
            }

            HorizontalDivider()

            Column(Modifier.padding(16.dp)) {
                Text("Comments (${state.comments.size})", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                state.comments.forEach { c ->
                    IssueCommentRow(c)
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    label = { Text("Add a comment") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        vm.addComment(commentText)
                        commentText = ""
                    },
                    enabled = !state.busy && commentText.isNotBlank(),
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Comment") }
            }
        }
    }
}

@Composable
private fun IssueCommentRow(comment: PrComment) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(comment.authorLogin, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(comment.body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CreateIssueDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, body: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New issue") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title.trim(), body.trim()) },
                enabled = title.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
