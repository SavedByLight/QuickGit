package com.quickgit.app.data

import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.github.toPrOpResult
import com.quickgit.app.data.models.GitHubRemoteRepo
import com.quickgit.app.data.models.PrOpResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Connects a GitHub account via personal access token (already stored in CredentialStore)
 * and lists repositories the user can clone.
 *
 * True OAuth would need a registered OAuth App + redirect URI; this app uses the same
 * encrypted PAT flow as git push/pull, then verifies the token with GET /user.
 */
class GitHubAccountManager(private val credentialStore: CredentialStore) {

    private val TAG = "GitHubAccountManager"
    private val host = "github.com"

    private val api: GitHubApi get() = GitHubApi(credentialStore.getHttpsToken(host))

    /** Short-lived in-memory cache so Profile/Browse re-entry is instant. */
    private data class RepoCacheEntry(val repos: List<GitHubRemoteRepo>, val expiresAtMs: Long)
    private val repoPageCache = ConcurrentHashMap<String, RepoCacheEntry>()
    private var orgReposCache: RepoCacheEntry? = null
    private val cacheTtlMs = 120_000L // 2 minutes

    private fun cacheGet(key: String): List<GitHubRemoteRepo>? {
        val e = repoPageCache[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAtMs) {
            repoPageCache.remove(key)
            return null
        }
        return e.repos
    }

    private fun cachePut(key: String, repos: List<GitHubRemoteRepo>) {
        repoPageCache[key] = RepoCacheEntry(repos, System.currentTimeMillis() + cacheTtlMs)
    }

    fun invalidateRepoCache() {
        repoPageCache.clear()
        orgReposCache = null
    }

    data class ConnectedAccount(
        val login: String,
        val name: String?,
        val email: String?,
        val avatarUrl: String?,
        val htmlUrl: String
    )

    fun isConnected(): Boolean = credentialStore.hasHttpsCredential(host)

    fun storedUsername(): String? = credentialStore.getHttpsUsername(host)

    /**
     * Saves the token, then calls GitHub to verify it and return the authenticated user.
     * On success, updates the stored username to the real GitHub login.
     */
    fun connect(token: String, preferredUsername: String? = null): Pair<ConnectedAccount?, PrOpResult> {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return null to PrOpResult.Error("Token is required")
        AppLog.i(TAG, "connect: verifying token with GitHub")
        val provisionalUser = preferredUsername?.trim()?.ifBlank { null } ?: "x-access-token"
        try {
            credentialStore.saveHttpsToken(host, provisionalUser, trimmed)
        } catch (e: Exception) {
            return null to PrOpResult.Error(e.message ?: "Failed to save token", e)
        }
        val result = api.getAuthenticatedUser()
        val user = result.getOrNull()
        return if (user != null) {
            try {
                credentialStore.saveHttpsToken(host, user.login, trimmed)
            } catch (_: Exception) { /* already saved */ }
            AppLog.i(TAG, "connect succeeded: ${user.login}")
            ConnectedAccount(user.login, user.name, user.email, user.avatarUrl, user.htmlUrl) to PrOpResult.Success
        } else {
            val op = result.toPrOpResult(host)
            if (op is PrOpResult.AuthRequired) {
                credentialStore.clearHttpsToken(host)
            }
            null to op
        }
    }

    fun disconnect() {
        AppLog.i(TAG, "disconnect")
        credentialStore.clearHttpsToken(host)
        invalidateRepoCache()
    }

    /** Re-verify the stored token and return the current user, or null if not connected / invalid. */
    fun refreshAccount(): Pair<ConnectedAccount?, PrOpResult> {
        if (!isConnected()) return null to PrOpResult.Error("Not connected")
        val result = api.getAuthenticatedUser()
        val user = result.getOrNull()
        return if (user != null) {
            ConnectedAccount(user.login, user.name, user.email, user.avatarUrl, user.htmlUrl) to PrOpResult.Success
        } else {
            null to result.toPrOpResult(host)
        }
    }

    /** Fetches every page of repos the authenticated user can access, including org repos. */
    fun listRepos(
        affiliation: String = "owner,collaborator,organization_member"
    ): Pair<List<GitHubRemoteRepo>, PrOpResult> {
        if (!isConnected()) return emptyList<GitHubRemoteRepo>() to PrOpResult.AuthRequired(host)
        val perPage = 100
        val byId = linkedMapOf<Long, GitHubRemoteRepo>()
        var page = 1
        while (true) {
            val result = api.listUserRepos(affiliation = affiliation, perPage = perPage, page = page)
            val batch = result.getOrNull()
            if (batch == null) {
                return if (page == 1 && byId.isEmpty()) emptyList<GitHubRemoteRepo>() to result.toPrOpResult(host)
                else byId.values.toList() to PrOpResult.Success
            }
            batch.forEach { byId[it.id] = it }
            if (batch.size < perPage || page >= 10) break // 10-page safety cap (1000 repos)
            page++
        }
        // /user/repos often under-reports org repos (token scopes / private membership).
        // Also walk organizations the user belongs to and merge those repos.
        mergeOrganizationRepos(byId)
        return byId.values
            .sortedByDescending { it.updatedAt }
            .toList() to PrOpResult.Success
    }

    /**
     * Single page of personal/collaborator/org-member repos via `/user/repos` (100 by default).
     * Does **not** walk every organization separately — that was the main first-load stall.
     * Call [listOrganizationReposExtra] in the background and merge if you need full org coverage.
     * Results are cached ~2 minutes.
     */
    fun listReposPage(
        page: Int = 1,
        perPage: Int = 100,
        affiliation: String = "owner,collaborator,organization_member"
    ): Triple<List<GitHubRemoteRepo>, Boolean, PrOpResult> {
        if (!isConnected()) return Triple(emptyList(), false, PrOpResult.AuthRequired(host))
        val cacheKey = "userRepos:$affiliation:$perPage:$page"
        cacheGet(cacheKey)?.let { cached ->
            AppLog.i(TAG, "listReposPage cache hit page=$page size=${cached.size}")
            return Triple(cached, cached.size >= perPage, PrOpResult.Success)
        }
        val result = api.listUserRepos(affiliation = affiliation, perPage = perPage, page = page)
        val batch = result.getOrNull()
        if (batch == null) {
            return Triple(emptyList(), false, result.toPrOpResult(host))
        }
        cachePut(cacheKey, batch)
        return Triple(batch, batch.size >= perPage, PrOpResult.Success)
    }

    /**
     * Org repos not always fully represented by `/user/repos`. Fetched separately so the UI
     * can show the first personal page immediately. Cached ~2 minutes.
     */
    fun listOrganizationReposExtra(): Pair<List<GitHubRemoteRepo>, PrOpResult> {
        if (!isConnected()) return emptyList<GitHubRemoteRepo>() to PrOpResult.AuthRequired(host)
        val existing = orgReposCache
        if (existing != null && System.currentTimeMillis() <= existing.expiresAtMs) {
            AppLog.i(TAG, "listOrganizationReposExtra cache hit size=${existing.repos.size}")
            return existing.repos to PrOpResult.Success
        }
        val byId = linkedMapOf<Long, GitHubRemoteRepo>()
        mergeOrganizationRepos(byId)
        val list = byId.values.sortedByDescending { it.updatedAt }.toList()
        orgReposCache = RepoCacheEntry(list, System.currentTimeMillis() + cacheTtlMs)
        return list to PrOpResult.Success
    }

    /**
     * Loads organizations for the authenticated user and merges each org's accessible
     * repositories into [into] (keyed by repo id). Best-effort: org listing failures are
     * logged and skipped so a missing `read:org` scope does not break personal repos.
     */
    private fun mergeOrganizationRepos(into: MutableMap<Long, GitHubRemoteRepo>) {
        val orgs = mutableListOf<String>()
        var orgPage = 1
        while (orgPage <= 5) {
            val orgResult = api.listUserOrganizations(perPage = 100, page = orgPage)
            val batch = orgResult.getOrNull()
            if (batch == null) {
                AppLog.w(TAG, "listUserOrganizations failed page=$orgPage: ${orgResult.exceptionOrNull()?.message}")
                break
            }
            orgs += batch
            if (batch.size < 100) break
            orgPage++
        }
        if (orgs.isEmpty()) {
            AppLog.i(TAG, "mergeOrganizationRepos: no organizations returned (token may lack read:org)")
            return
        }
        AppLog.i(TAG, "mergeOrganizationRepos: fetching repos for ${orgs.size} org(s)")
        for (org in orgs) {
            var page = 1
            while (page <= 5) {
                val repoResult = api.listOrgRepos(org, type = "all", perPage = 100, page = page)
                val batch = repoResult.getOrNull()
                if (batch == null) {
                    AppLog.w(TAG, "listOrgRepos($org) failed page=$page: ${repoResult.exceptionOrNull()?.message}")
                    break
                }
                batch.forEach { into[it.id] = it }
                if (batch.size < 100) break
                page++
            }
        }
    }

    fun searchReposPage(
        query: String,
        page: Int = 1,
        perPage: Int = 100
    ): Triple<List<GitHubRemoteRepo>, Boolean, PrOpResult> {
        if (!isConnected()) return Triple(emptyList(), false, PrOpResult.AuthRequired(host))
        // GitHub search API is not paged the same way for user repos; filter client-side from one page
        val (all, op) = searchRepos(query)
        if (op !is PrOpResult.Success) return Triple(emptyList(), false, op)
        val from = ((page - 1) * perPage).coerceAtLeast(0)
        if (from >= all.size) return Triple(emptyList(), false, PrOpResult.Success)
        val slice = all.drop(from).take(perPage)
        return Triple(slice, from + slice.size < all.size, PrOpResult.Success)
    }

    fun searchRepos(query: String): Pair<List<GitHubRemoteRepo>, PrOpResult> {
        if (!isConnected()) return emptyList<GitHubRemoteRepo>() to PrOpResult.AuthRequired(host)
        val login = storedUsername()
        if (query.isBlank()) {
            val (page, _, op) = listReposPage(page = 1, perPage = 100)
            return page to op
        }
        val q = query.trim().lowercase()
        val byId = linkedMapOf<Long, GitHubRemoteRepo>()
        // Fast path: filter first 2 pages of /user/repos (cached) — no full org walk.
        for (page in 1..2) {
            val (batch, hasMore, op) = listReposPage(page = page, perPage = 100)
            if (op !is PrOpResult.Success && page == 1) return emptyList<GitHubRemoteRepo>() to op
            batch.filter {
                it.name.lowercase().contains(q) ||
                    it.fullName.lowercase().contains(q) ||
                    it.ownerLogin.lowercase().contains(q) ||
                    (it.description?.lowercase()?.contains(q) == true)
            }.forEach { byId[it.id] = it }
            if (!hasMore) break
        }
        // GitHub search API for broader matches (includes org repos the token can see).
        if (!login.isNullOrBlank() && login != "x-access-token") {
            val orgs = api.listUserOrganizations(perPage = 100).getOrNull().orEmpty()
            val searchHits = api.searchUserRepos(login, query, orgLogins = orgs, perPage = 100)
                .getOrNull().orEmpty()
            searchHits.forEach { byId[it.id] = it }
        }
        return byId.values.sortedByDescending { it.updatedAt }.toList() to PrOpResult.Success
    }

    fun getUserProfile(login: String? = null): Pair<GitHubApi.GitHubUser?, PrOpResult> {
        if (!isConnected()) return null to PrOpResult.AuthRequired(host)
        val result = if (login.isNullOrBlank()) {
            api.getAuthenticatedUser()
        } else {
            api.getUser(login)
        }
        return result.getOrNull() to result.toPrOpResult(host)
    }

    fun searchUsers(
        query: String,
        page: Int = 1,
        perPage: Int = 100
    ): Triple<List<GitHubApi.GitHubUserSummary>, Boolean, PrOpResult> {
        if (!isConnected()) {
            return Triple(emptyList(), false, PrOpResult.AuthRequired(host))
        }
        val result = api.searchUsers(query, perPage = perPage, page = page)
        val batch = result.getOrNull() ?: emptyList()
        val hasMore = batch.size >= perPage
        return Triple(batch, hasMore, result.toPrOpResult(host))
    }

    /** Fetches every page of the user's public repos (GitHub paginates at 100/page). */
    fun listPublicRepos(login: String): Pair<List<GitHubRemoteRepo>, PrOpResult> {
        if (!isConnected()) return emptyList<GitHubRemoteRepo>() to PrOpResult.AuthRequired(host)
        val perPage = 100
        val all = mutableListOf<GitHubRemoteRepo>()
        var page = 1
        while (true) {
            val result = api.listPublicRepos(login, perPage = perPage, page = page)
            val batch = result.getOrNull()
            if (batch == null) {
                // First page failing is a real error; a later page failing just stops pagination.
                return if (page == 1) emptyList<GitHubRemoteRepo>() to result.toPrOpResult(host)
                else all to PrOpResult.Success
            }
            all += batch
            if (batch.size < perPage || page >= 10) break // 10-page safety cap (1000 repos)
            page++
        }
        return all to PrOpResult.Success
    }

    /** Single page of public repos for [login] (100 by default). [page] is 1-based. Cached ~2 min. */
    fun listPublicReposPage(
        login: String,
        page: Int = 1,
        perPage: Int = 100
    ): Triple<List<GitHubRemoteRepo>, Boolean, PrOpResult> {
        if (!isConnected()) return Triple(emptyList(), false, PrOpResult.AuthRequired(host))
        val cacheKey = "publicRepos:${login.lowercase()}:$perPage:$page"
        cacheGet(cacheKey)?.let { cached ->
            return Triple(cached, cached.size >= perPage, PrOpResult.Success)
        }
        val result = api.listPublicRepos(login, perPage = perPage, page = page)
        val batch = result.getOrNull()
        if (batch == null) {
            return Triple(emptyList(), false, result.toPrOpResult(host))
        }
        cachePut(cacheKey, batch)
        return Triple(batch, batch.size >= perPage, PrOpResult.Success)
    }

    /** Lists files/folders at a path in someone else's (or your own) repo, without cloning it. */
    fun getRepoContents(
        owner: String,
        repo: String,
        path: String,
        ref: String
    ): Pair<List<GitHubApi.RemoteEntry>, PrOpResult> {
        if (!isConnected()) return emptyList<GitHubApi.RemoteEntry>() to PrOpResult.AuthRequired(host)
        val result = api.getRepoContents(owner, repo, path, ref)
        return (result.getOrNull() ?: emptyList()) to result.toPrOpResult(host)
    }

    /** Fetches a single file's text content at a path/ref, without cloning the repo. */
    fun getFileContent(
        owner: String,
        repo: String,
        path: String,
        ref: String
    ): Pair<String?, PrOpResult> {
        if (!isConnected()) return null to PrOpResult.AuthRequired(host)
        val result = api.getFileContent(owner, repo, path, ref)
        return result.getOrNull() to result.toPrOpResult(host)
    }

    /** Forks someone else's repo into the authenticated user's own account. */
    fun forkRepo(owner: String, repo: String): Pair<GitHubRemoteRepo?, PrOpResult> {
        if (!isConnected()) return null to PrOpResult.AuthRequired(host)
        val result = api.forkRepo(owner, repo)
        return result.getOrNull() to result.toPrOpResult(host)
    }

    /** Creates a new repo owned by the authenticated user. */
    fun createRepo(name: String, description: String?, isPrivate: Boolean): Pair<GitHubRemoteRepo?, PrOpResult> {
        if (!isConnected()) return null to PrOpResult.AuthRequired(host)
        if (name.isBlank()) return null to PrOpResult.Error("Repo name is required")
        val result = api.createRepo(name.trim(), description, isPrivate)
        return result.getOrNull() to result.toPrOpResult(host)
    }
}
