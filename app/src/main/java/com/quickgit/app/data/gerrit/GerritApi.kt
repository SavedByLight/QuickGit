package com.quickgit.app.data.gerrit

import com.quickgit.app.data.AppLog
import com.quickgit.app.data.models.GerritChange
import com.quickgit.app.data.models.GerritProject
import com.quickgit.app.data.models.GerritLabelInfo
import com.quickgit.app.data.models.GerritMessage
import com.quickgit.app.data.models.GerritReviewInput
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
import java.util.Base64

/**
 * Minimal Gerrit REST API client. Host-agnostic (googlesource, self-hosted, etc.).
 * Uses HTTP Digest or Basic auth with username + HTTP password / access token.
 * Gerrit prefixes JSON responses with )]}'  — we strip that.
 */
class GerritApi(
    private val host: String,
    private val username: String?,
    private val passwordOrToken: String?,
    private val useHttps: Boolean = true
) {
    private val TAG = "GerritApi"
    private val baseUrl: String
        get() {
            val scheme = if (useHttps) "https" else "http"
            val h = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            // /a/ is the authenticated prefix on most Gerrit installs
            return "$scheme://$h/a"
        }

    data class GerritUser(
        val username: String,
        val name: String?,
        val email: String?,
        val accountId: Int
    )

    fun getAuthenticatedUser(): Result<GerritUser> = runCatching {
        val obj = request("GET", "/accounts/self") as JSONObject
        GerritUser(
            username = obj.optString("username", obj.optString("name", "")),
            name = obj.optString("name").takeIf { it.isNotBlank() },
            email = obj.optString("email").takeIf { it.isNotBlank() },
            accountId = obj.optInt("_account_id", 0)
        )
    }

    /**
     * List Changes. Query language is Gerrit search syntax.
     * Examples: "status:open", "is:open owner:self", "project:my/repo status:open"
     */
    fun listChanges(
        query: String = "status:open",
        limit: Int = 50,
        start: Int = 0
    ): Result<List<GerritChange>> = runCatching {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        // DETAILED_LABELS + CURRENT_REVISION give us enough for the list UI
        val path = "/changes/?q=$q&n=$limit&S=$start&o=DETAILED_LABELS&o=CURRENT_REVISION&o=DETAILED_ACCOUNTS"
        val arr = request("GET", path) as JSONArray
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toChange() }
    }

    fun getChange(changeId: String): Result<GerritChange> = runCatching {
        val encoded = java.net.URLEncoder.encode(changeId, "UTF-8")
        val path = "/changes/$encoded/detail?o=DETAILED_LABELS&o=CURRENT_REVISION&o=DETAILED_ACCOUNTS&o=MESSAGES"
        (request("GET", path) as JSONObject).toChange()
    }

    fun listMessages(changeId: String): Result<List<GerritMessage>> = runCatching {
        val change = getChange(changeId).getOrThrow()
        // messages are already in the detail response when o=MESSAGES is used;
        // re-fetch with messages if needed
        val encoded = java.net.URLEncoder.encode(changeId, "UTF-8")
        val obj = request("GET", "/changes/$encoded/detail?o=MESSAGES&o=DETAILED_ACCOUNTS") as JSONObject
        val arr = obj.optJSONArray("messages") ?: JSONArray()
        (0 until arr.length()).map { i -> arr.getJSONObject(i).toMessage() }
    }

    /**
     * Post a review: optional message + label votes (Code-Review, Verified, …).
     * Values typically range from -2 to +2 for Code-Review.
     */
    fun postReview(changeId: String, revision: String = "current", input: GerritReviewInput): Result<Unit> = runCatching {
        val encodedChange = java.net.URLEncoder.encode(changeId, "UTF-8")
        val encodedRev = java.net.URLEncoder.encode(revision, "UTF-8")
        val payload = JSONObject()
        if (!input.message.isNullOrBlank()) payload.put("message", input.message)
        if (input.labels.isNotEmpty()) {
            val labels = JSONObject()
            input.labels.forEach { (k, v) -> labels.put(k, v) }
            payload.put("labels", labels)
        }
        if (input.draft) payload.put("drafts", "PUBLISH")
        request("POST", "/changes/$encodedChange/revisions/$encodedRev/review", payload)
        Unit
    }

    /** Convenience: set Code-Review vote (+2, +1, 0, -1, -2) with optional comment. */
    fun voteCodeReview(
        changeId: String,
        value: Int,
        message: String? = null,
        revision: String = "current"
    ): Result<Unit> = postReview(
        changeId,
        revision,
        GerritReviewInput(message = message, labels = mapOf("Code-Review" to value))
    )


    /**
     * List projects the caller can see.
     * @param query optional substring match (Gerrit `m=` parameter)
     * @param limit max results (Gerrit `n=`)
     */
    fun listProjects(query: String? = null, limit: Int = 100, start: Int = 0): Result<List<GerritProject>> = runCatching {
        val params = mutableListOf("d", "n=$limit", "S=$start")
        val q = query?.trim().orEmpty()
        if (q.isNotEmpty()) {
            params += "m=" + java.net.URLEncoder.encode(q, "UTF-8")
        }
        val path = "/projects/?" + params.joinToString("&")
        val raw = request("GET", path)
        val obj = when (raw) {
            is JSONObject -> raw
            else -> JSONObject()
        }
        val scheme = if (useHttps) "https" else "http"
        val hostClean = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val list = mutableListOf<GerritProject>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val info = obj.optJSONObject(name) ?: continue
            val state = info.optString("state", "ACTIVE")
            if (state.equals("HIDDEN", ignoreCase = true)) continue
            val id = info.optString("id", name)
            val desc = info.optString("description").takeIf { it.isNotBlank() }
            // Gerrit HTTP clone: https://host/project  (or /a/ for auth — JGit uses credentials)
            val clone = "$scheme://$hostClean/$name"
            val sshUser = username?.takeIf { it.isNotBlank() } ?: "git"
            val ssh = "ssh://$sshUser@$hostClean:29418/$name"
            val web = "$scheme://$hostClean/admin/repos/$name"
            list += GerritProject(
                id = id,
                name = name,
                description = desc,
                state = state,
                webUrl = web,
                cloneUrl = clone,
                sshUrl = ssh
            )
        }
        list.sortedBy { it.name.lowercase() }
    }

    fun searchProjects(query: String, limit: Int = 100, start: Int = 0): Result<List<GerritProject>> =
        listProjects(query = query, limit = limit, start = start)


    data class GerritAccountSummary(
        val accountId: Int,
        val username: String,
        val name: String?,
        val email: String?
    )

    fun searchAccounts(query: String, limit: Int = 30): Result<List<GerritAccountSummary>> = runCatching {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val raw = request("GET", "/accounts/?q=$q&n=$limit&o=DETAILS")
        val arr = when (raw) {
            is JSONArray -> raw
            else -> JSONArray()
        }
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GerritAccountSummary(
                accountId = o.optInt("_account_id", 0),
                username = o.optString("username", o.optString("name", "")),
                name = o.optString("name").takeIf { it.isNotBlank() },
                email = o.optString("email").takeIf { it.isNotBlank() }
            )
        }
    }

    // ---- Helpers ----

    private fun JSONObject.toChange(): GerritChange {
        val owner = optJSONObject("owner")
        val labelsObj = optJSONObject("labels")
        val labels = mutableMapOf<String, GerritLabelInfo>()
        if (labelsObj != null) {
            val keys = labelsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val lo = labelsObj.optJSONObject(key) ?: continue
                val approved = lo.optJSONObject("approved") != null
                val rejected = lo.optJSONObject("rejected") != null
                val value = lo.optJSONObject("approved")?.optInt("value")
                    ?: lo.optJSONObject("rejected")?.optInt("value")
                    ?: lo.optJSONArray("all")?.let { arr ->
                        if (arr.length() > 0) arr.getJSONObject(0).optInt("value") else null
                    }
                labels[key] = GerritLabelInfo(approved = approved, rejected = rejected, value = value)
            }
        }
        val number = optInt("_number", 0)
        val project = optString("project", "")
        return GerritChange(
            id = optString("id", ""),
            number = number,
            project = project,
            branch = optString("branch", ""),
            subject = optString("subject", ""),
            status = optString("status", "NEW"),
            ownerName = owner?.optString("name") ?: owner?.optString("username") ?: "",
            ownerEmail = owner?.optString("email"),
            created = optString("created", ""),
            updated = optString("updated", ""),
            insertions = optInt("insertions", 0),
            deletions = optInt("deletions", 0),
            unresolvedCommentCount = optInt("unresolved_comment_count", 0),
            labels = labels,
            currentRevision = optString("current_revision").takeIf { it.isNotBlank() },
            webUrl = null // callers can construct from host + number
        )
    }

    private fun JSONObject.toMessage() = GerritMessage(
        id = optString("id", ""),
        message = optString("message", ""),
        authorName = optJSONObject("author")?.optString("name")
            ?: optJSONObject("author")?.optString("username") ?: "",
        date = optString("date", ""),
        revisionNumber = if (has("_revision_number")) optInt("_revision_number") else null
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
            if (!username.isNullOrBlank() && !passwordOrToken.isNullOrBlank()) {
                val creds = Base64.getEncoder().encodeToString(
                    "$username:$passwordOrToken".toByteArray(StandardCharsets.UTF_8)
                )
                setRequestProperty("Authorization", "Basic $creds")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { it.write(body.toString()) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        var text = stream?.use { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() } ?: ""
        // Gerrit XSSI protection prefix
        if (text.startsWith(")]}'")) {
            text = text.substring(text.indexOf('\n') + 1).trimStart()
        }
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

fun Result<*>.toGerritOpResult(host: String): PrOpResult {
    val ex = exceptionOrNull() ?: return PrOpResult.Success
    val msg = ex.message ?: "Unknown error"
    return when {
        msg.contains("401") || msg.contains("Unauthorized", ignoreCase = true) ||
                msg.contains("403") -> PrOpResult.AuthRequired(host)
        else -> PrOpResult.Error(msg, ex)
    }
}
