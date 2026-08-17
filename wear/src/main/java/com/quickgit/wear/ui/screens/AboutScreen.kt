package com.quickgit.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

@Composable
fun AboutScreen(onBack: () -> Unit) {
    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            ListHeader {
                Text("About", style = MaterialTheme.typography.title3)
            }
        }
        item {
            Text(
                "QuickGit on Wear shows local repository status. " +
                    "Clone, edit, commit, and sync from the phone app.",
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
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
