package com.quickgit.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.AppUpdateConfig
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

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.setReposRoot(it) } }

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
            Text("Repo storage location", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Where local clones live on disk. Point this at a folder you can also reach with " +
                    "your file manager if you need to copy files into a repo from outside the app — " +
                    "on newer Android versions the default folder is private to QuickGit and isn't " +
                    "reachable from other apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                state.reposRootPath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = { folderPicker.launch(null) }) {
                    Text("Choose folder…")
                }
                if (state.reposRootIsUserChosen) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.resetReposRoot() }) {
                        Text("Use default")
                    }
                }
            }
            Text(
                "Note: existing local repos won't move automatically — clone or re-clone them again " +
                    "after switching.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(Modifier.height(28.dp))

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
                        "QuickGit verifies it with GitHub and uses it for clone, push, pull, and pull requests."
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
                "Connect gitlab.com or a self-hosted instance. Token needs api + read_repository " +
                    "(and write_repository for push). Enables MRs, issues, and remote file browsing.",
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
            var gitlabToken by remember { mutableStateOf("") }
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
                    onClick = { vm.connectGitLab(gitlabHost, gitlabToken) },
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

            // ---- Gerrit ----
            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(Modifier.height(28.dp))

            Text("Gerrit account", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Connect any Gerrit host. Use your Gerrit username and an HTTP password " +
                    "(Settings → HTTP Credentials) or access token. Enables browsing Changes and posting " +
                    "comments / Code-Review votes (+2 / −1 etc.).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.gerritConnected && state.gerritUsername != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = GitGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Connected as ${state.gerritUsername} on ${state.gerritHost}",
                        style = MaterialTheme.typography.bodySmall,
                        color = GitGreen
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            var gerritHost by remember { mutableStateOf(state.gerritHost) }
            var gerritUser by remember { mutableStateOf("") }
            var gerritPass by remember { mutableStateOf("") }
            // Keep the host field in sync when verifyGerritIfConnected restores it from disk.
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
                label = { Text("Host") },
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
                value = gerritPass,
                onValueChange = { gerritPass = it },
                label = { Text("HTTP password / access token") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(
                    onClick = { vm.connectGerrit(gerritHost, gerritUser, gerritPass) },
                    enabled = gerritHost.isNotBlank() && gerritUser.isNotBlank() && gerritPass.isNotBlank() && !state.connecting
                ) {
                    if (state.connecting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("Connecting…")
                    } else {
                        Text("Connect Gerrit")
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { vm.disconnectGerrit() },
                    enabled = state.gerritConnected && !state.connecting
                ) { Text("Disconnect") }
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

            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(Modifier.height(28.dp))

            Text("GPG commit signing", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Import an armored OpenPGP secret key to sign commits. Add the matching public key " +
                    "to your GitHub account (Settings → SSH and GPG keys) so GitHub shows Verified.",
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
                modifier = Modifier.fillMaxWidth().height(160.dp)
            )
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
                Button(
                    onClick = { vm.saveGpgKey() },
                    enabled = state.gpgKey.isNotBlank() || state.hasStoredGpgKey
                ) { Text("Save") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.clearGpgKey() }) { Text("Clear") }
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

            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text("About & updates", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Current version ${state.appVersionName} (${state.appVersionCode}). " +
                    "Checks GitHub Releases on ${AppUpdateConfig.OWNER}/${AppUpdateConfig.REPO} " +
                    "for a newer APK published by the release workflow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (state.updateAvailable && state.updateLatestName != null) {
                Text(
                    "New version available: ${state.updateLatestName}",
                    color = GitGreen,
                    style = MaterialTheme.typography.bodyMedium
                )
                state.updateNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        notes.take(400) + if (notes.length > 400) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            if (state.updateDownloading) {
                LinearProgressIndicator(
                    progress = { state.updateProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Downloading… ${state.updateProgress}%",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
            }
            val context = LocalContext.current
            LaunchedEffect(state.updateNeedsInstallPermission) {
                if (state.updateNeedsInstallPermission) {
                    try {
                        context.startActivity(vm.installPermissionIntent())
                    } catch (_: Exception) { }
                    vm.clearInstallPermissionFlag()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.checkForUpdate() },
                    enabled = !state.updateChecking && !state.updateDownloading
                ) {
                    if (state.updateChecking) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.SystemUpdate, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (state.updateChecking) "Checking…" else "Check for updates")
                }
                if (state.updateAvailable) {
                    Button(
                        onClick = { vm.downloadAndInstallUpdate() },
                        enabled = !state.updateDownloading
                    ) {
                        Text(if (state.updateDownloading) "Downloading…" else "Download & install")
                    }
                }
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
