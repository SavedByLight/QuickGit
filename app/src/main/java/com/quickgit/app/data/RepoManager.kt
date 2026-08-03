package com.quickgit.app.data

import android.content.Context
import com.quickgit.app.data.models.*
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import java.io.ByteArrayOutputStream
import java.io.File

class RepoManager(private val context: Context, private val credentialStore: CredentialStore) {

    private val reposRoot: File
        get() = File(context.filesDir, "repos").apply { mkdirs() }

    private val sshFactory by lazy { SshSupport.buildSessionFactory(context, credentialStore) }

    // ---------------- Repo discovery / open / clone ----------------

    fun listLocalRepos(): List<RepoInfo> {
        val root = reposRoot
        val dirs = root.listFiles { f -> f.isDirectory && File(f, ".git").exists() } ?: return emptyList()
        return dirs.mapNotNull { runCatching { infoFor(it) }.getOrNull() }
    }

    private fun infoFor(dir: File): RepoInfo {
        Git.open(dir).use { git ->
            val repo = git.repository
            val branch = repo.branch ?: "(detached)"
            val remote = repo.config.getString("remote", "origin", "url")
            val dirty = !git.status().call().isClean
            return RepoInfo(dir.name, dir.absolutePath, branch, remote, dirty)
        }
    }

    fun cloneRepo(
        url: String,
        folderName: String,
        onProgress: (String) -> Unit = {}
    ): GitOpResult {
        val dest = File(reposRoot, folderName)
        if (dest.exists()) return GitOpResult.Error("A repo named '$folderName' already exists locally")
        return try {
            val cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dest)
                .setProgressMonitor(TextProgress(onProgress))
            applyTransportConfig(cmd, url)
            cmd.call().close()
            GitOpResult.Success
        } catch (e: org.eclipse.jgit.api.errors.TransportException) {
            dest.deleteRecursively()
            if (isAuthFailure(e)) GitOpResult.AuthRequired(url) else GitOpResult.Error(e.message ?: "Transport error", e)
        } catch (e: Exception) {
            dest.deleteRecursively()
            GitOpResult.Error(e.message ?: "Clone failed", e)
        }
    }

    fun openGit(path: String): Git = Git.open(File(path))

    // ---------------- Status / staging ----------------

    fun getStatus(path: String): RepoStatus {
        openGit(path).use { git ->
            val s = git.status().call()
            val staged = mutableListOf<FileChange>()
            s.added.forEach { staged += FileChange(it, ChangeType.ADDED, true) }
            s.changed.forEach { staged += FileChange(it, ChangeType.MODIFIED, true) }
            s.removed.forEach { staged += FileChange(it, ChangeType.DELETED, true) }

            val unstaged = mutableListOf<FileChange>()
            s.modified.forEach { unstaged += FileChange(it, ChangeType.MODIFIED, false) }
            s.missing.forEach { unstaged += FileChange(it, ChangeType.DELETED, false) }

            val untracked = s.untracked.map { FileChange(it, ChangeType.UNTRACKED, false) }
            val conflicting = s.conflicting.map { FileChange(it, ChangeType.CONFLICTING, false) }

            return RepoStatus(staged, unstaged, untracked, conflicting)
        }
    }

    fun stage(path: String, files: List<String>) {
        openGit(path).use { git ->
            val cmd = git.add()
            files.forEach { cmd.addFilepattern(it) }
            cmd.call()
        }
    }

    fun unstage(path: String, files: List<String>) {
        openGit(path).use { git ->
            val cmd = git.reset()
            files.forEach { cmd.addPath(it) }
            cmd.call()
        }
    }

    fun stageAll(path: String) {
        openGit(path).use { git -> git.add().addFilepattern(".").call() }
    }

    fun discardChanges(path: String, files: List<String>) {
        openGit(path).use { git ->
            val cmd = git.checkout()
            files.forEach { cmd.addPath(it) }
            cmd.call()
        }
    }

    // ---------------- Commit ----------------

    fun commit(path: String, message: String, authorName: String, authorEmail: String): GitOpResult {
        return try {
            openGit(path).use { git ->
                git.commit()
                    .setMessage(message)
                    .setAuthor(PersonIdent(authorName, authorEmail))
                    .setCommitter(PersonIdent(authorName, authorEmail))
                    .call()
            }
            GitOpResult.Success
        } catch (e: GitAPIException) {
            GitOpResult.Error(e.message ?: "Commit failed", e)
        }
    }

    // ---------------- Push / Pull / Fetch ----------------

    fun push(path: String): GitOpResult {
        return try {
            openGit(path).use { git ->
                val remoteUrl = git.repository.config.getString("remote", "origin", "url") ?: ""
                val cmd = git.push()
                applyTransportConfig(cmd, remoteUrl)
                val results = cmd.call()
                val rejected = results.flatMap { it.remoteUpdates }
                    .filter { it.status.name.contains("REJECTED") }
                if (rejected.isNotEmpty()) {
                    GitOpResult.Error("Push rejected — pull first (${rejected.size} ref(s) rejected)")
                } else {
                    GitOpResult.Success
                }
            }
        } catch (e: org.eclipse.jgit.api.errors.TransportException) {
            if (isAuthFailure(e)) {
                val url = openGit(path).use { it.repository.config.getString("remote", "origin", "url") } ?: ""
                GitOpResult.AuthRequired(url)
            } else GitOpResult.Error(e.message ?: "Push failed", e)
        } catch (e: Exception) {
            GitOpResult.Error(e.message ?: "Push failed", e)
        }
    }

    fun pull(path: String): GitOpResult {
        return try {
            openGit(path).use { git ->
                val remoteUrl = git.repository.config.getString("remote", "origin", "url") ?: ""
                val cmd = git.pull()
                applyTransportConfig(cmd, remoteUrl)
                val result = cmd.call()
                when {
                    !result.isSuccessful && result.mergeResult?.mergeStatus == MergeResult.MergeStatus.CONFLICTING -> {
                        val paths = result.mergeResult.conflicts?.keys?.toList() ?: emptyList()
                        GitOpResult.Conflict(paths)
                    }
                    result.mergeResult?.mergeStatus == MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> GitOpResult.UpToDate()
                    result.isSuccessful -> GitOpResult.Success
                    else -> GitOpResult.Error("Pull did not complete: ${result.mergeResult?.mergeStatus}")
                }
            }
        } catch (e: org.eclipse.jgit.api.errors.TransportException) {
            if (isAuthFailure(e)) {
                val url = openGit(path).use { it.repository.config.getString("remote", "origin", "url") } ?: ""
                GitOpResult.AuthRequired(url)
            } else GitOpResult.Error(e.message ?: "Pull failed", e)
        } catch (e: Exception) {
            GitOpResult.Error(e.message ?: "Pull failed", e)
        }
    }

    // ---------------- Branches ----------------

    fun listBranches(path: String): List<BranchInfo> {
        openGit(path).use { git ->
            val current = git.repository.branch
            val local = git.branchList().call().map {
                BranchInfo(it.name.removePrefix("refs/heads/"), it.name.removePrefix("refs/heads/") == current, false)
            }
            val remote = git.branchList()
                .setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE)
                .call()
                .map { BranchInfo(it.name.removePrefix("refs/remotes/"), false, true) }
            return local + remote
        }
    }

    fun createBranch(path: String, name: String, checkout: Boolean): GitOpResult = try {
        openGit(path).use { git ->
            git.branchCreate().setName(name).call()
            if (checkout) git.checkout().setName(name).call()
        }
        GitOpResult.Success
    } catch (e: GitAPIException) {
        GitOpResult.Error(e.message ?: "Failed to create branch", e)
    }

    fun checkoutBranch(path: String, name: String): GitOpResult = try {
        openGit(path).use { git ->
            val local = git.repository.findRef(name)
            if (local == null) {
                // Track a remote branch under the same short name.
                git.checkout()
                    .setCreateBranch(true)
                    .setName(name)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .setStartPoint("origin/$name")
                    .call()
            } else {
                git.checkout().setName(name).call()
            }
        }
        GitOpResult.Success
    } catch (e: GitAPIException) {
        GitOpResult.Error(e.message ?: "Checkout failed", e)
    }

    fun deleteBranch(path: String, name: String, force: Boolean): GitOpResult = try {
        openGit(path).use { git ->
            val cmd = git.branchDelete().setBranchNames(name)
            if (force) cmd.setForce(true)
            cmd.call()
        }
        GitOpResult.Success
    } catch (e: GitAPIException) {
        GitOpResult.Error(e.message ?: "Delete branch failed", e)
    }

    // ---------------- History ----------------

    fun getLog(path: String, maxCount: Int = 100): List<CommitInfo> {
        openGit(path).use { git ->
            return git.log().setMaxCount(maxCount).call().map { it.toCommitInfo() }
        }
    }

    private fun RevCommit.toCommitInfo() = CommitInfo(
        id = name(),
        shortId = name().take(7),
        message = shortMessage,
        authorName = authorIdent.name,
        authorEmail = authorIdent.emailAddress,
        timeEpochSeconds = commitTime.toLong()
    )

    // ---------------- Diff ----------------

    /** Working-tree diff for a single path (index vs. working tree), unstaged changes. */
    fun getWorkingDiff(path: String, filePath: String): FileDiff {
        openGit(path).use { git ->
            val diffOut = ByteArrayOutputStream()
            git.diff().setOutputStream(diffOut).setPathFilter(
                org.eclipse.jgit.treewalk.filter.PathFilter.create(filePath)
            ).call()
            return parseUnifiedDiff(filePath, diffOut.toString(Charsets.UTF_8.name()))
        }
    }

    /** Staged diff for a single path (HEAD vs. index) — used for the "staged changes" tab. */
    fun getStagedDiff(path: String, filePath: String): FileDiff {
        openGit(path).use { git ->
            val diffOut = ByteArrayOutputStream()
            git.diff().setCached(true).setOutputStream(diffOut).setPathFilter(
                org.eclipse.jgit.treewalk.filter.PathFilter.create(filePath)
            ).call()
            return parseUnifiedDiff(filePath, diffOut.toString(Charsets.UTF_8.name()))
        }
    }

    /** Diff between two commits (or a commit and its parent) for a single path — used in History. */
    fun getCommitDiff(path: String, commitId: String, filePath: String): FileDiff {
        openGit(path).use { git ->
            val repo = git.repository
            RevWalk(repo).use { walk ->
                val commit = walk.parseCommit(ObjectId.fromString(commitId))
                val newTree = commit.tree
                val oldTree = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0)).tree else null

                val out = ByteArrayOutputStream()
                DiffFormatter(out).use { formatter ->
                    formatter.setRepository(repo)
                    formatter.setDiffComparator(RawTextComparator.DEFAULT)
                    formatter.setDetectRenames(true)
                    formatter.pathFilter = org.eclipse.jgit.treewalk.filter.PathFilter.create(filePath)
                    val newParser = CanonicalTreeParser().apply {
                        repo.newObjectReader().use { reset(it, newTree) }
                    }
                    if (oldTree != null) {
                        val oldParser = CanonicalTreeParser().apply {
                            repo.newObjectReader().use { reset(it, oldTree) }
                        }
                        formatter.format(oldParser, newParser)
                    } else {
                        formatter.format(null, newParser)
                    }
                }
                return parseUnifiedDiff(filePath, out.toString(Charsets.UTF_8.name()))
            }
        }
    }

    private fun parseUnifiedDiff(filePath: String, raw: String): FileDiff {
        if (raw.contains("Binary files")) return FileDiff(filePath, emptyList(), isBinary = true)
        val lines = raw.lineSequence().mapNotNull { line ->
            when {
                line.startsWith("+++") || line.startsWith("---") -> DiffLine(DiffLineType.HEADER, line)
                line.startsWith("@@") -> DiffLine(DiffLineType.HEADER, line)
                line.startsWith("+") -> DiffLine(DiffLineType.ADDED, line)
                line.startsWith("-") -> DiffLine(DiffLineType.REMOVED, line)
                line.startsWith("diff ") || line.startsWith("index ") -> null
                else -> DiffLine(DiffLineType.CONTEXT, line)
            }
        }.toList()
        return FileDiff(filePath, lines)
    }

    // ---------------- Merge conflicts ----------------

    /** Marks a conflicted file as resolved by staging the version currently on disk. */
    fun markResolved(path: String, filePath: String) = stage(path, listOf(filePath))

    fun continueMergeAsCommit(path: String, message: String, authorName: String, authorEmail: String): GitOpResult =
        commit(path, message, authorName, authorEmail)

    fun abortMerge(path: String): GitOpResult = try {
        openGit(path).use { git ->
            git.repository.writeMergeCommitMsg(null)
            git.repository.writeMergeHeads(null)
            git.checkout().setAllPaths(true).call()
        }
        GitOpResult.Success
    } catch (e: Exception) {
        GitOpResult.Error(e.message ?: "Abort failed", e)
    }

    /** Returns the three sides (base/ours/theirs) of a conflicted file as raw text, when available. */
    fun getConflictSides(path: String, filePath: String): Triple<String?, String?, String?> {
        val repo = openGit(path).use { it.repository }
        val reader = repo.newObjectReader()
        try {
            org.eclipse.jgit.dircache.DirCache.read(repo).use { cache ->
                fun stageText(stage: Int): String? {
                    val entry = (0 until cache.entryCount)
                        .map { cache.getEntry(it) }
                        .firstOrNull { it.path == filePath && it.stage == stage } ?: return null
                    val bytes = reader.open(entry.objectId).bytes
                    return String(bytes, Charsets.UTF_8)
                }
                return Triple(stageText(1), stageText(2), stageText(3)) // base, ours, theirs
            }
        } finally {
            reader.close()
        }
    }

    fun writeResolvedContent(path: String, filePath: String, content: String) {
        File(path, filePath).writeText(content)
    }

    // ---------------- Transport / auth helpers ----------------

    private fun applyTransportConfig(cmd: org.eclipse.jgit.api.TransportCommand<*, *>, url: String) {
        if (url.startsWith("https://")) {
            val host = CredentialStore.hostOf(url)
            val user = credentialStore.getHttpsUsername(host)
            val token = credentialStore.getHttpsToken(host)
            if (token != null) {
                cmd.setCredentialsProvider(
                    UsernamePasswordCredentialsProvider(user ?: "x-access-token", token)
                )
            }
        } else if (url.startsWith("git@") || url.startsWith("ssh://")) {
            cmd.setTransportConfigCallback { transport ->
                if (transport is SshTransport) {
                    transport.sshSessionFactory = sshFactory
                }
            }
        }
    }

    private fun isAuthFailure(e: Exception): Boolean {
        val msg = e.message ?: return false
        return msg.contains("not authorized", true) ||
            msg.contains("authentication", true) ||
            msg.contains("Auth fail", true) ||
            msg.contains("no CredentialsProvider", true)
    }

    private class TextProgress(val onProgress: (String) -> Unit) : org.eclipse.jgit.lib.BatchingProgressMonitor() {
        override fun onUpdate(taskName: String?, workCurr: Int) { onProgress("$taskName: $workCurr") }
        override fun onUpdate(taskName: String?, workCurr: Int, workTotal: Int, percentDone: Int) {
            onProgress("$taskName: $percentDone%")
        }
        override fun onEndTask(taskName: String?, workCurr: Int) {}
        override fun onEndTask(taskName: String?, workCurr: Int, workTotal: Int, percentDone: Int) {}
    }
}
