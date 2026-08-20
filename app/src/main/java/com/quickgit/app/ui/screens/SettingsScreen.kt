package com.quickgit.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.AppUpdateConfig
import com.quickgit.app.data.DesktopLayoutMode
import com.quickgit.app.ui.adaptive.AdaptiveContent
import com.quickgit.app.ui.theme.GitGreen
import com.quickgit.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { vm.setReposRoot(it) } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
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


            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text("Layout", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Desktop layout shows a permanent left NavigationRail (Repos, Clone, Browse, " +
                    "Profile, Users, Logs, Creds, Settings) — the same chrome as the Linux desktop app. " +
                    "Auto follows window width (Chromebook / tablet / freeform). " +
                    "Always forces the rail even on phones; Never keeps phone-style navigation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            val layoutOptions = listOf(
                DesktopLayoutMode.AUTO to "Auto (by screen size)",
                DesktopLayoutMode.ALWAYS to "Always (desktop rail)",
                DesktopLayoutMode.NEVER to "Never (phone only)"
            )
            layoutOptions.forEach { (mode, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.setDesktopLayoutMode(mode) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = state.desktopLayoutMode == mode,
                        onClick = { vm.setDesktopLayoutMode(mode) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text("About & updates", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Current version ${state.appVersionName} (${state.appVersionCode}). " +
                    "Checks GitHub Releases on ${AppUpdateConfig.OWNER}/${AppUpdateConfig.REPO} " +
                    "for a newer release. Opens your default browser to download — no in-app install.",
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.checkForUpdate() },
                    enabled = !state.updateChecking
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
                    Button(onClick = {
                        val url = vm.openReleasesPageUrl()
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) { }
                        vm.dismissUpdatePrompt()
                    }) {
                        Text("Download update")
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

        } // AdaptiveContent
    }
}
