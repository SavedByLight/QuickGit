package com.quickgit.app.ui.components

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
 * On launch, checks GitHub Releases for a newer version and shows a notification
 * dialog. Does **not** download or install APKs (Play policy / sideload-safe):
 * the user opens the Releases page in the browser if they want to update.
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
                            "QuickGit ${info.latest.versionName} is on GitHub Releases " +
                                "(you have ${info.current.versionName}).\n\n" +
                                "Open the Releases page to download the build for your platform."
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                updateManager.openReleasesPage()
                                dismissed = true
                                available = null
                            }
                        ) { Text("Open Releases") }
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
