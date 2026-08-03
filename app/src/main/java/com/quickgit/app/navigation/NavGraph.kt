package com.quickgit.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.quickgit.app.data.models.RepoInfo
import com.quickgit.app.ui.screens.*
import com.quickgit.app.viewmodel.*

@Composable
fun QuickGitNavGraph() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val factory = ViewModelFactory(context.applicationContext as android.app.Application)

    NavHost(navController = navController, startDestination = Dest.REPO_LIST) {

        composable(Dest.REPO_LIST) {
            val vm: RepoListViewModel = viewModel(factory = factory)
            RepoListScreen(
                vm = vm,
                onOpenRepo = { repo: RepoInfo -> navController.navigate(Dest.repoDetail(repo.localPath)) },
                onClone = { navController.navigate(Dest.CLONE) },
                onSettings = { navController.navigate(Dest.SETTINGS) }
            )
        }

        composable(Dest.CLONE) {
            val vm: CloneViewModel = viewModel(factory = factory)
            CloneScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onCloned = { navController.popBackStack() },
                onNeedsAuth = { url -> navController.navigate(Dest.SETTINGS) }
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
                repoName = repoPath.substringAfterLast('/'),
                vm = vm,
                onBack = { navController.popBackStack() },
                onOpenDiff = { filePath, mode -> navController.navigate(Dest.diff(repoPath, filePath, mode)) },
                onOpenHistory = { navController.navigate(Dest.history(repoPath)) },
                onOpenBranches = { navController.navigate(Dest.branches(repoPath)) },
                onOpenFiles = { navController.navigate(Dest.files(repoPath)) },
                onConflicts = { navController.navigate(Dest.merge(repoPath)) },
                onNeedsAuth = { navController.navigate(Dest.SETTINGS) }
            )
        }

        composable(
            Dest.HISTORY,
            arguments = listOf(navArgument("repoPath") { type = NavType.StringType })
        ) { backStackEntry ->
            val repoPath = Dest.decode(backStackEntry.arguments!!.getString("repoPath")!!)
            val vm: HistoryViewModel = viewModel(factory = factory)
            HistoryScreen(repoPath = repoPath, vm = vm, onBack = { navController.popBackStack() })
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
    }
}
