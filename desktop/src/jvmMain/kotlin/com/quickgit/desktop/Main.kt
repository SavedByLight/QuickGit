package com.quickgit.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.quickgit.desktop.data.DesktopCredentialStore
import com.quickgit.desktop.data.DesktopRepoManager
import com.quickgit.desktop.ui.QuickGitDesktopApp
import com.quickgit.desktop.ui.theme.QuickGitTheme
import java.awt.Dimension

fun main() = application {
    val windowState = rememberWindowState(width = 1200.dp, height = 800.dp)

    Window(
        onCloseRequest = ::exitApplication,
        title = "QuickGit",
        state = windowState
    ) {
        window.minimumSize = Dimension(800, 600)

        val credentialStore = remember { DesktopCredentialStore() }
        val repoManager = remember { DesktopRepoManager(credentialStore) }

        QuickGitTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                QuickGitDesktopApp(
                    repoManager = repoManager,
                    credentialStore = credentialStore
                )
            }
        }
    }
}
