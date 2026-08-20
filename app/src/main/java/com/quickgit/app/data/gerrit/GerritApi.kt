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
     * List projects the user can see.
     * @param query optional Gerrit project query (e.g. "state:ACTIVE")
     */
    fun listProjects(query: String? = "state:ACTIVE", limit: Int = 100): Result<List<GerritProject>> =
        runCatching {
            val q = buildString {
                append("/a/projects/?d&n=$limit")
                if (!query.isNullOrBlank()) {
                    append("&q=").append(URLEncoder.encode(query, "UTF-8"))
                }
            }
            val body = request("GET", q)
            // Response is a JSON object: { "project/name": { "id", "state", ... }, ... }
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
