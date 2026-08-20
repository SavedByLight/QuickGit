package com.quickgit.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.models.Release
import com.quickgit.app.data.models.ReleaseAsset

/**
 * Checks GitHub Releases for a newer QuickGit version and exposes a download
 * or Releases URL for the system browser. Does **not** download or install APKs
 * inside the app.
 */
object AppUpdateConfig {
    const val OWNER = "SavedByLight"
    const val REPO = "QuickGit"

    fun releasesUrl(): String =
        "https://github.com/$OWNER/$REPO/releases"

    fun releaseUrl(tagName: String): String =
        "https://github.com/$OWNER/$REPO/releases/tag/${tagName.trim().removePrefix("v").let { if (tagName.startsWith("v")) "v$it" else tagName }}"
            .let { url ->
                // Prefer exact tag from API when available
                if (tagName.isNotBlank()) {
                    "https://github.com/$OWNER/$REPO/releases/tag/$tagName"
                } else {
                    releasesUrl()
                }
            }
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
        val apkAsset: ReleaseAsset? = null
    ) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
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

    fun isAutoPromptSuppressed(): Boolean {
        if (prefs.getBoolean(KEY_NEVER, false)) return true
        val until = prefs.getLong(KEY_SNOOZE_UNTIL_MS, 0L)
        return until > System.currentTimeMillis()
    }

    fun snoozeForDays(days: Int) {
        require(days > 0)
        val until = System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L
        prefs.edit()
            .putLong(KEY_SNOOZE_UNTIL_MS, until)
            .putBoolean(KEY_NEVER, false)
            .apply()
        AppLog.i(TAG, "Update prompt snoozed for $days days (until $until)")
    }

    fun snoozeNever() {
        prefs.edit()
            .putBoolean(KEY_NEVER, true)
            .remove(KEY_SNOOZE_UNTIL_MS)
            .apply()
        AppLog.i(TAG, "Update auto-prompt disabled permanently")
    }

    fun clearSnooze() {
        prefs.edit()
            .remove(KEY_SNOOZE_UNTIL_MS)
            .remove(KEY_NEVER)
            .apply()
    }

    /** URL for the GitHub Releases page (specific release when known). */
    fun releasesPageUrl(release: Release? = null): String {
        val tag = release?.tagName?.takeIf { it.isNotBlank() }
        return if (tag != null) {
            "https://github.com/${AppUpdateConfig.OWNER}/${AppUpdateConfig.REPO}/releases/tag/$tag"
        } else {
            AppUpdateConfig.releasesUrl()
        }
    }

    /**
     * Prefer the direct APK browser download URL when available so the default
     * browser can download the update; otherwise fall back to the release page.
     */
    fun downloadOrReleasesUrl(available: UpdateCheckResult.Available): String {
        val apkUrl = available.apkAsset?.browserDownloadUrl?.takeIf { it.isNotBlank() }
        return apkUrl ?: releasesPageUrl(available.release)
    }

    fun currentVersion(): AppVersionInfo {
        val pm = context.packageManager
        val pkg = context.packageName
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                val info = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                AppVersionInfo(
                    versionName = normalizeVersionName(info.versionName ?: "0.0.0"),
                    versionCode = info.longVersionCode
                )
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, 0)
                @Suppress("DEPRECATION")
                AppVersionInfo(
                    versionName = normalizeVersionName(info.versionName ?: "0.0.0"),
                    versionCode = info.versionCode.toLong()
                )
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "currentVersion failed", e)
            AppVersionInfo("0.0.0", 0L)
        }
    }

    /**
     * Fetches releases and picks the highest version greater than the installed app.
     * Uses listReleases (not only /latest) so we don't miss a higher tag.
     */
    fun checkForUpdate(): UpdateCheckResult {
        val current = currentVersion()
        AppLog.i(TAG, "checkForUpdate: installed ${current.versionName} (code ${current.versionCode})")
        return try {
            val token = credentialStore.getHttpsToken("github.com")
            val api = GitHubApi(token)

            // Prefer full list so we can pick the max semver; fall back to /latest
            val releases = api.listReleases(AppUpdateConfig.OWNER, AppUpdateConfig.REPO, perPage = 30)
                .getOrElse { listErr ->
                    AppLog.w(TAG, "listReleases failed: ${listErr.message}; trying getLatestRelease")
                    api.getLatestRelease(AppUpdateConfig.OWNER, AppUpdateConfig.REPO)
                        .map { listOf(it) }
                        .getOrElse {
                            return UpdateCheckResult.Error(
                                it.message ?: listErr.message ?: "Failed to fetch releases"
                            )
                        }
                }

            val candidates = releases.filter { !it.draft && !it.prerelease }
                .ifEmpty { releases.filter { !it.draft } }

            if (candidates.isEmpty()) {
                AppLog.i(TAG, "No non-draft releases found")
                return UpdateCheckResult.UpToDate(current)
            }

            var best: Pair<Release, AppVersionInfo>? = null
            for (rel in candidates) {
                val info = parseReleaseVersion(rel)
                if (best == null || compareVersionNames(info.versionName, best.second.versionName) > 0) {
                    best = rel to info
                } else if (
                    best != null &&
                    compareVersionNames(info.versionName, best.second.versionName) == 0 &&
                    info.versionCode > best.second.versionCode
                ) {
                    best = rel to info
                }
            }

            val (release, latest) = best!!
            AppLog.i(
                TAG,
                "best release tag=${release.tagName} name=${latest.versionName} code=${latest.versionCode}"
            )

            if (isNewer(latest, current)) {
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                UpdateCheckResult.Available(current, latest, release, apk)
            } else {
                AppLog.i(TAG, "Installed version is up to date relative to ${latest.versionName}")
                UpdateCheckResult.UpToDate(current)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "checkForUpdate failed", e)
            UpdateCheckResult.Error(e.message ?: "Update check failed")
        }
    }

    private fun normalizeVersionName(raw: String): String {
        return raw.trim()
            .removePrefix("QuickGit")
            .trim()
            .removePrefix("v")
            .trim()
            .ifBlank { "0.0.0" }
    }

    private fun parseReleaseVersion(release: Release): AppVersionInfo {
        val tag = normalizeVersionName(release.tagName)
        val name = normalizeVersionName(release.name)
        val versionName = when {
            tag.matches(Regex("""\d+(\.\d+)*([.-][\w.]+)?""")) -> tag.substringBefore("-").substringBefore("+")
                .let { if (it.matches(Regex("""\d+(\.\d+)*"""))) it else tag }
            name.matches(Regex("""\d+(\.\d+)*""")) -> name
            else -> tag.ifBlank { name }.ifBlank { "0.0.0" }
        }
        // Prefer a full monotonic code from x.y.z when possible
        val parts = versionName.split('.').mapNotNull { it.toLongOrNull() }
        val codeFromName = when (parts.size) {
            0 -> 0L
            1 -> parts[0]
            2 -> parts[0] * 1_000_000 + parts[1] * 1_000
            else -> parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
        }
        val codeFromTagSuffix = release.tagName.removePrefix("v").substringAfterLast('.').toLongOrNull() ?: 0L
        val code = maxOf(codeFromName, codeFromTagSuffix)
        return AppVersionInfo(versionName = versionName, versionCode = code)
    }

    /**
     * True if [latest] is a newer app version than [current].
     * Compares semver names first, then versionCode.
     */
    private fun isNewer(latest: AppVersionInfo, current: AppVersionInfo): Boolean {
        val byName = compareVersionNames(latest.versionName, current.versionName)
        if (byName != 0) {
            AppLog.i(TAG, "isNewer by name: ${latest.versionName} vs ${current.versionName} -> $byName")
            return byName > 0
        }
        if (latest.versionCode > 0 && current.versionCode > 0 && latest.versionCode != current.versionCode) {
            AppLog.i(TAG, "isNewer by code: ${latest.versionCode} vs ${current.versionCode}")
            return latest.versionCode > current.versionCode
        }
        return false
    }

    /** Dotted numeric compare: "2.0.1" vs "1.0.270". */
    private fun compareVersionNames(a: String, b: String): Int {
        val pa = normalizeVersionName(a).split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val pb = normalizeVersionName(b).split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
