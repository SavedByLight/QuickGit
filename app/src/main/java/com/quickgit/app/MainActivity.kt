package com.quickgit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.quickgit.app.navigation.QuickGitNavGraph
import com.quickgit.app.ui.theme.QuickGitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuickGitTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    QuickGitNavGraph()
                }
            }
        }
    }
}
