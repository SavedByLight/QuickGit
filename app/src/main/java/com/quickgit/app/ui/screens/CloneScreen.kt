package com.quickgit.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.ui.adaptive.AdaptiveContent
import com.quickgit.app.viewmodel.CloneViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloneScreen(
    vm: CloneViewModel,
    onBack: () -> Unit,
    onCloned: () -> Unit,
    onNeedsAuth: (String) -> Unit,
    onBrowseGitHub: () -> Unit = {}
) {
    var url by remember { mutableStateOf("") }
    val state by vm.state.collectAsState()

    val destinationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.onDestinationPicked(it) } }

    // Keep the default destination path in sync with the URL (unless user picked a folder).
    LaunchedEffect(url) {
        vm.previewDefaultDestination(url)
    }

    LaunchedEffect(state.result) {
        when (val r = state.result) {
            is GitOpResult.Success -> { vm.consumeResult(); onCloned() }
            is GitOpResult.AuthRequired -> { vm.consumeResult(); onNeedsAuth(r.remoteUrl) }
            else -> {}
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Clone repository") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
        )
    }) { padding ->
        AdaptiveContent(Modifier.padding(padding), fillHeight = false) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Repository URL") },
                placeholder = { Text("https://github.com/… or https://gerrit…/a/project") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onBrowseGitHub,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Browse my GitHub repositories")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.openGerritBrowser() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.gerritConnected || true
            ) {
                Text(
                    if (state.gerritConnected)
                        "Browse Gerrit projects (${state.gerritHost})"
                    else
                        "Browse Gerrit projects (connect under Credentials)"
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Gerrit HTTPS clones use https://host/a/project/path. After you connect under " +
                    "Credentials, QuickGit applies your HTTP password automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Text("Clone into", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.usingDefaultDestination)
                    "Defaults to a folder under your QuickGit storage location. Optionally pick a different empty folder."
                else
                    "Using the folder you picked. Clear it to fall back to the default location.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (state.destinationPath != null) {
                Text(
                    state.destinationPath!!,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
            }
            Row {
                OutlinedButton(onClick = { destinationPicker.launch(null) }) {
                    Text(if (state.usingDefaultDestination) "Choose folder…" else "Choose a different folder…")
                }
                if (!state.usingDefaultDestination) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { vm.clearPickedDestination(url) }) {
                        Text("Use default")
                    }
                }
            }
            state.destinationError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Text("History depth", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Shallow clones download less data and use less memory on mobile. Full history is needed for complete git log / blame.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.depth == 1,
                    onClick = { vm.setDepth(1) },
                    label = { Text("Shallow (1)") }
                )
                FilterChip(
                    selected = state.depth == 50,
                    onClick = { vm.setDepth(50) },
                    label = { Text("50 commits") }
                )
                FilterChip(
                    selected = state.depth == 0,
                    onClick = { vm.setDepth(0) },
                    label = { Text("Full history") }
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { vm.clone(url.trim()) },
                enabled = url.isNotBlank() && !state.inProgress,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Clone") }

            if (state.inProgress) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(state.progressText, style = MaterialTheme.typography.bodySmall)
            }

            val error = state.result as? GitOpResult.Error
            if (error != null) {
                Spacer(Modifier.height(16.dp))
                Text(error.message, color = MaterialTheme.colorScheme.error)
            }
        }
        } // AdaptiveContent
    }

    if (state.showGerritBrowser) {
        AlertDialog(
            onDismissRequest = { vm.closeGerritBrowser() },
            title = { Text("Gerrit projects") },
            text = {
                Column(Modifier.heightIn(max = 420.dp)) {
                    if (state.gerritLoading) {
                        CircularProgressIndicator()
                    } else if (state.gerritError != null) {
                        Text(state.gerritError!!, color = MaterialTheme.colorScheme.error)
                    } else if (state.gerritProjects.isEmpty()) {
                        Text("No projects found (or none visible to your account).")
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            state.gerritProjects.forEach { project ->
                                TextButton(
                                    onClick = {
                                        val cloneUrl = vm.cloneUrlForGerritProject(project.name)
                                        if (cloneUrl != null) {
                                            url = cloneUrl
                                            vm.previewDefaultDestination(cloneUrl)
                                        }
                                        vm.closeGerritBrowser()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(project.name, style = MaterialTheme.typography.bodyLarge)
                                        project.description?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.closeGerritBrowser() }) { Text("Close") }
            }
        )
    }

}
