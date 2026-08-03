package com.quickgit.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.models.BranchInfo
import com.quickgit.app.data.models.GitOpResult
import com.quickgit.app.viewmodel.BranchesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchesScreen(repoPath: String, vm: BranchesViewModel, onBack: () -> Unit) {
    LaunchedEffect(repoPath) { vm.init(repoPath) }
    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }
    var branchToDelete by remember { mutableStateOf<BranchInfo?>(null) }

    LaunchedEffect(state.lastResult) {
        val r = state.lastResult
        if (r is GitOpResult.Error) { snackbarHost.showSnackbar(r.message); vm.consumeResult() }
        else if (r != null) vm.consumeResult()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("Branches") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, "New branch") } }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.busy && state.branches.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.branches, key = { (if (it.isRemote) "r_" else "l_") + it.name }) { b ->
                        BranchRow(
                            b,
                            onCheckout = { vm.checkout(b.name) },
                            onDelete = if (!b.isRemote && !b.isCurrent) ({ branchToDelete = b }) else null
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showCreate) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("New branch") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Branch name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { vm.createBranch(name.trim(), checkout = true); showCreate = false }, enabled = name.isNotBlank()) {
                    Text("Create & checkout")
                }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Cancel") } }
        )
    }

    branchToDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { branchToDelete = null },
            title = { Text("Delete '${b.name}'?") },
            confirmButton = {
                TextButton(onClick = { vm.delete(b.name); branchToDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { branchToDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun BranchRow(b: BranchInfo, onCheckout: () -> Unit, onDelete: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (b.isRemote) Icons.Default.Cloud else Icons.Default.AccountTree,
            null,
            tint = if (b.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.name, fontWeight = if (b.isCurrent) FontWeight.Bold else FontWeight.Normal)
            if (b.isCurrent) Text("current", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (!b.isCurrent) {
            TextButton(onClick = onCheckout) { Text("Checkout") }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete branch") }
        }
    }
}
