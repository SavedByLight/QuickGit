package com.quickgit.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.models.CommitInfo
import com.quickgit.app.viewmodel.HistoryViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(repoPath: String, vm: HistoryViewModel, onBack: () -> Unit) {
    LaunchedEffect(repoPath) { vm.load(repoPath) }
    val commits by vm.commits.collectAsState()
    val loading by vm.loading.collectAsState()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("History") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(commits, key = { it.id }) { commit ->
                        CommitRow(commit)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitRow(commit: CommitInfo) {
    Column(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
        Text(commit.message, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2)
        Spacer(Modifier.height(4.dp))
        Text(
            "${commit.authorName} · ${commit.shortId} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(commit.timeEpochSeconds * 1000))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
