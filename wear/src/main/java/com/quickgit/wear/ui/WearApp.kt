package com.quickgit.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.quickgit.wear.data.WearRepoRepository
import com.quickgit.wear.data.WearRoutes
import com.quickgit.wear.ui.screens.AboutScreen
import com.quickgit.wear.ui.screens.RepoDetailScreen
import com.quickgit.wear.ui.screens.RepoListScreen

@Composable
fun WearApp() {
    val context = LocalContext.current
    val repo = remember { WearRepoRepository(context.applicationContext) }
    val nav = rememberSwipeDismissableNavController()

    DisposableEffect(repo) {
        repo.start()
        onDispose { repo.stop() }
    }

    MaterialTheme {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            timeText = { TimeText() }
        ) {
            SwipeDismissableNavHost(
                navController = nav,
                startDestination = WearRoutes.LIST
            ) {
                composable(WearRoutes.LIST) {
                    RepoListScreen(
                        repository = repo,
                        onOpenRepo = { path -> nav.navigate(WearRoutes.detail(path)) },
                        onAbout = { nav.navigate(WearRoutes.ABOUT) }
                    )
                }
                composable(
                    route = WearRoutes.DETAIL,
                    arguments = listOf(
                        navArgument("path") { type = NavType.StringType }
                    )
                ) { entry ->
                    val encoded = entry.arguments?.getString("path").orEmpty()
                    val path = runCatching { WearRoutes.decodePath(encoded) }.getOrDefault(encoded)
                    RepoDetailScreen(
                        repository = repo,
                        path = path,
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(WearRoutes.ABOUT) {
                    AboutScreen(onBack = { nav.popBackStack() })
                }
            }
        }
    }
}
