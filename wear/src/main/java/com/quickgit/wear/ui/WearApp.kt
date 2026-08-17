package com.quickgit.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import com.quickgit.wear.ui.screens.AboutScreen
import com.quickgit.wear.ui.screens.RepoDetailScreen
import com.quickgit.wear.ui.screens.RepoListScreen

object WearRoutes {
    const val LIST = "list"
    const val DETAIL = "detail/{path}"
    const val ABOUT = "about"

    fun detail(path: String): String {
        val encoded = android.util.Base64.encodeToString(
            path.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        return "detail/$encoded"
    }

    fun decodePath(encoded: String): String {
        val bytes = android.util.Base64.decode(
            encoded,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        return bytes.toString(Charsets.UTF_8)
    }
}

@Composable
fun WearApp() {
    val context = LocalContext.current
    val repo = remember { WearRepoRepository(context.applicationContext) }
    val nav = rememberSwipeDismissableNavController()

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
