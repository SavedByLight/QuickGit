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
        val message: String
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

        val missing = mutableListOf<Pointer>()
        var already = 0
        for (p in pointers) {
            val obj = lfsObjectFile(repoRoot, p.oid)
            if (obj.isFile && obj.length() == p.size) {
                already++
                // Ensure working tree has real content
                smudgeIfNeeded(repoRoot, p, obj)
            } else {
                missing += p
            }
        }

        if (missing.isEmpty()) {
            onProgress("All ${pointers.size} LFS object(s) already present")
            return FetchResult(0, already, 0, "All ${pointers.size} LFS objects already present")
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
                    smudgeIfNeeded(repoRoot, pointer, dest)
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
        return FetchResult(downloaded, already, failed, msg)
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

    private fun smudgeIfNeeded(repoRoot: File, pointer: Pointer, objectFile: File) {
        val workFile = File(repoRoot, pointer.relativePath)
        // If work file is still a pointer (or missing), replace with real content
        val stillPointer = !workFile.exists() || isPointerFile(workFile) || workFile.length() != pointer.size
        if (!stillPointer) return
        workFile.parentFile?.mkdirs()
        objectFile.copyTo(workFile, overwrite = true)
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
}
