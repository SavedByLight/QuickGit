package com.quickgit.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickgit.app.data.models.FileChange
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.ui.theme.GitAmber
import com.quickgit.app.viewmodel.MergeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(repoPath: String, vm: MergeViewModel, onBack: () -> Unit, onFinished: () -> Unit) {
    LaunchedEffect(repoPath) { vm.init(repoPath) }
    val state by vm.state.collectAsState()
    var editing by remember { mutableStateOf<FileChange?>(null) }
    var commitMessage by remember { mutableStateOf("Merge and resolve conflicts") }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.lastResult) {
        when (val r = state.lastResult) {
            is GitOpResult.Success -> { vm.consumeResult(); onFinished() }
            is GitOpResult.Error -> { snackbarHost.showSnackbar(r.message); vm.consumeResult() }
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Resolve conflicts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.conflicts.isEmpty() && !state.busy) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No conflicts remaining. Ready to finish the merge.")
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(state.conflicts, key = { it.path }) { fc ->
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp, 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = GitAmber)
                            Spacer(Modifier.width(12.dp))
                            Text(fc.path, Modifier.weight(1f), maxLines = 1)
                            TextButton(onClick = { editing = fc }) { Text("Resolve") }
                        }
                        HorizontalDivider()
                    }
                }
            }

            Surface(shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        label = { Text("Merge commit message") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        OutlinedButton(onClick = { vm.abort() }, modifier = Modifier.weight(1f)) { Text("Abort merge") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { vm.finishMerge(commitMessage, "Mobile User", "mobile@example.com") },
                            enabled = state.conflicts.isEmpty() && commitMessage.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Complete merge") }
                    }
                }
            }
        }
    }

    editing?.let { fc ->
        ConflictEditorDialog(
            fc = fc,
            vm = vm,
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun ConflictEditorDialog(fc: FileChange, vm: MergeViewModel, onDismiss: () -> Unit) {
    var ours by remember { mutableStateOf<String?>(null) }
    var theirs by remember { mutableStateOf<String?>(null) }
    var edited by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(fc.path) {
        vm.conflictSides(fc.path) { (_, o, t) ->
            ours = o; theirs = t; edited = o ?: t ?: ""
            loaded = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(fc.path.substringAfterLast('/')) },
        text = {
            if (!loaded) {
                Box(Modifier.height(120.dp).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Row {
                        OutlinedButton(onClick = { edited = ours ?: "" }, enabled = ours != null, modifier = Modifier.weight(1f)) {
                            Text("Keep ours")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { edited = theirs ?: "" }, enabled = theirs != null, modifier = Modifier.weight(1f)) {
                            Text("Keep theirs")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Edit merged content:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = edited ?: "",
                        onValueChange = { edited = it },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.resolveWithContent(fc.path, edited ?: ""); onDismiss() },
                enabled = loaded
            ) { Text("Mark resolved") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
