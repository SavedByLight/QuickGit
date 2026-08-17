package com.quickgit.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.quickgit.desktop.data.AppLog
import com.quickgit.desktop.data.DesktopAppUpdateConfig
import com.quickgit.desktop.data.DesktopAppUpdateManager
import com.quickgit.desktop.data.DesktopCredentialStore
import com.quickgit.desktop.data.DesktopRepoManager
import com.quickgit.desktop.data.DesktopUpdateCheckResult
import com.quickgit.desktop.ui.QuickGitDesktopApp
import com.quickgit.desktop.ui.theme.QuickGitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension

fun main() = application {
    // Allow CI-stamped version via -Dquickgit.version=1.0.N
    System.getProperty("quickgit.version")?.let {
        DesktopAppUpdateConfig.currentVersionName = it
    }

    val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)

    Window(
        onCloseRequest = ::exitApplication,
        title = "QuickGit",
        state = windowState
    ) {
        window.minimumSize = Dimension(800, 600)

        LaunchedEffect(Unit) {
            AppLog.i("Main", "QuickGit desktop started (v${DesktopAppUpdateConfig.currentVersionName})")
        }
        val credentialStore = remember { DesktopCredentialStore() }
        val repoManager = remember { DesktopRepoManager(credentialStore) }
        val updateManager = remember { DesktopAppUpdateManager(credentialStore) }

        QuickGitTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                var updatePrompt by remember { mutableStateOf<DesktopUpdateCheckResult.Available?>(null) }
                val scope = rememberCoroutineScope()

                // Auto-check once on launch (unless snoozed)
                LaunchedEffect(Unit) {
                    if (updateManager.isAutoPromptSuppressed()) return@LaunchedEffect
                    val result = withContext(Dispatchers.IO) { updateManager.checkForUpdate() }
                    if (result is DesktopUpdateCheckResult.Available) {
                        updatePrompt = result
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    QuickGitDesktopApp(
                        repoManager = repoManager,
                        credentialStore = credentialStore,
                        updateManager = updateManager
                    )

                    updatePrompt?.let { available ->
                        UpdateAvailableDialog(
                            available = available,
                            updateManager = updateManager,
                            onDismiss = { updatePrompt = null }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateAvailableDialog(
    available: DesktopUpdateCheckResult.Available,
    updateManager: DesktopAppUpdateManager,
    onDismiss: () -> Unit
) {
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "QuickGit ${available.latest.versionName} is available " +
                        "(you have ${available.current.versionName})."
                )
                Text("Package: ${available.assetName}", style = MaterialTheme.typography.bodySmall)
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Downloading… $progress%", style = MaterialTheme.typography.bodySmall)
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        downloading = true
                        status = null
                        val result = withContext(Dispatchers.IO) {
                            updateManager.downloadUpdate(
                                available.downloadUrl,
                                available.assetName
                            ) { progress = it }
                        }
                        downloading = false
                        result.fold(
                            onSuccess = { file ->
                                status = "Saved to ${file.absolutePath}"
                                updateManager.openFile(file)
                            },
                            onFailure = { status = "Download failed: ${it.message}" }
                        )
                    }
                },
                enabled = !downloading
            ) { Text(if (downloading) "Downloading…" else "Download") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    updateManager.snoozeForDays(7)
                    onDismiss()
                }, enabled = !downloading) { Text("Remind in 7 days") }
                TextButton(onClick = onDismiss, enabled = !downloading) { Text("Later") }
            }
        }
    )
}
