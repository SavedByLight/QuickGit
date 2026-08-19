package com.quickgit.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.quickgit.app.QuickGitApp
import com.quickgit.app.data.DesktopLayoutMode
import com.quickgit.app.data.models.RepoInfo
import com.quickgit.app.ui.adaptive.LocalWindowSizeClass
import com.quickgit.app.ui.adaptive.isTabletOrWider
import com.quickgit.app.ui.screens.*
import com.quickgit.app.viewmodel.*
import androidx.compose.runtime.collectAsState

/**
 * Root navigation graph.
 *
 * Desktop layout (left NavigationRail matching the Linux desktop app) is
 * controlled by [com.quickgit.app.data.AppPreferences.desktopLayoutMode]:
 * - AUTO — rail when window is tablet/Chromebook width or wider
 * - ALWAYS — always show the left rail
 * - NEVER — phone-style navigation only
 *
 * Toggle under Settings → Layout.
 */
@Composable
fun QuickGitNavGraph() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val factory = ViewModelFactory(context.applicationContext as android.app.Application)
    val wsc = LocalWindowSizeClass.current
    val app = context.applicationContext as QuickGitApp
    val desktopMode by app.appPreferences.desktopLayoutMode.collectAsState()
    // Left NavigationRail (same structure as Linux desktop):
    // ALWAYS = force on, NEVER = force off, AUTO = tablet/Chromebook width and up.
    val useRail = when (desktopMode) {
        DesktopLayoutMode.ALWAYS -> true
        DesktopLayoutMode.NEVER -> false
        DesktopLayoutMode.AUTO -> wsc.isTabletOrWider
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    fun isSelected(prefix: String): Boolean {
        return currentRoute == prefix || currentRoute?.startsWith(prefix) == true
    }

    @Composable
    fun NavContent() {
        NavHost(navController = navController, startDestination = Dest.REPO_LIST, modifier = Modifier.fillMaxSize()) {

        composable(Dest.REPO_LIST) {
            val vm: RepoListViewModel = viewModel(factory = factory)
            RepoListScreen(
                vm = vm,
                onOpenRepo = { repo: RepoInfo -> navController.navigate(Dest.repoDetail(repo.localPath)) },
                onClone = { navController.navigate(Dest.CLONE) },
                onBrowseGitHub = { navController.navigate(Dest.BROWSE_GITHUB) },
                onOpenProfile = { navController.navigate(Dest.profile()) },
                onSearchPeople = { navController.navigate(Dest.USER_SEARCH) },
                onSettings = { navController.navigate(Dest.SETTINGS) { launchSingleTop = true } },
                onLogs = { navController.navigate(Dest.LOGS) },
                isDesktopLayout = useRail
            )
        }





        composable(Dest.USER_SEARCH) {
            val vm: UserSearchViewModel = viewModel(factory = factory)
            UserSearchScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onUserClick = { login -> navController.navigate(Dest.profile(login)) },
                onNeedsAuth = { navController.navigate(Dest.SETTINGS) { launchSingleTop = true } }
            )
        }

        composable(Dest.PROFILE_SELF) {
            val vm: ProfileViewModel = viewModel(factory = factory)
            ProfileScreen(
                vm = vm,
                login = null,
                onBack = { navController.popBackStack() },
                onOpenUser = { login -> navController.navigate(Dest.profile(login)) },
                onCloneRepo = { navController.navigate(Dest.CLONE) },
                onOpenRepo = { repo ->
                    navController.navigate(Dest.remoteBrowse(repo.ownerLogin, repo.name, repo.defaultBranch))
                },
                onNeedsAuth = { navController.navigate(Dest.SETTINGS) { launchSingleTop = true } }
            )
        }

        composable(
            Dest.PROFILE_USER,
            arguments = listOf(navArgument("login") { type = NavType.StringType })
        ) { backStackEntry ->
            val login = Dest.decode(backStackEntry.arguments!!.getString("login")!!)
            val vm: ProfileViewModel = viewModel(factory = factory)
            ProfileScreen(
                vm = vm,
                login = login,
                onBack = { navController.popBackStack() },
                onOpenUser = { other -> navController.navigate(Dest.profile(other)) },
                onCloneRepo = { navController.navigate(Dest.CLONE) },
                onOpenRepo = { repo ->
                    navController.navigate(Dest.remoteBrowse(repo.ownerLogin, repo.name, repo.defaultBranch))
                },
                onNeedsAuth = { navController.navigate(Dest.SETTINGS) { launchSingleTop = true } }
            )
        }

        composable(
            Dest.REMOTE_BROWSE,
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType },
                navArgument("ref") { type = NavType.StringType },
                navArgument("path") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                }
            )
        ) { backStackEntry ->
            val owner = Dest.decode(backStackEntry.arguments!!.getString("owner")!!)
            val repo = Dest.decode(backStackEntry.arguments!!.getString("repo")!!)
            val ref = Dest.decode(backStackEntry.arguments!!.getString("ref")!!)
            val path = Dest.decode(backStackEntry.arguments?.getString("path").orEmpty())
            val vm: RemoteBrowseViewModel = viewModel(factory = factory)
            RemoteBrowseScreen(
                owner = owner,
                repo = repo,
                ref = ref,
                path = path,
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenFile = { filePath -> navController.navigate(Dest.remoteFile(owner, repo, ref, filePath)) },
                onNeedsAuth = { navController.navigate(Dest.SETTINGS) { launchSingleTop = true } }
            )
        }

        composable(
            Dest.REMOTE_FILE,
            arguments = listOf(
                navArgument("owner") { type = NavType.StringType },
                navArgument("repo") { type = NavType.StringType },
                navArgument("ref") { type = NavType.StringType },
                navArgument("path") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                }
            )
        ) { backStackEntry ->
            val owner = Dest.decode(backStackEntry.arguments!!.getString("owner")!!)
            val repo = Dest.decode(backStackEntry.arguments!!.getString("repo")!!)
            val ref = Dest.decode(backStackEntry.arguments!!.getString("ref")!!)
            val path = Dest.decode(backStackEntry.arguments?.getString("path").orEmpty())
            val vm: RemoteFileViewModel = viewModel(factory = factory)
            RemoteFileScreen(
                owner = owner,
                repo = repo,
                ref = ref,
                path = path,
                vm = vm,
                onBack = { navController.popBackStack() },
                onNeedsAuth = { navController.navigate(Dest.SETTINGS) { launchSingleTop = true } }
            )
        }

        composable(Dest.LOGS) {
            val vm: LogsViewModel = viewModel(factory = factory)
            LogsScreen(vm = vm, onBack = { navController.popBackStack() })
        }

        composable(Dest.CLONE) {
            val vm: CloneViewModel = viewModel(factory = factory)
            CloneScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onCloned = { navController.popBackStack() },
                onNeedsAuth = { url -> navController.navigate(Dest.SETTINGS) { launchSingleTop = true } },
                onBrowseGitHub = { navController.navigate(Dest.BROWSE_GITHUB) }
            )
        }

        composable(Dest.BROWSE_GITHUB) {
            val vm: BrowseGitHubViewModel = viewModel(factory = factory)
            BrowseGitHubScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onCloned = {
                    navController.popBackStack(Dest.REPO_LIST, inclusive = false)
                },
                onNeedsAuth = { navController.navigate(Dest.SETTINGS) { launchSingleTop = true } }
            )
        }

        composable(Dest.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = factory)
            SettingsScreen(vm = vm, onBack = { navController.popBackStack() })
        }

        composable(
            Dest.REPO_DETAIL,
            arguments = listOf(navArgument("repoPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val repoPath = Dest.decode(backStackEntry.arguments!!.getString("repoPath")!!)
            val vm: RepoDetailViewModel = viewModel(factory = factory)
            androidx.compose.runtime.LaunchedEffect(repoPath) { vm.init(repoPath) }
            RepoDetailScreen(
                repoPath = repoPath,
                repoName = repoPath.substringAfterLast('/'),
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenDiff = { filePath, mode -> navController.navigate(Dest.diff(repoPath, filePath, mode)) },
                onOpenFile = { filePath -> navController.navigate(Dest.editor(repoPath, filePath)) },
                onOpenHistory = { navController.navigate(Dest.history(repoPath)) },
                onOpenBranches = { navController.navigate(Dest.branches(repoPath)) },
                onOpenFiles = { navController.navigate(Dest.files(repoPath)) },
                onOpenPullRequests = { navController.navigate(Dest.pullRequests(repoPath)) },
                onOpenIssues = { navController.navigate(Dest.issues(repoPath)) },
                onOpenWorkflows = { navController.navigate(Dest.workflows(repoPath)) },
                onOpenReleases = { navController.navigate(Dest.releases(repoPath)) },
                onConflicts = { navController.navigate(Dest.merge(repoPath)) },
                onNeedsAuth = { _ -> navController.navigate(Dest.SETTINGS) { launchSingleTop = true } }
            )
        }





        composable(
            Dest.HISTORY,
            arguments = listOf(navArgument("repoPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val repoPath = Dest.decode(backStackEntry.arguments!!.getString("repoPath")!!)
            val vm: HistoryViewModel = viewModel(factory = factory)
            HistoryScreen(
                repoPath = repoPath,
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenCommitDiff = { filePath, commitId ->
                    navController.navigate(Dest.diff(repoPath, filePath, "commit:$commitId"))
                },
                onBrowseTreeAtCommit = { commitId ->
                    navController.navigate(Dest.commitTree(repoPath, commitId))
                },
                onBrowseTreeBefore = { parentId ->
                    navController.navigate(Dest.commitTree(repoPath, parentId))
                }
            )
        }

        composable(
            Dest.BRANCHES,
            arguments = listOf(navArgument("repoPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val repoPath = Dest.decode(backStackEntry.arguments!!.getString("repoPath")!!)
            val vm: BranchesViewModel = viewModel(factory = factory)
            BranchesScreen(repoPath = repoPath, vm = vm, onBack = { navController.popBackStack() })
        }

        composable(
            Dest.FILES,
            arguments = listOf(navArgument("repoPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val repoPath = Dest.decode(backStackEntry.arguments!!.getString("repoPath")!!)
            val vm: FilesViewModel = viewModel(factory = factory)
            FilesScreen(
                repoPath = repoPath,
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenFile = { filePath -> navController.navigate(Dest.editor(repoPath, filePath)) }
            )
        }

        composable(
            Dest.EDITOR,
            arguments = listOf(
                navArgument("repoPath") { type = NavType.StringType },
                navArgument("filePath") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val repoPath = Dest.decode(backStackEntry.arguments!!.getString("repoPath")!!)
            val filePath = Dest.decode(backStackEntry.arguments!!.getString("filePath")!!)
            val vm: EditorViewModel = viewModel(factory = factory)
            EditorScreen(
                repoPath = repoPath,
                relativePath = filePath,
                vm = vm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Dest.DIFF,
            arguments = listOf(
                navArgument("repoPath") { type = NavType.StringType },
                navArgument("filePath") { type = NavType.StringType },
                navArgument("mode") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val repoPath = Dest.decode(backStackEntry.arguments!!.getString("repoPath")!!)
            val filePath = Dest.decode(backStackEntry.arguments!!.getString("filePath")!!)
            val mode = Dest.decode(backStackEntry.arguments!!.getString("mode")!!)
            val vm: DiffViewModel = viewModel(factory = factory)
            DiffScreen(repoPath = repoPath, filePath = filePath, mode = mode, vm = vm, onBack = { navController.popBackStack() })
        }

        composable(
            Dest.COMMIT_TREE,
            arguments = listOf(
                navArgument("repoPath") { type = NavType.StringType },
                navArgument("commitId") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val repoPath = Dest.decode(backStackEntry.arguments!!.getString("repoPath")!!)
            val commitId = Dest.decode(backStackEntry.arguments!!.getString("commitId")!!)
            val path = Dest.decode(backStackEntry.arguments?.getString("path") ?: "")
            val vm: CommitTreeViewModel = viewModel(factory = factory)
            CommitTreeScreen(
                repoPath = repoPath,
                commitId = commitId,
                initialPath = path,
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenFile = { filePath ->
                    navController.navigate(Dest.commitFile(repoPath, commitId, filePath))
                },
                onNavigateDir = { dirPath ->
                    navController.navigate(Dest.commitTree(repoPath, commitId, dirPath)) {
                        // Replace so back stack doesn't grow unboundedly for deep trees
                        popUpTo(Dest.COMMIT_TREE) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(
            Dest.COMMIT_FILE,
            arguments = listOf(
                navArgument("repoPath") { type = NavType.StringType },
                navArgument("commitId") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val repoPath = Dest.decode(backStackEntry.arguments!!.getString("repoPath")!!)
            val commitId = Dest.decode(backStackEntry.arguments!!.getString("commitId")!!)
            val filePath = Dest.decode(backStackEntry.arguments?.getString("path") ?: "")
            val vm: CommitFileViewModel = viewModel(factory = factory)
            CommitFileScreen(
                repoPath = repoPath,
                commitId = commitId,
                filePath = filePath,
                vm = vm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            Dest.MERGE,
            arguments = listOf(navArgument("repoPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val repoPath = Dest.decode(backStackEntry.arguments!!.getString("repoPath")!!)
            val vm: MergeViewModel = viewModel(factory = factory)
            MergeScreen(
                repoPath = repoPath,
                vm = vm,
                onBack = { navController.popBackStack() },
                onFinished = { navController.popBackStack() }
            )
        }

        composable(
            Dest.ISSUES,
            arguments = listOf(navArgument("repoPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val repoPath = Dest.decode(backStackEntry.arguments!!.getString("repoPath")!!)
            val vm: IssuesViewModel = viewModel(factory = factory)
            androidx.compose.runtime.LaunchedEffect(repoPath) { vm.init(repoPath) }
            IssuesScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onNeedsAuth = { navController.navigate(Dest.SETTINGS) { launchSingleTop = true } }
            )
        }

        composable(
            Dest.WORKFLOWS,
            arguments = listOf(navArgument("repoPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val repoPath = Dest.decode(backStackEntry.arguments!!.getString("repoPath")!!)
            val vm: WorkflowsViewModel = viewModel(factory = factory)
            androidx.compose.runtime.LaunchedEffect(repoPath) { vm.init(repoPath) }
            WorkflowsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onNeedsAuth = { navController.navigate(Dest.SETTINGS) { launchSingleTop = true } }
            )
        }

        composable(
            Dest.RELEASES,
            arguments = listOf(navArgument("repoPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val repoPath = Dest.decode(backStackEntry.arguments!!.getString("repoPath")!!)
            val vm: ReleasesViewModel = viewModel(factory = factory)
            androidx.compose.runtime.LaunchedEffect(repoPath) { vm.init(repoPath) }
            ReleasesScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onNeedsAuth = { navController.navigate(Dest.SETTINGS) { launchSingleTop = true } }
            )
        }

        composable(
            Dest.PULL_REQUESTS,
            arguments = listOf(navArgument("repoPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val repoPath = Dest.decode(backStackEntry.arguments!!.getString("repoPath")!!)
            val vm: PullRequestsViewModel = viewModel(factory = factory)
            androidx.compose.runtime.LaunchedEffect(repoPath) { vm.init(repoPath) }
            PullRequestsScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onNeedsAuth = { navController.navigate(Dest.SETTINGS) { launchSingleTop = true } }
            )
        }


        }
    }

    if (useRail) {
        // Chromebook / tablet / desktop-window layout: permanent NavigationRail
        // structured identically to the Linux Compose Desktop app.
        Row(Modifier.fillMaxSize()) {
            NavigationRail(modifier = Modifier.fillMaxHeight()) {
                RailItem(
                    icon = Icons.Default.Folder,
                    label = "Repos",
                    selected = isSelected(Dest.REPO_LIST) ||
                        isSelected("repo_detail") || isSelected("history") ||
                        isSelected("branches") || isSelected("diff") ||
                        isSelected("merge") || isSelected("files") ||
                        isSelected("editor") || isSelected("pull_requests") ||
                        isSelected("issues") || isSelected("workflows") ||
                        isSelected("releases")
                ) {
                    navController.navigate(Dest.REPO_LIST) {
                        popUpTo(Dest.REPO_LIST) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                RailItem(
                    icon = Icons.Default.CloudDownload,
                    label = "Clone",
                    selected = isSelected(Dest.CLONE)
                ) {
                    navController.navigate(Dest.CLONE) { launchSingleTop = true }
                }
                RailItem(
                    icon = Icons.Default.TravelExplore,
                    label = "Browse",
                    selected = isSelected(Dest.BROWSE_GITHUB) ||
                        isSelected("remote_browse") || isSelected("remote_file")
                ) {
                    navController.navigate(Dest.BROWSE_GITHUB) { launchSingleTop = true }
                }
                RailItem(
                    icon = Icons.Default.Person,
                    label = "Profile",
                    selected = isSelected(Dest.PROFILE_SELF) || isSelected("profile/")
                ) {
                    navController.navigate(Dest.profile()) { launchSingleTop = true }
                }
                RailItem(
                    icon = Icons.Default.Search,
                    label = "Users",
                    selected = isSelected(Dest.USER_SEARCH)
                ) {
                    navController.navigate(Dest.USER_SEARCH) { launchSingleTop = true }
                }
                Spacer(Modifier.weight(1f))
                RailItem(
                    icon = Icons.Default.Article,
                    label = "Logs",
                    selected = isSelected(Dest.LOGS)
                ) {
                    navController.navigate(Dest.LOGS) { launchSingleTop = true }
                }
                RailItem(
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    selected = isSelected(Dest.SETTINGS)
                ) {
                    navController.navigate(Dest.SETTINGS) { launchSingleTop = true }
                }
            }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                NavContent()
            }
        }
    } else {
        NavContent()
    }
}

@Composable
private fun RailItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) }
    )
}
