package com.quickgit.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.LogEntry
import com.quickgit.app.data.LogLevel
import com.quickgit.app.ui.theme.GitAmber
import com.quickgit.app.ui.theme.GitRed
import com.quickgit.app.viewmodel.LogsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    vm: LogsViewModel,
    onBack: () -> Unit
) {
    val entries by vm.entries.collectAsState()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }

    // System file picker — user chooses Downloads / USB / SD card (ideal on tablets).
    val saveDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val err = withContext(Dispatchers.IO) { vm.saveToUri(context, uri) }
            snackbarHost.showSnackbar(
                if (err == null) "Logs saved" else "Save failed: $err"
            )
        }
    }

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(vm.asPlainText()))
                            scope.launch { snackbarHost.showSnackbar("Logs copied to clipboard") }
                        },
                        enabled = entries.isNotEmpty()
                    ) { Icon(Icons.Default.ContentCopy, "Copy logs") }

                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            enabled = entries.isNotEmpty()
                        ) {
                            Icon(Icons.Default.SaveAlt, "Save logs")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Save as file…") },
                                onClick = {
                                    menuExpanded = false
                                    saveDocument.launch(vm.suggestedFileName())
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Save to app folder") },
                                onClick = {
                                    menuExpanded = false
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            vm.saveToAppFiles(context)
                                        }
                                        result.fold(
                                            onSuccess = { file ->
                                                snackbarHost.showSnackbar("Saved to ${file.absolutePath}")
                                            },
                                            onFailure = { e ->
                                                snackbarHost.showSnackbar(
                                                    "Save failed: ${e.message ?: e}"
                                                )
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }

                    IconButton(onClick = vm::clear, enabled = entries.isNotEmpty()) {
                        Icon(Icons.Default.DeleteSweep, "Clear logs")
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No log entries yet — git operations will show up here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding).fillMaxSize()
            ) {
                items(entries) { entry -> LogRow(entry) }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.ERROR -> GitRed
        LogLevel.WARN -> GitAmber
        LogLevel.INFO, LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            entry.formattedTime,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.tag,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
            Text(
                entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color
            )
        }
    }
}
