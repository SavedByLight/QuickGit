package com.quickgit.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.quickgit.wear.data.WearRepoRepository

@Composable
fun RepoDetailScreen(
    repository: WearRepoRepository,
    path: String,
    onBack: () -> Unit
) {
    val repo = remember(path) { repository.getRepo(path) }

    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            ListHeader {
                Text(
                    repo?.name ?: "Repo",
                    style = MaterialTheme.typography.title3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (repo == null) {
            item {
                Text(
                    "Repository not found on this device.",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
        } else {
            item {
                Text(
                    "Branch: ${repo.branch}",
                    style = MaterialTheme.typography.body1,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
            }
            item {
                Text(
                    if (repo.dirty) "Status: local changes" else "Status: clean",
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
            }
            repo.remoteUrl?.let { url ->
                item {
                    Text(
                        url,
                        style = MaterialTheme.typography.caption2,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    )
                }
            }
            item {
                Text(
                    "Push, pull, and commit on the phone app.",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = onBack,
                label = { Text("Back") },
                colors = ChipDefaults.secondaryChipColors()
            )
        }
    }
}
