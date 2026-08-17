package com.quickgit.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.quickgit.app.QuickGitApp
import com.quickgit.app.data.DownloadResult
import com.quickgit.app.data.UpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs once on composition: checks GitHub Releases for a newer APK, downloads it
 * when available, then prompts the system installer.
 *
 * Fully silent install is not allowed on normal Android; the user still confirms
 * the package installer step. Download itself is automatic.
 *
 * If the user dismisses the install prompt they can snooze auto-prompts for
 * 7 / 14 / 31 days or disable them permanently ("Never").
 */
@Composable
fun AutoUpdateHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as QuickGitApp
    val updateManager = app.appUpdateManager
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<AutoUpdatePhase>(AutoUpdatePhase.Idle) }
    var progress by remember { mutableIntStateOf(0) }
    var latestName by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var apkFile by remember { mutableStateOf<File?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var showSnoozeChoices by remember { mutableStateOf(false) }

    // Kick off automatic check once per process (unless snoozed / never)
    LaunchedEffect(Unit) {
        if (dismissed) return@LaunchedEffect
        if (updateManager.isAutoPromptSuppressed()) {
            phase = AutoUpdatePhase.Idle
            return@LaunchedEffect
        }
        phase = AutoUpdatePhase.Checking
        val check = withContext(Dispatchers.IO) { updateManager.checkForUpdate() }
        when (check) {
            is UpdateCheckResult.UpToDate -> {
                phase = AutoUpdatePhase.Idle
            }
            is UpdateCheckResult.Error -> {
                // Silent on startup failures — Settings still has manual check
                phase = AutoUpdatePhase.Idle
            }
            is UpdateCheckResult.Available -> {
                latestName = check.latest.versionName
                phase = AutoUpdatePhase.Downloading
                progress = 0
                val dl = withContext(Dispatchers.IO) {
                    updateManager.downloadApk(check.apkAsset) { pct ->
                        progress = pct
                    }
                }
                when (dl) {
                    is DownloadResult.Success -> {
                        apkFile = dl.apkFile
                        phase = AutoUpdatePhase.ReadyToInstall
                    }
                    is DownloadResult.Error -> {
                        errorMessage = dl.message
                        phase = AutoUpdatePhase.Failed
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        content()

        when (phase) {
            AutoUpdatePhase.Idle, AutoUpdatePhase.Checking -> Unit
            AutoUpdatePhase.Downloading -> {
                AlertDialog(
                    onDismissRequest = { /* block dismiss while downloading */ },
                    title = { Text("Downloading update") },
                    text = {
                        Column {
                            Text("Version ${latestName ?: ""} is downloading…")
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$progress%",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    confirmButton = {}
                )
            }
            AutoUpdatePhase.ReadyToInstall -> {
                if (showSnoozeChoices) {
                    SnoozeChoicesDialog(
                        onChoose = { choice ->
                            when (choice) {
                                SnoozeChoice.Days7 -> updateManager.snoozeForDays(7)
                                SnoozeChoice.Days14 -> updateManager.snoozeForDays(14)
                                SnoozeChoice.Days31 -> updateManager.snoozeForDays(31)
                                SnoozeChoice.Never -> updateManager.snoozeNever()
                            }
                            showSnoozeChoices = false
                            dismissed = true
                            phase = AutoUpdatePhase.Idle
                        },
                        onCancel = {
                            // Back to the install prompt
                            showSnoozeChoices = false
                        }
                    )
                } else {
                    AlertDialog(
                        onDismissRequest = {
                            // Treat outside-tap / back as "remind me later" choices
                            showSnoozeChoices = true
                        },
                        title = { Text("Update ready") },
                        text = {
                            Text(
                                "Version ${latestName ?: ""} has been downloaded. " +
                                    "Install it now to finish updating."
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    scope.launch {
                                        if (!updateManager.canRequestPackageInstalls()) {
                                            try {
                                                context.startActivity(
                                                    updateManager.installPermissionSettingsIntent()
                                                )
                                            } catch (_: Exception) { }
                                            return@launch
                                        }
                                        val file = apkFile
                                        if (file != null) {
                                            val ok = updateManager.installApk(file)
                                            if (!ok) {
                                                errorMessage = "Could not open the package installer"
                                                phase = AutoUpdatePhase.Failed
                                            } else {
                                                dismissed = true
                                                phase = AutoUpdatePhase.Idle
                                            }
                                        }
                                    }
                                }
                            ) { Text("Install") }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showSnoozeChoices = true }
                            ) { Text("Later") }
                        }
                    )
                }
            }
            AutoUpdatePhase.Failed -> {
                errorMessage?.let { msg ->
                    AlertDialog(
                        onDismissRequest = {
                            dismissed = true
                            phase = AutoUpdatePhase.Idle
                        },
                        title = { Text("Update failed") },
                        text = { Text(msg) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    dismissed = true
                                    phase = AutoUpdatePhase.Idle
                                }
                            ) { Text("OK") }
                        }
                    )
                }
            }
        }
    }
}

private enum class SnoozeChoice {
    Days7,
    Days14,
    Days31,
    Never
}

@Composable
private fun SnoozeChoicesDialog(
    onChoose: (SnoozeChoice) -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Remind me later") },
        text = {
            Column {
                Text(
                    "When should we ask about this update again?",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                SnoozeRow("In 7 days") { onChoose(SnoozeChoice.Days7) }
                SnoozeRow("In 14 days") { onChoose(SnoozeChoice.Days14) }
                SnoozeRow("In 31 days") { onChoose(SnoozeChoice.Days31) }
                SnoozeRow("Never") { onChoose(SnoozeChoice.Never) }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("Back") }
        }
    )
}

@Composable
private fun SnoozeRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    )
}

private enum class AutoUpdatePhase {
    Idle,
    Checking,
    Downloading,
    ReadyToInstall,
    Failed
}
