package com.quickgit.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickgit.app.ui.components.SyntaxHighlight
import com.quickgit.app.ui.components.rememberSyntaxPalette
import com.quickgit.app.viewmodel.RemoteFileViewModel

/** Read-only view of a single file's content, fetched straight from GitHub — no clone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteFileScreen(
    owner: String,
    repo: String,
    ref: String,
    path: String,
    vm: RemoteFileViewModel,
    onBack: () -> Unit,
    onNeedsAuth: () -> Unit
) {
    LaunchedEffect(owner, repo, ref, path) { vm.load(owner, repo, ref, path) }

    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.authRequired) {
        if (state.authRequired) {
            // Consume first so Back from Credentials does not re-open Settings in a loop.
            vm.consumeAuthRequired()
            onNeedsAuth()
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    val baseColor = MaterialTheme.colorScheme.onSurface
    val language = remember(path) { SyntaxHighlight.languageFromPath(path) }
    val palette = rememberSyntaxPalette(baseColor)
    val highlighted = remember(state.content, language, palette) {
        SyntaxHighlight.highlight(state.content ?: "", language, palette)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(path.substringAfterLast('/')) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> {
                    Text(
                        state.error ?: "Couldn't load this file",
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> {
                    Text(
                        text = highlighted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}
