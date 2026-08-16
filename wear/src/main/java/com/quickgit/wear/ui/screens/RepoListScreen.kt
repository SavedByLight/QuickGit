package com.quickgit.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.quickgit.wear.data.WearRepoRepository
import com.quickgit.wear.data.WearRepoSummary

@Composable
fun RepoListScreen(
    repository: WearRepoRepository,
    onOpenRepo: (String) -> Unit,
    onAbout: () -> Unit
) {
    var repos by remember { mutableStateOf<List<WearRepoSummary>>(emptyList()) }

    LaunchedEffect(Unit) {
        repos = repository.listRepos()
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            ListHeader {
                Text("QuickGit", style = MaterialTheme.typography.title2)
            }
        }
        if (repos.isEmpty()) {
            item {
                Text(
                    "No local repos on this watch.\nClone on your phone, or sync a QuickGit folder here.",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        } else {
            items(repos, key = { it.path }) { repo ->
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpenRepo(repo.path) },
                    label = {
                        Text(
                            repo.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    secondaryLabel = {
                        Text(
                            buildString {
                                append(repo.branch)
                                if (repo.dirty) append(" · dirty")
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = ChipDefaults.primaryChipColors()
                )
            }
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
