package com.quickgit.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.quickgit.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    initialHost: String? = null,
    onBack: () -> Unit
) {
    var host by remember { mutableStateOf(initialHost ?: "github.com") }
    var username by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var sshKey by remember { mutableStateOf("") }
    var sshPassphrase by remember { mutableStateOf("") }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Credentials") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Text("HTTPS personal access token", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Used for https:// remotes. Generate a token with repo scope from your host (GitHub, GitLab, etc.).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("Host") }, placeholder = { Text("github.com") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Personal access token") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = {
                    vm.saveHttpsToken(host.trim(), username.trim().ifBlank { "x-access-token" }, token.trim())
                    savedMessage = "Saved token for $host"
                }, enabled = host.isNotBlank() && token.isNotBlank()) { Text("Save") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    vm.clearHttpsToken(host.trim())
                    savedMessage = "Cleared token for $host"
                }) { Text("Clear") }
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
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = sshKey, onValueChange = { sshKey = it },
                label = { Text("Private key (PEM)") },
                modifier = Modifier.fillMaxWidth().height(160.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sshPassphrase, onValueChange = { sshPassphrase = it },
                label = { Text("Passphrase (optional)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = {
                    vm.saveSshKey(sshKey, sshPassphrase.ifBlank { null })
                    savedMessage = "SSH key saved"
                }, enabled = sshKey.isNotBlank()) { Text("Save") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    vm.clearSshKey()
                    savedMessage = "SSH key cleared"
                }) { Text("Clear") }
            }

            savedMessage?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
