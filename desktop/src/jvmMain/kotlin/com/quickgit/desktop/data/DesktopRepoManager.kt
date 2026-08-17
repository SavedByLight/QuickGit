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
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
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
        return if (!token.isNullOrBlank()) {
            UsernamePasswordCredentialsProvider(token, "")
        } else null
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
            Result.success(dest)
        } catch (e: Exception) {
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
        open(File(repoPath)).use { git ->
            val add = git.add()
            paths.forEach { add.addFilepattern(it) }
            add.call()
        }
    }

    fun stageAll(repoPath: String) {
        open(File(repoPath)).use { git ->
            git.add().addFilepattern(".").call()
            // Also stage deletions
            val status = git.status().call()
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
                Result.success(commit.name)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---------- Push / Pull ----------

    fun push(repoPath: String, progress: ((String) -> Unit)? = null): Result<Unit> {
        return try {
            open(File(repoPath)).use { git ->
                val remoteUrl = git.repository.config.getString("remote", "origin", "url")
                val cmd = git.push().setRemote("origin")
                credentialsFor(remoteUrl)?.let { cmd.setCredentialsProvider(it) }
                cmd.call()
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun pull(repoPath: String): Result<Unit> {
        return try {
            open(File(repoPath)).use { git ->
                val remoteUrl = git.repository.config.getString("remote", "origin", "url")
                val cmd = git.pull().setRemote("origin")
                credentialsFor(remoteUrl)?.let { cmd.setCredentialsProvider(it) }
                val result = cmd.call()
                if (result.mergeResult?.mergeStatus?.isSuccessful == false) {
                    return Result.failure(IllegalStateException("Merge conflict during pull"))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
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

    // ---------- Branches ----------

    data class BranchInfo(val name: String, val isCurrent: Boolean, val isRemote: Boolean)

    fun listBranches(repoPath: String): List<BranchInfo> {
        open(File(repoPath)).use { git ->
            val current = try { git.repository.branch } catch (_: Exception) { null }
            val locals = git.branchList().call().map {
                BranchInfo(it.name.removePrefix("refs/heads/"), it.name.removePrefix("refs/heads/") == current, false)
            }
            val remotes = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call().map {
                BranchInfo(it.name.removePrefix("refs/remotes/"), false, true)
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

    fun checkout(repoPath: String, branch: String): Result<Unit> {
        return try {
            open(File(repoPath)).use { git ->
                git.checkout().setName(branch).call()
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun deleteBranch(repoPath: String, branch: String, force: Boolean = false): Result<Unit> {
        return try {
            open(File(repoPath)).use { git ->
                git.branchDelete().setBranchNames(branch).setForce(force).call()
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
}
