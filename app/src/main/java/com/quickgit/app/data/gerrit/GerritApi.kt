package com.quickgit.app.data.gerrit

import com.quickgit.app.data.AppLog
import com.quickgit.app.data.models.GerritProject
import com.quickgit.app.data.models.PrOpResult
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import android.util.Base64

/**
 * Minimal Gerrit REST API client.
 *
 * Auth: HTTP Basic with username + HTTP password (Settings → HTTP Credentials).
 * All JSON responses may be prefixed with Gerrit's XSS shield: )]}'
 */
class GerritApi(
    private val host: String,
    private val username: String?,
    private val httpPassword: String?,
    private val useHttps: Boolean = true
) {
    private val TAG = "GerritApi"

    private val baseUrl: String
        get() {
            val scheme = if (useHttps) "https" else "http"
            val h = host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            return "$scheme://$h"
        }

    data class GerritUser(
        val username: String,
        val name: String?,
        val email: String?
    )

    /** Verify credentials via GET /a/accounts/self */
    fun getAuthenticatedUser(): Result<GerritUser> = runCatching {
        val body = request("GET", "/a/accounts/self")
        val obj = parseJsonObject(body)
        GerritUser(
            username = obj.optString("username").ifBlank { username.orEmpty() },
            name = obj.optString("name").takeIf { it.isNotBlank() },
            email = obj.optString("email").takeIf { it.isNotBlank() }
        )
    }

    /**
     * List projects the user can see via List Projects REST endpoint.
     *
     * Gerrit does **not** accept `q=` on `/projects/` (returns 400: "-q" is not a valid option).
     * Use `state=` for ACTIVE/READ_ONLY/HIDDEN and `m=` for substring match.
     *
     * @param query optional filter. Supports `state:ACTIVE` (etc.) and free-text substring.
     *              Examples: null/"state:ACTIVE", "state:ACTIVE foo", "android"
     */
    /**
     * @param start skip the first [start] projects (Gerrit `S` / `start` query param).
     */
    fun listProjects(
        query: String? = "state:ACTIVE",
        limit: Int = 100,
        start: Int = 0
    ): Result<List<GerritProject>> =
        runCatching {
            val raw = query?.trim().orEmpty()
            val stateRegex = Regex("""(?i)\bstate:(ACTIVE|READ_ONLY|HIDDEN)\b""")
            val stateMatch = stateRegex.find(raw)
            val state = stateMatch?.groupValues?.get(1)?.uppercase()
                ?: if (raw.isBlank()) "ACTIVE" else null
            val substring = raw.replace(stateRegex, "").trim().takeIf { it.isNotBlank() }

            val path = buildString {
                append("/a/projects/?d&n=$limit")
                if (start > 0) append("&S=$start")
                if (state != null) append("&state=").append(state)
                if (substring != null) {
                    append("&m=").append(URLEncoder.encode(substring, "UTF-8"))
                }
            }
            val body = request("GET", path)
            // List Projects returns a JSON object: { "project/name": { "id", "state", ... }, ... }
            val obj = parseJsonObject(body)
            val keys = obj.keys().asSequence().toList().sorted()
            keys.map { name ->
                val p = obj.getJSONObject(name)
                val id = p.optString("id", name)
                GerritProject(
                    id = id,
                    name = name,
                    state = p.optString("state").takeIf { it.isNotBlank() },
                    description = p.optString("description").takeIf { it.isNotBlank() },
                    webUrl = "$baseUrl/admin/repos/${name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }}"
                )
            }
        }

    /** HTTPS clone URL for a project (uses /a/ path for authenticated clone). */
    fun cloneUrl(projectName: String): String {
        val path = projectName.trim().removePrefix("/").removeSuffix(".git")
        return "$baseUrl/a/$path"
    }

    private fun request(method: String, path: String): String {
        val url = URL(baseUrl.trimEnd('/') + path)
        AppLog.i(TAG, "$method $url")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            if (!username.isNullOrBlank() && !httpPassword.isNullOrBlank()) {
                val basic = Base64.encodeToString(
                    "$username:$httpPassword".toByteArray(StandardCharsets.UTF_8),
                    Base64.NO_WRAP
                )
                conn.setRequestProperty("Authorization", "Basic $basic")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { s ->
                BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { parseJsonObject(text).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Gerrit HTTP $code"
                throw java.io.IOException(msg)
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parseJsonObject(raw: String): JSONObject {
        val cleaned = raw.trim().removePrefix(")]}'").trim()
        if (cleaned.isBlank()) return JSONObject()
        return JSONObject(cleaned)
    }
}

fun Result<*>.toPrOpResult(host: String): PrOpResult {
    return fold(
        onSuccess = { PrOpResult.Success },
        onFailure = { e ->
            val msg = e.message.orEmpty()
            when {
                msg.contains("401") || msg.contains("Unauthorized", true) ->
                    PrOpResult.AuthRequired(host)
                else -> PrOpResult.Error(msg.ifBlank { e.toString() }, e)
            }
        }
    )
}
