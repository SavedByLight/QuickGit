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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class RepoManager(private val context: Context, private val credentialStore: CredentialStore) {

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
      val gitDir = File(path, ".git")
      val indexLock = File(gitDir, "index.lock")
         if (indexLock.exists()) {
             indexLock.delete()
         }
         openGit(path).use { git -> git.add().addFilepattern(".").call() }
    }

    fun discardChanges(path: String, files: List<String>) {
       openGit(path).use { git ->
          val cmd = git.checkout()
          files.forEach { cmd.addPath(it) }
          cmd.call()
       }
    }

    // ---------------- Commit & Revert ----------------

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

  fun revertCommit(path: String, commitHash: String): GitOpResult {
     return try {
        openGit(path).use { git ->
           val repo = git.repository
           RevWalk(repo).use { revWalk ->
              val objectId = ObjectId.fromString(commitHash)
              val revCommit = revWalk.parseCommit(objectId)
              git.cherryPick().include(revCommit).setNoCommit(false).call()
           }
        }
        GitOpResult.Success
     } catch (e: GitAPIException) {
        GitOpResult.Error(e.message ?: "Revert commit failed", e)
     } catch (e: Exception) {
        GitOpResult.Error(e.message ?: "Revert commit failed", e)
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

    fun getWorkingDiff(path: String, filePath: String): FileDiff {
      openGit(path).use { git ->
         val diffOut = ByteArrayOutputStream()
         git.diff().setOutputStream(diffOut).setPathFilter(
            org.eclipse.jgit.treewalk.filter.PathFilter.create(filePath)
         ).call()
         return parseUnifiedDiff(filePath, diffOut.toString(Charsets.UTF_8.name()))
         }
    }

    fun getStagedDiff(path: String, filePath: String): FileDiff {
       openGit(path).use { git ->
          val diffOut = ByteArrayOutputStream()
          git.diff().setCached(true).setOutputStream(diffOut).setPathFilter(
             org.eclipse.jgit.treewalk.filter.PathFilter.create(filePath)
          ).call()
          return parseUnifiedDiff(filePath, diffOut.toString(Charsets.UTF_8.name()))
       }
    }

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
         return Triple(stageText(1), stageText(2), stageText(3))
     } finally {
         reader.close()
     }
  }

    fun writeResolvedContent(path: String, filePath: String, content: String) {
       File(path, filePath).writeText(content)
    }

    // ---------------- File browser / editor / upload / rename ----------------

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
      val sample = file.inputStream().use { it.readNBytes(8192) }
      if (sample.any { it == 0.toByte() }) throw IllegalArgumentException("Binary file — cannot edit as text")
      return file.readText(Charsets.UTF_8)
   }

   fun writeTextFile(repoPath: String, relativePath: String, content: String) {
      val file = File(repoPath, relativePath)
      file.parentFile?.mkdirs()
      file.writeText(content, Charsets.UTF_8)
   }

   fun createTextFile(repoPath:String, relativePath: String, initialContent: String = ""): String {
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

  fun uploadFile(repoPath: String, currentPath: String, fileName: String, inputStream: InputStream): GitOpResult {
     return try {
        val targetDir = File(repoPath, currentPath)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val targetFile = File(targetDir, fileName)

        inputStream.use { input ->
            targetFile.outputStream().use { output ->
               input.copyTo(output)
            }
        }

        val gitDir = File(repoPath, ".git")
        val indexLock = File(gitDir, "index.lock")
        if (indexLock.exists()) {
            indexLock.delete()
        }

        openGit(repoPath).use { git ->
            val filePattern = if (currentPath.isEmpty()) fileName else "$currentPath/$fileName"
            git.add().addFilepattern(filePattern).call()
            git.commit().setMessage("Upload $fileName").call()
        }
        GitOpResult.Success
     } catch (e: Exception) {
        GitOpResult.Error(e.message ?: "Failed to upload file", e)
     }
  }

    fun renameFile(repoPath: String, oldPath: String, newPath: String): GitOpResult {
      return try {
         val oldFile = File(repoPath, oldPath)
         val newFile = File(repoPath, newPath)

           newFile.parentFile?.mkdirs()
           val success = oldFile.renameTo(newFile)
           if (!success) {
               return GitOpResult.Error("Failed to rename file on disk from $oldPath to $newPath")
           }

           val gitDir = File(repoPath, ".git")
           val indexLock = File(gitDir, "index.lock")
           if (indexLock.exists()) {
               indexLock.delete()
           }

            openGit(repoPath).use { git ->
               git.rm().addFilepattern(oldPath).call()
               git.add().addFilepattern(newPath).call()
               git.commit().setMessage("Rename $oldPath to $newPath").call()
            }
            GitOpResult.Success
         } catch (e: Exception) {
            GitOpResult.Error(e.message ?: "Failed to rename file", e)
         }
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
       override fun onUpdate(taskName: String, workCurr: Int, totalWork: Int, percentDone: Int, duration: java.time.Duration) {
          onProgress("$taskName: $percentDone%")
       }
       override fun onEndTask(taskName: String, workCurr: Int, duration: java.time.Duration) {}
    }
}
