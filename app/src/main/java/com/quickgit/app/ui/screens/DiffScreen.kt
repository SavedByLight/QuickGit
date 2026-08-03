package com.quickgit.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickgit.app.data.models.DiffLineType
import com.quickgit.app.ui.theme.GitGreen
import com.quickgit.app.ui.theme.GitRed
import com.quickgit.app.viewmodel.DiffViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(repoPath: String, filePath: String, mode: String, vm: DiffViewModel, onBack: () -> Unit) {
    LaunchedEffect(repoPath, filePath, mode) { vm.load(repoPath, filePath, mode) }
    val diff by vm.diff.collectAsState()
    val loading by vm.loading.collectAsState()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(filePath.substringAfterLast('/'), maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                diff == null || (diff!!.lines.isEmpty() && !diff!!.isBinary) ->
                    Text("No changes to show", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                diff!!.isBinary -> Text("Binary file — diff not shown", Modifier.align(Alignment.Center))
                else -> {
                    LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        items(diff!!.lines) { line ->
                            val (bg, fg) = when (line.type) {
                                DiffLineType.ADDED -> GitGreen.copy(alpha = 0.15f) to GitGreen
                                DiffLineType.REMOVED -> GitRed.copy(alpha = 0.15f) to GitRed
                                DiffLineType.HEADER -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                                DiffLineType.CONTEXT -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
                            }
                            Text(
                                line.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = fg,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bg)
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
