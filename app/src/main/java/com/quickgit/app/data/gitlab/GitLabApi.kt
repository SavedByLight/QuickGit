package com.quickgit.app.data.gitlab

import com.quickgit.app.data.AppLog
import com.quickgit.app.data.models.GitLabIssue
import com.quickgit.app.data.models.GitLabNote
import com.quickgit.app.data.models.GitLabProject
import com.quickgit.app.data.models.MergeRequest
import com.quickgit.app.data.models.PrOpResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Minimal GitLab REST API v4 client. Host-agnostic (gitlab.com or self-hosted).
 * Uses PRIVATE-TOKEN header. Dependency-free (HttpURLConnection + org.json).
 */
class GitLabApi(
    private val host: String,
    private val token: String?,
    private val useHttps: Boolean = true
) {
    private val TAG = "GitLabApi"
    private val baseUrl: String
        get() {
            val scheme = if (useHttps) "https" else "http"
            val h = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            return "$scheme://$h/api/v4"
        }

    data class OwnerProject(val owner: String, val project: String)

    /** Parse owner/project from a GitLab remote URL (any host). */
    fun parseOwnerProject(remoteUrl: String?): OwnerProject? {
        if (remoteUrl == null) return null
        val cleaned = remoteUrl.trim().removeSuffix(".git").removeSuffix("/")
        // https://gitlab.com/group/subgroup/project or git@host:group/project
        val https = Regex("^https?://[^/]+/(.+)$")
        val ssh = Regex("^git@[^:]+:(.+)$")
        val ssh2 = Regex("^ssh://git@[^/]+/(.+)$")
        val path = when {
            https.find(cleaned) != null -> https.find(cleaned)!!.groupValues[1]
            ssh.find(cleaned) != null -> ssh.find(cleaned)!!.groupValues[1]
            ssh2.find(cleaned) != null -> ssh2.find(cleaned)!!.groupValues[1]
            else -> return null
        }
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        return OwnerProject(parts.dropLast(1).joinToString("/"), parts.last())
    }

    data class GitLabUser(
        val id: Long,
        val username: String,
        val name: String?,
        val email: String?,
        val avatarUrl: String?,
        val webUrl: String
    )

    fun getAuthenticatedUser(): Result<GitLabUser> = runCatching {
        (request("GET", "/user") as JSONObject).toUser()
    }

    fun listProjects(
        membership: Boolean = true,
        perPage: Int = 50,
        page: Int = 1
    ): Result<List<GitLabProject>> = runCatching {
        val path = "/projects?membership=$membership&order_by=updated_at&sort=desc&per_page=$perPage&page=$page&simple=true"
        val arr = request("GET", path) as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toProject() }
    }

    fun searchProjects(query: String, perPage: Int = 30): Result<List<GitLabProject>> = runCatching {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val arr = request("GET", "/projects?search=$q&order_by=updated_at&sort=desc&per_page=$perPage&membership=true") as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toProject() }
    }

    fun getProject(idOrPath: String): Result<GitLabProject> = runCatching {
        val encoded = java.net.URLEncoder.encode(idOrPath, "UTF-8")
        (request("GET", "/projects/$encoded") as JSONObject).toProject()
    }

    // ---- Merge Requests ----

    fun listMergeRequests(
        projectId: String,
        state: String = "opened",
        perPage: Int = 50
    ): Result<List<MergeRequest>> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val arr = request("GET", "/projects/$encoded/merge_requests?state=$state&per_page=$perPage&order_by=updated_at") as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toMergeRequest() }
    }

    fun getMergeRequest(projectId: String, iid: Int): Result<MergeRequest> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        (request("GET", "/projects/$encoded/merge_requests/$iid") as JSONObject).toMergeRequest()
    }

    fun listMrNotes(projectId: String, iid: Int): Result<List<GitLabNote>> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val arr = request("GET", "/projects/$encoded/merge_requests/$iid/notes?per_page=100&sort=asc") as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toNote() }
    }

    fun addMrNote(projectId: String, iid: Int, body: String): Result<GitLabNote> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val payload = JSONObject().put("body", body)
        (request("POST", "/projects/$encoded/merge_requests/$iid/notes", payload) as JSONObject).toNote()
    }

    // ---- Issues ----

    fun listIssues(
        projectId: String,
        state: String = "opened",
        perPage: Int = 50
    ): Result<List<GitLabIssue>> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val arr = request("GET", "/projects/$encoded/issues?state=$state&per_page=$perPage&order_by=updated_at") as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toIssue() }
    }

    fun getIssue(projectId: String, iid: Int): Result<GitLabIssue> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        (request("GET", "/projects/$encoded/issues/$iid") as JSONObject).toIssue()
    }

    fun listIssueNotes(projectId: String, iid: Int): Result<List<GitLabNote>> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val arr = request("GET", "/projects/$encoded/issues/$iid/notes?per_page=100&sort=asc") as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toNote() }
    }

    fun addIssueNote(projectId: String, iid: Int, body: String): Result<GitLabNote> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val payload = JSONObject().put("body", body)
        (request("POST", "/projects/$encoded/issues/$iid/notes", payload) as JSONObject).toNote()
    }

    // ---- Remote file browsing (Repository Tree + Files API) ----

    data class RemoteEntry(
        val name: String,
        val path: String,
        val type: String, // "tree" | "blob"
        val sizeBytes: Long = 0L
    )

    fun getRepoTree(
        projectId: String,
        path: String = "",
        ref: String = "HEAD",
        recursive: Boolean = false
    ): Result<List<RemoteEntry>> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val encodedPath = if (path.isBlank()) "" else "&path=${java.net.URLEncoder.encode(path.trim('/'), "UTF-8")}"
        val encodedRef = java.net.URLEncoder.encode(ref, "UTF-8")
        val rec = if (recursive) "&recursive=true" else ""
        val arr = request("GET", "/projects/$encoded/repository/tree?ref=$encodedRef$encodedPath$rec&per_page=100") as JSONArray
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteEntry(
                name = o.getString("name"),
                path = o.getString("path"),
                type = o.optString("type", "blob"),
                sizeBytes = 0L
            )
        }.sortedWith(compareBy({ it.type != "tree" }, { it.name.lowercase() }))
    }

    fun getFileContent(projectId: String, path: String, ref: String = "HEAD"): Result<String> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val encodedPath = java.net.URLEncoder.encode(path.trim('/'), "UTF-8")
        val encodedRef = java.net.URLEncoder.encode(ref, "UTF-8")
        val obj = request("GET", "/projects/$encoded/repository/files/$encodedPath?ref=$encodedRef") as JSONObject
        val encoding = obj.optString("encoding", "base64")
        val content = obj.optString("content", "")
        if (content.isBlank()) return@runCatching ""
        if (encoding != "base64") throw IOException("Unsupported encoding: $encoding")
        val bytes = android.util.Base64.decode(content, android.util.Base64.DEFAULT)
        String(bytes, StandardCharsets.UTF_8)
    }

    // ---- Helpers ----

    private fun JSONObject.toUser() = GitLabUser(
        id = getLong("id"),
        username = getString("username"),
        name = optString("name").takeIf { it.isNotBlank() },
        email = optString("email").takeIf { it.isNotBlank() },
        avatarUrl = optString("avatar_url").takeIf { it.isNotBlank() },
        webUrl = optString("web_url", "")
    )

    private fun JSONObject.toProject(): GitLabProject {
        val visibility = optString("visibility", "private")
        return GitLabProject(
            id = getLong("id"),
            name = getString("name"),
            pathWithNamespace = getString("path_with_namespace"),
            description = optString("description").takeIf { it.isNotBlank() },
            webUrl = optString("web_url", ""),
            httpUrlToRepo = optString("http_url_to_repo", ""),
            sshUrlToRepo = optString("ssh_url_to_repo", ""),
            isPrivate = visibility != "public",
            isFork = optJSONObject("forked_from_project") != null,
            defaultBranch = optString("default_branch", "main"),
            updatedAt = optString("last_activity_at", optString("updated_at", "")),
            starCount = optInt("star_count", 0)
        )
    }

    private fun JSONObject.toMergeRequest() = MergeRequest(
        iid = getInt("iid"),
        id = getLong("id"),
        title = getString("title"),
        description = optString("description").takeIf { it.isNotBlank() },
        state = optString("state", "opened"),
        draft = optBoolean("draft", optBoolean("work_in_progress", false)),
        merged = optString("state") == "merged",
        mergeStatus = optString("merge_status").takeIf { it.isNotBlank() },
        authorUsername = optJSONObject("author")?.optString("username") ?: "",
        sourceBranch = optString("source_branch", ""),
        targetBranch = optString("target_branch", ""),
        webUrl = optString("web_url", ""),
        createdAt = optString("created_at", ""),
        updatedAt = optString("updated_at", ""),
        userNotesCount = optInt("user_notes_count", 0)
    )

    private fun JSONObject.toIssue(): GitLabIssue {
        val labelsArr = optJSONArray("labels")
        val labels = if (labelsArr != null) {
            (0 until labelsArr.length()).map { labelsArr.getString(it) }
        } else emptyList()
        return GitLabIssue(
            iid = getInt("iid"),
            id = getLong("id"),
            title = getString("title"),
            description = optString("description").takeIf { it.isNotBlank() },
            state = optString("state", "opened"),
            authorUsername = optJSONObject("author")?.optString("username") ?: "",
            webUrl = optString("web_url", ""),
            createdAt = optString("created_at", ""),
            updatedAt = optString("updated_at", ""),
            labels = labels
        )
    }

    private fun JSONObject.toNote() = GitLabNote(
        id = getLong("id"),
        body = optString("body", ""),
        authorUsername = optJSONObject("author")?.optString("username") ?: "",
        createdAt = optString("created_at", ""),
        system = optBoolean("system", false)
    )

    private fun request(method: String, path: String, body: JSONObject? = null): Any {
        val url = URL(baseUrl + path)
        AppLog.d(TAG, "$method $url")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "QuickGit-Android")
            if (!token.isNullOrBlank()) {
                setRequestProperty("PRIVATE-TOKEN", token)
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { it.write(body.toString()) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() } ?: ""
        if (code !in 200..299) {
            val msg = try {
                JSONObject(text).optString("message", text.take(200))
            } catch (_: Exception) {
                text.take(200)
            }
            throw IOException("HTTP $code: $msg")
        }
        if (text.isBlank()) return JSONObject()
        return when {
            text.trimStart().startsWith("[") -> JSONArray(text)
            else -> JSONObject(text)
        }
    }
}

fun Result<*>.toPrOpResult(host: String): PrOpResult {
    val ex = exceptionOrNull() ?: return PrOpResult.Success
    val msg = ex.message ?: "Unknown error"
    return when {
        msg.contains("401") || msg.contains("Unauthorized", ignoreCase = true) ->
            PrOpResult.AuthRequired(host)
        else -> PrOpResult.Error(msg, ex)
    }
}
