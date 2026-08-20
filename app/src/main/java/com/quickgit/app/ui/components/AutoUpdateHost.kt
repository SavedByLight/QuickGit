package com.quickgit.app.ui.components

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.quickgit.app.QuickGitApp
import com.quickgit.app.data.UpdateCheckResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On launch, checks GitHub Releases for a newer version and shows a dialog.
 * "Open Releases" loads the page in an in-app WebView (no APK install).
 */
@Composable
fun AutoUpdateHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as QuickGitApp
    val updateManager = app.appUpdateManager

    var available by remember { mutableStateOf<UpdateCheckResult.Available?>(null) }
    var showSnoozeChoices by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var webUrl by remember { mutableStateOf<String?>(null) }

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
        if (info != null && !dismissed && webUrl == null) {
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
                                "Open the Releases page to view the changelog and download builds."
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                webUrl = updateManager.releasesPageUrl(info.release)
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

        webUrl?.let { url ->
            ReleasesWebView(
                url = url,
                onClose = {
                    webUrl = null
                    dismissed = true
                    available = null
                }
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReleasesWebView(
    url: String,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "GitHub Releases",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 12.dp)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }
            HorizontalDivider()
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        webViewClient = WebViewClient()
                        loadUrl(url)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    if (view.url != url) view.loadUrl(url)
                }
            )
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
