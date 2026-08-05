package com.quickgit.app.data.github

import com.quickgit.app.data.AppLog
import com.quickgit.app.data.models.GitHubRemoteRepo
import com.quickgit.app.data.models.MergeMethod
import com.quickgit.app.data.models.PrComment
import com.quickgit.app.data.models.PrOpResult
import com.quickgit.app.data.models.PullRequest
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
 */
class GitHubApi(private val token: String?) {

    private val TAG = "GitHubApi"

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
        val avatarUrl: String?,
        val htmlUrl: String
    )

    /** Verifies the token and returns the authenticated user. */
    fun getAuthenticatedUser(): Result<GitHubUser> = runCatching {
        val o = request("GET", "/user") as JSONObject
        GitHubUser(
            login = o.getString("login"),
            name = if (o.isNull("name")) null else o.optString("name").takeIf { it.isNotBlank() },
            avatarUrl = o.optString("avatar_url").takeIf { it.isNotBlank() },
            htmlUrl = o.optString("html_url", "")
        )
    }

    /**
     * Lists repositories the authenticated user can access.
     * @param affiliation comma-separated: owner, collaborator, organization_member (default all three)
     * @param type all|owner|public|private|member
     * @param sort created|updated|pushed|full_name
     */
    fun listUserRepos(
        affiliation: String = "owner,collaborator,organization_member",
        type: String = "all",
        sort: String = "updated",
        perPage: Int = 50,
        page: Int = 1
    ): Result<List<GitHubRemoteRepo>> = runCatching {
        val path = "/user/repos?affiliation=$affiliation&type=$type&sort=$sort&direction=desc&per_page=$perPage&page=$page"
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

    /** Maps a Result<T> failure onto PrOpResult, distinguishing auth failures for the caller. */
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
        AppLog.i(TAG, "$method $path")
        val url = URL("https://api.github.com$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
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

/** Maps a Result<T> failure from any GitHubApi call onto PrOpResult, distinguishing auth failures. */
fun <T> Result<T>.toPrOpResult(host: String = "github.com"): PrOpResult {
    val e = exceptionOrNull() ?: return PrOpResult.Success
    AppLog.e("GitHubApi", "request failed", e)
    return if (e is GitHubApi.HttpStatusException && (e.code == 401 || e.code == 403)) {
        PrOpResult.AuthRequired(host)
    } else {
        PrOpResult.Error(e.message ?: "GitHub request failed", e)
    }
}
