package com.quickgit.app.data

import android.content.Context
import android.os.Environment
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
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class RepoManager(private val context: Context, private val credentialStore: CredentialStore) {

    private val TAG = "RepoManager"

    private val repoOperationLocks = ConcurrentHashMap<String, Any>()

    /**
     * Local clones live under Documents/QuickGit so they are visible in the
     * system file manager. Falls back to app-specific external storage if the
     * public Documents tree is not writable (scoped storage / missing permission).
     */
    val reposRoot: File
        get() {
            val publicRoot = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "QuickGit"
            )
            return try {
                if (!publicRoot.exists()) publicRoot.mkdirs()
                if (publicRoot.isDirectory && publicRoot.canWrite()) publicRoot
                else fallbackReposRoot()
            } catch (_: Exception) {
                fallbackReposRoot()
            }
        }

    private fun fallbackReposRoot(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: context.getExternalFilesDir(null)
            ?: context.filesDir
        return File(base, "QuickGit").apply { mkdirs() }
    }

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
        AppLog.i(TAG, "clone: $url -> $folderName")
        return try {
            val cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dest)
                .setProgressMonitor(TextProgress(onProgress))
            applyTransportConfig(cmd, url)
            cmd.call().close()
            AppLog.i(TAG, "clone succeeded: $folderName")
            GitOpResult.Success
        } catch (e: org.eclipse.jgit.api.errors.TransportException) {
            dest.deleteRecursively()
            AppLog.e(TAG, "clone failed (transport): $folderName", e)
            if (isAuthFailure(e)) GitOpResult.AuthRequired(url) else GitOpResult.Error(e.message ?: "Transport error", e)
        } catch (e: Exception) {
            dest.deleteRecursively()
            AppLog.e(TAG, "clone failed: $folderName", e)
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
        AppLog.i(TAG, "stage: $files")
        openGit(path).use { git ->
            // JGit's AddCommand mirrors old git semantics: it happily stages new/modified
            // content but silently skips paths that no longer exist on disk, so deletions
            // never make it into the index. Route those through RmCommand(cached) instead.
            val status = git.status().call()
            val missing = files.filter { it in status.missing || it in status.removed }
            val present = files - missing.toSet()

            if (present.isNotEmpty()) {
                val cmd = git.add()
                present.forEach { cmd.addFilepattern(it) }
                cmd.call()
            }
            if (missing.isNotEmpty()) {
                val cmd = git.rm().setCached(true)
                missing.forEach { cmd.addFilepattern(it) }
                cmd.call()
            }
        }
    }

    fun unstage(path: String, files: List<String>) {
        AppLog.i(TAG, "unstage: $files")
        openGit(path).use { git ->
            val cmd = git.reset()
            files.forEach { cmd.addPath(it) }
            cmd.call()
        }
    }

    fun stageAll(path: String) {
        AppLog.i(TAG, "stageAll: $path")
        openGit(path).use { git ->
            git.add().addFilepattern(".").call()
            // Same deletion gap as stage() above — catch any remaining missing paths.
            val missing = git.status().call().missing
            if (missing.isNotEmpty()) {
                val cmd = git.rm().setCached(true)
                missing.forEach { cmd.addFilepattern(it) }
                cmd.call()
            }
        }
    }

    /**
     * Discards local changes to the given paths, restoring both the working tree and the
     * index entry to match HEAD.
     *
     * This intentionally avoids JGit's `git.checkout().addPath(...)`, which (a) checks out
     * from the INDEX rather than HEAD — so a file with staged changes doesn't actually get
     * reset to HEAD — and (b) runs through DirCacheCheckout's conflict-safety checks, which
     * routinely throw CheckoutConflictException for exactly the "overwrite my local edits"
     * case a discard button is meant to perform (e.g. on app/build.gradle.kts). Reading the
     * blob straight out of the HEAD tree and writing it to disk sidesteps both problems.
     */
    fun discardChanges(path: String, files: List<String>): GitOpResult {
        AppLog.i(TAG, "discard: $files")
        val operationLock = repoOperationLocks.getOrPut(path) { Any() }
        return synchronized(operationLock) {
            try {
                val indexLock = File(path, ".git/index.lock")
                if (indexLock.exists() && !indexLock.delete()) {
                    AppLog.w(TAG, "discard blocked: index.lock present")
                    return@synchronized GitOpResult.Error(
                        "Git index is locked. Close other Git operations and delete .git/index.lock, then retry."
                    )
                }

                openGit(path).use { git ->
                    val repository = git.repository
                    val headTreeId = repository.resolve("HEAD^{tree}")
                        ?: return@synchronized GitOpResult.Error("No HEAD commit to discard to")

                    RevWalk(repository).use { walk ->
                        val headTree = walk.parseTree(headTreeId)
                        files.forEach { filePath ->
                            val outFile = File(path, filePath)
                            TreeWalk.forPath(repository, filePath, headTree)?.use { tw ->
                                val loader = repository.open(tw.getObjectId(0))
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out -> loader.copyTo(out) }
                            } ?: outFile.delete() // didn't exist at HEAD (newly added) — discard removes it
                        }
                    }

                    // Bring the index back in line with HEAD for these paths too, so a
                    // partially-staged file ends up fully clean rather than still staged.
                    val resetCmd = git.reset().setRef("HEAD")
                    files.forEach { resetCmd.addPath(it) }
                    resetCmd.call()
                }
                AppLog.i(TAG, "discard succeeded: $files")
                GitOpResult.Success
            } catch (e: Exception) {
                AppLog.e(TAG, "discard failed: $files", e)
                GitOpResult.Error(e.message ?: "Failed to discard changes", e)
            }
        }
    }

    /** Discards every unstaged/untracked change in the working tree — the bulk version of discardChanges(). */
    fun discardAll(path: String): GitOpResult {
        val status = openGit(path).use { it.status().call() }
        val paths = (status.modified + status.missing + status.untracked + status.conflicting).distinct()
        AppLog.i(TAG, "discardAll: ${paths.size} path(s)")
        if (paths.isEmpty()) return GitOpResult.Success
        return discardChanges(path, paths)
    }

    fun unstageAll(path: String) {
        openGit(path).use { git ->
            val status = git.status().call()
            val staged = (status.added + status.changed + status.removed).distinct()
            AppLog.i(TAG, "unstageAll: ${staged.size} path(s)")
            if (staged.isEmpty()) return
            val cmd = git.reset()
            staged.forEach { cmd.addPath(it) }
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
            AppLog.i(TAG, "commit succeeded: \"${message.take(50)}\"")
            GitOpResult.Success
        } catch (e: GitAPIException) {
            AppLog.e(TAG, "commit failed", e)
            GitOpResult.Error(e.message ?: "Commit failed", e)
        }
    }

    // ---------------- Push / Pull / Fetch ----------------

    fun push(path: String): GitOpResult {
        AppLog.i(TAG, "push: $path")
        return try {
            openGit(path).use { git ->
                val remoteUrl = git.repository.config.getString("remote", "origin", "url") ?: ""
                val cmd = git.push()
                applyTransportConfig(cmd, remoteUrl)
                val results = cmd.call()
                val rejected = results.flatMap { it.remoteUpdates }
                    .filter { it.status.name.contains("REJECTED") }
                if (rejected.isNotEmpty()) {
                    AppLog.w(TAG, "push rejected: ${rejected.size} ref(s)")
                    GitOpResult.Error("Push rejected — pull first (${rejected.size} ref(s) rejected)")
                } else {
                    AppLog.i(TAG, "push succeeded")
                    GitOpResult.Success
                }
            }
        } catch (e: org.eclipse.jgit.api.errors.TransportException) {
            AppLog.e(TAG, "push failed (transport)", e)
            if (isAuthFailure(e)) {
                val url = openGit(path).use { it.repository.config.getString("remote", "origin", "url") } ?: ""
                GitOpResult.AuthRequired(url)
            } else GitOpResult.Error(e.message ?: "Push failed", e)
        } catch (e: Exception) {
            AppLog.e(TAG, "push failed", e)
            GitOpResult.Error(e.message ?: "Push failed", e)
        }
    }

    fun pull(path: String): GitOpResult {
        AppLog.i(TAG, "pull: $path")
        return try {
            openGit(path).use { git ->
                val remoteUrl = git.repository.config.getString("remote", "origin", "url") ?: ""
                val cmd = git.pull()
                applyTransportConfig(cmd, remoteUrl)
                val result = cmd.call()
                when {
                    !result.isSuccessful && result.mergeResult?.mergeStatus == MergeResult.MergeStatus.CONFLICTING -> {
                        val paths = result.mergeResult.conflicts?.keys?.toList() ?: emptyList()
                        AppLog.w(TAG, "pull conflict: ${paths.size} path(s)")
                        GitOpResult.Conflict(paths)
                    }
                    result.mergeResult?.mergeStatus == MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> {
                        AppLog.i(TAG, "pull: already up to date")
                        GitOpResult.UpToDate()
                    }
                    result.isSuccessful -> {
                        AppLog.i(TAG, "pull succeeded")
                        GitOpResult.Success
                    }
                    else -> {
                        AppLog.w(TAG, "pull incomplete: ${result.mergeResult?.mergeStatus}")
                        GitOpResult.Error("Pull did not complete: ${result.mergeResult?.mergeStatus}")
                    }
                }
            }
        } catch (e: org.eclipse.jgit.api.errors.TransportException) {
            AppLog.e(TAG, "pull failed (transport)", e)
            if (isAuthFailure(e)) {
                val url = openGit(path).use { it.repository.config.getString("remote", "origin", "url") } ?: ""
                GitOpResult.AuthRequired(url)
            } else GitOpResult.Error(e.message ?: "Pull failed", e)
        } catch (e: Exception) {
            AppLog.e(TAG, "pull failed", e)
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

    /**
     * Reverts an existing commit by creating a new inverse commit.
     * The repository is left in JGit's normal conflict state when a revert
     * cannot be applied cleanly, allowing the UI to surface the affected paths.
     */
    fun revertCommit(path: String, commitHash: String, message: String? = null): GitOpResult = try {
        AppLog.i(TAG, "revert: $commitHash")
        openGit(path).use { git ->
            val repository = git.repository
            val objectId = repository.resolve(commitHash)
                ?: return GitOpResult.Error("Commit not found: $commitHash")

            RevWalk(repository).use { walk ->
                val commit = walk.parseCommit(objectId)
                val reverted = git.revert().include(commit).call()

                if (reverted != null) {
                    // RevertCommand always generates its own "Revert \"...\"" message and has
                    // no setter for a custom one, so honor a user-edited message by amending.
                    if (!message.isNullOrBlank() && message != reverted.fullMessage.trim()) {
                        git.commit().setAmend(true).setMessage(message).call()
                    }
                    AppLog.i(TAG, "revert succeeded: $commitHash")
                    GitOpResult.Success
                } else {
                    val conflicts = git.status().call().conflicting.toList().sorted()
                    if (conflicts.isNotEmpty()) {
                        AppLog.w(TAG, "revert conflict: $commitHash, ${conflicts.size} path(s)")
                        GitOpResult.Conflict(conflicts)
                    } else {
                        AppLog.w(TAG, "revert produced no change: $commitHash")
                        GitOpResult.Error("Could not revert commit ${commitHash.take(7)}")
                    }
                }
            }
        }
    } catch (e: GitAPIException) {
        AppLog.e(TAG, "revert failed: $commitHash", e)
        GitOpResult.Error(e.message ?: "Revert failed", e)
    } catch (e: Exception) {
        AppLog.e(TAG, "revert failed: $commitHash", e)
        GitOpResult.Error(e.message ?: "Revert failed", e)
    }

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
            val cache = org.eclipse.jgit.dircache.DirCache.read(repo)
            fun stageText(stage: Int): String? {
                val entry = (0 until cache.entryCount)
                    .map { cache.getEntry(it) }
                    .firstOrNull { it.pathString == filePath && it.stage == stage } ?: return null
                val bytes = reader.open(entry.objectId).bytes
                return String(bytes, Charsets.UTF_8)
            }
            return Triple(stageText(1), stageText(2), stageText(3)) // base, ours, theirs
        } finally {
            reader.close()
        }
    }

    fun writeResolvedContent(path: String, filePath: String, content: String) {
        File(path, filePath).writeText(content)
    }


    // ---------------- File browser / editor ----------------

    fun listDirectory(repoPath: String, relativeDir: String = ""): List<RepoEntry> {
        val root = File(repoPath)
        val dir = if (relativeDir.isBlank()) root else File(root, relativeDir)
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.name != ".git" }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .map { f ->
                val rel = if (relativeDir.isBlank()) f.name else "$relativeDir/${f.name}"
                RepoEntry(
                    name = f.name,
                    relativePath = rel,
                    isDirectory = f.isDirectory,
                    sizeBytes = if (f.isFile) f.length() else 0L
                )
            }
    }

    fun readTextFile(repoPath: String, relativePath: String, maxBytes: Long = 1_500_000L): String {
        val file = File(repoPath, relativePath)
        if (!file.isFile) throw IllegalArgumentException("Not a file: $relativePath")
        if (file.length() > maxBytes) throw IllegalArgumentException("File too large to edit in-app (${file.length()} bytes)")
        // Reject obvious binaries by sampling NUL bytes
        val sample = file.inputStream().use { it.readNBytes(8192) }
        if (sample.any { it == 0.toByte() }) throw IllegalArgumentException("Binary file — cannot edit as text")
        return file.readText(Charsets.UTF_8)
    }

    fun writeTextFile(repoPath: String, relativePath: String, content: String) {
        val file = File(repoPath, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    /**
     * Creates a new empty text file (or with optional initial content) under the repo.
     * Parent directories are created as needed. Fails if the path already exists.
     */
    fun createTextFile(repoPath: String, relativePath: String, initialContent: String = ""): String {
        val cleaned = relativePath.trim().trimStart('/').replace("\\", "/")
        if (cleaned.isBlank()) throw IllegalArgumentException("File name is required")
        if (cleaned.contains("..")) throw IllegalArgumentException("Invalid path")
        val file = File(repoPath, cleaned)
        if (file.exists()) throw IllegalArgumentException("Already exists: $cleaned")
        file.parentFile?.mkdirs()
        file.writeText(initialContent, Charsets.UTF_8)
        return cleaned
    }

    fun createDirectory(repoPath: String, relativePath: String): String {
        val cleaned = relativePath.trim().trimStart('/').replace("\\", "/")
        if (cleaned.isBlank()) throw IllegalArgumentException("Folder name is required")
        if (cleaned.contains("..")) throw IllegalArgumentException("Invalid path")
        val dir = File(repoPath, cleaned)
        if (dir.exists()) throw IllegalArgumentException("Already exists: $cleaned")
        if (!dir.mkdirs()) throw IllegalStateException("Could not create folder: $cleaned")
        return cleaned
    }

    /** Resolves a human-readable file name for a content Uri picked via the system file picker. */
    fun displayNameFor(uri: android.net.Uri): String {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
            }
        return (name ?: uri.lastPathSegment ?: "file").substringAfterLast('/')
    }

    /** Whether [fileName] already exists directly under [relativeDir] in the repo. */
    fun fileExists(repoPath: String, relativeDir: String, fileName: String): Boolean {
        val dir = if (relativeDir.isBlank()) File(repoPath) else File(repoPath, relativeDir)
        return File(dir, fileName).exists()
    }

    /**
     * Copies the content behind [uri] (picked from local/device storage) into the repo under
     * [relativeDir] as [fileName]. Fails if the destination already exists unless [overwrite] is
     * true, and refuses to clobber a same-named directory outright either way.
     */
    fun importFile(repoPath: String, relativeDir: String, uri: android.net.Uri, fileName: String, overwrite: Boolean): String {
        val cleanedDir = relativeDir.trim().trimStart('/').replace("\\", "/")
        val cleanedName = fileName.trim().replace("\\", "/").substringAfterLast('/')
        if (cleanedName.isBlank()) throw IllegalArgumentException("File name is required")
        val relative = if (cleanedDir.isBlank()) cleanedName else "$cleanedDir/$cleanedName"
        if (relative.contains("..")) throw IllegalArgumentException("Invalid path")

        val dest = File(repoPath, relative)
        if (dest.isDirectory) throw IllegalArgumentException("A folder named '$cleanedName' already exists")
        if (dest.exists() && !overwrite) throw IllegalArgumentException("Already exists: $cleanedName")

        dest.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not read '$cleanedName'")
        input.use { inStream ->
            dest.outputStream().use { outStream -> inStream.copyTo(outStream) }
        }
        return relative
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
        override fun onUpdate(taskName: String?, workCurr: Int, duration: java.time.Duration?) {
            onProgress("$taskName: $workCurr")
        }
        override fun onUpdate(taskName: String?, workCurr: Int, workTotal: Int, percentDone: Int, duration: java.time.Duration?) {
            onProgress("$taskName: $percentDone%")
        }
        override fun onEndTask(taskName: String?, workCurr: Int, duration: java.time.Duration?) {}
        override fun onEndTask(taskName: String?, workCurr: Int, workTotal: Int, percentDone: Int, duration: java.time.Duration?) {}
    }
}
