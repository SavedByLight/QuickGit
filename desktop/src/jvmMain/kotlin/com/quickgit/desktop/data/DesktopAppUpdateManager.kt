package com.quickgit.desktop.data

import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Properties
import java.util.regex.Pattern

/**
 * Checks GitHub Releases for a newer QuickGit desktop package and downloads it.
 * Mirrors Android AppUpdateManager but targets platform packages (.msi / .deb / AppImage / .dmg).
 */
object DesktopAppUpdateConfig {
    const val OWNER = "SavedByLight"
    const val REPO = "QuickGit"
    /** Stamped by CI / packageVersion; override via system property quickgit.version. */
    var currentVersionName: String =
        System.getProperty("quickgit.version")
            ?: System.getenv("QUICKGIT_VERSION")
            ?: "1.0.0"
}

data class DesktopVersionInfo(val versionName: String, val versionCode: Long)

sealed class DesktopUpdateCheckResult {
    data class UpToDate(val current: DesktopVersionInfo) : DesktopUpdateCheckResult()
    data class Available(
        val current: DesktopVersionInfo,
        val latest: DesktopVersionInfo,
        val releaseName: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val assetName: String
    ) : DesktopUpdateCheckResult()
    data class Error(val message: String) : DesktopUpdateCheckResult()
}

class DesktopAppUpdateManager(
    private val credentialStore: DesktopCredentialStore
) {
    private val prefsFile = File(
        System.getProperty("user.home"),
        ".config/quickgit/update_prefs.properties"
    )
    private val prefs = Properties().apply {
        if (prefsFile.exists()) prefsFile.inputStream().use { load(it) }
    }

    private fun savePrefs() {
        prefsFile.parentFile?.mkdirs()
        prefsFile.outputStream().use { prefs.store(it, "QuickGit update prefs") }
    }

    fun isAutoPromptSuppressed(): Boolean {
        if (prefs.getProperty("never") == "true") return true
        val until = prefs.getProperty("snooze_until_ms")?.toLongOrNull() ?: 0L
        return until > System.currentTimeMillis()
    }

    fun snoozeForDays(days: Int) {
        prefs.setProperty(
            "snooze_until_ms",
            (System.currentTimeMillis() + days * 24L * 60 * 60 * 1000).toString()
        )
        savePrefs()
    }

    fun neverPrompt() {
        prefs.setProperty("never", "true")
        savePrefs()
    }

    fun clearSuppression() {
        prefs.remove("never")
        prefs.remove("snooze_until_ms")
        savePrefs()
    }

    fun currentVersion(): DesktopVersionInfo {
        val name = DesktopAppUpdateConfig.currentVersionName
        return DesktopVersionInfo(name, parseVersionCode(name))
    }

    private fun preferredAssetSuffixes(): List<String> {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> listOf(".msi", ".exe")
            os.contains("mac") || os.contains("darwin") -> listOf(".dmg", ".pkg")
            else -> {
                // Linux and unknowns
                val osRelease = File("/etc/os-release").readTextSafe().lowercase()
                val isDebianFamily = File("/etc/debian_version").exists() ||
                    "ubuntu" in osRelease || "debian" in osRelease || "linuxmint" in osRelease
                if (isDebianFamily) listOf(".deb", ".AppImage", ".rpm")
                else listOf(".AppImage", ".deb", ".rpm")
            }
        }
    }

    private fun File.readTextSafe(): String =
        try { if (isFile) readText() else "" } catch (_: Exception) { "" }

    fun checkForUpdate(): DesktopUpdateCheckResult {
        val current = currentVersion()
        return try {
            val api =
                "https://api.github.com/repos/${DesktopAppUpdateConfig.OWNER}/${DesktopAppUpdateConfig.REPO}/releases/latest"
            val body = httpGet(api) ?: return DesktopUpdateCheckResult.Error("Empty response from GitHub")
            if (body.contains("\"message\"") && body.contains("Not Found")) {
                return DesktopUpdateCheckResult.UpToDate(current)
            }
            val tag = jsonString(body, "tag_name")?.removePrefix("v")
                ?: return DesktopUpdateCheckResult.Error("No tag_name in release")
            val latestCode = parseVersionCode(tag)
            if (latestCode <= current.versionCode) {
                return DesktopUpdateCheckResult.UpToDate(current)
            }
            val releaseName = jsonString(body, "name") ?: tag
            val notes = jsonString(body, "body") ?: ""
            val assetsBlob = extractArray(body, "assets") ?: ""
            val suffixes = preferredAssetSuffixes()
            var chosenUrl: String? = null
            var chosenName: String? = null
            for (suffix in suffixes) {
                val match = findAsset(assetsBlob, suffix)
                if (match != null) {
                    chosenName = match.first
                    chosenUrl = match.second
                    break
                }
            }
            if (chosenUrl.isNullOrBlank() || chosenName.isNullOrBlank()) {
                return DesktopUpdateCheckResult.Error("No desktop package for this OS in latest release")
            }
            DesktopUpdateCheckResult.Available(
                current = current,
                latest = DesktopVersionInfo(tag, latestCode),
                releaseName = releaseName,
                releaseNotes = notes,
                downloadUrl = chosenUrl,
                assetName = chosenName
            )
        } catch (e: Exception) {
            DesktopUpdateCheckResult.Error(e.message ?: "Update check failed")
        }
    }

    fun downloadUpdate(
        url: String,
        assetName: String,
        onProgress: (Int) -> Unit = {}
    ): Result<File> {
        return try {
            val dir = File(System.getProperty("user.home"), "Downloads/QuickGit-updates").apply { mkdirs() }
            val out = File(dir, assetName)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "QuickGit-Desktop")
                authHeader()?.let { setRequestProperty("Authorization", it) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return Result.failure(IllegalStateException("Download failed (HTTP $code)"))
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            conn.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            onProgress(100)
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun openFile(file: File) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file)
        } catch (_: Exception) { }
    }

    fun openReleasePage() {
        val uri = URI(
            "https://github.com/${DesktopAppUpdateConfig.OWNER}/${DesktopAppUpdateConfig.REPO}/releases/latest"
        )
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(uri)
        } catch (_: Exception) { }
    }

    private fun authHeader(): String? {
        val token = credentialStore.getGithubToken()?.takeIf { it.isNotBlank() }
            ?: credentialStore.getHttpsToken("github.com")?.takeIf { it.isNotBlank() }
        return token?.let { "Bearer $it" }
    }

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "QuickGit-Desktop/${DesktopAppUpdateConfig.currentVersionName}")
            authHeader()?.let { setRequestProperty("Authorization", it) }
        }
        val code = conn.responseCode
        if (code == 404) return """{"message":"Not Found"}"""
        if (code !in 200..299) throw IllegalStateException("GitHub API HTTP $code")
        return conn.inputStream.bufferedReader().readText()
    }

    companion object {
        private val VERSION_PATTERN = Pattern.compile("""(\d+)\.(\d+)(?:\.(\d+))?""")

        fun parseVersionCode(name: String): Long {
            val m = VERSION_PATTERN.matcher(name.trim().removePrefix("v"))
            if (!m.find()) return 0L
            val major = m.group(1).toLongOrNull() ?: 0L
            val minor = m.group(2).toLongOrNull() ?: 0L
            val patch = m.group(3)?.toLongOrNull() ?: 0L
            return major * 1_000_000L + minor * 1_000L + patch
        }

        private fun jsonString(json: String, key: String): String? {
            val re = Pattern.compile("\"${Pattern.quote(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            val m = re.matcher(json)
            if (!m.find()) return null
            return m.group(1)
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }

        private fun extractArray(json: String, key: String): String? {
            val idx = json.indexOf("\"$key\"")
            if (idx < 0) return null
            val bracket = json.indexOf('[', idx)
            if (bracket < 0) return null
            var depth = 0
            for (i in bracket until json.length) {
                when (json[i]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return json.substring(bracket, i + 1)
                    }
                }
            }
            return null
        }

        /** Returns (name, browser_download_url) for first asset ending with [suffix]. */
        private fun findAsset(assetsJson: String, suffix: String): Pair<String, String>? {
            // Split roughly on asset objects
            val nameRe = Pattern.compile("\"name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            val urlRe = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            var searchFrom = 0
            while (searchFrom < assetsJson.length) {
                val nm = nameRe.matcher(assetsJson)
                if (!nm.find(searchFrom)) break
                val name = nm.group(1)
                val afterName = nm.end()
                val um = urlRe.matcher(assetsJson)
                if (!um.find(afterName)) {
                    searchFrom = afterName
                    continue
                }
                // Prefer url that belongs to this asset (before next name)
                val url = um.group(1)
                val nextName = nameRe.matcher(assetsJson)
                val nextNamePos = if (nextName.find(afterName)) nextName.start() else assetsJson.length
                if (um.start() < nextNamePos && name.endsWith(suffix, ignoreCase = true)) {
                    return name to url
                }
                searchFrom = afterName
            }
            return null
        }
    }
}
