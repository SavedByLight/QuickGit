package com.quickgit.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.quickgit.app.ui.theme.GitGreen
import com.quickgit.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    initialHost: String? = null,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(initialHost) {
        if (!initialHost.isNullOrBlank()) vm.loadForHost(initialHost)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Credentials") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
        )
    }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text("HTTPS personal access token", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Used for https:// remotes. Generate a token with repo scope from your host (GitHub, GitLab, etc.).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.hasStoredToken) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = GitGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "A token is saved for ${state.host}",
                        style = MaterialTheme.typography.bodySmall,
                        color = GitGreen
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.host,
                onValueChange = {
                    vm.setHost(it)
                },
                label = { Text("Host") },
                placeholder = { Text("github.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            // Reload stored username when host field loses focus-ish: button below
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.loadForHost(state.host) }, modifier = Modifier.fillMaxWidth()) {
                Text("Load saved credentials for host")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.username,
                onValueChange = vm::setUsername,
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.token,
                onValueChange = vm::setToken,
                label = {
                    Text(
                        if (state.hasStoredToken) "New personal access token (leave blank to keep)"
                        else "Personal access token"
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(
                    onClick = { vm.saveHttpsToken() },
                    enabled = state.host.isNotBlank() && state.token.isNotBlank()
                ) { Text("Save") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.clearHttpsToken() }) { Text("Clear") }
            }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(Modifier.height(28.dp))

            Text("SSH key", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Used for git@ / ssh:// remotes. Paste a PEM-format private key (ed25519 or RSA).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.hasStoredSshKey) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = GitGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "An SSH private key is stored",
                        style = MaterialTheme.typography.bodySmall,
                        color = GitGreen
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.sshKey,
                onValueChange = vm::setSshKey,
                label = {
                    Text(
                        if (state.hasStoredSshKey) "Replace private key (PEM) — optional"
                        else "Private key (PEM)"
                    )
                },
                modifier = Modifier.fillMaxWidth().height(160.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.sshPassphrase,
                onValueChange = vm::setSshPassphrase,
                label = { Text("Passphrase (optional)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(
                    onClick = { vm.saveSshKey() },
                    // Allow save when pasting a new key, or when a key exists and user is setting passphrase
                    enabled = state.sshKey.isNotBlank() || state.hasStoredSshKey
                ) { Text("Save") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.clearSshKey() }) { Text("Clear") }
            }

            state.statusMessage?.let { msg ->
                Spacer(Modifier.height(16.dp))
                Text(
                    msg,
                    color = if (state.isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
