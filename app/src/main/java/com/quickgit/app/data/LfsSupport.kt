package com.quickgit.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Minimal Git LFS client for Android (no git-lfs binary).
 *
 * Supports:
 * - Detecting LFS pointer files in the working tree
 * - Batch download of missing objects into `.git/lfs/objects`
 * - Replacing pointer files with real content (smudge)
 * - Batch upload of local objects before push
 *
 * Auth uses the same HTTPS token as git operations for the remote host.
 */
object LfsSupport {

    private const val TAG = "LfsSupport"
    private const val POINTER_VERSION = "https://git-lfs.github.com/spec/v1"
    private val POINTER_OID = Regex("""^oid\s+sha256:([a-f0-9]{64})\s*$""", RegexOption.IGNORE_CASE)
    private val POINTER_SIZE = Regex("""^size\s+(\d+)\s*$""", RegexOption.IGNORE_CASE)

    data class Pointer(
        val oid: String,
        val size: Long,
        val relativePath: String
    )

    data class FetchResult(
        val downloaded: Int,
        val alreadyPresent: Int,
        val failed: Int,
        val message: String,
        /** Paths whose working-tree content was replaced from LFS pointers (smudged). */
        val smudgedPaths: List<String> = emptyList()
    )

    fun isPointerFile(file: File): Boolean {
        if (!file.isFile || file.length() > 1024) return false
        return parsePointerContent(file.readText(Charsets.UTF_8)) != null
    }

    fun parsePointerContent(text: String): Pair<String, Long>? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        if (!lines[0].startsWith("version ")) return null
        if (!lines[0].contains("git-lfs")) return null
        var oid: String? = null
        var size: Long? = null
        for (line in lines.drop(1)) {
            POINTER_OID.matchEntire(line)?.let { oid = it.groupValues[1].lowercase() }
            POINTER_SIZE.matchEntire(line)?.let { size = it.groupValues[1].toLongOrNull() }
        }
        val resolvedOid = oid
        val resolvedSize = size
        if (resolvedOid != null && resolvedSize != null) return resolvedOid to resolvedSize
        return null
    }

    fun pointerFileContent(oid: String, size: Long): String =
        "version $POINTER_VERSION\noid sha256:$oid\nsize $size\n"

    fun lfsObjectFile(repoRoot: File, oid: String): File {
        val o = oid.lowercase()
        return File(repoRoot, ".git/lfs/objects/${o.substring(0, 2)}/${o.substring(2, 4)}/$o")
    }

    /** Walk the working tree (skipping .git) and collect LFS pointer files. */
    fun findPointerFiles(repoRoot: File): List<Pointer> {
        val result = mutableListOf<Pointer>()
        val rootPath = repoRoot.toPath()
        repoRoot.walkTopDown()
            .onEnter { dir -> dir.name != ".git" }
            .filter { it.isFile }
            .forEach { file ->
                if (file.length() in 1..1024) {
                    val parsed = try {
                        parsePointerContent(file.readText(Charsets.UTF_8))
                    } catch (_: Exception) {
                        null
                    }
                    if (parsed != null) {
                        val rel = rootPath.relativize(file.toPath()).toString().replace('\\', '/')
                        result += Pointer(parsed.first, parsed.second, rel)
                    }
                }
            }
        return result
    }

    /**
     * Download missing LFS objects for pointer files and smudge them into the working tree.
     * [remoteUrl] is the origin URL (https preferred). [token] / [username] used for HTTPS auth.
     */
    fun fetchAndSmudge(
        repoRoot: File,
        remoteUrl: String,
        username: String?,
        token: String?,
        onProgress: (String) -> Unit = {}
    ): FetchResult {
        val pointers = findPointerFiles(repoRoot)
        if (pointers.isEmpty()) {
            return FetchResult(0, 0, 0, "No LFS pointer files found")
        }

        val smudged = mutableListOf<String>()
        val missing = mutableListOf<Pointer>()
        var already = 0
        for (p in pointers) {
            val obj = lfsObjectFile(repoRoot, p.oid)
            if (obj.isFile && obj.length() == p.size) {
                already++
                // Ensure working tree has real content
                if (smudgeIfNeeded(repoRoot, p, obj)) smudged += p.relativePath
            } else {
                missing += p
            }
        }

        if (missing.isEmpty()) {
            onProgress("All ${pointers.size} LFS object(s) already present")
            return FetchResult(0, already, 0, "All ${pointers.size} LFS objects already present", smudged)
        }

        onProgress("Downloading ${missing.size} LFS object(s)…")
        val batchUrl = lfsBatchUrl(remoteUrl)
            ?: return FetchResult(0, already, missing.size, "Unsupported remote for LFS (need https github-style URL)")

        val byOid = missing.associateBy { it.oid }
        // Chunk large batches — hosts reject or time out on multi-thousand-object payloads.
        val batchSize = 100
        val allOids = missing.map { it.oid to it.size }
        var downloaded = 0
        var failed = 0
        for (chunkStart in allOids.indices step batchSize) {
            val chunk = allOids.subList(chunkStart, minOf(chunkStart + batchSize, allOids.size))
            val objects = try {
                batchRequest(
                    batchUrl = batchUrl,
                    operation = "download",
                    oids = chunk,
                    username = username,
                    token = token
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "LFS batch download failed at offset $chunkStart", e)
                failed += chunk.size
                continue
            }

            for (obj in objects) {
                val oid = obj.optString("oid").lowercase()
                val pointer = byOid[oid]
                if (pointer == null) continue
                val err = obj.optJSONObject("error")
                if (err != null) {
                    AppLog.w(TAG, "LFS object $oid error: ${err.optString("message")}")
                    failed++
                    continue
                }
                val actions = obj.optJSONObject("actions")
                if (actions == null) {
                    failed++
                    continue
                }
                val download = actions.optJSONObject("download")
                if (download == null) {
                    failed++
                    continue
                }
                val href = download.optString("href")
                if (href.isBlank()) {
                    failed++
                    continue
                }
                val headerJson = download.optJSONObject("header")
                try {
                    val dest = lfsObjectFile(repoRoot, oid)
                    dest.parentFile?.mkdirs()
                    downloadToFile(href, headerJson, dest)
                    if (dest.length() != pointer.size) {
                        AppLog.w(TAG, "size mismatch for $oid: got ${dest.length()} expected ${pointer.size}")
                    }
                    if (smudgeIfNeeded(repoRoot, pointer, dest)) smudged += pointer.relativePath
                    downloaded++
                    onProgress("LFS $downloaded/${missing.size}: ${pointer.relativePath}")
                } catch (e: Exception) {
                    AppLog.e(TAG, "download failed for $oid", e)
                    failed++
                }
            }
        }

        val msg = buildString {
            append("LFS: downloaded $downloaded")
            if (already > 0) append(", $already already present")
            if (failed > 0) append(", $failed failed")
        }
        return FetchResult(downloaded, already, failed, msg, smudged)
    }

    /**
     * Upload local LFS objects referenced by pointer files so a subsequent git push can succeed
     * for LFS-tracked content already stored under `.git/lfs/objects`.
     */
    fun uploadLocalObjects(
        repoRoot: File,
        remoteUrl: String,
        username: String?,
        token: String?,
        onProgress: (String) -> Unit = {}
    ): FetchResult {
        val pointers = findPointerFiles(repoRoot)
        val local = pointers.filter { lfsObjectFile(repoRoot, it.oid).isFile }
        if (local.isEmpty()) {
            return FetchResult(0, 0, 0, "No local LFS objects to upload")
        }

        val batchUrl = lfsBatchUrl(remoteUrl)
            ?: return FetchResult(0, 0, local.size, "Unsupported remote for LFS upload")

        onProgress("Uploading ${local.size} LFS object(s)…")
        var uploaded = 0
        var skipped = 0
        var failed = 0
        val byOid = local.associateBy { it.oid }
        val allOids = local.map { it.oid to it.size }
        val batchSize = 100
        for (chunkStart in allOids.indices step batchSize) {
            val chunk = allOids.subList(chunkStart, minOf(chunkStart + batchSize, allOids.size))
            val objects = try {
                batchRequest(
                    batchUrl = batchUrl,
                    operation = "upload",
                    oids = chunk,
                    username = username,
                    token = token
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "LFS batch upload failed at offset $chunkStart", e)
                failed += chunk.size
                continue
            }
            for (obj in objects) {
            val oid = obj.optString("oid").lowercase()
            val pointer = byOid[oid] ?: continue
            val err = obj.optJSONObject("error")
            if (err != null) {
                failed++
                continue
            }
            val actions = obj.optJSONObject("actions")
            if (actions == null || !actions.has("upload")) {
                skipped++ // server already has it
                continue
            }
            val upload = actions.getJSONObject("upload")
            val href = upload.optString("href")
            val headerJson = upload.optJSONObject("header")
            val verify = actions.optJSONObject("verify")
            try {
                val src = lfsObjectFile(repoRoot, oid)
                uploadFile(href, headerJson, src)
                if (verify != null) {
                    verifyObject(verify, oid, pointer.size, username, token)
                }
                uploaded++
                onProgress("LFS upload $uploaded: ${pointer.relativePath}")
            } catch (e: Exception) {
                AppLog.e(TAG, "upload failed for $oid", e)
                failed++
            }
            } // end for (obj in objects)
        } // end chunk loop
        val msg = "LFS: uploaded $uploaded, skipped $skipped, failed $failed"
        return FetchResult(uploaded, skipped, failed, msg)
    }

    /** @return true if the working-tree file was rewritten from an LFS pointer. */
    private fun smudgeIfNeeded(repoRoot: File, pointer: Pointer, objectFile: File): Boolean {
        val workFile = File(repoRoot, pointer.relativePath)
        // If work file is still a pointer (or missing), replace with real content
        val stillPointer = !workFile.exists() || isPointerFile(workFile) || workFile.length() != pointer.size
        if (!stillPointer) return false
        workFile.parentFile?.mkdirs()
        objectFile.copyTo(workFile, overwrite = true)
        return true
    }

    fun lfsBatchUrl(remoteUrl: String): String? {
        // https://github.com/owner/repo.git  -> https://github.com/owner/repo.git/info/lfs/objects/batch
        // git@github.com:owner/repo.git -> convert to https
        val https = when {
            remoteUrl.startsWith("https://") || remoteUrl.startsWith("http://") -> remoteUrl
            remoteUrl.startsWith("git@") -> {
                // git@host:path
                val host = remoteUrl.substringAfter("git@").substringBefore(":")
                val path = remoteUrl.substringAfter(":").removeSuffix(".git")
                "https://$host/$path.git"
            }
            remoteUrl.startsWith("ssh://") -> {
                val without = remoteUrl.removePrefix("ssh://")
                val host = without.substringAfter("@").substringBefore("/")
                val path = without.substringAfter("/").removeSuffix(".git")
                "https://$host/$path.git"
            }
            else -> return null
        }
        val base = https.removeSuffix("/").let {
            if (it.endsWith(".git")) it else "$it.git"
        }
        return "$base/info/lfs/objects/batch"
    }

    private fun batchRequest(
        batchUrl: String,
        operation: String,
        oids: List<Pair<String, Long>>,
        username: String?,
        token: String?
    ): List<JSONObject> {
        val payload = JSONObject()
            .put("operation", operation)
            .put("transfers", JSONArray().put("basic"))
            .put("objects", JSONArray().apply {
                oids.forEach { (oid, size) ->
                    put(JSONObject().put("oid", oid).put("size", size))
                }
            })

        val conn = (URL(batchUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.git-lfs+json")
            setRequestProperty("Content-Type", "application/vnd.git-lfs+json")
            applyAuth(this, username, token)
        }
        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            throw IllegalStateException("LFS batch HTTP $code: ${body.take(200)}")
        }
        val arr = JSONObject(body).optJSONArray("objects") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun downloadToFile(href: String, headers: JSONObject?, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val conn = (URL(href).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 60_000
            // Large firmware/blob dumps can take many minutes on mobile networks.
            readTimeout = 30 * 60_000
            instanceFollowRedirects = true
            headers?.keys()?.forEach { key ->
                val k = key as String
                setRequestProperty(k, headers.getString(k))
            }
        }
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("LFS download HTTP ${conn.responseCode}")
        }
        BufferedInputStream(conn.inputStream).use { input ->
            BufferedOutputStream(FileOutputStream(tmp)).use { output ->
                input.copyTo(output, 64 * 1024)
            }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun uploadFile(href: String, headers: JSONObject?, src: File) {
        val conn = (URL(href).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 30_000
            readTimeout = 300_000
            doOutput = true
            setRequestProperty("Content-Length", src.length().toString())
            headers?.keys()?.forEach { key ->
                val k = key as String
                setRequestProperty(k, headers.getString(k))
            }
            // Some hosts want Content-Type application/octet-stream
            if (headers?.has("Content-Type") != true) {
                setRequestProperty("Content-Type", "application/octet-stream")
            }
        }
        BufferedInputStream(src.inputStream()).use { input ->
            BufferedOutputStream(conn.outputStream).use { output ->
                input.copyTo(output, 64 * 1024)
            }
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
            throw IllegalStateException("LFS upload HTTP $code: ${err.take(200)}")
        }
    }

    private fun verifyObject(
        verify: JSONObject,
        oid: String,
        size: Long,
        username: String?,
        token: String?
    ) {
        val href = verify.optString("href")
        if (href.isBlank()) return
        val headers = verify.optJSONObject("header")
        val payload = JSONObject().put("oid", oid).put("size", size)
        val conn = (URL(href).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.git-lfs+json")
            setRequestProperty("Content-Type", "application/vnd.git-lfs+json")
            headers?.keys()?.forEach { key ->
                val k = key as String
                setRequestProperty(k, headers.getString(k))
            }
            if (headers == null || !headers.has("Authorization")) {
                applyAuth(this, username, token)
            }
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
        // verify failures are non-fatal for many hosts
        if (conn.responseCode !in 200..299) {
            AppLog.w(TAG, "LFS verify HTTP ${conn.responseCode} for $oid")
        }
    }

    private fun applyAuth(conn: HttpURLConnection, username: String?, token: String?) {
        if (token.isNullOrBlank()) return
        val user = username?.ifBlank { null } ?: "git"
        val basic = android.util.Base64.encodeToString(
            "$user:$token".toByteArray(StandardCharsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        conn.setRequestProperty("Authorization", "Basic $basic")
    }

    /** Hash a file as sha256 for turning content into an LFS object (optional helper). */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }


    data class LfsStatus(
        val installed: Boolean,
        val trackedPatterns: List<String>,
        val pointerCount: Int,
        val localObjectCount: Int,
        val missingObjectCount: Int,
        val smudgedEstimate: Int,
        val message: String
    )

    /** True when remote is GitHub or GitLab (including self-hosted gitlab.* hosts). */
    fun isSupportedRemote(remoteUrl: String): Boolean {
        val u = remoteUrl.lowercase()
        if ("github.com" in u) return true
        if ("gitlab.com" in u) return true
        if ("gitlab." in u) return true // self-hosted e.g. gitlab.example.com
        // ssh form git@gitlab...
        if (u.startsWith("git@") && "gitlab" in u.substringBefore(":")) return true
        if (u.startsWith("git@") && "github.com" in u) return true
        return false
    }

    /**
     * Equivalent of `git lfs install` for this repo (local only — no global hooks binary).
     * Creates `.git/lfs`, records local config flags used by QuickGit.
     */
    fun install(repoRoot: File): String {
        val lfsDir = File(repoRoot, ".git/lfs")
        File(lfsDir, "objects").mkdirs()
        File(lfsDir, "tmp").mkdirs()
        // Local config markers (JGit-readable)
        val cfgFile = File(repoRoot, ".git/config")
        val text = if (cfgFile.isFile) cfgFile.readText() else ""
        if ("[lfs]" !in text) {
            cfgFile.appendText("\n[lfs]\n\trepositoryformatversion = 0\n")
        }
        // Ensure a .gitattributes exists so track can append
        val ga = File(repoRoot, ".gitattributes")
        if (!ga.exists()) ga.writeText("")
        AppLog.i(TAG, "lfs install: ${repoRoot.absolutePath}")
        return "Git LFS enabled for this repository"
    }

    fun isInstalled(repoRoot: File): Boolean =
        File(repoRoot, ".git/lfs").isDirectory

    /** Patterns from .gitattributes that use filter=lfs (like `git lfs track` list). */
    fun trackedPatterns(repoRoot: File): List<String> {
        val ga = File(repoRoot, ".gitattributes")
        if (!ga.isFile) return emptyList()
        return ga.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { "filter=lfs" in it }
            .map { it.split(Regex("\\s+")).first() }
            .distinct()
    }

    /**
     * `git lfs track <pattern>` — appends to .gitattributes.
     * Example pattern: `*.psd` or `path/to/large.bin`
     */
    fun track(repoRoot: File, pattern: String): String {
        val p = pattern.trim()
        if (p.isEmpty()) throw IllegalArgumentException("Pattern is empty")
        install(repoRoot)
        val existing = trackedPatterns(repoRoot)
        if (p in existing) return "Already tracking $p"
        val line = "$p filter=lfs diff=lfs merge=lfs -text\n"
        File(repoRoot, ".gitattributes").appendText(line)
        AppLog.i(TAG, "lfs track: $p")
        return "Tracking $p"
    }

    /** `git lfs untrack <pattern>` — removes matching filter=lfs lines from .gitattributes. */
    fun untrack(repoRoot: File, pattern: String): String {
        val p = pattern.trim()
        val ga = File(repoRoot, ".gitattributes")
        if (!ga.isFile) return "No .gitattributes"
        val lines = ga.readLines()
        val kept = lines.filterNot { line ->
            val t = line.trim()
            t.isNotEmpty() && !t.startsWith("#") && "filter=lfs" in t &&
                t.split(Regex("\\s+")).first() == p
        }
        if (kept.size == lines.size) return "Pattern not tracked: $p"
        ga.writeText(kept.joinToString("\n").let { if (it.endsWith("\n") || it.isEmpty()) it else "$it\n" })
        AppLog.i(TAG, "lfs untrack: $p")
        return "Untracked $p"
    }

    /** `git lfs status` / `git lfs ls-files` summary. */
    fun status(repoRoot: File): LfsStatus {
        val installed = isInstalled(repoRoot)
        val patterns = trackedPatterns(repoRoot)
        val pointers = findPointerFiles(repoRoot)
        var local = 0
        var missing = 0
        var smudged = 0
        for (p in pointers) {
            val obj = lfsObjectFile(repoRoot, p.oid)
            if (obj.isFile) local++ else missing++
            val work = File(repoRoot, p.relativePath)
            if (work.isFile && !isPointerFile(work) && work.length() == p.size) smudged++
        }
        // Also count objects on disk not referenced by current pointers
        val objRoot = File(repoRoot, ".git/lfs/objects")
        val diskObjects = if (objRoot.isDirectory) {
            objRoot.walkTopDown().filter { it.isFile && it.name.length == 64 }.count()
        } else 0
        val msg = buildString {
            append(if (installed) "LFS installed" else "LFS not installed")
            append(" · tracked ${patterns.size} pattern(s)")
            append(" · ${pointers.size} pointer(s)")
            append(" · $local object(s) present")
            if (missing > 0) append(" · $missing missing")
            if (diskObjects > local) append(" · $diskObjects object file(s) on disk")
        }
        return LfsStatus(installed, patterns, pointers.size, local, missing, smudged, msg)
    }

    /**
     * Clean filter: if [relativePath] matches a tracked LFS pattern and the working file
     * is not already a pointer, store the blob under `.git/lfs/objects` and rewrite the
     * working file as an LFS pointer (so `git add` stores the pointer in the index).
     * Returns true if the file was converted.
     */
    fun cleanIfNeeded(repoRoot: File, relativePath: String): Boolean {
        val patterns = trackedPatterns(repoRoot)
        if (patterns.isEmpty()) return false
        if (!pathMatchesAny(relativePath, patterns)) return false
        val work = File(repoRoot, relativePath)
        if (!work.isFile) return false
        if (isPointerFile(work)) return false
        install(repoRoot)
        val oid = sha256(work)
        val size = work.length()
        val dest = lfsObjectFile(repoRoot, oid)
        dest.parentFile?.mkdirs()
        if (!dest.exists()) {
            work.copyTo(dest, overwrite = false)
        }
        work.writeText(pointerFileContent(oid, size))
        AppLog.i(TAG, "lfs clean: $relativePath -> $oid ($size bytes)")
        return true
    }

    /** Simple gitignore-style match for LFS track patterns (e.g. *.psd, path globs, exact paths). */
    fun pathMatchesAny(relativePath: String, patterns: List<String>): Boolean {
        val path = relativePath.replace('\\', '/').removePrefix("./")
        return patterns.any { patternMatches(path, it) }
    }

    private fun patternMatches(path: String, pattern: String): Boolean {
        val p = pattern.replace('\\', '/').removePrefix("./")
        if (p == path) return true
        // *.ext
        if (p.startsWith("*.") && !p.contains('/')) {
            return path.substringAfterLast('/').endsWith(p.removePrefix("*"))
        }
        // ** / or simple contains glob — convert * to regex
        val regex = buildString {
            append('^')
            var i = 0
            while (i < p.length) {
                when (val c = p[i]) {
                    '*' -> {
                        if (i + 1 < p.length && p[i + 1] == '*') {
                            append(".*")
                            i += 2
                            if (i < p.length && p[i] == '/') i++
                        } else {
                            append("[^/]*")
                            i++
                        }
                    }
                    '?' -> { append("[^/]"); i++ }
                    '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']' -> {
                        append('\\'); append(c); i++
                    }
                    else -> { append(c); i++ }
                }
            }
            append('$')
        }
        return try {
            path.matches(Regex(regex))
        } catch (_: Exception) {
            false
        }
    }

}
