package com.quickgit.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.quickgit.app.data.AppLog
import com.quickgit.app.data.GitProgressNotifier
import com.quickgit.app.navigation.QuickGitNavGraph
import com.quickgit.app.ui.adaptive.LocalWindowSizeClass
import com.quickgit.app.ui.components.AutoUpdateHost
import com.quickgit.app.ui.theme.QuickGitTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val notif = results[Manifest.permission.POST_NOTIFICATIONS]
        if (notif == false) {
            AppLog.w("MainActivity", "POST_NOTIFICATIONS denied — git progress notifications disabled")
        } else if (notif == true) {
            AppLog.i("MainActivity", "POST_NOTIFICATIONS granted")
            GitProgressNotifier.ensureChannel(this)
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        preferHighestRefreshRate()
        GitProgressNotifier.ensureChannel(this)
        requestPermissionsIfNeeded()
        setContent {
            // Recalculated on every recomposition when the window is resized
            // (tablets, Chromebook freeform, multi-window, foldables).
            val windowSizeClass = calculateWindowSizeClass(this)
            QuickGitTheme {
                CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AutoUpdateHost {
                            QuickGitNavGraph()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-apply in case the user switched displays / system refresh policy while away.
        preferHighestRefreshRate()
        // Re-push repo list whenever the phone app is foregrounded so the watch
        // can receive data even if it missed the cold-start broadcast.
        runCatching {
            (application as QuickGitApp).wearSyncManager.syncReposToWear()
        }
    }

    /**
     * Request the display mode with the highest refresh rate available on this device
     * (60 / 90 / 120 / 144 / 165 Hz, etc.). Without this, many OEMs keep the app at 60 Hz
     * even when the panel supports higher rates.
     *
     * Uses [WindowManager.LayoutParams.preferredDisplayModeId] (API 23+; minSdk is 26).
     */
    private fun preferHighestRefreshRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }
            if (display == null) return

            val modes = display.supportedModes
            if (modes.isEmpty()) return

            // Prefer highest refresh rate; if rates tie, prefer higher resolution.
            val best = modes.maxWithOrNull(
                compareBy<Display.Mode> { it.refreshRate }
                    .thenBy { it.physicalWidth.toLong() * it.physicalHeight.toLong() }
            ) ?: return

            val params = window.attributes
            if (params.preferredDisplayModeId != best.modeId) {
                params.preferredDisplayModeId = best.modeId
                window.attributes = params
                AppLog.i(
                    "MainActivity",
                    "preferredDisplayModeId=${best.modeId} " +
                        "${best.physicalWidth}x${best.physicalHeight} @ ${"%.2f".format(best.refreshRate)}Hz " +
                        "(${modes.size} modes)"
                )
            }
        } catch (e: Exception) {
            AppLog.w("MainActivity", "preferHighestRefreshRate failed: ${e.message}")
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.READ_EXTERNAL_STORAGE
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
