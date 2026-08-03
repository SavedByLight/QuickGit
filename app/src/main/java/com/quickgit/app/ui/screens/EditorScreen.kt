package com.quickgit.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickgit.app.ui.theme.GitGreen
import com.quickgit.app.viewmodel.EditorViewModel

/**
 * Mobile-first text editor for repo files.
 *
 * Intentionally not a VS Code clone: Material 3 chrome, QuickGit accents,
 * bottom save bar, no activity bar / minimap / command palette.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    repoPath: String,
    relativePath: String,
    vm: EditorViewModel,
    onBack: () -> Unit
) {
    LaunchedEffect(repoPath, relativePath) { vm.load(repoPath, relativePath) }
    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.savedMessage, state.error) {
        state.savedMessage?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeMessage()
        }
        state.error?.let {
            if (!state.loading) {
                snackbarHost.showSnackbar(it)
                vm.consumeMessage()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            relativePath.substringAfterLast('/'),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            relativePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    if (state.isDirty) {
                        Text(
                            "edited",
                            color = GitGreen,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (!state.loading && state.error == null) {
                Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (state.isDirty) "Unsaved changes" else "No changes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { vm.save() },
                            enabled = state.isDirty && !state.saving
                        ) {
                            if (state.saving) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Save")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.content.isEmpty() -> {
                    Text(
                        state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    )
                }
                else -> CodeEditor(
                    text = state.content,
                    onTextChange = vm::setContent,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun CodeEditor(
    text: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    val gutterWidth = when {
        lineCount < 100 -> 40.dp
        lineCount < 1000 -> 48.dp
        else -> 56.dp
    }
    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface
    )
    val gutterBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val gutterFg = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)

    Row(modifier.background(MaterialTheme.colorScheme.surface)) {
        // Line-number gutter — fixed width, scrolls with content vertically
        Column(
            Modifier
                .width(gutterWidth)
                .fillMaxHeight()
                .background(gutterBg)
                .verticalScroll(vScroll)
                .padding(top = 12.dp, bottom = 12.dp, end = 6.dp),
            horizontalAlignment = Alignment.End
        ) {
            for (i in 1..lineCount) {
                Text(
                    text = i.toString(),
                    style = codeStyle.copy(color = gutterFg, fontSize = 11.sp),
                    modifier = Modifier.padding(vertical = 0.dp)
                )
            }
        }
        // Editable body
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(vScroll)
                .horizontalScroll(hScroll)
                .padding(12.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = codeStyle,
                cursorBrush = SolidColor(GitGreen),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
