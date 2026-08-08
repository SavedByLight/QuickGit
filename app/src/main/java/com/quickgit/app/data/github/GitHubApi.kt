package com.quickgit.app.data.github

import com.quickgit.app.data.AppLog
import com.quickgit.app.data.models.GitHubRemoteRepo
import com.quickgit.app.data.models.Issue
import com.quickgit.app.data.models.MergeMethod
import com.quickgit.app.data.models.PrComment
import com.quickgit.app.data.models.PrOpResult
import com.quickgit.app.data.models.PullRequest
import com.quickgit.app.data.models.Release
import com.quickgit.app.data.models.ReleaseAsset
import com.quickgit.app.data.models.Workflow
import com.quickgit.app.data.models.WorkflowAnnotation
import com.quickgit.app.data.models.WorkflowJob
import com.quickgit.app.data.models.WorkflowRun
import com.quickgit.app.data.models.WorkflowStep
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
 * Minimal GitHub REST v3 client for account identity, repository listing, and pull-request management. Deliberately dependency-free
 * (HttpURLConnection + org.json, both already on the platform) rather than pulling in
 * Retrofit/OkHttp for a handful of endpoints.
 *
 * Security notes:
 * - Token is only ever sent as Authorization: Bearer over HTTPS.
 * - Token value is never logged.
 * - Prefer short-lived / fine-grained tokens from the caller (CredentialStore).
 */
class GitHubApi(private val token: String?) {

    private val TAG = "GitHubApi"

    /** True when a non-blank token was supplied. Useful for UI to know auth is present. */
    val isAuthenticated: Boolean get() = !token.isNullOrBlank()

    /** owner/repo parsed out of an https/ssh/git remote URL, or null if it isn't a github.com remote. */
    data class OwnerRepo(val owner: String, val repo: String)

    fun parseOwnerRepo(remoteUrl: String?): OwnerRepo? {
        if (remoteUrl == null) return null
        val cleaned = remoteUrl.trim().removeSuffix(".git").removeSuffix("/")
        val patterns = listOf(
            Regex("^https?://github\\.com/([^/]+)/([^/]+)$"),
            Regex("^git@github\\.com:([^/]+)/([^/]+)$"),
            Regex("^ssh://git@github\\.com/([^/]+)/([^/]+)$")
        )
        for (p in patterns) {
            val m = p.find(cleaned) ?: continue
            return OwnerRepo(m.groupValues[1], m.groupValues[2])
        }
        return null
    }

    data class GitHubUser(
        val login: String,
        val name: String?,
        val email: String?,
        val avatarUrl: String?,
        val htmlUrl: String,
        val bio: String? = null,
        val company: String? = null,
        val location: String? = null,
        val blog: String? = null,
        val publicRepos: Int = 0,
        val followers: Int = 0,
        val following: Int = 0,
        val createdAt: String = ""
    )

    data class GitHubUserSummary(
        val login: String,
        val avatarUrl: String?,
        val htmlUrl: String,
        val type: String = "User"
    )

    /** Verifies the token and returns the authenticated user. */
    fun getAuthenticatedUser(): Result<GitHubUser> = runCatching {
        (request("GET", "/user") as JSONObject).toGitHubUser()
    }

    /** Public profile for any user. */
    fun getUser(login: String): Result<GitHubUser> = runCatching {
        (request("GET", "/users/${login.trim()}") as JSONObject).toGitHubUser()
    }

    /** Search users by login/name (GitHub Search API). */
    fun searchUsers(query: String, perPage: Int = 30): Result<List<GitHubUserSummary>> = runCatching {
        val q = query.trim()
        if (q.isEmpty()) return@runCatching emptyList()
        val encoded = java.net.URLEncoder.encode(q, "UTF-8")
        val arr = (request("GET", "/search/users?q=$encoded&per_page=$perPage") as JSONObject)
            .getJSONArray("items")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GitHubUserSummary(
                login = o.getString("login"),
                avatarUrl = o.optString("avatar_url").takeIf { it.isNotBlank() },
                htmlUrl = o.optString("html_url", ""),
                type = o.optString("type", "User")
            )
        }
    }

    /** Public repos for a user (or the authenticated user when login matches). */
    fun listPublicRepos(login: String, perPage: Int = 30, page: Int = 1): Result<List<GitHubRemoteRepo>> = runCatching {
        val path = "/users/${login.trim()}/repos?sort=updated&direction=desc&per_page=$perPage&page=$page&type=owner"
        val arr = request("GET", path) as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toRemoteRepo() }
    }

    private fun JSONObject.toGitHubUser(): GitHubUser = GitHubUser(
        login = getString("login"),
        name = if (isNull("name")) null else optString("name").takeIf { it.isNotBlank() },
        email = if (isNull("email")) null else optString("email").takeIf { it.isNotBlank() },
        avatarUrl = optString("avatar_url").takeIf { it.isNotBlank() },
        htmlUrl = optString("html_url", ""),
        bio = if (isNull("bio")) null else optString("bio").takeIf { it.isNotBlank() },
        company = if (isNull("company")) null else optString("company").takeIf { it.isNotBlank() },
        location = if (isNull("location")) null else optString("location").takeIf { it.isNotBlank() },
        blog = if (isNull("blog")) null else optString("blog").takeIf { it.isNotBlank() },
        publicRepos = optInt("public_repos", 0),
        followers = optInt("followers", 0),
        following = optInt("following", 0),
        createdAt = optString("created_at", "")
    )

    /**
     * Lists repositories the authenticated user can access.
     * GitHub forbids combining `type` with `affiliation`/`visibility` (HTTP 422), so we only
     * use affiliation here.
     * @param affiliation comma-separated: owner, collaborator, organization_member (default all three)
     * @param sort created|updated|pushed|full_name
     */
    fun listUserRepos(
        affiliation: String = "owner,collaborator,organization_member",
        sort: String = "updated",
        perPage: Int = 50,
        page: Int = 1
    ): Result<List<GitHubRemoteRepo>> = runCatching {
        val path = "/user/repos?affiliation=$affiliation&sort=$sort&direction=desc&per_page=$perPage&page=$page"
        val arr = request("GET", path) as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toRemoteRepo() }
    }

    /** Search the authenticated user's accessible repos by name (GitHub search API, limited to user:login). */
    fun searchUserRepos(login: String, query: String, perPage: Int = 30): Result<List<GitHubRemoteRepo>> = runCatching {
        val q = if (query.isBlank()) "user:$login" else "${query.trim()} user:$login"
        val encoded = java.net.URLEncoder.encode(q, "UTF-8")
        val arr = (request("GET", "/search/repositories?q=$encoded&per_page=$perPage&sort=updated") as JSONObject)
            .getJSONArray("items")
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toRemoteRepo() }
    }

    /**
     * Lists the contents of a directory (or the repo root) at a given ref, without cloning.
     * GitHub's Contents API returns a JSON array for directories and a single object for files;
     * calling this on a file path throws, so callers should route files to [getFileContent].
     */
    fun getRepoContents(owner: String, repo: String, path: String, ref: String): Result<List<RemoteEntry>> = runCatching {
        val cleanPath = path.trim('/')
        val encodedPath = cleanPath.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }
        val encodedRef = java.net.URLEncoder.encode(ref, "UTF-8")
        val result = request("GET", "/repos/$owner/$repo/contents/$encodedPath?ref=$encodedRef")
        val arr = result as? JSONArray
            ?: throw IOException("Path is a file, not a directory")
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toRemoteEntry() }
            .sortedWith(compareBy({ it.type != "dir" }, { it.name.lowercase() }))
    }

    /** Fetches and decodes the text content of a single file at a given ref, without cloning. */
    fun getFileContent(owner: String, repo: String, path: String, ref: String): Result<String> = runCatching {
        val cleanPath = path.trim('/')
        val encodedPath = cleanPath.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }
        val encodedRef = java.net.URLEncoder.encode(ref, "UTF-8")
        val result = request("GET", "/repos/$owner/$repo/contents/$encodedPath?ref=$encodedRef")
        val obj = result as? JSONObject
            ?: throw IOException("Path is a directory, not a file")
        val encoding = obj.optString("encoding", "base64")
        val rawContent = obj.optString("content", "")
        if (rawContent.isBlank()) return@runCatching ""
        if (encoding != "base64") throw IOException("Unsupported content encoding: $encoding")
        val bytes = android.util.Base64.decode(rawContent, android.util.Base64.DEFAULT)
        String(bytes, StandardCharsets.UTF_8)
    }

    /** A file or directory entry from the Contents API. */
    data class RemoteEntry(
        val name: String,
        val path: String,
        val type: String, // "file" | "dir" | "symlink" | "submodule"
        val sizeBytes: Long
    )

    private fun JSONObject.toRemoteEntry() = RemoteEntry(
        name = getString("name"),
        path = getString("path"),
        type = optString("type", "file"),
        sizeBytes = optLong("size", 0L)
    )

    /** Forks a repo into the authenticated user's own account. */
    fun forkRepo(owner: String, repo: String): Result<GitHubRemoteRepo> = runCatching {
        (request("POST", "/repos/$owner/$repo/forks", JSONObject()) as JSONObject).toRemoteRepo()
    }

    /** Creates a new repo owned by the authenticated user. */
    fun createRepo(name: String, description: String?, isPrivate: Boolean): Result<GitHubRemoteRepo> = runCatching {
        val payload = JSONObject()
            .put("name", name)
            .put("private", isPrivate)
        if (!description.isNullOrBlank()) payload.put("description", description)
        (request("POST", "/user/repos", payload) as JSONObject).toRemoteRepo()
    }

    private fun JSONObject.toRemoteRepo(): GitHubRemoteRepo {
        val owner = optJSONObject("owner")
        val fullName = getString("full_name")
        return GitHubRemoteRepo(
            id = getLong("id"),
            name = getString("name"),
            fullName = fullName,
            description = if (isNull("description")) null else optString("description").takeIf { it.isNotBlank() },
            htmlUrl = optString("html_url", ""),
            cloneUrl = optString("clone_url", ""),
            sshUrl = optString("ssh_url", ""),
            isPrivate = optBoolean("private", false),
            isFork = optBoolean("fork", false),
            ownerLogin = owner?.optString("login") ?: fullName.substringBefore('/'),
            defaultBranch = optString("default_branch", "main"),
            updatedAt = optString("updated_at", ""),
            language = if (isNull("language")) null else optString("language").takeIf { it.isNotBlank() }
        )
    }

    fun listPullRequests(owner: String, repo: String, state: String): Result<List<PullRequest>> = runCatching {
        val arr = request("GET", "/repos/$owner/$repo/pulls?state=$state&per_page=50") as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toPullRequest() }
    }

    fun getPullRequest(owner: String, repo: String, number: Int): Result<PullRequest> = runCatching {
        (request("GET", "/repos/$owner/$repo/pulls/$number") as JSONObject).toPullRequest()
    }

    fun listComments(owner: String, repo: String, number: Int): Result<List<PrComment>> = runCatching {
        val arr = request("GET", "/repos/$owner/$repo/issues/$number/comments?per_page=100") as JSONArray
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PrComment(
                id = o.getLong("id"),
                authorLogin = o.optJSONObject("user")?.optString("login") ?: "unknown",
                body = o.optString("body", ""),
                createdAt = o.optString("created_at", "")
            )
        }
    }

    fun createPullRequest(
        owner: String, repo: String, title: String, body: String, head: String, base: String, draft: Boolean
    ): Result<PullRequest> = runCatching {
        val payload = JSONObject()
            .put("title", title)
            .put("body", body)
            .put("head", head)
            .put("base", base)
            .put("draft", draft)
        (request("POST", "/repos/$owner/$repo/pulls", payload) as JSONObject).toPullRequest()
    }

    fun mergePullRequest(
        owner: String, repo: String, number: Int, method: MergeMethod, commitTitle: String?
    ): Result<Unit> = runCatching {
        val payload = JSONObject().put("merge_method", method.apiValue)
        if (!commitTitle.isNullOrBlank()) payload.put("commit_title", commitTitle)
        request("PUT", "/repos/$owner/$repo/pulls/$number/merge", payload)
        Unit
    }

    fun setPullRequestState(owner: String, repo: String, number: Int, open: Boolean): Result<Unit> = runCatching {
        request("PATCH", "/repos/$owner/$repo/pulls/$number", JSONObject().put("state", if (open) "open" else "closed"))
        Unit
    }

    fun addComment(owner: String, repo: String, number: Int, body: String): Result<Unit> = runCatching {
        request("POST", "/repos/$owner/$repo/issues/$number/comments", JSONObject().put("body", body))
        Unit
    }

    fun listLocalAndRemoteBranches(owner: String, repo: String): Result<List<String>> = runCatching {
        val arr = request("GET", "/repos/$owner/$repo/branches?per_page=100") as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).getString("name") }
    }

    fun listIssues(owner: String, repo: String, state: String): Result<List<Issue>> = runCatching {
        // GitHub's issues endpoint also returns PRs; exclude those via pull_request field.
        val arr = request("GET", "/repos/$owner/$repo/issues?state=$state&per_page=50") as JSONArray
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (o.has("pull_request")) null else o.toIssue()
        }
    }

    fun getIssue(owner: String, repo: String, number: Int): Result<Issue> = runCatching {
        (request("GET", "/repos/$owner/$repo/issues/$number") as JSONObject).toIssue()
    }

    fun createIssue(owner: String, repo: String, title: String, body: String): Result<Issue> = runCatching {
        val payload = JSONObject().put("title", title).put("body", body)
        (request("POST", "/repos/$owner/$repo/issues", payload) as JSONObject).toIssue()
    }

    fun setIssueState(owner: String, repo: String, number: Int, open: Boolean): Result<Unit> = runCatching {
        request(
            "PATCH",
            "/repos/$owner/$repo/issues/$number",
            JSONObject().put("state", if (open) "open" else "closed")
        )
        Unit
    }

    // ── GitHub Actions / Workflows ──────────────────────────────────────────

    fun listWorkflows(owner: String, repo: String): Result<List<Workflow>> = runCatching {
        val obj = request("GET", "/repos/$owner/$repo/actions/workflows?per_page=100") as JSONObject
        val arr = obj.getJSONArray("workflows")
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toWorkflow() }
    }

    fun listWorkflowRuns(
        owner: String,
        repo: String,
        workflowId: Long? = null,
        status: String? = null,
        perPage: Int = 30
    ): Result<List<WorkflowRun>> = runCatching {
        val params = mutableListOf("per_page=$perPage")
        if (!status.isNullOrBlank()) params += "status=$status"
        val path = if (workflowId != null) {
            "/repos/$owner/$repo/actions/workflows/$workflowId/runs?${params.joinToString("&")}"
        } else {
            "/repos/$owner/$repo/actions/runs?${params.joinToString("&")}"
        }
        val obj = request("GET", path) as JSONObject
        val arr = obj.getJSONArray("workflow_runs")
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toWorkflowRun() }
    }

    fun getWorkflowRun(owner: String, repo: String, runId: Long): Result<WorkflowRun> = runCatching {
        (request("GET", "/repos/$owner/$repo/actions/runs/$runId") as JSONObject).toWorkflowRun()
    }

    fun listJobsForRun(owner: String, repo: String, runId: Long): Result<List<WorkflowJob>> = runCatching {
        val obj = request("GET", "/repos/$owner/$repo/actions/runs/$runId/jobs?per_page=100") as JSONObject
        val arr = obj.getJSONArray("jobs")
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toWorkflowJob() }
    }

    /**
     * Trigger a workflow_dispatch event.
     * [ref] is the branch/tag/SHA; [inputs] are optional key/value pairs for the workflow's inputs.
     */
    fun dispatchWorkflow(
        owner: String,
        repo: String,
        workflowId: Long,
        ref: String,
        inputs: Map<String, String> = emptyMap()
    ): Result<Unit> = runCatching {
        val payload = JSONObject().put("ref", ref)
        if (inputs.isNotEmpty()) {
            val inputsObj = JSONObject()
            inputs.forEach { (k, v) -> inputsObj.put(k, v) }
            payload.put("inputs", inputsObj)
        }
        request("POST", "/repos/$owner/$repo/actions/workflows/$workflowId/dispatches", payload)
        Unit
    }

    fun cancelWorkflowRun(owner: String, repo: String, runId: Long): Result<Unit> = runCatching {
        request("POST", "/repos/$owner/$repo/actions/runs/$runId/cancel")
        Unit
    }

    fun rerunWorkflowRun(owner: String, repo: String, runId: Long): Result<Unit> = runCatching {
        request("POST", "/repos/$owner/$repo/actions/runs/$runId/rerun")
        Unit
    }

    /**
     * Downloads the full plain-text log for a workflow job.
     * GitHub responds with a 302 to a short-lived signed URL; redirects are followed.
     */
    fun getJobLogs(owner: String, repo: String, jobId: Long): Result<String> = runCatching {
        downloadTextFollowingRedirects("/repos/$owner/$repo/actions/jobs/$jobId/logs")
    }

    /**
     * Check-run annotations for a job (errors/warnings with file:line).
     * Job IDs from Actions are check-run IDs for this endpoint.
     */
    fun getJobAnnotations(
        owner: String,
        repo: String,
        jobId: Long
    ): Result<List<WorkflowAnnotation>> = runCatching {
        val arr = request(
            "GET",
            "/repos/$owner/$repo/check-runs/$jobId/annotations?per_page=100"
        ) as JSONArray
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            WorkflowAnnotation(
                path = o.optString("path").takeIf { it.isNotBlank() },
                startLine = o.optInt("start_line").takeIf { o.has("start_line") },
                endLine = o.optInt("end_line").takeIf { o.has("end_line") },
                startColumn = o.optInt("start_column").takeIf { o.has("start_column") },
                endColumn = o.optInt("end_column").takeIf { o.has("end_column") },
                annotationLevel = o.optString("annotation_level", "notice"),
                message = o.optString("message", ""),
                title = o.optString("title").takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * Follow redirects (GitHub job logs → blob storage) and return response body as text.
     * Caps extremely large logs to avoid OOM on mobile.
     */
    private fun downloadTextFollowingRedirects(path: String, maxBytes: Int = 2_000_000): String {
        AppLog.i(TAG, "GET $path (log download)")
        var currentUrl = URL("https://api.github.com$path")
        var redirects = 0
        while (redirects < 8) {
            val conn = currentUrl.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                // Auth only needed for api.github.com hops; blob storage URLs are pre-signed.
                // Never attach the token to non-GitHub hosts.
                if (currentUrl.host.equals("api.github.com", ignoreCase = true) && !token.isNullOrBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                }
                val status = conn.responseCode
                if (status in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw HttpStatusException(status, "Redirect without Location")
                    currentUrl = URL(currentUrl, location)
                    redirects++
                    continue
                }
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                if (status !in 200..299) {
                    val err = stream?.let { s ->
                        BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).use { it.readText() }
                    } ?: ""
                    val msg = runCatching { JSONObject(err).optString("message") }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "GitHub returned HTTP $status"
                    throw HttpStatusException(status, msg)
                }
                val bytes = stream?.readBytes() ?: ByteArray(0)
                val truncated = bytes.size > maxBytes
                val slice = if (truncated) bytes.copyOfRange(bytes.size - maxBytes, bytes.size) else bytes
                val text = String(slice, StandardCharsets.UTF_8)
                return if (truncated) {
                    "… [log truncated to last ${maxBytes / 1000}KB] …\n\n$text"
                } else text
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects fetching job logs")
    }

    // ── Releases ────────────────────────────────────────────────────────────

    fun listReleases(owner: String, repo: String, perPage: Int = 30): Result<List<Release>> = runCatching {
        val arr = request("GET", "/repos/$owner/$repo/releases?per_page=$perPage") as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toRelease() }
    }

    fun getRelease(owner: String, repo: String, releaseId: Long): Result<Release> = runCatching {
        (request("GET", "/repos/$owner/$repo/releases/$releaseId") as JSONObject).toRelease()
    }

    fun getLatestRelease(owner: String, repo: String): Result<Release> = runCatching {
        (request("GET", "/repos/$owner/$repo/releases/latest") as JSONObject).toRelease()
    }

    private fun JSONObject.toRelease(): Release {
        val assetsArr = optJSONArray("assets")
        val assets = if (assetsArr == null) emptyList() else (0 until assetsArr.length()).map { i ->
            val a = assetsArr.getJSONObject(i)
            ReleaseAsset(
                id = a.getLong("id"),
                name = a.optString("name", ""),
                size = a.optLong("size", 0L),
                downloadCount = a.optInt("download_count", 0),
                contentType = a.optString("content_type").takeIf { it.isNotBlank() },
                browserDownloadUrl = a.optString("browser_download_url", ""),
                createdAt = a.optString("created_at", ""),
                updatedAt = a.optString("updated_at", "")
            )
        }
        return Release(
            id = getLong("id"),
            tagName = optString("tag_name", ""),
            name = optString("name", "").ifBlank { optString("tag_name", "") },
            body = if (!has("body") || isNull("body")) null else getString("body"),
            draft = optBoolean("draft", false),
            prerelease = optBoolean("prerelease", false),
            authorLogin = optJSONObject("author")?.optString("login"),
            htmlUrl = optString("html_url", ""),
            tarballUrl = optString("tarball_url").takeIf { it.isNotBlank() },
            zipballUrl = optString("zipball_url").takeIf { it.isNotBlank() },
            createdAt = optString("created_at", ""),
            publishedAt = optString("published_at").takeIf { it.isNotBlank() },
            assets = assets
        )
    }

    private fun JSONObject.toWorkflow() = Workflow(
        id = getLong("id"),
        name = optString("name", ""),
        path = optString("path", ""),
        state = optString("state", "active"),
        badgeUrl = optString("badge_url").takeIf { it.isNotBlank() },
        htmlUrl = optString("html_url", ""),
        createdAt = optString("created_at", ""),
        updatedAt = optString("updated_at", "")
    )

    private fun JSONObject.toWorkflowRun() = WorkflowRun(
        id = getLong("id"),
        name = optString("name", ""),
        displayTitle = optString("display_title", optString("name", "")),
        workflowId = optLong("workflow_id", 0L),
        workflowName = optString("name", ""),
        status = optString("status", ""),
        conclusion = if (has("conclusion") && !isNull("conclusion")) optString("conclusion") else null,
        event = optString("event", ""),
        headBranch = optString("head_branch").takeIf { it.isNotBlank() },
        headSha = optString("head_sha").takeIf { it.isNotBlank() },
        actorLogin = optJSONObject("actor")?.optString("login"),
        runNumber = optInt("run_number", 0),
        runAttempt = optInt("run_attempt", 1),
        htmlUrl = optString("html_url", ""),
        createdAt = optString("created_at", ""),
        updatedAt = optString("updated_at", ""),
        runStartedAt = optString("run_started_at").takeIf { it.isNotBlank() }
    )

    private fun JSONObject.toWorkflowJob(): WorkflowJob {
        val stepsArr = optJSONArray("steps")
        val steps = if (stepsArr == null) emptyList() else (0 until stepsArr.length()).map { i ->
            val s = stepsArr.getJSONObject(i)
            WorkflowStep(
                name = s.optString("name", ""),
                status = s.optString("status", ""),
                conclusion = if (s.has("conclusion") && !s.isNull("conclusion")) s.optString("conclusion") else null,
                number = s.optInt("number", 0),
                startedAt = s.optString("started_at").takeIf { it.isNotBlank() },
                completedAt = s.optString("completed_at").takeIf { it.isNotBlank() }
            )
        }
        return WorkflowJob(
            id = getLong("id"),
            name = optString("name", ""),
            status = optString("status", ""),
            conclusion = if (has("conclusion") && !isNull("conclusion")) optString("conclusion") else null,
            startedAt = optString("started_at").takeIf { it.isNotBlank() },
            completedAt = optString("completed_at").takeIf { it.isNotBlank() },
            htmlUrl = optString("html_url", ""),
            steps = steps
        )
    }

    private fun JSONObject.toIssue(): Issue {
        val labelsArr = optJSONArray("labels")
        val labels = if (labelsArr == null) emptyList() else (0 until labelsArr.length()).mapNotNull { i ->
            val item = labelsArr.opt(i)
            when (item) {
                is String -> item
                is JSONObject -> item.optString("name").takeIf { it.isNotBlank() }
                else -> null
            }
        }
        return Issue(
            number = getInt("number"),
            title = optString("title", ""),
            body = if (!has("body") || isNull("body")) null else getString("body"),
            state = optString("state", "open"),
            authorLogin = optJSONObject("user")?.optString("login") ?: "unknown",
            commentsCount = optInt("comments", 0),
            labels = labels,
            htmlUrl = optString("html_url", ""),
            createdAt = optString("created_at", ""),
            updatedAt = optString("updated_at", ""),
            isPullRequest = has("pull_request")
        )
    }

private fun JSONObject.toPullRequest(): PullRequest {
        val head = getJSONObject("head")
        val base = getJSONObject("base")
        return PullRequest(
            number = getInt("number"),
            title = optString("title", ""),
            body = if (!has("body") || isNull("body")) null else getString("body"),
            state = optString("state", "open"),
            isDraft = optBoolean("draft", false),
            merged = optBoolean("merged", false),
            mergeable = if (has("mergeable") && !isNull("mergeable")) getBoolean("mergeable") else null,
            authorLogin = optJSONObject("user")?.optString("login") ?: "unknown",
            headRef = head.optString("ref", ""),
            headRepoFullName = head.optJSONObject("repo")?.optString("full_name"),
            headSha = head.optString("sha", ""),
            baseRef = base.optString("ref", ""),
            commentsCount = optInt("comments", 0),
            htmlUrl = optString("html_url", ""),
            createdAt = optString("created_at", ""),
            updatedAt = optString("updated_at", "")
        )
    }

    class HttpStatusException(val code: Int, message: String) : IOException(message)

    private fun request(method: String, path: String, body: JSONObject? = null): Any {
        // Never log the token or Authorization header.
        AppLog.i(TAG, "$method $path")
        val url = URL("https://api.github.com$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            // Token is sent only over HTTPS and never written to logs.
            if (!token.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body.toString()) }
            }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { s -> BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).use { it.readText() } } ?: ""

            if (status !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "GitHub returned HTTP $status"
                // Log status + path only; never the token or full error body that might contain secrets.
                AppLog.w(TAG, "HTTP $status for $method $path: $msg")
                throw HttpStatusException(status, msg)
            }
            if (text.isBlank()) return JSONObject()
            return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}

/** Maps a Result<T> failure from GitHub/GitLab API calls onto PrOpResult, distinguishing auth failures. */
fun <T> Result<T>.toPrOpResult(host: String = "github.com"): PrOpResult {
    val e = exceptionOrNull() ?: return PrOpResult.Success
    AppLog.e("GitHubApi", "request failed", e)
    val code = when (e) {
        is GitHubApi.HttpStatusException -> e.code
        is com.quickgit.app.data.gitlab.GitLabApi.HttpStatusException -> e.code
        else -> null
    }
    return if (code == 401 || code == 403) {
        PrOpResult.AuthRequired(host)
    } else {
        PrOpResult.Error(e.message ?: "API request failed", e)
    }
}
