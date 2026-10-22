package com.quickgit.app.ui.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.quickgit.app.data.CredentialStore
import com.quickgit.app.ui.theme.GitGreen

/**
 * Chromebook / ARC detection. These devices often lack working biometrics and
 * system DEVICE_CREDENTIAL prompts for third-party apps; QuickGit uses an
 * in-app Credentials password instead.
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
 * Wraps [content] so Credentials only shows after successful auth.
 *
 * - Phones & tablets: biometrics and/or device PIN when available; otherwise in-app password
 * - Chromebooks: **in-app Credentials password only** (system lock is unreliable for apps)
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
    val store = remember(context) { CredentialStore.getInstance(context) }
    var unlocked by remember { mutableStateOf(false) }
    var mode by remember {
        // Chromebook always uses app password; phones try system auth first.
        mutableStateOf(if (isChromebook) GateMode.AppPassword else GateMode.SystemAuth)
    }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var authAttempted by remember { mutableStateOf(false) }

    fun launchSystemPrompt() {
        val activity = context as? FragmentActivity
        if (activity == null) {
            mode = GateMode.AppPassword
            return
        }
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val biometricManager = BiometricManager.from(context)
        when (val can = biometricManager.canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                // proceed
            }
            else -> {
                // No usable system authenticator — use in-app password
                mode = GateMode.AppPassword
                statusMessage = null
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
                    // User cancel stays on gate; other errors offer app password
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        statusMessage = errString.toString()
                    } else {
                        statusMessage = errString.toString()
                        mode = GateMode.AppPassword
                    }
                }

                override fun onAuthenticationFailed() {
                    statusMessage = "Authentication failed — try again"
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Credentials")
            .setSubtitle("Only this screen — confirm to view accounts and tokens")
            .setAllowedAuthenticators(authenticators)
            .build()
        authAttempted = true
        prompt.authenticate(info)
    }

    LaunchedEffect(mode) {
        if (!unlocked && mode == GateMode.SystemAuth) {
            launchSystemPrompt()
        }
    }

    if (unlocked) {
        content()
        return
    }

    when (mode) {
        GateMode.AppPassword -> {
            AppPasswordGate(
                store = store,
                isChromebook = isChromebook,
                onUnlocked = { unlocked = true },
                onCancel = onCancel,
                onTrySystemAuth = if (!isChromebook) {
                    { mode = GateMode.SystemAuth; statusMessage = null }
                } else null
            )
        }
        GateMode.SystemAuth -> {
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
                    Text("Credentials locked", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Use fingerprint, face, or your device PIN to open Credentials only. " +
                            "The rest of the app stays unlocked.",
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
                    Button(onClick = { launchSystemPrompt() }) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (authAttempted) "Try again" else "Unlock")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        mode = GateMode.AppPassword
                        statusMessage = null
                    }) {
                        Text("Use a Credentials password instead")
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onCancel) { Text("Back") }
                }
            }
        }
    }
}

private enum class GateMode { SystemAuth, AppPassword }

@Composable
private fun AppPasswordGate(
    store: CredentialStore,
    isChromebook: Boolean,
    onUnlocked: () -> Unit,
    onCancel: () -> Unit,
    onTrySystemAuth: (() -> Unit)?
) {
    val needsSetup = remember { !store.hasCredentialsLockPassword() }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

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
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = GitGreen
            )
            Spacer(Modifier.height(16.dp))
            Text(
                if (needsSetup) "Protect Credentials" else "Unlock Credentials",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (needsSetup) {
                    if (isChromebook) {
                        "Chromebooks often can’t use a system PIN for apps. " +
                            "Create a password that unlocks only the Credentials screen in QuickGit. " +
                            "The rest of the app stays unlocked."
                    } else {
                        "Create a password that unlocks only the Credentials screen. " +
                            "The rest of the app stays unlocked."
                    }
                } else {
                    "Enter your Credentials password. Only this screen is locked."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text(if (needsSetup) "New password" else "Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            if (needsSetup) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (needsSetup) {
                        if (password.length < 4) {
                            error = "Password must be at least 4 characters"
                            return@Button
                        }
                        if (password != confirm) {
                            error = "Passwords do not match"
                            return@Button
                        }
                        store.setCredentialsLockPassword(password)
                        onUnlocked()
                    } else {
                        if (store.verifyCredentialsLockPassword(password)) {
                            onUnlocked()
                        } else {
                            error = "Incorrect password"
                            password = ""
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (needsSetup) "Set password & unlock" else "Unlock")
            }
            Spacer(Modifier.height(8.dp))
            onTrySystemAuth?.let { trySys ->
                TextButton(onClick = trySys) {
                    Text("Use fingerprint / device PIN instead")
                }
            }
            TextButton(onClick = onCancel) { Text("Back") }
        }
    }
}
