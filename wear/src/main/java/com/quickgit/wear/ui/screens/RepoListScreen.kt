package com.quickgit.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.quickgit.wear.data.WearConnectionState
import com.quickgit.wear.data.WearRepoRepository
import com.quickgit.wear.data.WearRepoSummary

@Composable
fun RepoListScreen(
    repository: WearRepoRepository,
    onOpenRepo: (String) -> Unit,
    onAbout: () -> Unit
) {
    val repos by repository.repos.collectAsState()
    val connection by repository.connection.collectAsState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            ListHeader {
                Text("QuickGit", style = MaterialTheme.typography.title2)
            }
        }

        item {
            Text(
                connectionLabel(connection),
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        when {
            connection is WearConnectionState.Loading && repos.isEmpty() -> {
                item {
                    Text(
                        "Connecting to phone…\nOpen QuickGit on your phone.",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            connection is WearConnectionState.Disconnected && repos.isEmpty() -> {
                val reason = (connection as WearConnectionState.Disconnected).reason
                item {
                    Text(
                        reason,
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            connection is WearConnectionState.Error && repos.isEmpty() -> {
                item {
                    Text(
                        "Error: ${(connection as WearConnectionState.Error).message}",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            repos.isEmpty() -> {
                item {
                    Text(
                        "No repos on phone yet.\nClone a repository in the phone app.",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            else -> {
                items(repos, key = { it.path }) { repo ->
                    RepoChip(repo = repo, onClick = { onOpenRepo(repo.path) })
                }
            }
        }

        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = { repository.requestSyncFromPhone() },
                label = { Text("Refresh") },
                colors = ChipDefaults.secondaryChipColors()
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = onAbout,
                label = { Text("About") },
                colors = ChipDefaults.secondaryChipColors()
            )
        }
    }
}

@Composable
private fun RepoChip(repo: WearRepoSummary, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        label = {
            Text(
                repo.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        secondaryLabel = {
            val dirty = if (repo.hasUncommittedChanges) " · dirty" else ""
            Text(
                "${repo.branch}$dirty",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        colors = ChipDefaults.primaryChipColors()
    )
}

private fun connectionLabel(state: WearConnectionState): String = when (state) {
    is WearConnectionState.Loading -> "Syncing…"
    is WearConnectionState.Connected -> "Synced from phone"
    is WearConnectionState.Disconnected -> "Phone offline"
    is WearConnectionState.Error -> "Sync error"
}
