package com.quickgit.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    var folderName by remember { mutableStateOf("") }
    val state by vm.state.collectAsState()

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
                onValueChange = {
                    url = it
                    if (folderName.isBlank()) {
                        folderName = it.substringAfterLast('/').removeSuffix(".git")
                    }
                },
                label = { Text("Repository URL") },
                placeholder = { Text("https://github.com/user/repo.git") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("Local folder name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { vm.clone(url.trim(), folderName.trim()) },
                enabled = url.isNotBlank() && folderName.isNotBlank() && !state.inProgress,
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
