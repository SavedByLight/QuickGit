package com.quickgit.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.quickgit.app.navigation.QuickGitNavGraph
import com.quickgit.app.ui.components.AutoUpdateHost
import com.quickgit.app.ui.theme.QuickGitTheme

class MainActivity : ComponentActivity() {

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* reposRoot falls back if still not writable */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStorageIfNeeded()
        setContent {
            QuickGitTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // On launch: check GitHub Releases, auto-download newer APK, prompt install
                    AutoUpdateHost {
                        QuickGitNavGraph()
                    }
                }
            }
        }
    }

    private fun requestStorageIfNeeded() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            // API 30+ ignores requestLegacyExternalStorage and enforces scoped
            // storage no matter what permissions are held, so there's nothing
            // to request here — RepoManager.reposRoot falls back to
            // app-private storage on these versions instead.
            return
        }
        // API 29 (Q) still needs WRITE_EXTERNAL_STORAGE granted for the legacy
        // flag to actually restore public-folder write access.
        val needed = mutableListOf<String>()
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
        if (needed.isNotEmpty()) {
            storagePermissionLauncher.launch(needed.toTypedArray())
        }
    }
}
