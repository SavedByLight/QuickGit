package com.quickgit.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.quickgit.app.QuickGitApp
import com.quickgit.app.data.UpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On launch, checks GitHub Releases for a newer version and shows a dialog.
 * "Download update" opens the APK (or Releases page) in the user's default browser.
 */
@Composable
fun AutoUpdateHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as QuickGitApp
    val updateManager = app.appUpdateManager

    var available by remember { mutableStateOf<UpdateCheckResult.Available?>(null) }
    var showSnoozeChoices by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (dismissed) return@LaunchedEffect
        if (updateManager.isAutoPromptSuppressed()) return@LaunchedEffect
        val check = withContext(Dispatchers.IO) { updateManager.checkForUpdate() }
        if (check is UpdateCheckResult.Available) {
            available = check
        }
    }

    Box(Modifier.fillMaxSize()) {
        content()

        val info = available
        if (info != null && !dismissed) {
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
                        available = null
                    },
                    onCancel = { showSnoozeChoices = false }
                )
            } else {
                AlertDialog(
                    onDismissRequest = { showSnoozeChoices = true },
                    title = { Text("New release available") },
                    text = {
                        Text(
                            "QuickGit ${info.latest.versionName} is available " +
                                "(you have ${info.current.versionName}).\n\n" +
                                "Open your browser to view the changelog and download the APK."
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val url = updateManager.downloadOrReleasesUrl(info)
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    // No browser available — still dismiss prompt
                                }
                                dismissed = true
                                available = null
                            }
                        ) { Text("Download update") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSnoozeChoices = true }) {
                            Text("Later")
                        }
                    }
                )
            }
        }
    }
}

private enum class SnoozeChoice {
    Days7, Days14, Days31, Never
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
                    "When should we ask about this release again?",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                listOf(
                    "In 7 days" to SnoozeChoice.Days7,
                    "In 14 days" to SnoozeChoice.Days14,
                    "In 31 days" to SnoozeChoice.Days31,
                    "Never" to SnoozeChoice.Never
                ).forEach { (label, choice) ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChoose(choice) }
                            .padding(vertical = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("Back") }
        }
    )
}
