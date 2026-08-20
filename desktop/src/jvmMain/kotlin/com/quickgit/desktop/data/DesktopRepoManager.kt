package com.quickgit.desktop.data

import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop port of the core RepoManager.
 * Uses standard File IO and stores the repos root under ~/QuickGit by default.
 */
class DesktopRepoManager(
    private val credentialStore: DesktopCredentialStore
) {
    private val TAG = "DesktopRepoManager"
    private val repoLocks = ConcurrentHashMap<String, Any>()

    private val configDir = File(System.getProperty("user.home"), ".config/quickgit").also { it.mkdirs() }
    private val prefsFile = File(configDir, "prefs.properties")
    private val prefs = java.util.Properties().apply {
        if (prefsFile.exists()) prefsFile.inputStream().use { load(it) }
    }

    private fun savePrefs() {
        prefsFile.outputStream().use { prefs.store(it, "QuickGit desktop prefs") }
    }

    // ---------- Root path ----------

    fun getReposRoot(): File {
        val custom = prefs.getProperty("repos_root")
        if (!custom.isNullOrBlank()) {
            val f = File(custom)
            if (f.isDirectory || f.mkdirs()) return f
        }
        val default = File(System.getProperty("user.home"), "QuickGit")
        default.mkdirs()
        return default
    }

    fun setReposRoot(path: String) {
        prefs.setProperty("repos_root", path)
        savePrefs()
    }

    // ---------- Author / signing ----------

    fun getCommitAuthorName(): String =
        credentialStore.getAuthorName()?.takeIf { it.isNotBlank() }
            ?: prefs.getProperty("author_name")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.name") ?: "Desktop User"

    fun getCommitAuthorEmail(): String =
        credentialStore.getAuthorEmail()?.takeIf { it.isNotBlank() }
            ?: prefs.getProperty("author_email")?.takeIf { it.isNotBlank() }
            ?: "${System.getProperty("user.name") ?: "user"}@localhost"

    fun setCommitAuthor(name: String, email: String) {
        credentialStore.setAuthorName(name)
        credentialStore.setAuthorEmail(email)
        prefs.setProperty("author_name", name)
        prefs.setProperty("author_email", email)
        savePrefs()
    }

    fun isGpgSigningEnabled(): Boolean = credentialStore.isGpgSignEnabled()
    fun setGpgSigningEnabled(enabled: Boolean) = credentialStore.setGpgSignEnabled(enabled)

    // ---------- List local repos ----------

    data class LocalRepo(
        val name: String,
        val path: String,
        val branch: String?,
        val isDirty: Boolean,
        val remoteUrl: String?
    )

    fun listLocalRepos(): List<LocalRepo> {
        val root = getReposRoot()
        if (!root.isDirectory) return emptyList()

        val result = mutableListOf<LocalRepo>()
        root.listFiles()?.filter { it.isDirectory && File(it, ".git").exists() }?.forEach { dir ->
            try {
                open(dir).use { git ->
                    val repo = git.repository
                    val branch = try { repo.branch } catch (_: Exception) { null }
                    val dirty = try {
                        !git.status().call().isClean
                    } catch (_: Exception) { false }
                    val remote = try {
                        repo.config.getString("remote", "origin", "url")
                    } catch (_: Exception) { null }
                    result += LocalRepo(dir.name, dir.absolutePath, branch, dirty, remote)
                }
            } catch (e: Exception) {
                System.err.println("$TAG: skip ${dir.name}: ${e.message}")
            }
        }
        // Extra paths
        prefs.getProperty("extra_repo_paths")?.split("|")?.filter { it.isNotBlank() }?.forEach { p ->
            val dir = File(p)
            if (dir.isDirectory && File(dir, ".git").exists()) {
                try {
                    open(dir).use { git ->
                        val repo = git.repository
                        val branch = try { repo.branch } catch (_: Exception) { null }
                        val dirty = try { !git.status().call().isClean } catch (_: Exception) { false }
                        val remote = try { repo.config.getString("remote", "origin", "url") } catch (_: Exception) { null }
                        result += LocalRepo(dir.name, dir.absolutePath, branch, dirty, remote)
                    }
                } catch (_: Exception) { }
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }

    fun addExtraRepoPath(path: String) {
        val current = prefs.getProperty("extra_repo_paths")?.split("|")?.toMutableList() ?: mutableListOf()
        if (path !in current) {
            current += path
            prefs.setProperty("extra_repo_paths", current.joinToString("|"))
            savePrefs()
        }
    }

    /**
     * Create a new local-only git repository under [getReposRoot].
     * Initial branch defaults to `main`. Nothing is pushed until a remote is added.
     * @return absolute path of the new repo on success
     */
    fun initLocalRepo(folderName: String, initialBranch: String = "main"): Result<File> {
        val name = folderName.trim()
        if (name.isBlank()) return Result.failure(IllegalArgumentException("Folder name required"))
        if (name.contains('/') || name.contains('\\') || name.contains("..")) {
            return Result.failure(IllegalArgumentException("Invalid folder name"))
        }
        val branch = initialBranch.trim().ifBlank { "main" }
        val destination = File(getReposRoot(), name)
        if (destination.exists() && (destination.listFiles()?.isNotEmpty() == true || File(destination, ".git").exists())) {
            return Result.failure(IllegalStateException("'$name' already exists under ${getReposRoot().absolutePath}"))
        }
        return try {
            destination.mkdirs()
            Git.init().setDirectory(destination).call().use { git ->
                // Point HEAD at unborn branch (no commits yet)
                val refUpdate = git.repository.updateRef(org.eclipse.jgit.lib.Constants.HEAD)
                refUpdate.link("refs/heads/$branch")
                // Sensible desktop defaults
                val cfg = git.repository.config
                cfg.setBoolean("core", null, "filemode", true)
                cfg.setString("init", null, "defaultBranch", branch)
                cfg.save()
            }
            Result.success(destination)
        } catch (e: Exception) {
            destination.deleteRecursively()
            Result.failure(e)
        }
    }

    // ---------- Open / helpers ----------

    private fun open(dir: File): Git {
        val repo = FileRepositoryBuilder()
            .setGitDir(File(dir, ".git"))
            .readEnvironment()
            .findGitDir()
            .build()
        return Git(repo)
    }

    private fun withLock(path: String, block: () -> Unit) {
        val lock = repoLocks.computeIfAbsent(path) { Any() }
        synchronized(lock) { block() }
    }

    private fun credentialsFor(url: String?): CredentialsProvider? {
        if (url == null) return null
        val host = credentialStore.hostFromRemoteUrl(url) ?: return null
        val token = credentialStore.getHttpsToken(host) ?: credentialStore.getGithubToken()
        if (token.isNullOrBlank()) return null
        // Prefer stored username; fall back to x-access-token (GitHub PAT convention)
        // or the token itself as username (some hosts accept that).
        val user = credentialStore.getHttpsUsername(host)
            ?.takeIf { it.isNotBlank() }
            ?: if (host.contains("github")) "x-access-token" else token
        return UsernamePasswordCredentialsProvider(user, token)
    }

    // ---------- Clone ----------

    data class CloneProgress(val task: String, val completed: Int, val total: Int, val message: String)

    fun cloneRepo(
        url: String,
        targetName: String? = null,
        progress: ((CloneProgress) -> Unit)? = null
    ): Result<File> {
        return try {
            val root = getReposRoot()
            val name = targetName?.takeIf { it.isNotBlank() }
                ?: url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
            val dest = File(root, name)
            if (dest.exists()) {
                return Result.failure(IllegalStateException("Directory already exists: ${dest.absolutePath}"))
            }

            val monitor = object : org.eclipse.jgit.lib.ProgressMonitor {
                override fun start(totalTasks: Int) {}
                override fun beginTask(title: String, totalWork: Int) {
                    progress?.invoke(CloneProgress(title, 0, totalWork, title))
                }
                override fun update(completed: Int) {
                    progress?.invoke(CloneProgress("", completed, -1, ""))
                }
                override fun endTask() {}
                override fun isCancelled() = false
            }

            val cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dest)
                .setProgressMonitor(monitor)
                .setCloneAllBranches(true)

            credentialsFor(url)?.let { cmd.setCredentialsProvider(it) }

            // SSH support
            if (url.startsWith("git@") || url.startsWith("ssh://")) {
                // JGit SSH will use the default identity; advanced key injection can be added later
            }

            cmd.call().close()
            AppLog.i(TAG, "clone OK → ${dest.absolutePath}")
            Result.success(dest)
        } catch (e: Exception) {
            AppLog.e(TAG, "clone failed: $url", e)
            Result.failure(e)
        }
    }

    // ---------- Status / Changes ----------

    data class FileChange(
        val path: String,
        val status: ChangeStatus
    )

    enum class ChangeStatus { STAGED, UNSTAGED, UNTRACKED, CONFLICT }

    fun getStatus(repoPath: String): List<FileChange> {
        val dir = File(repoPath)
        open(dir).use { git ->
            val status = git.status().call()
            val result = mutableListOf<FileChange>()
            status.added.forEach { result += FileChange(it, ChangeStatus.STAGED) }
            status.changed.forEach { result += FileChange(it, ChangeStatus.STAGED) }
            status.removed.forEach { result += FileChange(it, ChangeStatus.STAGED) }
            status.modified.forEach { result += FileChange(it, ChangeStatus.UNSTAGED) }
            status.missing.forEach { result += FileChange(it, ChangeStatus.UNSTAGED) }
            status.untracked.forEach { result += FileChange(it, ChangeStatus.UNTRACKED) }
            status.conflicting.forEach { result += FileChange(it, ChangeStatus.CONFLICT) }
            return result.distinctBy { it.path }.sortedBy { it.path }
        }
    }

    fun stage(repoPath: String, paths: List<String>) {
        val root = File(repoPath)
        paths.forEach { rel ->
            try { LfsSupport.cleanIfNeeded(root, rel) } catch (_: Exception) { /* non-fatal */ }
        }
        open(root).use { git ->
            val add = git.add()
            paths.forEach { add.addFilepattern(it) }
            add.call()
        }
    }

    fun stageAll(repoPath: String) {
        val root = File(repoPath)
        open(root).use { git ->
            val status = git.status().call()
            val candidates = (status.untracked + status.modified + status.changed).distinct()
            candidates.forEach { rel ->
                try { LfsSupport.cleanIfNeeded(root, rel) } catch (_: Exception) { /* non-fatal */ }
            }
            git.add().addFilepattern(".").call()
            // Also stage deletions
            status.missing.forEach { git.rm().addFilepattern(it).call() }
        }
    }

    fun unstage(repoPath: String, paths: List<String>) {
        open(File(repoPath)).use { git ->
            val reset = git.reset()
            paths.forEach { reset.addPath(it) }
            reset.call()
        }
    }

    fun discard(repoPath: String, paths: List<String>) {
        open(File(repoPath)).use { git ->
            git.checkout().addPaths(paths).call()
        }
    }

    // ---------- Commit ----------

    fun commit(repoPath: String, message: String): Result<String> {
        return try {
            open(File(repoPath)).use { git ->
                val author = PersonIdent(getCommitAuthorName(), getCommitAuthorEmail())
                val commit = git.commit()
                    .setMessage(message)
                    .setAuthor(author)
                    .setCommitter(author)
                    .call()
                AppLog.i(TAG, "commit ${commit.name.take(7)} in $repoPath: ${message.take(80)}")
                Result.success(commit.name)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "commit failed in $repoPath", e)
            Result.failure(e)
        }
    }

    // ---------- Push / Pull ----------

    /**
     * Push current branch to origin.
     * - [forceWithLease]: only overwrite remote if it still matches our remote-tracking tip
     *   (same as `git push --force-with-lease`). Wins over [force] if both are true.
     * - [force]: unconditional overwrite (`git push --force`).
     */
    fun push(
        repoPath: String,
        force: Boolean = false,
        forceWithLease: Boolean = false,
        progress: ((String) -> Unit)? = null
    ): Result<Unit> {
        return try {
            open(File(repoPath)).use { git ->
                val remoteUrl = git.repository.config.getString("remote", "origin", "url")
                    ?: return Result.failure(IllegalStateException("No URL configured for remote 'origin'"))
                if (forceWithLease || force) {
                    progress?.invoke("Force pushing…")
                    pushForced(git, remoteUrl, withLease = forceWithLease)
                } else {
                    progress?.invoke("Pushing…")
                    val cmd = git.push().setRemote("origin")
                    credentialsFor(remoteUrl)?.let { cmd.setCredentialsProvider(it) }
                    // Push only the current branch (never all local branches).
                    val branch = try { git.repository.branch } catch (_: Exception) { null }
                    if (branch.isNullOrBlank()) {
                        return Result.failure(IllegalStateException("Detached HEAD — check out a branch before push"))
                    }
                    cmd.setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                    val results = cmd.call()
                    val rejected = results.flatMap { it.remoteUpdates }
                        .filter { it.status.name.contains("REJECTED") || it.status.name.contains("NON_EXISTING") }
                    if (rejected.isNotEmpty()) {
                        val details = rejected.joinToString("; ") {
                            it.message?.takeIf { m -> m.isNotBlank() } ?: (it.status?.name ?: "REJECTED")
                        }
                        AppLog.w(TAG, "push rejected: $details")
                        return Result.failure(IllegalStateException("Push rejected: $details"))
                    }
                    AppLog.i(TAG, "push OK: $repoPath")
                    Result.success(Unit)
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "push failed: $repoPath", e)
            Result.failure(e)
        }
    }

    /**
     * Force / force-with-lease push of the current branch via [RemoteRefUpdate].
     * Lease uses the remote-tracking ref (`refs/remotes/origin/<branch>`) as the expected
     * remote tip — same as `git push --force-with-lease`.
     */
    private fun pushForced(git: Git, remoteUrl: String, withLease: Boolean): Result<Unit> {
        val repo = git.repository
        val branch = repo.branch
            ?: return Result.failure(IllegalStateException("Detached HEAD — check out a branch before force push"))
        val localRef = "refs/heads/$branch"
        val remoteRef = "refs/heads/$branch"
        val trackingRef = "refs/remotes/origin/$branch"
        val localId = repo.resolve(localRef)
            ?: return Result.failure(IllegalStateException("No local branch $branch"))

        val expectedRemoteId: ObjectId? = if (withLease) {
            val id = repo.resolve(trackingRef)
            if (id == null) {
                return Result.failure(
                    IllegalStateException(
                        "No remote-tracking branch origin/$branch — fetch first before force-with-lease"
                    )
                )
            }
            id
        } else {
            null
        }

        if (withLease) {
            AppLog.w(TAG, "push: FORCE-WITH-LEASE expected remote tip ${expectedRemoteId!!.name}")
        } else {
            AppLog.w(TAG, "push: FORCE (no lease)")
        }

        val update = RemoteRefUpdate(
            repo,
            localRef,
            remoteRef,
            /* forceUpdate = */ true,
            trackingRef,
            expectedRemoteId
        )

        Transport.open(repo, remoteUrl).use { transport ->
            credentialsFor(remoteUrl)?.let { transport.credentialsProvider = it }
            if ((remoteUrl.startsWith("git@") || remoteUrl.startsWith("ssh://")) && transport is SshTransport) {
                // Default JGit SSH identity; advanced key injection can be added later
            }
            val result = transport.push(NullProgressMonitor.INSTANCE, listOf(update))
            val rejected = result.remoteUpdates
                .filter { it.status.name.contains("REJECTED") || it.status.name.contains("NON_EXISTING") }
            if (rejected.isNotEmpty()) {
                val statuses = rejected.joinToString { "${it.remoteName}: ${it.status}" }
                AppLog.w(TAG, "force push rejected: $statuses")
                val leaseHint = if (withLease) {
                    "Remote moved since your last fetch — pull/rebase and try again"
                } else {
                    "Force push rejected"
                }
                return Result.failure(IllegalStateException("$leaseHint ($statuses)"))
            }
        }
        AppLog.i(TAG, "push succeeded (${if (withLease) "force-with-lease" else "force"})")
        return Result.success(Unit)
    }

    fun pull(repoPath: String): Result<Unit> {
        return try {
            open(File(repoPath)).use { git ->
                val repo = git.repository
                val remoteUrl = repo.config.getString("remote", "origin", "url")
                val creds = credentialsFor(remoteUrl)
                val localBranch = try { repo.branch } catch (_: Exception) { null }

                // 1) Prefer a normal pull when the remote advertises this branch
                try {
                    val cmd = git.pull().setRemote("origin")
                    creds?.let { cmd.setCredentialsProvider(it) }
                    val result = cmd.call()
                    if (result.mergeResult?.mergeStatus?.isSuccessful == false) {
                        AppLog.w(TAG, "pull merge conflict: $repoPath")
                        return Result.failure(IllegalStateException("Merge conflict during pull"))
                    }
                    AppLog.i(TAG, "pull OK: $repoPath (branch=${localBranch ?: "?"})")
                    return Result.success(Unit)
                } catch (e: org.eclipse.jgit.api.errors.RefNotAdvertisedException) {
                    AppLog.w(
                        TAG,
                        "Remote did not advertise branch '${localBranch ?: "?"}'; " +
                            "fetching and merging remote default (master/main mismatch is common)"
                    )
                }

                // 2) Fallback: fetch everything, then merge origin/HEAD, origin/<local>, main, or master
                val fetch = git.fetch().setRemote("origin").setRemoveDeletedRefs(true)
                creds?.let { fetch.setCredentialsProvider(it) }
                fetch.call()

                val candidates = buildList {
                    // Symbolic origin/HEAD → default branch on remote
                    repo.findRef("refs/remotes/origin/HEAD")?.target?.name?.let { add(it) }
                    if (!localBranch.isNullOrBlank()) add("refs/remotes/origin/$localBranch")
                    add("refs/remotes/origin/main")
                    add("refs/remotes/origin/master")
                }.distinct()

                var mergeBase: org.eclipse.jgit.lib.ObjectId? = null
                var mergedRef: String? = null
                for (refName in candidates) {
                    val id = repo.resolve(refName) ?: continue
                    mergeBase = id
                    mergedRef = refName
                    break
                }
                if (mergeBase == null) {
                    val msg =
                        "Pull failed: remote has no branch matching local " +
                            "'${localBranch ?: "unknown"}' and no origin/main or origin/master after fetch. " +
                            "Check the remote default branch or set upstream (branch.*.merge)."
                    AppLog.e(TAG, msg)
                    return Result.failure(IllegalStateException(msg))
                }

                val mergeResult = git.merge()
                    .include(mergeBase)
                    .setCommit(true)
                    .call()
                if (!mergeResult.mergeStatus.isSuccessful) {
                    AppLog.w(TAG, "pull fallback merge conflict from $mergedRef: ${mergeResult.mergeStatus}")
                    return Result.failure(
                        IllegalStateException("Merge conflict while pulling $mergedRef")
                    )
                }

                // Remember upstream so the next pull works without fallback
                if (!localBranch.isNullOrBlank() && mergedRef != null) {
                    val shortRemote = mergedRef.removePrefix("refs/remotes/origin/")
                    try {
                        val cfg = repo.config
                        cfg.setString("branch", localBranch, "remote", "origin")
                        cfg.setString("branch", localBranch, "merge", "refs/heads/$shortRemote")
                        cfg.save()
                        AppLog.i(TAG, "set upstream $localBranch → origin/$shortRemote")
                    } catch (e: Exception) {
                        AppLog.w(TAG, "could not save upstream: ${e.message}")
                    }
                }

                AppLog.i(TAG, "pull OK (fallback merge $mergedRef): $repoPath")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "pull failed: $repoPath", e)
            Result.failure(e)
        }
    }

    fun fetch(repoPath: String): Result<Unit> {
        return try {
            open(File(repoPath)).use { git ->
                val remoteUrl = git.repository.config.getString("remote", "origin", "url")
                val cmd = git.fetch().setRemote("origin")
                credentialsFor(remoteUrl)?.let { cmd.setCredentialsProvider(it) }
                cmd.call()
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---------- History ----------

    data class CommitInfo(
        val id: String,
        val shortId: String,
        val message: String,
        val author: String,
        val email: String,
        val time: Long
    )

    fun getHistory(repoPath: String, maxCount: Int = 100): List<CommitInfo> {
        open(File(repoPath)).use { git ->
            return git.log().setMaxCount(maxCount).call().map { c ->
                CommitInfo(
                    id = c.name,
                    shortId = c.name.take(7),
                    message = c.shortMessage,
                    author = c.authorIdent.name,
                    email = c.authorIdent.emailAddress,
                    time = c.authorIdent.`when`.time
                )
            }
        }
    }

    data class CommitChange(
        val path: String,
        val changeType: String,
        val oldPath: String? = null
    )

    data class TreeEntry(
        val name: String,
        val relativePath: String,
        val isDirectory: Boolean,
        val sizeBytes: Long = 0L
    )

    fun listCommitChanges(repoPath: String, commitId: String): List<CommitChange> {
        open(File(repoPath)).use { git ->
            val repo = git.repository
            RevWalk(repo).use { walk ->
                val commit = walk.parseCommit(ObjectId.fromString(commitId))
                val newTree = commit.tree
                val oldTree = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)).tree else null
                val reader = repo.newObjectReader()
                try {
                    val newParser = CanonicalTreeParser().apply { reset(reader, newTree) }
                    val oldParser = if (oldTree != null) CanonicalTreeParser().apply { reset(reader, oldTree) } else null
                    DiffFormatter(org.eclipse.jgit.util.io.NullOutputStream.INSTANCE).use { formatter ->
                        formatter.setRepository(repo)
                        formatter.setDetectRenames(true)
                        return formatter.scan(oldParser, newParser).map { d ->
                            val type = when (d.changeType) {
                                org.eclipse.jgit.diff.DiffEntry.ChangeType.ADD -> "ADD"
                                org.eclipse.jgit.diff.DiffEntry.ChangeType.MODIFY -> "MODIFY"
                                org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE -> "DELETE"
                                org.eclipse.jgit.diff.DiffEntry.ChangeType.RENAME -> "RENAME"
                                org.eclipse.jgit.diff.DiffEntry.ChangeType.COPY -> "COPY"
                                else -> d.changeType.name
                            }
                            val pathStr = if (d.changeType == org.eclipse.jgit.diff.DiffEntry.ChangeType.DELETE) d.oldPath else d.newPath
                            CommitChange(
                                path = pathStr,
                                changeType = type,
                                oldPath = if (d.changeType == org.eclipse.jgit.diff.DiffEntry.ChangeType.RENAME ||
                                    d.changeType == org.eclipse.jgit.diff.DiffEntry.ChangeType.COPY) d.oldPath else null
                            )
                        }.sortedBy { it.path.lowercase() }
                    }
                } finally {
                    reader.close()
                }
            }
        }
    }

    fun listTreeAtCommit(repoPath: String, commitId: String, relativeDir: String = ""): List<TreeEntry> {
        open(File(repoPath)).use { git ->
            val repo = git.repository
            RevWalk(repo).use { walk ->
                val commit = walk.parseCommit(ObjectId.fromString(commitId))
                TreeWalk(repo).use { tw ->
                    tw.addTree(commit.tree)
                    tw.isRecursive = false
                    if (relativeDir.isNotBlank()) {
                        val parts = relativeDir.trim('/').split('/')
                        for (part in parts) {
                            var found = false
                            while (tw.next()) {
                                if (tw.isSubtree && tw.nameString == part) {
                                    tw.enterSubtree()
                                    found = true
                                    break
                                }
                            }
                            if (!found) return emptyList()
                        }
                    }
                    val entries = mutableListOf<TreeEntry>()
                    while (tw.next()) {
                        val name = tw.nameString
                        if (name == ".git") continue
                        val rel = if (relativeDir.isBlank()) name else "$relativeDir/$name"
                        val isDir = tw.isSubtree
                        val size = if (!isDir) {
                            try { repo.open(tw.getObjectId(0)).size } catch (_: Exception) { 0L }
                        } else 0L
                        entries.add(TreeEntry(name, rel, isDir, size))
                    }
                    return entries.sortedWith(compareBy<TreeEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
                }
            }
        }
    }

    fun readTextAtCommit(repoPath: String, commitId: String, relativePath: String, maxBytes: Long = 1_500_000L): String {
        open(File(repoPath)).use { git ->
            val repo = git.repository
            RevWalk(repo).use { walk ->
                val commit = walk.parseCommit(ObjectId.fromString(commitId))
                TreeWalk.forPath(repo, relativePath, commit.tree)?.use { tw ->
                    if (tw.isSubtree) return ""
                    val loader = repo.open(tw.getObjectId(0))
                    if (loader.size > maxBytes) {
                        return loader.openStream().use { stream ->
                            val buf = ByteArray(maxBytes.toInt())
                            val n = stream.read(buf)
                            String(buf, 0, n, Charsets.UTF_8) + "\n\n… (truncated)"
                        }
                    }
                    return String(loader.bytes, Charsets.UTF_8)
                } ?: return ""
            }
        }
    }

    fun getParentCommitId(repoPath: String, commitId: String): String? {
        open(File(repoPath)).use { git ->
            val repo = git.repository
            RevWalk(repo).use { walk ->
                val commit = walk.parseCommit(ObjectId.fromString(commitId))
                return if (commit.parentCount > 0) commit.getParent(0).name else null
            }
        }
    }

    /**
     * Cherry-pick [commitHash] onto the current branch.
     * Returns failure with a message that includes conflict paths when the pick conflicts.
     */
    fun cherryPick(repoPath: String, commitHash: String): Result<Unit> {
        return try {
            open(File(repoPath)).use { git ->
                val objectId = git.repository.resolve(commitHash)
                    ?: return Result.failure(IllegalArgumentException("Commit not found: $commitHash"))
                RevWalk(git.repository).use { walk ->
                    val commit = walk.parseCommit(objectId)
                    val result = git.cherryPick().include(commit).call()
                    when (result.status) {
                        org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.OK ->
                            Result.success(Unit)
                        org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.CONFLICTING -> {
                            val conflicts = git.status().call().conflicting.toList().sorted()
                            Result.failure(
                                IllegalStateException(
                                    "Cherry-pick conflict on ${conflicts.joinToString().ifBlank { "unknown paths" }}"
                                )
                            )
                        }
                        else -> Result.failure(
                            IllegalStateException("Could not cherry-pick ${commitHash.take(7)} (${result.status})")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Revert [commitHash] by creating an inverse commit on the current branch.
     * Optional [message] amends the auto-generated revert message when provided.
     */
    fun revertCommit(repoPath: String, commitHash: String, message: String? = null): Result<Unit> {
        return try {
            open(File(repoPath)).use { git ->
                val objectId = git.repository.resolve(commitHash)
                    ?: return Result.failure(IllegalArgumentException("Commit not found: $commitHash"))
                RevWalk(git.repository).use { walk ->
                    val commit = walk.parseCommit(objectId)
                    val reverted = git.revert().include(commit).call()
                    if (reverted != null) {
                        if (!message.isNullOrBlank() && message != reverted.fullMessage.trim()) {
                            git.commit().setAmend(true).setMessage(message).call()
                        }
                        Result.success(Unit)
                    } else {
                        val conflicts = git.status().call().conflicting.toList().sorted()
                        if (conflicts.isNotEmpty()) {
                            Result.failure(
                                IllegalStateException(
                                    "Revert conflict on ${conflicts.joinToString()}"
                                )
                            )
                        } else {
                            Result.failure(IllegalStateException("Could not revert ${commitHash.take(7)}"))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Current local branch short name, or null if detached / unborn. */
    fun currentBranch(repoPath: String): String? {
        return try {
            open(File(repoPath)).use { git ->
                try { git.repository.branch } catch (_: Exception) { null }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- Branches ----------

    /**
     * @param name bare local name (e.g. "main") or remote-tracking name as listed
     *             (e.g. "origin/main")
     * @param upstream for local branches: "origin/main" style tracking target, or null
     */
    data class BranchInfo(
        val name: String,
        val isCurrent: Boolean,
        val isRemote: Boolean,
        val upstream: String? = null
    )

    fun listBranches(repoPath: String): List<BranchInfo> {
        open(File(repoPath)).use { git ->
            val repo = git.repository
            val current = try { repo.branch } catch (_: Exception) { null }
            val cfg = repo.config

            val locals = git.branchList().call().map { ref ->
                val short = ref.name.removePrefix("refs/heads/")
                val remote = cfg.getString("branch", short, "remote")
                val merge = cfg.getString("branch", short, "merge")
                val upstream = when {
                    !remote.isNullOrBlank() && !merge.isNullOrBlank() ->
                        "$remote/${merge.removePrefix("refs/heads/")}"
                    else -> null
                }
                BranchInfo(short, short == current, false, upstream)
            }

            val remotes = git.branchList()
                .setListMode(ListBranchCommand.ListMode.REMOTE)
                .call()
                .map { ref ->
                    BranchInfo(ref.name.removePrefix("refs/remotes/"), false, true, null)
                }

            return locals + remotes
        }
    }

    fun createBranch(repoPath: String, name: String, checkout: Boolean = true): Result<Unit> {
        return try {
            open(File(repoPath)).use { git ->
                git.branchCreate().setName(name).call()
                if (checkout) git.checkout().setName(name).call()
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check out [branch] with full tracking support.
     *
     * [branch] is either a bare local name (e.g. "feature-x") or a remote-tracking
     * name as shown in the branch list (e.g. "origin/feature-x").
     *
     * - Existing local branch → plain checkout (preserves any upstream already set).
     * - Remote-only branch → create local branch that tracks the remote ref
     *   (equivalent to `git checkout -b <short> --track <remote-ref>`).
     * - If a local branch with the same short name already exists, just switch to it.
     */
    fun checkout(repoPath: String, branch: String): Result<Unit> {
        return try {
            open(File(repoPath)).use { git ->
                val repo = git.repository
                val remoteRef = repo.findRef("refs/remotes/$branch")
                val shortName = if (remoteRef != null) branch.substringAfter('/') else branch
                val localRef = repo.findRef("refs/heads/$shortName")

                when {
                    localRef != null -> {
                        git.checkout().setName(shortName).call()
                    }
                    remoteRef != null -> {
                        git.checkout()
                            .setCreateBranch(true)
                            .setName(shortName)
                            .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                            .setStartPoint(remoteRef.name)
                            .call()
                    }
                    else -> {
                        // Fallback: try the name as-is
                        git.checkout().setName(branch).call()
                    }
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Set or clear upstream tracking for a local branch.
     * [upstream] is "origin/main" style, or null/blank to clear.
     */
    fun setUpstream(repoPath: String, branch: String, upstream: String?): Result<Unit> {
        return try {
            open(File(repoPath)).use { git ->
                val repo = git.repository
                val cfg = repo.config
                val short = branch.removePrefix("refs/heads/")
                if (upstream.isNullOrBlank()) {
                    cfg.unset("branch", short, "remote")
                    cfg.unset("branch", short, "merge")
                } else {
                    val remote = upstream.substringBefore('/')
                    val remoteBranch = upstream.substringAfter('/')
                    if (remote.isBlank() || remoteBranch.isBlank()) {
                        return Result.failure(IllegalArgumentException("Upstream must be remote/branch, e.g. origin/main"))
                    }
                    cfg.setString("branch", short, "remote", remote)
                    cfg.setString("branch", short, "merge", "refs/heads/$remoteBranch")
                }
                cfg.save()
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun deleteBranch(repoPath: String, branch: String, force: Boolean = false): Result<Unit> {
        return try {
            open(File(repoPath)).use { git ->
                // Only delete local branch names; strip remote prefix if passed by mistake
                val localName = if (branch.contains('/')) {
                    // Caller might pass short local name only; reject pure remote refs
                    val remoteRef = git.repository.findRef("refs/remotes/$branch")
                    if (remoteRef != null) {
                        return Result.failure(IllegalArgumentException("Cannot delete remote-tracking branch '$branch' from here; use the remote."))
                    }
                    branch
                } else branch
                git.branchDelete().setBranchNames(localName).setForce(force).call()
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---------- Diff ----------

    fun getDiff(repoPath: String, path: String? = null, staged: Boolean = false): String {
        open(File(repoPath)).use { git ->
            val repo = git.repository
            val baos = ByteArrayOutputStream()
            DiffFormatter(baos).use { formatter ->
                formatter.setRepository(repo)
                formatter.setDiffComparator(RawTextComparator.DEFAULT)
                formatter.isDetectRenames = true
                path?.let { formatter.pathFilter = PathFilter.create(it) }

                val head = repo.resolve("HEAD")
                if (head == null) {
                    // New repo, show untracked as empty
                    return "(no commits yet)"
                }

                val oldTree = if (staged) {
                    // staged vs HEAD
                    CanonicalTreeParser().apply {
                        RevWalk(repo).use { walk ->
                            val commit = walk.parseCommit(head)
                            reset(repo.newObjectReader(), commit.tree)
                        }
                    }
                } else {
                    // working tree vs index (or HEAD if clean index)
                    null
                }

                if (staged) {
                    val diffs = git.diff()
                        .setCached(true)
                        .setShowNameAndStatusOnly(false)
                        .call()
                    diffs.forEach { formatter.format(it) }
                } else {
                    val diffs = git.diff().setShowNameAndStatusOnly(false).call()
                    diffs.forEach { formatter.format(it) }
                }
            }
            return baos.toString("UTF-8")
        }
    }

    // ---------- Merge conflicts ----------

    fun getConflictingFiles(repoPath: String): List<String> {
        open(File(repoPath)).use { git ->
            return git.status().call().conflicting.toList().sorted()
        }
    }

    fun resolveOurs(repoPath: String, path: String) {
        open(File(repoPath)).use { git ->
            git.checkout().setStage(org.eclipse.jgit.api.CheckoutCommand.Stage.OURS).addPath(path).call()
            git.add().addFilepattern(path).call()
        }
    }

    fun resolveTheirs(repoPath: String, path: String) {
        open(File(repoPath)).use { git ->
            git.checkout().setStage(org.eclipse.jgit.api.CheckoutCommand.Stage.THEIRS).addPath(path).call()
            git.add().addFilepattern(path).call()
        }
    }

    fun continueMerge(repoPath: String, message: String = "Merge"): Result<String> {
        return commit(repoPath, message)
    }

    fun abortMerge(repoPath: String): Result<Unit> {
        return try {
            open(File(repoPath)).use { git ->
                git.reset().setMode(ResetCommand.ResetType.HARD).call()
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isMerging(repoPath: String): Boolean {
        return try {
            open(File(repoPath)).use { git ->
                git.repository.repositoryState == RepositoryState.MERGING
            }
        } catch (_: Exception) { false }
    }

    // ---------- Remote URL ----------

    fun getRemoteUrl(repoPath: String): String? {
        return try {
            open(File(repoPath)).use { git ->
                git.repository.config.getString("remote", "origin", "url")
            }
        } catch (_: Exception) { null }
    }

    // ---------- Git LFS ----------

    private fun lfsAuth(remoteUrl: String): Pair<String?, String?> {
        val host = credentialStore.hostFromRemoteUrl(remoteUrl)
            ?: remoteUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
        return credentialStore.getHttpsUsername(host) to credentialStore.getHttpsToken(host)
    }

    fun lfsInstall(repoPath: String): Result<String> = try {
        Result.success(LfsSupport.install(File(repoPath)))
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun lfsTrack(repoPath: String, pattern: String): Result<String> = try {
        Result.success(LfsSupport.track(File(repoPath), pattern))
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun lfsUntrack(repoPath: String, pattern: String): Result<String> = try {
        Result.success(LfsSupport.untrack(File(repoPath), pattern))
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun lfsStatus(repoPath: String): Result<LfsSupport.LfsStatus> = try {
        Result.success(LfsSupport.status(File(repoPath)))
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun fetchLfs(repoPath: String, onProgress: (String) -> Unit = {}): Result<String> {
        return try {
            val remoteUrl = getRemoteUrl(repoPath)
                ?: return Result.failure(IllegalStateException("No origin remote"))
            if (!LfsSupport.isSupportedRemote(remoteUrl)) {
                return Result.failure(IllegalStateException("LFS requires an HTTPS remote"))
            }
            val (user, token) = lfsAuth(remoteUrl)
            val result = LfsSupport.fetchAndSmudge(
                repoRoot = File(repoPath),
                remoteUrl = remoteUrl,
                username = user,
                token = token,
                onProgress = onProgress
            )
            AppLog.i(TAG, "fetchLfs: ${result.message}")
            if (result.failed > 0 && result.downloaded == 0 && result.alreadyPresent == 0) {
                Result.failure(IllegalStateException(result.message))
            } else {
                Result.success(result.message)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "fetchLfs failed", e)
            Result.failure(e)
        }
    }

    fun pushLfs(repoPath: String, onProgress: (String) -> Unit = {}): Result<String> {
        return try {
            val remoteUrl = getRemoteUrl(repoPath)
                ?: return Result.failure(IllegalStateException("No origin remote"))
            if (!LfsSupport.isSupportedRemote(remoteUrl)) {
                return Result.failure(IllegalStateException("LFS requires an HTTPS remote"))
            }
            val (user, token) = lfsAuth(remoteUrl)
            val result = LfsSupport.uploadLocalObjects(
                repoRoot = File(repoPath),
                remoteUrl = remoteUrl,
                username = user,
                token = token,
                onProgress = onProgress
            )
            AppLog.i(TAG, "pushLfs: ${result.message}")
            if (result.failed > 0 && result.downloaded == 0) {
                Result.failure(IllegalStateException(result.message))
            } else {
                Result.success(result.message)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "pushLfs failed", e)
            Result.failure(e)
        }
    }

    /** After a successful git pull, download/smudge any LFS pointers (best-effort). */
    fun pullWithLfs(repoPath: String, onProgress: (String) -> Unit = {}): Result<Unit> {
        val pullResult = pull(repoPath)
        if (pullResult.isFailure) return pullResult
        val lfs = fetchLfs(repoPath, onProgress)
        return if (lfs.isFailure) {
            AppLog.w(TAG, "pull OK but LFS: ${lfs.exceptionOrNull()?.message}")
            Result.success(Unit) // git pull succeeded
        } else {
            Result.success(Unit)
        }
    }

    /** Git push then LFS upload (best-effort LFS after successful push). */
    fun pushWithLfs(
        repoPath: String,
        force: Boolean = false,
        forceWithLease: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): Result<Unit> {
        val pushResult = push(repoPath, force = force, forceWithLease = forceWithLease) { onProgress(it) }
        if (pushResult.isFailure) return pushResult
        val lfs = pushLfs(repoPath, onProgress)
        return if (lfs.isFailure) {
            AppLog.w(TAG, "push OK but LFS: ${lfs.exceptionOrNull()?.message}")
            Result.success(Unit)
        } else {
            Result.success(Unit)
        }
    }
}
