package com.quickgit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quickgit.app.ui.components.UserAvatar
import com.quickgit.app.viewmodel.UserSearchProvider
import com.quickgit.app.viewmodel.UserSearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSearchScreen(
    vm: UserSearchViewModel,
    onBack: () -> Unit,
    onUserClick: (login: String) -> Unit,
    onNeedsAuth: () -> Unit
) {
    val state by vm.state.collectAsState()
    val tabs = listOf(
        UserSearchProvider.GITHUB to "GitHub",
        UserSearchProvider.GITLAB to "GitLab",
        UserSearchProvider.GERRIT to "Gerrit"
    )
    val selectedIndex = tabs.indexOfFirst { it.first == state.selectedTab }.coerceAtLeast(0)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search people") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.authRequired) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Connect an account in Settings to search people.")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onNeedsAuth) { Text("Open Settings") }
                    }
                }
                return@Column
            }

            TabRow(selectedTabIndex = selectedIndex) {
                tabs.forEachIndexed { index, (tab, label) ->
                    val connected = when (tab) {
                        UserSearchProvider.GITHUB -> state.githubConnected
                        UserSearchProvider.GITLAB -> state.gitlabConnected
                        UserSearchProvider.GERRIT -> state.gerritConnected
                    }
                    Tab(
                        selected = selectedIndex == index,
                        onClick = { vm.selectTab(tab) },
                        text = { Text(if (connected) label else "$label · off") },
                        enabled = connected
                    )
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Search by name or username…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true
            )

            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }

            LazyColumn(Modifier.fillMaxSize()) {
                when (state.selectedTab) {
                    UserSearchProvider.GITHUB -> {
                        items(state.githubResults, key = { it.login }) { user ->
                            ListItem(
                                headlineContent = { Text(user.login, fontWeight = FontWeight.Medium) },
                                supportingContent = { Text(user.type) },
                                leadingContent = {
                                    UserAvatar(avatarUrl = user.avatarUrl, login = user.login, size = 40.dp)
                                },
                                modifier = Modifier.clickable { onUserClick(user.login) }
                            )
                            HorizontalDivider()
                        }
                    }
                    UserSearchProvider.GITLAB -> {
                        items(state.gitlabResults, key = { it.id }) { user ->
                            ListItem(
                                headlineContent = { Text(user.username, fontWeight = FontWeight.Medium) },
                                supportingContent = { user.name?.let { Text(it) } },
                                leadingContent = {
                                    UserAvatar(avatarUrl = user.avatarUrl, login = user.username, size = 40.dp)
                                },
                                modifier = Modifier.clickable { onUserClick(user.username) }
                            )
                            HorizontalDivider()
                        }
                    }
                    UserSearchProvider.GERRIT -> {
                        items(state.gerritResults, key = { it.accountId }) { user ->
                            ListItem(
                                headlineContent = {
                                    Text(user.username.ifBlank { "account ${user.accountId}" }, fontWeight = FontWeight.Medium)
                                },
                                supportingContent = {
                                    Text(listOfNotNull(user.name, user.email).joinToString(" · "))
                                },
                                leadingContent = {
                                    Icon(Icons.Default.Person, null, Modifier.size(40.dp))
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
                if (state.searched && !state.loading) {
                    val empty = when (state.selectedTab) {
                        UserSearchProvider.GITHUB -> state.githubResults.isEmpty()
                        UserSearchProvider.GITLAB -> state.gitlabResults.isEmpty()
                        UserSearchProvider.GERRIT -> state.gerritResults.isEmpty()
                    }
                    if (empty) {
                        item {
                            Text(
                                "No people found.",
                                modifier = Modifier.padding(24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
