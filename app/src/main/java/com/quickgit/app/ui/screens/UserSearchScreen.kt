package com.quickgit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.ui.components.UserAvatar
import com.quickgit.app.viewmodel.UserSearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSearchScreen(
    vm: UserSearchViewModel,
    onBack: () -> Unit,
    onOpenUser: (String) -> Unit,
    onNeedsAuth: () -> Unit
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(state.authRequired) {
        if (state.authRequired) onNeedsAuth()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search people") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp, 12.dp),
                placeholder = { Text("Search GitHub users") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.searchNow() })
            )

            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.errorMessage != null -> {
                    Text(
                        state.errorMessage!!,
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                state.searched && state.results.isEmpty() -> {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No users found for “${state.query.trim()}”",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                state.results.isNotEmpty() -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.results, key = { it.login }) { user ->
                            UserResultRow(user, onClick = { onOpenUser(user.login) })
                            HorizontalDivider()
                        }
                    }
                }
                else -> {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Search for developers by name or username",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserResultRow(user: GitHubApi.GitHubUserSummary, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(avatarUrl = user.avatarUrl, login = user.login, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(user.login, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                user.type,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
