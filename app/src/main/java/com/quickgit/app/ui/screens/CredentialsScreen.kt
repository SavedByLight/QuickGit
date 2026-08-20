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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.quickgit.app.ui.adaptive.AdaptiveContent
import com.quickgit.app.ui.theme.GitGreen
import com.quickgit.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialsScreen(
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
        AdaptiveContent(Modifier.padding(padding), fillHeight = false) {
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text("Commit identity", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Name and email written on every commit (and shown on GitHub after you push). " +
                    "Connecting a GitHub account fills these in if they are still the defaults.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.authorName,
                onValueChange = vm::setAuthorName,
                label = { Text("Name") },
                placeholder = { Text("Your name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.authorEmail,
                onValueChange = vm::setAuthorEmail,
                label = { Text("Email") },
                placeholder = { Text("you@users.noreply.github.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.saveAuthor() },
                enabled = state.authorName.isNotBlank() && state.authorEmail.isNotBlank()
            ) { Text("Save identity") }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(Modifier.height(28.dp))

            Text(
                if (state.host.equals("github.com", ignoreCase = true)) "GitHub account"
                else "HTTPS personal access token",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.host.equals("github.com", ignoreCase = true))
                    "Paste a personal access token (classic or fine-grained) with repo access. " +
                        "For organization repos, classic tokens also need read:org; fine-grained tokens must " +
                        "grant access to each organization. QuickGit verifies it with GitHub and uses it for " +
                        "clone, push, pull, pull requests, and listing org repositories."
                else
                    "Used for https:// remotes. Generate a token with repo scope from your host (GitHub, GitLab, etc.).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.githubLogin != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = GitGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Connected as @${state.githubLogin}" +
                            (state.githubName?.let { " ($it)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = GitGreen
                    )
                }
            } else if (state.hasStoredToken) {
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
                    enabled = state.host.isNotBlank() && state.token.isNotBlank() && !state.connecting
                ) {
                    if (state.connecting) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Connecting…")
                    } else {
                        Text(
                            if (state.host.equals("github.com", ignoreCase = true)) "Connect"
                            else "Save"
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { vm.clearHttpsToken() },
                    enabled = !state.connecting
                ) {
                    Text(
                        if (state.host.equals("github.com", ignoreCase = true) && state.hasStoredToken)
                            "Disconnect"
                        else "Clear"
                    )
                }
            }

            // ---- GitLab ----
            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(Modifier.height(28.dp))

            Text("GitLab account", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Connect gitlab.com or a self-hosted instance. Paste a personal access token with " +
                    "api + read_repository scopes (add write_repository for push). Username is optional — " +
                    "it will be filled from the token when you connect. Enables MRs, issues, and remote browsing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.gitlabConnected && state.gitlabUsername != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = GitGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Connected as @${state.gitlabUsername} on ${state.gitlabHost}",
                        style = MaterialTheme.typography.bodySmall,
                        color = GitGreen
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            var gitlabHost by remember { mutableStateOf(state.gitlabHost.ifBlank { "gitlab.com" }) }
            var gitlabUser by remember { mutableStateOf(state.gitlabUsername.orEmpty()) }
            var gitlabToken by remember { mutableStateOf("") }
            // Keep fields in sync when verifyGitLabIfConnected restores values from disk.
            LaunchedEffect(state.gitlabHost) {
                if (state.gitlabHost.isNotBlank() && gitlabHost != state.gitlabHost) {
                    gitlabHost = state.gitlabHost
                }
            }
            LaunchedEffect(state.gitlabUsername) {
                if (!state.gitlabUsername.isNullOrBlank() && gitlabUser.isBlank()) {
                    gitlabUser = state.gitlabUsername.orEmpty()
                }
            }
            OutlinedTextField(
                value = gitlabHost,
                onValueChange = { gitlabHost = it },
                label = { Text("Host") },
                placeholder = { Text("gitlab.com or gitlab.example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = gitlabUser,
                onValueChange = { gitlabUser = it },
                label = { Text("Username") },
                placeholder = { Text("Optional — filled from token if left blank") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = gitlabToken,
                onValueChange = { gitlabToken = it },
                label = { Text("Personal access token") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(
                    onClick = {
                        vm.connectGitLab(
                            gitlabHost,
                            gitlabToken,
                            gitlabUser.takeIf { it.isNotBlank() }
                        )
                    },
                    enabled = gitlabToken.isNotBlank() && !state.connecting
                ) {
                    if (state.connecting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Connecting…")
                    } else {
                        Text("Connect GitLab")
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { vm.disconnectGitLab() },
                    enabled = state.gitlabConnected && !state.connecting
                ) { Text("Disconnect") }
            }

            Spacer(Modifier.height(28.dp))
            
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text("Gerrit account", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Connect a Gerrit host to clone repositories. Use your Gerrit username and an " +
                    "HTTP password from Gerrit → Settings → HTTP Credentials (not necessarily your web login password).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (state.gerritConnected && state.gerritUsername != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = GitGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Connected as ${state.gerritUsername} on ${state.gerritHost}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            var gerritHost by remember { mutableStateOf(state.gerritHost.ifBlank { "" }) }
            var gerritUser by remember { mutableStateOf(state.gerritUsername.orEmpty()) }
            var gerritPassword by remember { mutableStateOf("") }
            LaunchedEffect(state.gerritHost) {
                if (state.gerritHost.isNotBlank() && gerritHost != state.gerritHost) {
                    gerritHost = state.gerritHost
                }
            }
            LaunchedEffect(state.gerritUsername) {
                if (!state.gerritUsername.isNullOrBlank() && gerritUser.isBlank()) {
                    gerritUser = state.gerritUsername.orEmpty()
                }
            }
            OutlinedTextField(
                value = gerritHost,
                onValueChange = { gerritHost = it },
                label = { Text("Gerrit host") },
                placeholder = { Text("gerrit.example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = gerritUser,
                onValueChange = { gerritUser = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = gerritPassword,
                onValueChange = { gerritPassword = it },
                label = { Text("HTTP password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        vm.connectGerrit(gerritHost, gerritUser, gerritPassword)
                        gerritPassword = ""
                    },
                    enabled = gerritHost.isNotBlank() && gerritUser.isNotBlank() &&
                        gerritPassword.isNotBlank() && !state.connecting
                ) {
                    if (state.connecting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.gerritConnected) "Reconnect Gerrit" else "Connect Gerrit")
                }
                if (state.gerritConnected) {
                    OutlinedButton(onClick = { vm.disconnectGerrit() }) {
                        Text("Disconnect")
                    }
                }
            }

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

            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(Modifier.height(28.dp))

            Text("GPG commit signing", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Import an armored OpenPGP secret key to sign commits. Add the matching public key " +
                    "to your GitHub account (Settings → SSH and GPG keys) so GitHub shows Verified.\n\n" +
                    "Export on a computer:\n" +
                    "  gpg --armor --export-secret-keys YOUR_KEY_ID\n" +
                    "Paste the full block including BEGIN/END lines. If the key is encrypted, enter the passphrase below before Save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.hasStoredGpgKey) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = GitGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "A GPG secret key is stored",
                        style = MaterialTheme.typography.bodySmall,
                        color = GitGreen
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val clipboard = LocalClipboardManager.current
            OutlinedTextField(
                value = state.gpgKey,
                onValueChange = vm::setGpgKey,
                label = {
                    Text(
                        if (state.hasStoredGpgKey) "Replace secret key (ASCII-armored) — optional"
                        else "Secret key (ASCII-armored)"
                    )
                },
                placeholder = { Text("-----BEGIN PGP PRIVATE KEY BLOCK-----") },
                // minLines instead of fixed height so Chromebook keyboard/paste works more reliably
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                maxLines = 12
            )
            Spacer(Modifier.height(8.dp))
            // Explicit paste — Ctrl+V / trackpad paste sometimes does not update Compose state on ChromeOS
            OutlinedButton(
                onClick = {
                    val clip = clipboard.getText()?.text
                    if (!clip.isNullOrBlank()) {
                        vm.setGpgKey(clip)
                    } else {
                        vm.reportGpgPasteEmpty()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Paste key from clipboard")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.gpgPassphrase,
                onValueChange = vm::setGpgPassphrase,
                label = { Text("Passphrase (if the key is encrypted)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Row {
                // Always enabled: validation runs on click. Disabled Save was blocking Chromebook
                // users whose paste did not update the TextField state.
                Button(onClick = { vm.saveGpgKey() }) { Text("Save") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.clearGpgKey() }) { Text("Clear") }
            }
            // Show save result next to the GPG controls (not only at the bottom of the page)
            state.statusMessage?.let { msg ->
                if (msg.contains("GPG", ignoreCase = true) || msg.contains("Clipboard", ignoreCase = true) ||
                    msg.contains("key", ignoreCase = true) && state.isError
                ) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        msg,
                        color = if (state.isError) MaterialTheme.colorScheme.error
                        else GitGreen,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Sign commits with GPG",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = state.gpgSignEnabled,
                    onCheckedChange = vm::setGpgSignEnabled,
                    enabled = state.hasStoredGpgKey
                )
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
        } // AdaptiveContent
    }
}
