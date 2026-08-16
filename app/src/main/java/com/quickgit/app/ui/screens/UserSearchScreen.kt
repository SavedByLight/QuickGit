package com.quickgit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.models.GitHubRemoteRepo
import com.quickgit.app.ui.components.UserAvatar
import com.quickgit.app.viewmodel.UserSearchKind
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
    val snackbarHost = remember { SnackbarHostState() }
    val tabs = listOf(
        UserSearchProvider.GITHUB to "GitHub",
        UserSearchProvider.GITLAB to "GitLab"
    )
    val selectedIndex = tabs.indexOfFirst { it.first == state.selectedTab }.coerceAtLeast(0)

    LaunchedEffect(state.errorMessage, state.statusMessage, state.authRequired) {
        state.statusMessage?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeMessages()
        }
        state.errorMessage?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeMessages()
        }
        // authRequired is shown as an in-screen CTA; clear the flag so it does not re-fire
        if (state.authRequired && state.errorMessage == null && state.statusMessage == null) {
            // leave auth UI visible until user acts; no auto-nav
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.searchKind) {
                            UserSearchKind.PEOPLE -> "Search people"
                            UserSearchKind.REPOS -> "Search repositories"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.authRequired && !state.githubConnected && !state.gitlabConnected) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Connect an account in Credentials to search.")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onNeedsAuth) { Text("Open Credentials") }
                    }
                }
                return@Column
            }

            // People vs Repositories
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SegmentedButton(
                    selected = state.searchKind == UserSearchKind.PEOPLE,
                    onClick = { vm.selectKind(UserSearchKind.PEOPLE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("People") }
                SegmentedButton(
                    selected = state.searchKind == UserSearchKind.REPOS,
                    onClick = { vm.selectKind(UserSearchKind.REPOS) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("Repos") }
            }

            if (state.searchKind == UserSearchKind.PEOPLE) {
                TabRow(selectedTabIndex = selectedIndex) {
                    tabs.forEachIndexed { index, (tab, label) ->
                        val connected = when (tab) {
                            UserSearchProvider.GITHUB -> state.githubConnected
                            UserSearchProvider.GITLAB -> state.gitlabConnected
                        }
                        Tab(
                            selected = selectedIndex == index,
                            onClick = { vm.selectTab(tab) },
                            text = {
                                Text(if (connected) label else "$label (sign in)")
                            },
                            enabled = connected || state.selectedTab == tab
                        )
                    }
                }
            } else if (!state.githubConnected) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Connect GitHub in Credentials to search and fork repositories.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onNeedsAuth) { Text("Open Credentials") }
                    }
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = {
                    Text(
                        when (state.searchKind) {
                            UserSearchKind.PEOPLE -> "Search by username…"
                            UserSearchKind.REPOS -> "Search repositories…"
                        }
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        TextButton(onClick = { vm.searchNow() }) { Text("Go") }
                    }
                }
            )

            if (state.loading && !state.loadingMore) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                when (state.searchKind) {
                    UserSearchKind.PEOPLE -> {
                        when (state.selectedTab) {
                            UserSearchProvider.GITHUB -> {
                                items(state.githubResults, key = { it.login }) { user ->
                                    ListItem(
                                        headlineContent = {
                                            Text(user.login, fontWeight = FontWeight.Medium)
                                        },
                                        supportingContent = { Text(user.type) },
                                        leadingContent = {
                                            UserAvatar(
                                                avatarUrl = user.avatarUrl,
                                                login = user.login,
                                                size = 40.dp
                                            )
                                        },
                                        modifier = Modifier.clickable { onUserClick(user.login) }
                                    )
                                    HorizontalDivider()
                                }
                            }
                            UserSearchProvider.GITLAB -> {
                                items(state.gitlabResults, key = { it.id }) { user ->
                                    ListItem(
                                        headlineContent = {
                                            Text(user.username, fontWeight = FontWeight.Medium)
                                        },
                                        supportingContent = { user.name?.let { Text(it) } },
                                        leadingContent = {
                                            UserAvatar(
                                                avatarUrl = user.avatarUrl,
                                                login = user.username,
                                                size = 40.dp
                                            )
                                        },
                                        modifier = Modifier.clickable { onUserClick(user.username) }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                        if (state.searched && !state.loading) {
                            val empty = when (state.selectedTab) {
                                UserSearchProvider.GITHUB -> state.githubResults.isEmpty()
                                UserSearchProvider.GITLAB -> state.gitlabResults.isEmpty()
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
                    UserSearchKind.REPOS -> {
                        items(state.repoResults, key = { it.id }) { repo ->
                            SearchRepoRow(
                                repo = repo,
                                forking = state.forkingRepoId == repo.id,
                                onFork = { vm.fork(repo) },
                                onOpenOwner = { onUserClick(repo.ownerLogin) }
                            )
                            HorizontalDivider()
                        }
                        if (state.searched && !state.loading && state.repoResults.isEmpty()) {
                            item {
                                Text(
                                    "No repositories found.",
                                    modifier = Modifier.padding(24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (state.hasMore && !state.loading) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (state.loadingMore) {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                            } else {
                                OutlinedButton(onClick = { vm.loadMore() }) {
                                    Text("Load more")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRepoRow(
    repo: GitHubRemoteRepo,
    forking: Boolean,
    onFork: () -> Unit,
    onOpenOwner: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Code,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable(onClick = onOpenOwner)) {
            Text(
                repo.fullName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = buildString {
                repo.description?.takeIf { it.isNotBlank() }?.let { append(it) }
                repo.language?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
                if (repo.isPrivate) {
                    if (isNotEmpty()) append(" · ")
                    append("Private")
                }
            }
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (forking) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onFork) {
                Icon(Icons.Default.CallSplit, contentDescription = "Fork this repository")
            }
        }
    }
}
