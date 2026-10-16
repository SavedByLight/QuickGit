package com.quickgit.app.ui.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.quickgit.app.ui.theme.GitGreen

/**
 * Chromebook / ARC detection. These devices typically lack biometrics but still
 * have a screen-lock PIN/password that [BiometricPrompt] can request via
 * [BiometricManager.Authenticators.DEVICE_CREDENTIAL].
 */
fun Context.isChromebookDevice(): Boolean {
    val pm = packageManager
    return pm.hasSystemFeature("org.chromium.arc") ||
        pm.hasSystemFeature("org.chromium.arc.device_management") ||
        pm.hasSystemFeature("org.chromium.arc.scalable_mode") ||
        (Build.DEVICE?.contains("chrome", ignoreCase = true) == true) ||
        (Build.MODEL?.contains("Chromebook", ignoreCase = true) == true) ||
        (Build.BRAND?.equals("chromium", ignoreCase = true) == true &&
            pm.hasSystemFeature(PackageManager.FEATURE_PC))
}

/**
 * Wraps [content] so Credentials / account manager only shows after successful auth.
 *
 * - Phones & tablets: biometrics and/or device PIN/pattern/password
 * - Chromebooks (Android app): device PIN/password only (no biometrics required)
 *
 * Only this screen is gated; the rest of the app stays unlocked.
 */
@Composable
fun CredentialsAuthGate(
    onCancel: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isChromebook = remember(context) { context.isChromebookDevice() }
    var unlocked by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var authAttempted by remember { mutableStateOf(false) }

    // Chromebooks: screen lock only. Phones/tablets: biometrics + device credential.
    val authenticators = if (isChromebook) {
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    fun launchPrompt() {
        val activity = context as? FragmentActivity
        if (activity == null) {
            unlocked = true
            return
        }
        val biometricManager = BiometricManager.from(context)
        when (val can = biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                // proceed
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                statusMessage = if (isChromebook) {
                    "No screen lock is set up on this Chromebook. Add a PIN or password in system Settings to protect Credentials."
                } else {
                    "No fingerprint, face, or screen lock is set up. Add one in system Settings to protect Credentials."
                }
                // Nothing enrolled — open rather than brick the screen
                unlocked = true
                return
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> {
                statusMessage = "Device lock unavailable (code $can). Opening Credentials."
                unlocked = true
                return
            }
            else -> {
                statusMessage = "Authentication unavailable (code $can). Opening Credentials."
                unlocked = true
                return
            }
        }

        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    statusMessage = null
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    statusMessage = errString.toString()
                }

                override fun onAuthenticationFailed() {
                    statusMessage = "Authentication failed — try again"
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Credentials")
            .setSubtitle(
                if (isChromebook) {
                    "Only this screen — enter your Chromebook PIN or password"
                } else {
                    "Only this screen — confirm to view accounts and tokens"
                }
            )
            .setAllowedAuthenticators(authenticators)
            .build()
        authAttempted = true
        prompt.authenticate(info)
    }

    LaunchedEffect(Unit) {
        if (!unlocked) launchPrompt()
    }

    if (unlocked) {
        content()
        return
    }

    Scaffold { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                if (isChromebook) Icons.Default.Lock else Icons.Default.Fingerprint,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = GitGreen
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Credentials locked",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isChromebook) {
                    "Enter your Chromebook PIN or password to open Credentials only. " +
                        "The rest of the app stays unlocked. Biometrics are not required."
                } else {
                    "Use fingerprint, face, or your device PIN to open Credentials only. " +
                        "The rest of the app stays unlocked."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            statusMessage?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { launchPrompt() }) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (authAttempted) "Try again" else "Unlock")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel) {
                Text("Back")
            }
        }
    }
}
