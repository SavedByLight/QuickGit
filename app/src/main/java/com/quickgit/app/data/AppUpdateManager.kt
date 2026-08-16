package com.quickgit.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.models.Release
import com.quickgit.app.data.models.ReleaseAsset
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer QuickGit APK (published by
 * `.github/workflows/release.yml`) and downloads/installs it.
 *
 * [OWNER] / [REPO] must match the GitHub repository that hosts those releases.
 * Update them if you fork or rename the upstream.
 */
object AppUpdateConfig {
    const val OWNER = "SavedByLight"
    const val REPO = "QuickGit"
}

data class AppVersionInfo(
    val versionName: String,
    val versionCode: Long
)

sealed class UpdateCheckResult {
    data class UpToDate(val current: AppVersionInfo) : UpdateCheckResult()
    data class Available(
        val current: AppVersionInfo,
        val latest: AppVersionInfo,
        val release: Release,
        val apkAsset: ReleaseAsset
    ) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

sealed class DownloadResult {
    data class Success(val apkFile: File) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

class AppUpdateManager(
    private val context: Context,
    private val credentialStore: CredentialStore
) {
    private val TAG = "AppUpdateManager"

    companion object {
        private const val PREFS_NAME = "app_update_prefs"
        private const val KEY_SNOOZE_UNTIL_MS = "snooze_until_ms"
        private const val KEY_NEVER = "never_prompt"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * True when the user asked not to be auto-prompted for updates right now
     * (timed snooze still active, or "never").
     * Manual "Check for updates" in Settings ignores this.
     */
    fun isAutoPromptSuppressed(): Boolean {
        if (prefs.getBoolean(KEY_NEVER, false)) return true
        val until = prefs.getLong(KEY_SNOOZE_UNTIL_MS, 0L)
        return until > System.currentTimeMillis()
    }

    /** Suppress auto prompts for [days] days from now. */
    fun snoozeForDays(days: Int) {
        require(days > 0)
        val until = System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L
        prefs.edit()
            .putLong(KEY_SNOOZE_UNTIL_MS, until)
            .putBoolean(KEY_NEVER, false)
            .apply()
        AppLog.i(TAG, "Update prompt snoozed for $days days (until $until)")
    }

    /** Never auto-prompt again (until the user clears it via a future Settings option). */
    fun snoozeNever() {
        prefs.edit()
            .putBoolean(KEY_NEVER, true)
            .remove(KEY_SNOOZE_UNTIL_MS)
            .apply()
        AppLog.i(TAG, "Update auto-prompt disabled permanently")
    }

    /** Clear any snooze / never so the next launch can prompt again. */
    fun clearSnooze() {
        prefs.edit()
            .remove(KEY_SNOOZE_UNTIL_MS)
            .remove(KEY_NEVER)
            .apply()
    }

    fun currentVersion(): AppVersionInfo {
        val pm = context.packageManager
        val pkg = context.packageName
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                val info = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                AppVersionInfo(
                    versionName = info.versionName ?: "?",
                    versionCode = info.longVersionCode
                )
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, 0)
                @Suppress("DEPRECATION")
                AppVersionInfo(
                    versionName = info.versionName ?: "?",
                    versionCode = info.versionCode.toLong()
                )
            }
        } catch (e: Exception) {
            AppVersionInfo("?", 0L)
        }
    }

    /**
     * Fetches the latest GitHub Release and compares it to the installed version.
     * Uses the stored github.com token when present (private repos); otherwise
     * unauthenticated public API access.
     */
    fun checkForUpdate(): UpdateCheckResult {
        val current = currentVersion()
        return try {
            val token = credentialStore.getHttpsToken("github.com")
            val api = GitHubApi(token)
            val release = api.getLatestRelease(AppUpdateConfig.OWNER, AppUpdateConfig.REPO)
                .getOrElse { return UpdateCheckResult.Error(it.message ?: "Failed to fetch latest release") }

            if (release.draft) {
                return UpdateCheckResult.UpToDate(current)
            }

            val latest = parseReleaseVersion(release)
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return UpdateCheckResult.Error("Latest release has no APK asset")

            if (isNewer(latest, current)) {
                UpdateCheckResult.Available(current, latest, release, apk)
            } else {
                UpdateCheckResult.UpToDate(current)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "checkForUpdate failed", e)
            UpdateCheckResult.Error(e.message ?: "Update check failed")
        }
    }

    /**
     * Downloads [asset] to the app cache. [onProgress] receives 0..100.
     */
    fun downloadApk(
        asset: ReleaseAsset,
        onProgress: (Int) -> Unit = {}
    ): DownloadResult {
        return try {
            val url = asset.browserDownloadUrl
            if (url.isBlank()) return DownloadResult.Error("Missing download URL")

            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Clear previous downloads
            dir.listFiles()?.forEach { it.delete() }
            val outFile = File(dir, asset.name.ifBlank { "quickgit-update.apk" })

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
                credentialStore.getHttpsToken("github.com")?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
            }

            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return DownloadResult.Error("Download failed (HTTP $code)")
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: asset.size
                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                            }
                        }
                        output.flush()
                    }
                }
                onProgress(100)
                AppLog.i(TAG, "Downloaded ${outFile.length()} bytes -> ${outFile.absolutePath}")
                DownloadResult.Success(outFile)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "downloadApk failed", e)
            DownloadResult.Error(e.message ?: "Download failed")
        }
    }

    /** Fires the system package installer for a downloaded APK. */
    fun installApk(apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "installApk failed", e)
            false
        }
    }

    /** True if the app is allowed to install packages (Android 8+). */
    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun installPermissionSettingsIntent(): Intent {
        return Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun parseReleaseVersion(release: Release): AppVersionInfo {
        // Prefer tag like "v1.0.42" / "1.0.42" — last segment as versionCode when numeric.
        val tag = release.tagName.removePrefix("v").trim()
        val name = release.name.removePrefix("QuickGit").trim().removePrefix("v").trim()
            .ifBlank { tag }
        val code = tag.substringAfterLast('.').toLongOrNull()
            ?: name.substringAfterLast('.').toLongOrNull()
            ?: 0L
        val versionName = when {
            tag.matches(Regex("""\d+(\.\d+)*""")) -> tag
            name.matches(Regex("""\d+(\.\d+)*""")) -> name
            else -> tag.ifBlank { name }
        }
        return AppVersionInfo(versionName = versionName, versionCode = code)
    }

    private fun isNewer(latest: AppVersionInfo, current: AppVersionInfo): Boolean {
        if (latest.versionCode > 0 && current.versionCode > 0) {
            return latest.versionCode > current.versionCode
        }
        return compareVersionNames(latest.versionName, current.versionName) > 0
    }

    /** Simple dotted numeric compare: "1.0.12" vs "1.0.9". */
    private fun compareVersionNames(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
