package com.quickgit.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.models.Release
import com.quickgit.app.data.models.ReleaseAsset
import com.quickgit.app.ui.theme.GitAmber
import com.quickgit.app.ui.theme.GitGreen
import com.quickgit.app.viewmodel.ReleasesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleasesScreen(
    vm: ReleasesViewModel,
    onBack: (() -> Unit)? = null,
    onNeedsAuth: (String) -> Unit
) {
    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage, state.statusMessage, state.authRequiredHost) {
        state.errorMessage?.let { snackbarHost.showSnackbar(it); vm.consumeMessages() }
        state.statusMessage?.let { snackbarHost.showSnackbar(it); vm.consumeMessages() }
        state.authRequiredHost?.let { onNeedsAuth("https://$it/"); vm.consumeMessages() }
    }

    if (state.selected != null) {
        BackHandler { vm.closeDetail() }
        ReleaseDetailContent(release = state.selected!!, loading = state.detailLoading, onBack = { vm.closeDetail() })
        return
    }

    if (!state.supported) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Releases") },
                    navigationIcon = {
                        onBack?.let { back ->
                            IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Back") }
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Releases are only available for repositories whose origin points at github.com.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Releases") },
                navigationIcon = {
                    onBack?.let { back ->
                        IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Back") }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading && state.releases.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                state.releases.isEmpty() -> {
                    Text(
                        "No releases published yet.",
                        Modifier.align(Alignment.Center).padding(32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.releases, key = { it.id }) { release ->
                            ReleaseCard(release = release, onClick = { vm.openDetail(release.id) })
                        }
                    }
                }
            }
            if (state.loading && state.releases.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun ReleaseCard(release: Release, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.NewReleases,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (release.prerelease) GitAmber else GitGreen
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    release.name.ifBlank { release.tagName },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (release.draft) {
                    AssistChip(onClick = {}, label = { Text("Draft") }, enabled = false)
                } else if (release.prerelease) {
                    AssistChip(onClick = {}, label = { Text("Pre-release") }, enabled = false)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(release.tagName)
                    if (!release.authorLogin.isNullOrBlank()) append(" · ${release.authorLogin}")
                    val date = release.publishedAt ?: release.createdAt
                    if (date.isNotBlank()) append(" · ${formatIso(date)}")
                    if (release.assets.isNotEmpty()) append(" · ${release.assets.size} asset(s)")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReleaseDetailContent(
    release: Release,
    loading: Boolean,
    onBack: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            release.name.ifBlank { release.tagName },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(release.tagName, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (release.draft) {
                    AssistChip(onClick = {}, label = { Text("Draft") }, enabled = false)
                }
                if (release.prerelease) {
                    AssistChip(onClick = {}, label = { Text("Pre-release") }, enabled = false)
                }
                if (!release.draft && !release.prerelease) {
                    AssistChip(onClick = {}, label = { Text("Latest") }, enabled = false)
                }
            }

            Spacer(Modifier.height(12.dp))
            InfoLine("Tag", release.tagName)
            if (!release.authorLogin.isNullOrBlank()) InfoLine("Author", release.authorLogin)
            InfoLine("Published", formatIso(release.publishedAt ?: release.createdAt))
            if (release.htmlUrl.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { uriHandler.openUri(release.htmlUrl) }) {
                    Text("Open on GitHub")
                }
            }

            if (!release.body.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text("Notes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(release.body, style = MaterialTheme.typography.bodyMedium)
            }

            if (release.assets.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Assets (${release.assets.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                release.assets.forEach { asset ->
                    AssetRow(asset) {
                        if (asset.browserDownloadUrl.isNotBlank()) {
                            uriHandler.openUri(asset.browserDownloadUrl)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (!release.zipballUrl.isNullOrBlank() || !release.tarballUrl.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text("Source code", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!release.zipballUrl.isNullOrBlank()) {
                        OutlinedButton(onClick = { uriHandler.openUri(release.zipballUrl) }) {
                            Text("zip")
                        }
                    }
                    if (!release.tarballUrl.isNullOrBlank()) {
                        OutlinedButton(onClick = { uriHandler.openUri(release.tarballUrl) }) {
                            Text("tar.gz")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetRow(asset: ReleaseAsset, onDownload: () -> Unit) {
    Card(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    asset.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatBytes(asset.size)} · ${asset.downloadCount} downloads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onDownload) { Text("Download") }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            Modifier.width(90.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatIso(iso: String): String =
    iso.replace("T", " ").removeSuffix("Z").take(19)

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}
