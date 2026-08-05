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
import com.quickgit.app.viewmodel.CloneViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloneScreen(
    vm: CloneViewModel,
    onBack: () -> Unit,
    onCloned: () -> Unit,
    onNeedsAuth: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    val state by vm.state.collectAsState()

    val destinationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.onDestinationPicked(it) } }

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
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Repository URL") },
                placeholder = { Text("https://github.com/user/repo.git") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(20.dp))
            Text("Clone into", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Browse to an empty folder, or use \"New folder\" in the picker to create one.",
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
            OutlinedButton(onClick = { destinationPicker.launch(null) }) {
                Text(if (state.destinationPath == null) "Choose folder…" else "Choose a different folder…")
            }
            state.destinationError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { vm.clone(url.trim()) },
                enabled = url.isNotBlank() && state.destinationPath != null && !state.inProgress,
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
    }
}

