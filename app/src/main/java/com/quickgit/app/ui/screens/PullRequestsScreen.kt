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
import com.quickgit.app.data.models.BranchInfo
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.data.models.MergeMethod
import com.quickgit.app.data.models.PrComment
import com.quickgit.app.data.models.PrStateFilter
import com.quickgit.app.data.models.PullRequest
import com.quickgit.app.ui.theme.GitAmber
import com.quickgit.app.ui.theme.GitGreen
import com.quickgit.app.ui.theme.GitRed
import com.quickgit.app.viewmodel.PullRequestsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRequestsScreen(
    vm: PullRequestsViewModel,
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
        PullRequestDetailContent(vm = vm, onBack = { vm.closeDetail() }, snackbarHost = snackbarHost)
        return
    }

    if (!state.supported) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (state.isGitLab) "Merge requests" else "Pull requests") },
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
                    "Pull / merge request management supports GitHub and GitLab. " +
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
                title = { Text(if (state.isGitLab) "Merge requests" else "Pull requests") },
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
                text = { Text("New PR") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp)) {
                PrStateFilter.entries.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = state.filter == f,
                        onClick = { vm.setFilter(f) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = PrStateFilter.entries.size)
                    ) { Text(f.label) }
                }
            }

            if (state.loading && state.pullRequests.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (state.pullRequests.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No ${state.filter.label.lowercase()} pull requests", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.pullRequests, key = { it.number }) { pr ->
                        PrRow(pr, onClick = { vm.openDetail(pr.number) })
                        HorizontalDivider()
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePullRequestDialog(
            branches = state.localBranches,
            onDismiss = { showCreateDialog = false },
            onCreate = { title, body, head, base, draft ->
                vm.createPullRequest(title, body, head, base, draft)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun PrRow(pr: PullRequest, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${pr.number}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                PrStatusChip(pr)
            }
            Spacer(Modifier.height(2.dp))
            Text(pr.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                "${pr.authorLogin} · ${pr.headRef} → ${pr.baseRef}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PrStatusChip(pr: PullRequest) {
    val (label, color) = when {
        pr.merged -> "Merged" to GitAmber
        pr.state == "closed" -> "Closed" to GitRed
        pr.isDraft -> "Draft" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Open" to GitGreen
    }
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(6.dp, 2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullRequestDetailContent(
    vm: PullRequestsViewModel,
    onBack: () -> Unit,
    snackbarHost: SnackbarHostState
) {
    val state by vm.state.collectAsState()
    val pr = state.selected
    var showMergeDialog by remember { mutableStateOf(false) }
    var commentText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(if (pr != null) "PR #${pr.number}" else "Pull request") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        if (state.detailLoading || pr == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(16.dp)) {
                PrStatusChip(pr)
                Spacer(Modifier.height(8.dp))
                Text(pr.title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${pr.authorLogin} wants to merge into ${pr.baseRef} from ${pr.headRef}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!pr.body.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(pr.body, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            HorizontalDivider()

            // Actions
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            vm.checkoutLocally { result ->
                                scope.launch {
                                    val msg = when (result) {
                                        is GitOpResult.Success -> "Checked out pr-${pr.number} locally"
                                        is GitOpResult.Error -> result.message
                                        else -> "Checkout did not complete"
                                    }
                                    snackbarHost.showSnackbar(msg)
                                }
                            }
                        },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Checkout locally") }
                }

                if (pr.state == "open" && !pr.merged) {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { vm.setOpen(false) }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                            Text("Close")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { showMergeDialog = true }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                            Text("Merge")
                        }
                    }
                } else if (pr.state == "closed" && !pr.merged) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.setOpen(true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Reopen")
                    }
                }
            }

            HorizontalDivider()

            // Comments
            Column(Modifier.padding(16.dp)) {
                Text("Comments (${state.comments.size})", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                state.comments.forEach { c -> CommentRow(c); Spacer(Modifier.height(8.dp)) }

                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    label = { Text("Add a comment") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.addComment(commentText); commentText = "" },
                    enabled = !state.busy && commentText.isNotBlank(),
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Comment") }
            }
        }
    }

    if (showMergeDialog && pr != null) {
        MergeDialog(
            onDismiss = { showMergeDialog = false },
            onMerge = { method, title -> vm.merge(method, title); showMergeDialog = false }
        )
    }
}

@Composable
private fun CommentRow(comment: PrComment) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(comment.authorLogin, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(comment.body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MergeDialog(onDismiss: () -> Unit, onMerge: (MergeMethod, String?) -> Unit) {
    var method by remember { mutableStateOf(MergeMethod.MERGE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge pull request") },
        text = {
            Column {
                MergeMethod.entries.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { method = m },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = method == m, onClick = { method = m })
                        Text(m.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMerge(method, null) }) { Text("Merge") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePullRequestDialog(
    branches: List<BranchInfo>,
    onDismiss: () -> Unit,
    onCreate: (title: String, body: String, head: String, base: String, draft: Boolean) -> Unit
) {
    val localBranches = branches.filter { !it.isRemote }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var head by remember { mutableStateOf(localBranches.firstOrNull { it.isCurrent }?.name ?: localBranches.firstOrNull()?.name ?: "") }
    var base by remember { mutableStateOf(localBranches.firstOrNull { it.name == "main" || it.name == "master" }?.name ?: "main") }
    var draft by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New pull request") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Spacer(Modifier.height(8.dp))
                BranchDropdown(label = "From (head)", selected = head, options = localBranches.map { it.name }, onSelect = { head = it })
                Spacer(Modifier.height(8.dp))
                BranchDropdown(label = "Into (base)", selected = base, options = localBranches.map { it.name }, onSelect = { base = it })
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = draft, onCheckedChange = { draft = it })
                    Text("Open as draft")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title, body, head, base, draft) },
                enabled = title.isNotBlank() && head.isNotBlank() && base.isNotBlank() && head != base
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchDropdown(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}
