package com.quickgit.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
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
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var importHost by remember { mutableStateOf("") }
    var importUser by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }

    val csvPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val textCsv = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            }.orEmpty()
            if (textCsv.isNotBlank()) vm.importPasswordManagerCsv(textCsv)
        } catch (e: Exception) {
            // Surface via status if possible — ViewModel status is the usual channel
        }
    }

    LaunchedEffect(initialHost) {
        if (!initialHost.isNullOrBlank()) vm.loadForHost(initialHost)
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import from password manager") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Store the personal access token (or Gerrit HTTP password) in the " +
                            "password field of your password manager. Host/URL and username " +
                            "map as usual.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = importHost,
                        onValueChange = { importHost = it },
                        label = { Text("Host or URL") },
                        placeholder = { Text("github.com or https://gitlab.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = importUser,
                        onValueChange = { importUser = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        label = { Text("Password (token)") },
                        placeholder = { Text("Personal access token") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.importFromPasswordManager(importHost, importUser, importPassword)
                        showImportDialog = false
                        importPassword = ""
                    },
                    enabled = importHost.isNotBlank() && importPassword.isNotBlank()
                ) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
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
            // ---- Managed accounts (multi-account) ----
            Text("Connected accounts", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "You can keep multiple accounts of each type (e.g. two GitHub, two GitLab, two Gerrit). " +
                    "Switch the active one per provider below — that account is used for API calls and git over HTTPS. " +
                    "Remove an account with the trash icon; adding more is done in the forms further down.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (state.managedAccounts.isEmpty()) {
                Text(
                    "No accounts yet. Add one below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.managedAccounts.forEach { acct ->
                    val isActive = state.activeAccountIds[acct.provider] == acct.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { vm.setActiveManagedAccount(acct.id) }) {
                                Icon(
                                    if (isActive) Icons.Default.RadioButtonChecked
                                    else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = if (isActive) "Active" else "Set active",
                                    tint = if (isActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(acct.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    acct.providerLabel() + if (isActive) " · active" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { vm.removeManagedAccount(acct.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove account",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "To add another account of any type, fill the matching form below and connect. " +
                    "Each successful connection is added to the list above (you do not need to disconnect first). " +
                    "Password managers: put the token in the password field.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showImportDialog = true }) {
                    Text("Import account")
                }
                OutlinedButton(onClick = {
                    csvPicker.launch(arrayOf("text/*", "text/csv", "application/csv", "*/*"))
                }) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import CSV")
                }
            }

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
                if (state.host.equals("github.com", ignoreCase = true)) "Add GitHub account"
                else "Add HTTPS personal access token",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.host.equals("github.com", ignoreCase = true))
                    "Paste a personal access token (classic or fine-grained) with repo access. " +
                        "For organization repos, classic tokens also need read:org; fine-grained tokens must " +
                        "grant access to each organization. Each successful connect adds (or updates) that " +
                        "account in the list above — you can keep several GitHub accounts at once."
                else
                    "Used for https:// remotes. Generate a token with repo scope from your host. " +
                        "Each successful save is tracked as a managed account when the host is a known forge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.githubLogin != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = GitGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Active GitHub: @${state.githubLogin}" +
                            (state.githubName?.let { " ($it)" } ?: "") +
                            " — add another with a different token below",
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
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.loadForHost(state.host) }, modifier = Modifier.fillMaxWidth()) {
                Text("Load saved credentials for host")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.username,
                onValueChange = vm::setUsername,
                label = { Text("Username (optional for GitHub)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.token,
                onValueChange = vm::setToken,
                label = { Text("Personal access token (password field)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Spacer(Modifier.height(12.dp))
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
                        if (state.host.equals("github.com", ignoreCase = true)) "Add GitHub account"
                        else "Save token"
                    )
                }
            }

            // ---- GitLab ----
            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(Modifier.height(28.dp))

            Text("Add GitLab account", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Connect gitlab.com or a self-hosted instance. Paste a personal access token with " +
                    "api + read_repository scopes (add write_repository for push). Username is optional — " +
                    "it will be filled from the token when you connect. Each successful connect adds that " +
                    "account to the list above; you can keep several GitLab accounts at once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.gitlabConnected && state.gitlabUsername != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = GitGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Active GitLab: @${state.gitlabUsername} on ${state.gitlabHost} — add another below",
                        style = MaterialTheme.typography.bodySmall,
                        color = GitGreen
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            var gitlabHost by remember { mutableStateOf(state.gitlabHost.ifBlank { "gitlab.com" }) }
            var gitlabUser by remember { mutableStateOf("") }
            var gitlabToken by remember { mutableStateOf("") }
            // Keep host in sync when verify restores the active host from disk; do not overwrite
            // username/token so the form stays ready for adding another account.
            LaunchedEffect(state.gitlabHost) {
                if (state.gitlabHost.isNotBlank() && gitlabHost != state.gitlabHost) {
                    gitlabHost = state.gitlabHost
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
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = gitlabToken,
                onValueChange = { gitlabToken = it },
                label = { Text("Personal access token (password field)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    vm.connectGitLab(
                        gitlabHost,
                        gitlabToken,
                        gitlabUser.takeIf { it.isNotBlank() }
                    )
                    gitlabToken = ""
                    gitlabUser = ""
                },
                enabled = gitlabToken.isNotBlank() && !state.connecting
            ) {
                if (state.connecting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Connecting…")
                } else {
                    Text("Add GitLab account")
                }
            }

            Spacer(Modifier.height(28.dp))
            
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text("Add Gerrit account", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Connect a Gerrit host to clone repositories. Use your Gerrit username and an " +
                    "HTTP password from Gerrit → Settings → HTTP Credentials (not necessarily your web login password). " +
                    "Each successful connect adds that account to the list above; you can keep several Gerrit accounts at once.",
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
                        "Active Gerrit: ${state.gerritUsername} on ${state.gerritHost} — add another below",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            var gerritHost by remember { mutableStateOf(state.gerritHost.ifBlank { "" }) }
            var gerritUser by remember { mutableStateOf("") }
            var gerritPassword by remember { mutableStateOf("") }
            // Keep host in sync with active account; leave username/password empty so the form
            // is ready for adding another account without overwriting.
            LaunchedEffect(state.gerritHost) {
                if (state.gerritHost.isNotBlank() && gerritHost != state.gerritHost) {
                    gerritHost = state.gerritHost
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
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = gerritPassword,
                onValueChange = { gerritPassword = it },
                label = { Text("HTTP password (token)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    vm.connectGerrit(gerritHost, gerritUser, gerritPassword)
                    gerritPassword = ""
                    gerritUser = ""
                },
                enabled = gerritHost.isNotBlank() && gerritUser.isNotBlank() &&
                    gerritPassword.isNotBlank() && !state.connecting
            ) {
                if (state.connecting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Add Gerrit account")
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
