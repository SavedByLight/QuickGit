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
 * Chromebook / ARC devices skip the biometric gate (keyboard + trackpad class).
 * Phones and tablets require biometrics or device PIN/pattern/password when available.
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
 * Wraps [content] so Credentials / account manager only shows after successful
 * biometric or device-credential auth on phones and tablets. Chromebooks open immediately.
 */
@Composable
fun CredentialsAuthGate(
    onCancel: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val skipAuth = remember(context) { context.isChromebookDevice() }
    var unlocked by remember { mutableStateOf(skipAuth) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var authAttempted by remember { mutableStateOf(false) }

    fun launchPrompt() {
        val activity = context as? FragmentActivity
        if (activity == null) {
            // Non-activity host — fail open rather than brick credentials
            unlocked = true
            return
        }
        val biometricManager = BiometricManager.from(context)
        val canBio = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        when (canBio) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                // proceed to prompt
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                statusMessage =
                    "No fingerprint, face, or screen lock is set up. Add one in system Settings, or continue unprotected."
                // Allow continue without lock when nothing enrolled
                unlocked = true
                return
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> {
                // No usable authenticator — don't lock the user out
                unlocked = true
                return
            }
            else -> {
                statusMessage = "Biometric check unavailable (code $canBio). Opening Credentials."
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
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        statusMessage = errString.toString()
                    } else {
                        statusMessage = errString.toString()
                    }
                }

                override fun onAuthenticationFailed() {
                    statusMessage = "Authentication failed — try again"
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Credentials")
            .setSubtitle("Confirm it's you to open accounts and tokens")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        authAttempted = true
        prompt.authenticate(info)
    }

    LaunchedEffect(skipAuth) {
        if (!skipAuth && !unlocked) {
            launchPrompt()
        }
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
                Icons.Default.Fingerprint,
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
                "Use fingerprint, face, or your device PIN to open the account manager.",
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
