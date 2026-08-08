package com.quickgit.app.data.gitlab

import com.quickgit.app.data.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Minimal GitLab REST API client for pipelines / jobs.
 * Mirrors the surface used by [com.quickgit.app.data.WorkflowManager].
 *
 * Security: token is only sent as PRIVATE-TOKEN / Bearer over HTTPS and is never logged.
 */
class GitLabApi(
    host: String,
    rawToken: String?
) {
    private val TAG = "GitLabApi"
    private val host = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
    private val token: String? = rawToken
        ?.trim()
        ?.removePrefix("Bearer ")?.removePrefix("bearer ")
        ?.removePrefix("token ")?.removePrefix("Token ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    private val baseUrl = "https://$host/api/v4"

    data class OwnerProject(val owner: String, val project: String)

    /** Parse owner/project from a GitLab remote URL. */
    fun parseOwnerProject(remoteUrl: String?): OwnerProject? {
        if (remoteUrl.isNullOrBlank()) return null
        val cleaned = remoteUrl.trim()
            .removeSuffix(".git")
            .removeSuffix("/")
        // https://gitlab.com/group/subgroup/project
        // git@gitlab.com:group/subgroup/project.git
        val patterns = listOf(
            Regex("""^https?://[^/]+/(.+)$"""),
            Regex("""^git@[^:]+:(.+)$"""),
            Regex("""^ssh://git@[^/]+/(.+)$""")
        )
        for (p in patterns) {
            val m = p.find(cleaned) ?: continue
            val path = m.groupValues[1].trim('/')
            val parts = path.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            val project = parts.last()
            val owner = parts.dropLast(1).joinToString("/")
            return OwnerProject(owner, project)
        }
        return null
    }

    data class Pipeline(
        val id: Long,
        val iid: Int,
        val status: String,
        val ref: String?,
        val sha: String?,
        val source: String?,
        val webUrl: String,
        val createdAt: String,
        val updatedAt: String
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

    fun listPipelines(projectPath: String, status: String? = null): Result<List<Pipeline>> = runCatching {
        val encoded = URLEncoder.encode(projectPath, "UTF-8")
        var path = "/projects/$encoded/pipelines?per_page=30&order_by=id&sort=desc"
        if (!status.isNullOrBlank()) path += "&status=${URLEncoder.encode(status, "UTF-8")}"
        val arr = request("GET", path) as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toPipeline() }
    }

    fun getPipeline(projectPath: String, pipelineId: Long): Result<Pipeline> = runCatching {
        val encoded = URLEncoder.encode(projectPath, "UTF-8")
        (request("GET", "/projects/$encoded/pipelines/$pipelineId") as JSONObject).toPipeline()
    }

    fun listPipelineJobs(projectPath: String, pipelineId: Long): Result<List<PipelineJob>> = runCatching {
        val encoded = URLEncoder.encode(projectPath, "UTF-8")
        val arr = request("GET", "/projects/$encoded/pipelines/$pipelineId/jobs?per_page=100") as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toPipelineJob() }
    }

    fun cancelPipeline(projectPath: String, pipelineId: Long): Result<Unit> = runCatching {
        val encoded = URLEncoder.encode(projectPath, "UTF-8")
        request("POST", "/projects/$encoded/pipelines/$pipelineId/cancel")
        Unit
    }

    fun retryPipeline(projectPath: String, pipelineId: Long): Result<Unit> = runCatching {
        val encoded = URLEncoder.encode(projectPath, "UTF-8")
        request("POST", "/projects/$encoded/pipelines/$pipelineId/retry")
        Unit
    }

    private fun JSONObject.toPipeline() = Pipeline(
        id = getLong("id"),
        iid = optInt("iid", 0),
        status = optString("status", ""),
        ref = optString("ref").takeIf { it.isNotBlank() },
        sha = optString("sha").takeIf { it.isNotBlank() },
        source = optString("source").takeIf { it.isNotBlank() },
        webUrl = optString("web_url", ""),
        createdAt = optString("created_at", ""),
        updatedAt = optString("updated_at", "")
    )

    private fun JSONObject.toPipelineJob() = PipelineJob(
        id = getLong("id"),
        name = optString("name", "job"),
        status = optString("status", ""),
        stage = optString("stage").takeIf { it.isNotBlank() },
        webUrl = optString("web_url", ""),
        startedAt = optString("started_at").takeIf { it.isNotBlank() },
        finishedAt = optString("finished_at").takeIf { it.isNotBlank() }
    )

    class HttpStatusException(val code: Int, message: String) : IOException(message)

    private fun request(method: String, path: String, body: JSONObject? = null): Any {
        // Never log the token.
        AppLog.i(TAG, "$method $path")
        val url = URL("$baseUrl$path")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                // GitLab accepts PRIVATE-TOKEN; Bearer also works for OAuth tokens.
                conn.setRequestProperty("PRIVATE-TOKEN", token)
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body.toString()) }
            }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { s ->
                BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).use { it.readText() }
            } ?: ""

            if (status !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: runCatching { JSONObject(text).optString("error") }.getOrNull()
                    ?: "GitLab returned HTTP $status"
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
