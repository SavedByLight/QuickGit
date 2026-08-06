package com.quickgit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quickgit.app.data.models.GitHubRemoteRepo
import com.quickgit.app.ui.components.UserAvatar
import com.quickgit.app.viewmodel.ProfileViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    vm: ProfileViewModel,
    login: String? = null,
    onBack: () -> Unit,
    onOpenUser: (String) -> Unit = {},
    onCloneRepo: (GitHubRemoteRepo) -> Unit = {},
    onNeedsAuth: () -> Unit
) {
    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(login) { vm.load(login) }

    LaunchedEffect(state.authRequired) {
        if (state.authRequired) onNeedsAuth()
    }

    LaunchedEffect(state.statusMessage, state.forkError) {
        state.statusMessage?.let { snackbarHost.showSnackbar(it) }
        state.forkError?.let { snackbarHost.showSnackbar(it) }
        if (state.statusMessage != null || state.forkError != null) vm.consumeMessages()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.isSelf -> "Your profile"
                            state.user != null -> state.user!!.login
                            else -> "Profile"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        when {
            state.loading && state.user == null -> {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.user == null -> {
                Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        state.errorMessage ?: "Connect a GitHub account in Settings to view profiles.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                val user = state.user!!
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                UserAvatar(
                                    avatarUrl = user.avatarUrl,
                                    login = user.login,
                                    size = 72.dp,
                                    textStyle = MaterialTheme.typography.headlineMedium
                                )
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    if (!user.name.isNullOrBlank()) {
                                        Text(user.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    }
                                    Text(
                                        "@${user.login}",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (!user.bio.isNullOrBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Text(user.bio, style = MaterialTheme.typography.bodyMedium)
                            }

                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                StatChip(user.publicRepos.toString(), "Repos")
                                StatChip(user.followers.toString(), "Followers")
                                StatChip(user.following.toString(), "Following")
                            }

                            val meta = listOfNotNull(
                                user.company?.let { Icons.Default.Business to it },
                                user.location?.let { Icons.Default.LocationOn to it },
                                user.blog?.let { Icons.Default.Link to it }
                            )
                            if (meta.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                meta.forEach { (icon, text) ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    ) {
                                        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(8.dp))
                                        Text(text, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                        Text(
                            "Repositories",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)
                        )
                    }

                    if (state.repos.isEmpty()) {
                        item {
                            Text(
                                "No public repositories",
                                Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(state.repos, key = { it.id }) { repo ->
                            ProfileRepoRow(
                                repo,
                                showFork = !state.isSelf,
                                forking = state.forkingRepoId == repo.id,
                                onClick = { onCloneRepo(repo) },
                                onFork = { vm.fork(repo) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProfileRepoRow(
    repo: GitHubRemoteRepo,
    showFork: Boolean,
    forking: Boolean,
    onClick: () -> Unit,
    onFork: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(16.dp, 12.dp)
        ) {
            Text(repo.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (!repo.description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    repo.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(
                    repo.language,
                    if (repo.isPrivate) "Private" else "Public",
                    if (repo.isFork) "Fork" else null
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showFork) {
            if (forking) {
                CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onFork, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(Icons.Default.CallSplit, "Fork this repo")
                }
            }
        }
    }
}
