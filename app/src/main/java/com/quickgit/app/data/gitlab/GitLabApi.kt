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

    fun searchProjects(query: String, perPage: Int = 30, page: Int = 1): Result<List<GitLabProject>> = runCatching {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val arr = request("GET", "/projects?search=$q&order_by=updated_at&sort=desc&per_page=$perPage&page=$page&membership=true") as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toProject() }
    }

    data class GitLabUserSummary(
        val id: Long,
        val username: String,
        val name: String?,
        val avatarUrl: String?,
        val webUrl: String
    )

    fun searchUsers(query: String, perPage: Int = 30): Result<List<GitLabUserSummary>> = runCatching {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val arr = request("GET", "/users?search=$q&per_page=$perPage") as JSONArray
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GitLabUserSummary(
                id = o.getLong("id"),
                username = o.optString("username", ""),
                name = o.optString("name").takeIf { it.isNotBlank() },
                avatarUrl = o.optString("avatar_url").takeIf { it.isNotBlank() },
                webUrl = o.optString("web_url", "")
            )
        }
    }

    fun getUser(username: String): Result<GitLabUser> = runCatching {
        val encoded = java.net.URLEncoder.encode(username, "UTF-8")
        // GitLab: /users?username= exact match returns array
        val arr = request("GET", "/users?username=$encoded") as JSONArray
        if (arr.length() == 0) error("User not found: $username")
        val o = arr.getJSONObject(0)
        // Re-fetch full profile if needed; array items are partial
        val id = o.getLong("id")
        val full = request("GET", "/users/$id") as JSONObject
        GitLabUser(
            id = id,
            username = full.optString("username", o.optString("username", "")),
            name = full.optString("name").takeIf { it.isNotBlank() },
            email = full.optString("public_email").takeIf { it.isNotBlank() }
                ?: full.optString("email").takeIf { it.isNotBlank() },
            avatarUrl = full.optString("avatar_url").takeIf { it.isNotBlank() },
            webUrl = full.optString("web_url", "")
        )
    }

    fun getProject(idOrPath: String): Result<GitLabProject> = runCatching {
        val encoded = java.net.URLEncoder.encode(idOrPath, "UTF-8")
        (request("GET", "/projects/$encoded") as JSONObject).toProject()
    }

    /** Creates a new project owned by the authenticated user. */
    fun createProject(
        name: String,
        description: String?,
        isPrivate: Boolean
    ): Result<GitLabProject> = runCatching {
        val payload = JSONObject()
            .put("name", name.trim())
            .put("description", description ?: "")
            .put("visibility", if (isPrivate) "private" else "public")
            .put("initialize_with_readme", false)
        (request("POST", "/projects", payload) as JSONObject).toProject()
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

    fun createIssue(projectId: String, title: String, description: String): Result<GitLabIssue> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val payload = JSONObject().put("title", title).put("description", description)
        (request("POST", "/projects/$encoded/issues", payload) as JSONObject).toIssue()
    }

    fun setIssueState(projectId: String, iid: Int, open: Boolean): Result<GitLabIssue> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val payload = JSONObject().put("state_event", if (open) "reopen" else "close")
        (request("PUT", "/projects/$encoded/issues/$iid", payload) as JSONObject).toIssue()
    }

    fun createMergeRequest(
        projectId: String,
        title: String,
        description: String,
        sourceBranch: String,
        targetBranch: String,
        draft: Boolean
    ): Result<MergeRequest> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val payload = JSONObject()
            .put("title", if (draft && !title.startsWith("Draft:")) "Draft: $title" else title)
            .put("description", description)
            .put("source_branch", sourceBranch)
            .put("target_branch", targetBranch)
        (request("POST", "/projects/$encoded/merge_requests", payload) as JSONObject).toMergeRequest()
    }

    fun mergeMergeRequest(projectId: String, iid: Int, squash: Boolean = false): Result<MergeRequest> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val payload = JSONObject().put("squash", squash)
        (request("PUT", "/projects/$encoded/merge_requests/$iid/merge", payload) as JSONObject).toMergeRequest()
    }

    fun setMergeRequestState(projectId: String, iid: Int, open: Boolean): Result<MergeRequest> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val payload = JSONObject().put("state_event", if (open) "reopen" else "close")
        (request("PUT", "/projects/$encoded/merge_requests/$iid", payload) as JSONObject).toMergeRequest()
    }

    // ---- CI Pipelines (GitLab "Build") ----

    data class Pipeline(
        val id: Long,
        val iid: Int,
        val status: String,
        val ref: String?,
        val sha: String?,
        val webUrl: String,
        val createdAt: String,
        val updatedAt: String,
        val source: String?
    )

    data class PipelineJob(
        val id: Long,
        val name: String,
        val status: String,
        val stage: String?,
        val webUrl: String,
        val startedAt: String?,
        val finishedAt: String?
    )

    fun listPipelines(projectId: String, status: String? = null, perPage: Int = 30): Result<List<Pipeline>> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val statusQ = status?.takeIf { it.isNotBlank() }?.let { "&status=$it" } ?: ""
        val arr = request("GET", "/projects/$encoded/pipelines?per_page=$perPage&order_by=id&sort=desc$statusQ") as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toPipeline() }
    }

    fun getPipeline(projectId: String, pipelineId: Long): Result<Pipeline> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        (request("GET", "/projects/$encoded/pipelines/$pipelineId") as JSONObject).toPipeline()
    }

    fun listPipelineJobs(projectId: String, pipelineId: Long): Result<List<PipelineJob>> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        val arr = request("GET", "/projects/$encoded/pipelines/$pipelineId/jobs?per_page=100") as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toPipelineJob() }
    }

    fun cancelPipeline(projectId: String, pipelineId: Long): Result<Pipeline> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        (request("POST", "/projects/$encoded/pipelines/$pipelineId/cancel", JSONObject()) as JSONObject).toPipeline()
    }

    fun retryPipeline(projectId: String, pipelineId: Long): Result<Pipeline> = runCatching {
        val encoded = java.net.URLEncoder.encode(projectId, "UTF-8")
        (request("POST", "/projects/$encoded/pipelines/$pipelineId/retry", JSONObject()) as JSONObject).toPipeline()
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

    private fun JSONObject.toPipeline() = Pipeline(
        id = getLong("id"),
        iid = optInt("iid", 0),
        status = optString("status", ""),
        ref = optString("ref").takeIf { it.isNotBlank() },
        sha = optString("sha").takeIf { it.isNotBlank() },
        webUrl = optString("web_url", ""),
        createdAt = optString("created_at", ""),
        updatedAt = optString("updated_at", ""),
        source = optString("source").takeIf { it.isNotBlank() }
    )

    private fun JSONObject.toPipelineJob() = PipelineJob(
        id = getLong("id"),
        name = optString("name", ""),
        status = optString("status", ""),
        stage = optString("stage").takeIf { it.isNotBlank() },
        webUrl = optString("web_url", ""),
        startedAt = optString("started_at").takeIf { it.isNotBlank() },
        finishedAt = optString("finished_at").takeIf { it.isNotBlank() }
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
