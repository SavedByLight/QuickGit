package com.quickgit.app.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.quickgit.app.data.models.*
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
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
    private val PREF_ROOT_PATH = "repos_root_path"
    private val PREF_ROOT_URI = "repos_root_uri"
    private val PREF_EXTRA_REPO_PATHS = "extra_repo_paths"
    private val PREF_AUTHOR_NAME = "commit_author_name"
    private val PREF_AUTHOR_EMAIL = "commit_author_email"
    private val PREF_GPG_SIGN = "gpg_sign_commits"
    private val PREF_SIGN_OFF = "commit_sign_off"

    private val repoOperationLocks = ConcurrentHashMap<String, Any>()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("quickgit_prefs", Context.MODE_PRIVATE)

    init {
        installJGitMemoryLimits()
    }

    /**
     * Cap JGit's pack window cache so large clones/fetches are less likely to blow the
     * Android heap (default packed-git limits are desktop-sized).
     *
     * "Inflater has been closed" on Android is usually WindowCache eviction / SoftReference
     * GC recycling a window while pack inflation is still in progress, or mmap issues.
     * We disable mmap, keep a moderate packed limit, and avoid the pure-streaming path for
     * mid-size objects.
     */
    private fun installJGitMemoryLimits() {
        try {
            val cfg = WindowCacheConfig()
            // Cap total packed-git windows. Higher = fewer mid-read evictions on large packs;
            // still bounded so a single clone cannot exhaust a mid-range phone heap.
            cfg.packedGitLimit = 64L * 1024 * 1024
            // Smaller windows = finer-grained caching; pairs better with a higher limit.
            cfg.packedGitWindowSize = 16 * 1024
            cfg.deltaBaseCacheLimit = 8 * 1024 * 1024
            // Prefer loading objects into windows rather than pure streaming where possible.
            // Streaming + concurrent SoftRef GC is a common "Inflater has been closed" source.
            cfg.streamFileThreshold = 20 * 1024 * 1024
            // mmap of pack files is unreliable on many Android devices / filesystems.
            // Use the Java setter — the field itself is private in WindowCacheConfig.
            cfg.setPackedGitMMAP(false)
            cfg.install()
            AppLog.i(TAG, "JGit WindowCache limits installed for mobile heap")
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not install JGit WindowCache config: ${e.message}")
        }
    }

    /** True when the exception looks like the Android/JGit Inflater + WindowCache race. */
    private fun isInflaterRace(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val msg = t.message.orEmpty()
            if (msg.contains("Inflater has been closed", ignoreCase = true)) return true
            if (msg.contains("Inflater", ignoreCase = true) && msg.contains("closed", ignoreCase = true)) return true
            t = t.cause
        }
        return false
    }

    /**
     * JGit's LockFile.commit() does one atomic rename (config.lock -> config, etc.) and, on
     * Unix/Android (FS_POSIX.retryFailedLockFileCommit() == false), does NOT retry on failure —
     * unlike Windows, which retries up to 9x to dodge transient AV/indexer locks. This is usually
     * transient OEM storage-stack flakiness, retryable at the clone-attempt level. But on some
     * devices (MIUI in particular) the underlying public/shared-storage path is FUSE-emulated and
     * doesn't support atomic rename reliably at all, so the same commit fails on every attempt —
     * see [cloneRepo], which now clones into app-private storage first and moves the finished
     * repo into place, avoiding lock-file commits on that kind of path entirely.
     */
    private fun isLockFileCommitRace(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val msg = t.message.orEmpty()
            if (msg.contains("Cannot commit write to", ignoreCase = true)) return true
            if (msg.contains("LockFailedException", ignoreCase = true)) return true
            t = t.cause
        }
        return false
    }

    /** Removes any *.lock files JGit may have left in .git after a failed atomic rename. */
    private fun clearStaleGitLockFiles(destination: File) {
        val dotGit = File(destination, ".git")
        if (!dotGit.isDirectory) return
        runCatching {
            dotGit.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".lock") }
                .forEach { lock ->
                    val deleted = lock.delete()
                    AppLog.w(TAG, "clearStaleGitLockFiles: ${if (deleted) "removed" else "failed to remove"} ${lock.absolutePath}")
                }
        }.onFailure { AppLog.w(TAG, "clearStaleGitLockFiles failed: ${it.message}") }
    }

    /** Serializes JGit pack access — concurrent clones can close each other's Inflater. */
    private val jgitIoLock = Any()

    /** Name used for commit author/committer (Settings → Commit identity). */
    fun getCommitAuthorName(): String =
        prefs.getString(PREF_AUTHOR_NAME, null)?.takeIf { it.isNotBlank() } ?: "Mobile User"

    fun getCommitAuthorEmail(): String =
        prefs.getString(PREF_AUTHOR_EMAIL, null)?.takeIf { it.isNotBlank() } ?: "mobile@example.com"

    fun setCommitAuthor(name: String, email: String) {
        prefs.edit()
            .putString(PREF_AUTHOR_NAME, name.trim())
            .putString(PREF_AUTHOR_EMAIL, email.trim())
            .commit()
    }

    fun isGpgSigningEnabled(): Boolean = prefs.getBoolean(PREF_GPG_SIGN, false)

    fun setGpgSigningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_GPG_SIGN, enabled).commit()
    }

    /** When true, commits get a `Signed-off-by: Name <email>` trailer (git commit -s). */
    fun isSignOffEnabled(): Boolean = prefs.getBoolean(PREF_SIGN_OFF, false)

    fun setSignOffEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_SIGN_OFF, enabled).commit()
    }

    /**
     * Fills author identity from a connected GitHub profile when the user has not set one yet
     * (still on the placeholder Mobile User defaults), or when [force] is true.
     */
    fun seedCommitAuthorFromGitHub(login: String, displayName: String?, emailFromApi: String?, force: Boolean = false) {
        val currentName = prefs.getString(PREF_AUTHOR_NAME, null)
        val currentEmail = prefs.getString(PREF_AUTHOR_EMAIL, null)
        val stillDefault = currentName.isNullOrBlank() || currentName == "Mobile User"
        val emailStillDefault = currentEmail.isNullOrBlank() || currentEmail == "mobile@example.com"
        if (!force && !stillDefault && !emailStillDefault) return
        val name = when {
            force || stillDefault -> displayName?.takeIf { it.isNotBlank() } ?: login
            else -> currentName!!
        }
        val email = when {
            force || emailStillDefault ->
                emailFromApi?.takeIf { it.isNotBlank() } ?: "$login@users.noreply.github.com"
            else -> currentEmail!!
        }
        setCommitAuthor(name, email)
        AppLog.i(TAG, "commit author seeded from GitHub: $name <$email>")
    }

    /**
     * Local clones live under Documents/QuickGit so they are visible in the
     * system file manager. Falls back to app-specific external storage if the
     * public Documents tree isn't actually writable (scoped storage on API 30+,
     * or missing permission below that) — see [resolveReposRoot].
     *
     * The user can override this from Settings by picking a folder via SAF
     * (see [setReposRootFromTree]); that choice is what actually fixes the
     * "external file manager can't reach the repo" problem, since it lets the
     * user point QuickGit at a folder they can *also* browse to directly.
     *
     * Both the override and the auto-detected default are resolved once and
     * cached — re-checking on every access let the two auto-detected roots
     * flip mid-session (canWrite() is racy under scoped storage), which made
     * JGit's index see files as missing right after a resolve, showing up in
     * the UI as every file being "deleted". A real probe write, done once,
     * avoids both problems.
     */
    var reposRoot: File = readStoredOverride() ?: resolveReposRoot()
        private set

    /**
     * Whether [reposRoot] is currently a user-picked folder (Settings) rather
     * than the auto-detected default. Shown in the UI so users understand
     * which folder to point their file manager at.
     */
    val reposRootIsUserChosen: Boolean
        get() = prefs.contains(PREF_ROOT_PATH)

    private fun readStoredOverride(): File? {
        val storedPath = prefs.getString(PREF_ROOT_PATH, null) ?: return null
        val dir = File(storedPath)
        // The user could have moved the SD card, revoked access, etc. since
        // this was picked — fall back to the default rather than silently
        // failing every git operation if it's no longer usable.
        val stillUsable = dir.isDirectory && canActuallyWrite(dir)
        if (!stillUsable) {
            AppLog.w(TAG, "stored reposRoot override no longer usable: $storedPath — reverting to default")
            prefs.edit().remove(PREF_ROOT_PATH).remove(PREF_ROOT_URI).apply()
            return null
        }
        return dir
    }

    /**
     * Result of attempting to point [reposRoot] at a SAF-picked tree.
     */
    sealed class SetReposRootResult {
        data class Success(val path: File) : SetReposRootResult()
        data class Error(val message: String) : SetReposRootResult()
    }

    /**
     * Points [reposRoot] at a folder the user picked via
     * `ActivityResultContracts.OpenDocumentTree()`.
     *
     * JGit needs a real filesystem path — it can't open a repo directly
     * against a `content://` tree, and SAF trees aren't guaranteed to have
     * one at all (a folder in Google Drive, for instance, has no local path).
     * So this only succeeds when the picked tree resolves to somewhere on
     * local device storage (internal or an SD card); anything else is
     * rejected with an explanation rather than silently misbehaving later.
     */
    fun setReposRootFromTree(treeUri: Uri): SetReposRootResult {
        val resolved = filePathForTreeUri(treeUri)
            ?: return SetReposRootResult.Error(
                "That folder isn't on local device storage, so QuickGit can't use it directly " +
                    "(this happens with cloud-backed providers like Drive). Pick a folder on your " +
                    "phone's internal storage or an SD card instead."
            )
        try {
            resolved.mkdirs()
        } catch (_: Exception) {
            // fall through to the write probe below, which will report the real failure
        }
        if (!resolved.isDirectory || !canActuallyWrite(resolved)) {
            return SetReposRootResult.Error(
                "QuickGit can't write to that folder. Try a different one, or a subfolder of it."
            )
        }
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "could not persist URI permission for $treeUri: ${e.message}")
        }
        prefs.edit()
            .putString(PREF_ROOT_PATH, resolved.absolutePath)
            .putString(PREF_ROOT_URI, treeUri.toString())
            .apply()
        reposRoot = resolved
        AppLog.i(TAG, "reposRoot set from user-picked folder: ${resolved.absolutePath}")
        return SetReposRootResult.Success(resolved)
    }

    /** Reverts to the auto-detected default location, clearing any user-picked folder. */
    fun resetReposRootToDefault() {
        prefs.edit().remove(PREF_ROOT_PATH).remove(PREF_ROOT_URI).apply()
        reposRoot = resolveReposRoot()
    }

    /**
     * Best-effort conversion of a SAF tree URI to a real filesystem path.
     * Only works for the local "primary" volume (internal storage) and
     * physical SD cards exposed by Android's ExternalStorageProvider — other
     * providers (Drive, OneDrive, other apps' document providers) have no
     * such path and return null.
     */
    private fun filePathForTreeUri(treeUri: Uri): File? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return null
            val (volume, relativePath) = parts
            when {
                volume.equals("primary", ignoreCase = true) ->
                    File(Environment.getExternalStorageDirectory(), relativePath)
                volume.isNotBlank() ->
                    // Physical SD card: Android exposes these under /storage/<volume-id>/
                    File("/storage/$volume", relativePath)
                else -> null
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "could not resolve tree uri to path: $treeUri: ${e.message}")
            null
        }
    }

    /**
     * Result of resolving a SAF-picked folder as a clone destination.
     */
    sealed class ResolveCloneDestinationResult {
        data class Success(val path: File) : ResolveCloneDestinationResult()
        data class Error(val message: String) : ResolveCloneDestinationResult()
    }

    /**
     * Validates a folder the user picked (via `ActivityResultContracts.OpenDocumentTree()`,
     * typically after using the picker's own "New folder" action) as a `git clone` destination.
     * Same local-storage-only restriction as [setReposRootFromTree] — JGit needs a real path.
     */
    fun resolveCloneDestination(treeUri: Uri): ResolveCloneDestinationResult {
        val resolved = filePathForTreeUri(treeUri)
            ?: return ResolveCloneDestinationResult.Error(
                "That folder isn't on local device storage, so QuickGit can't clone into it directly " +
                    "(this happens with cloud-backed providers like Drive). Pick a folder on your " +
                    "phone's internal storage or an SD card instead."
            )
        if (resolved.exists() && resolved.listFiles()?.isNotEmpty() == true) {
            return ResolveCloneDestinationResult.Error("'${resolved.name}' isn't empty — pick an empty or new folder.")
        }
        resolved.mkdirs()
        if (!resolved.isDirectory || !canActuallyWrite(resolved)) {
            return ResolveCloneDestinationResult.Error("QuickGit can't write to that folder.")
        }
        return ResolveCloneDestinationResult.Success(resolved)
    }

    /**
     * Tracks a clone destination that lives outside [reposRoot] so [listLocalRepos] still finds
     * it — the repo list otherwise only scans directly under reposRoot.
     */
    private fun rememberExternalRepoPath(dir: File) {
        val root = runCatching { reposRoot.canonicalFile }.getOrElse { reposRoot.absoluteFile }
        val d = runCatching { dir.canonicalFile }.getOrElse { dir.absoluteFile }
        if (d.absolutePath == root.absolutePath || d.absolutePath.startsWith(root.absolutePath + File.separator)) {
            AppLog.i(TAG, "rememberExternalRepoPath: under reposRoot, no extra tracking needed: ${d.absolutePath}")
            return // already discoverable via the normal reposRoot scan
        }
        // Copy the set — SharedPreferences may return its live internal instance.
        val current = (prefs.getStringSet(PREF_EXTRA_REPO_PATHS, emptySet()) ?: emptySet()).toMutableSet()
        if (current.add(d.absolutePath)) {
            // commit() so listLocalRepos sees the path immediately after a successful clone
            prefs.edit().putStringSet(PREF_EXTRA_REPO_PATHS, current).commit()
            AppLog.i(TAG, "rememberExternalRepoPath: tracking ${d.absolutePath}")
        }
    }

    private fun forgetExternalRepoPath(dir: File) {
        val d = runCatching { dir.canonicalFile }.getOrElse { dir.absoluteFile }
        val current = (prefs.getStringSet(PREF_EXTRA_REPO_PATHS, emptySet()) ?: emptySet()).toMutableSet()
        if (current.remove(d.absolutePath)) {
            prefs.edit().putStringSet(PREF_EXTRA_REPO_PATHS, current).commit()
            AppLog.i(TAG, "forgetExternalRepoPath: stopped tracking ${d.absolutePath}")
        }
    }

    /** True when [dir] is tracked outside [reposRoot] (imported / clone-to-folder). */
    fun isExternalRepo(dir: File): Boolean {
        val d = runCatching { dir.canonicalFile }.getOrElse { dir.absoluteFile }
        val extras = prefs.getStringSet(PREF_EXTRA_REPO_PATHS, emptySet()) ?: emptySet()
        return extras.any { path ->
            val e = runCatching { File(path).canonicalFile }.getOrElse { File(path).absoluteFile }
            e.absolutePath == d.absolutePath
        }
    }

    sealed class ImportRepoResult {
        data class Success(val path: File, val name: String) : ImportRepoResult()
        data class Error(val message: String) : ImportRepoResult()
    }

    /**
     * Registers an existing local Git repository (folder containing `.git`) so it
     * appears in the repo list. Used when the user already has a clone on disk
     * (Termux, another app, PC sync, etc.) and wants QuickGit to manage it.
     *
     * [treeUri] is a SAF tree from `OpenDocumentTree`. The folder must resolve to
     * a real path on device storage (same limitation as [setReposRootFromTree]).
     */
    fun importLocalRepoFromTree(treeUri: Uri): ImportRepoResult {
        val resolved = filePathForTreeUri(treeUri)
            ?: return ImportRepoResult.Error(
                "That folder isn't on local device storage, so QuickGit can't open it " +
                    "(this happens with cloud-backed providers like Drive). Pick a folder " +
                    "on your phone's internal storage or an SD card."
            )
        return importLocalRepo(resolved, treeUri)
    }

    /**
     * Same as [importLocalRepoFromTree] when you already have a filesystem [dir].
     * Optionally pass [treeUri] so read/write access is persisted.
     */
    fun importLocalRepo(dir: File, treeUri: Uri? = null): ImportRepoResult {
        val resolved = runCatching { dir.canonicalFile }.getOrElse { dir.absoluteFile }
        if (!resolved.isDirectory) {
            return ImportRepoResult.Error("Not a folder: ${resolved.absolutePath}")
        }
        val gitDir = File(resolved, ".git")
        if (!gitDir.exists()) {
            return ImportRepoResult.Error(
                "'${resolved.name}' is not a Git repository (no .git folder). " +
                    "Pick the repo root — the folder that contains .git."
            )
        }
        // Already listed?
        val already = listLocalRepos().any {
            runCatching { File(it.localPath).canonicalPath }.getOrDefault(it.localPath) ==
                resolved.absolutePath
        }
        if (already) {
            return ImportRepoResult.Error("'${resolved.name}' is already in your repo list.")
        }
        try {
            // Verify JGit can open it before we track it.
            Git.open(resolved).use { /* ok */ }
        } catch (e: Exception) {
            AppLog.e(TAG, "importLocalRepo: cannot open ${resolved.absolutePath}", e)
            return ImportRepoResult.Error(
                "Couldn't open that Git repository: ${e.message ?: "unknown error"}"
            )
        }
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                AppLog.w(TAG, "importLocalRepo: could not persist URI permission: ${e.message}")
            }
        }
        rememberExternalRepoPath(resolved)
        // Under reposRoot the scan finds it without extra tracking; still apply mobile config.
        ensureMobileRepoConfig(resolved.absolutePath)
        AppLog.i(TAG, "importLocalRepo: ${resolved.absolutePath}")
        return ImportRepoResult.Success(resolved, resolved.name)
    }

    /**
     * Removes a repo from the list. Clones under [reposRoot] are deleted from disk.
     * Imported / external paths are only untracked (files left on disk).
     */
    fun removeFromList(dir: File): GitOpResult {
        return try {
            val resolved = runCatching { dir.canonicalFile }.getOrElse { dir.absoluteFile }
            val root = runCatching { reposRoot.canonicalFile }.getOrElse { reposRoot.absoluteFile }
            val underRoot = resolved.absolutePath == root.absolutePath ||
                resolved.absolutePath.startsWith(root.absolutePath + File.separator)
            if (underRoot) {
                if (!resolved.deleteRecursively()) {
                    return GitOpResult.Error("Could not delete ${resolved.name}")
                }
            } else {
                forgetExternalRepoPath(resolved)
            }
            GitOpResult.Success
        } catch (e: Exception) {
            AppLog.e(TAG, "removeFromList failed", e)
            GitOpResult.Error(e.message ?: "Could not remove repository", e)
        }
    }

    private fun resolveReposRoot(): File {
        val publicRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "QuickGit"
        )
        val usablePublic = try {
            if (!publicRoot.exists() && !publicRoot.mkdirs()) false
            else publicRoot.isDirectory && canActuallyWrite(publicRoot)
        } catch (_: Exception) {
            false
        }
        AppLog.i(TAG, "reposRoot: public Documents/QuickGit usable=$usablePublic")
        return if (usablePublic) publicRoot else fallbackReposRoot()
    }

    /**
     * File.canWrite() reads POSIX permission bits, not scoped-storage
     * enforcement, so it can report true on API 29+ even when writes will
     * actually fail. Do a real probe write+delete instead.
     */
    private fun canActuallyWrite(dir: File): Boolean {
        val probe = File(dir, ".quickgit_write_probe")
        return try {
            FileOutputStream(probe).use { it.write(1) }
            probe.delete()
            true
        } catch (_: Exception) {
            probe.delete()
            false
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
        // Ensure the root directory exists so listFiles doesn't return null after a fresh install.
        if (!root.exists()) root.mkdirs()
        val rootDirs = root.listFiles { f -> f.isDirectory && File(f, ".git").exists() }?.toList() ?: emptyList()

        val extraPaths = (prefs.getStringSet(PREF_EXTRA_REPO_PATHS, emptySet()) ?: emptySet()).toSet()
        val extraDirs = extraPaths.map { File(it) }.filter { it.isDirectory && File(it, ".git").exists() }
        if (extraDirs.size != extraPaths.size) {
            // Self-heal: a picked folder was deleted/moved elsewhere — stop tracking it.
            prefs.edit().putStringSet(PREF_EXTRA_REPO_PATHS, extraDirs.map { it.absolutePath }.toSet()).commit()
        }

        val allDirs = (rootDirs + extraDirs).distinctBy {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
        }
        AppLog.i(TAG, "listLocalRepos: root=${root.absolutePath} found=${allDirs.size} (root=${rootDirs.size}, extra=${extraDirs.size})")
        return allDirs.mapNotNull { dir ->
            runCatching { infoFor(dir) }.onFailure { e ->
                AppLog.w(TAG, "listLocalRepos: skip ${dir.absolutePath}: ${e.message}")
            }.getOrNull()
        }
    }

    private fun infoFor(dir: File): RepoInfo {
        ensureMobileRepoConfig(dir.absolutePath)
        Git.open(dir).use { git ->
            val repo = git.repository
            val branch = repo.branch ?: "(detached)"
            val remote = repo.config.getString("remote", "origin", "url")
            val dirty = !git.status().call().isClean
            return RepoInfo(dir.name, dir.absolutePath, branch, remote, dirty)
        }
    }

    /** Clones into a new named subfolder of [reposRoot] — the common case (typed folder name). */
    fun cloneRepo(
        url: String,
        folderName: String,
        depth: Int = 1,
        onProgress: (String) -> Unit = {}
    ): GitOpResult = cloneRepo(url, File(reposRoot, folderName), depth, onProgress)

    /** Clones into an explicit [destination], e.g. one the user picked via the system file manager. */
    fun cloneRepo(
        url: String,
        destination: File,
        depth: Int = 1,
        onProgress: (String) -> Unit = {}
    ): GitOpResult {
        val label = destination.name
        if (destination.exists() && destination.listFiles()?.isNotEmpty() == true) {
            return GitOpResult.Error("'$label' already exists and isn't empty")
        }
        val alreadyExisted = destination.exists()
        val cloneUrl = normalizeCloneUrl(url)

        // Clone into app-private storage first, then move the finished repo into place as a
        // single step. Public/shared storage (Documents/... etc.) goes through Android's
        // FUSE-based scoped-storage emulation on many OEM stacks (MIUI in particular): plain
        // writes succeed, but the atomic write-lock-file-then-rename pattern JGit uses for
        // every commit of config/refs/HEAD is not reliably atomic there — clones can fail
        // deterministically (not just flakily) with "Cannot commit write to .../.git/config".
        // App-private storage (context.filesDir) is a real filesystem with normal POSIX
        // semantics, so all of JGit's lock-file commits happen safely there; only a plain
        // file copy (no atomic renames) touches the public path, at the very end.
        val staging = newStagingDir(label)
        AppLog.i(TAG, "clone: $cloneUrl -> staging ${staging.absolutePath} (final: ${destination.absolutePath})")

        val result = cloneIntoStaging(cloneUrl, staging, depth, onProgress)
        if (result !is GitOpResult.Success) {
            staging.deleteRecursively()
            return result
        }

        onProgress("Moving repository into place…")
        return try {
            moveDirectory(staging, destination)
            rememberExternalRepoPath(destination)
            AppLog.i(TAG, "clone: moved staged clone into place: ${destination.absolutePath}")
            GitOpResult.Success
        } catch (e: Exception) {
            AppLog.e(TAG, "clone: failed to move staged clone into place: ${destination.absolutePath}", e)
            staging.deleteRecursively()
            if (!alreadyExisted) destination.deleteRecursively()
            GitOpResult.Error("Clone succeeded but couldn't move it into place: ${e.message}", e)
        }
    }

    /** Creates a fresh, empty app-private directory to clone into before the final move. */
    private fun newStagingDir(label: String): File {
        val root = File(context.filesDir, "clone_staging").apply { mkdirs() }
        val safeLabel = label.ifBlank { "repo" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var dir = File(root, "$safeLabel-${System.currentTimeMillis()}")
        var suffix = 0
        while (dir.exists()) {
            suffix++
            dir = File(root, "$safeLabel-${System.currentTimeMillis()}-$suffix")
        }
        dir.mkdirs()
        return dir
    }

    /**
     * Moves [src] into [dst]. Tries a plain rename first (fast path, works if both happen to be
     * on the same volume); falls back to a recursive copy + delete for the common case of moving
     * from app-private storage to public/shared storage, which are different volumes. The copy
     * step is plain file writes (no lock-file atomic renames), so it's safe on flaky
     * scoped-storage stacks even though a live JGit clone there is not — but file creation itself
     * can still spuriously fail there (some devices' scoped-storage FUSE/MediaProvider layer has
     * known bugs indexing dot-prefixed hidden files/folders like `.git`, throwing EEXIST for a
     * path that doesn't actually exist yet), so each file copy is retried a few times.
     */
    private fun moveDirectory(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return
        dst.mkdirs()
        src.walkTopDown().forEach { file ->
            val relative = file.relativeTo(src).path
            val target = if (relative.isEmpty()) dst else File(dst, relative)
            if (file.isDirectory) {
                target.mkdirs()
            } else {
                copyFileWithRetry(file, target)
            }
        }
        src.deleteRecursively()
    }

    /**
     * Copies [src] to [dst], retrying on failure. Public/shared storage on some devices spuriously
     * throws EEXIST (or similar) creating a file that doesn't actually exist yet — a known
     * scoped-storage FUSE/MediaProvider quirk, worse for dot-prefixed paths like `.git/...`.
     *
     * We deliberately force-overwrite: try a normal delete first, but if the file still exists
     * (or delete is denied) we open it for write and stream the content over the top. That way
     * an existing destination file never aborts the staged-clone → Documents move.
     */
    private fun copyFileWithRetry(src: File, dst: File, maxAttempts: Int = 5) {
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                dst.parentFile?.mkdirs()
                // Prefer a clean slate, but do not fail if delete is refused.
                if (dst.exists()) {
                    if (!dst.delete()) {
                        AppLog.w(TAG, "copyFileWithRetry: couldn't clear existing $dst before copy (attempt $attempt/$maxAttempts) — will force-overwrite")
                    }
                }
                // Stream overwrite: works even when delete failed or the path is partially
                // visible to MediaProvider. Truncates/replaces any residual content.
                src.inputStream().use { input ->
                    dst.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return
            } catch (e: Exception) {
                lastError = e
                AppLog.w(TAG, "copyFileWithRetry: attempt $attempt/$maxAttempts failed for $dst: ${e.message}")
                if (attempt < maxAttempts) {
                    try { Thread.sleep(150L * attempt) } catch (_: InterruptedException) {}
                }
            }
        }
        throw lastError ?: java.io.IOException("Failed to copy $src to $dst")
    }

    /**
     * Does the actual JGit clone + checkout + LFS fetch into [destination], which the caller
     * guarantees is a fresh, empty, app-private directory (see [cloneRepo]). Retry logic here
     * handles genuinely transient issues (Inflater races under memory pressure, occasional
     * lock-file commit races); it no longer needs to work around structurally non-atomic public
     * storage, since that's now handled by staging + a single move in [cloneRepo].
     */
    private fun cloneIntoStaging(
        cloneUrl: String,
        destination: File,
        depth: Int,
        onProgress: (String) -> Unit
    ): GitOpResult {
        val label = destination.name
        var lastError: Exception? = null
        // More attempts for large packs / LFS-heavy repos where Android GC closes Inflater mid-read.
        val maxAttempts = 5
        for (attempt in 1..maxAttempts) {
            // If a previous attempt already fetched objects (valid .git) but failed during
            // checkout, keep the pack and only retry working-tree checkout — do NOT delete
            // multi-GB downloads (Android kernel trees) and start over.
            val hasPartialGit = isValidGitDir(destination)
            if (attempt > 1 && !hasPartialGit) {
                cleanUpFailedCloneDestination(destination, alreadyExisted = true)
                // Give the GC time to release SoftReferences holding stale WindowCache entries.
                System.gc()
                try { Thread.sleep(800L * attempt) } catch (_: InterruptedException) {}
                onProgress("Retrying clone (attempt $attempt/$maxAttempts)…")
                AppLog.w(TAG, "clone retry $attempt/$maxAttempts for $label after: ${lastError?.message}")
            } else if (attempt > 1 && hasPartialGit) {
                // A failed LockFile.commit() (e.g. the config write) can leave a stray *.lock
                // file behind. We keep the downloaded pack data, but a leftover .lock would make
                // the retry's own lock attempt fail too, so clear those before trying again.
                if (lastError != null && isLockFileCommitRace(lastError!!)) {
                    clearStaleGitLockFiles(destination)
                }
                System.gc()
                try { Thread.sleep(400L * attempt) } catch (_: InterruptedException) {}
                onProgress("Retrying checkout (attempt $attempt/$maxAttempts)…")
                AppLog.w(TAG, "checkout retry $attempt/$maxAttempts for $label after: ${lastError?.message}")
            }
            try {
                synchronized(jgitIoLock) {
                    // Re-install window cache each attempt — soft refs can leave a bad
                    // Inflater in the shared cache after a failed clone.
                    installJGitMemoryLimits()

                    val git: Git = if (hasPartialGit && attempt > 1) {
                        // Objects already on disk — skip the transport phase.
                        onProgress("Opening partial clone…")
                        openGit(destination.absolutePath)
                    } else {
                        // Two-phase clone reduces peak concurrent inflater use on large packs:
                        // 1) fetch objects without writing the working tree
                        // 2) checkout into a fresh cache state
                        onProgress(if (attempt == 1) "Downloading…" else "Downloading (retry $attempt)…")
                        val cmd = Git.cloneRepository()
                            .setURI(cloneUrl)
                            .setDirectory(destination)
                            // Mobile: single branch; optional shallow depth avoids huge pack inflation
                            // that triggers "Inflater has been closed" on Android heaps.
                            // depth <= 0 means full history (no --depth).
                            .setCloneAllBranches(false)
                            .setCloneSubmodules(false)
                            .setNoCheckout(true)
                            .setProgressMonitor(TextProgress(onProgress))
                        if (depth > 0) {
                            // NOTE: CloneCommand.setDepth() doesn't exist until JGit 6.3; this
                            // project is pinned to 5.13.3, which has no shallow-clone support in
                            // the fluent API for clone or fetch. Falling back to a full clone.
                            AppLog.i(TAG, "shallow clone requested (depth=$depth) but unsupported on JGit 5.13.3 — doing full clone")
                        }
                        applyTransportConfig(cmd, cloneUrl)
                        cmd.call()
                    }
                    try {
                        installJGitMemoryLimits()
                        // Apply mobile config BEFORE checkout so the index/working tree
                        // are not compared using desktop filemode/symlink rules.
                        applyMobileRepoConfig(git)

                        // Empty remote (no commits yet): HEAD is unborn / unresolvable.
                        // JGit has already configured the remote; skip checkout + hard-reset
                        // so we don't hit "Invalid ref name: HEAD".
                        val headId = try {
                            git.repository.resolve(org.eclipse.jgit.lib.Constants.HEAD)
                        } catch (_: Exception) {
                            null
                        }
                        if (headId == null) {
                            onProgress("Empty repository — ready for first commit")
                            AppLog.i(TAG, "clone of empty repo: ${destination.absolutePath}")
                        } else {
                            // After setNoCheckout(true), materialize the working tree with a
                            // single hard reset — same idea as `git clone --no-checkout &&
                            // git reset --hard`. A plain CheckoutCommand can leave the index
                            // populated while files are missing on Android storage, so the UI
                            // shows every path as "deleted". One HARD reset writes index +
                            // worktree in one pass (faster than checkout+reset, and correct).
                            onProgress("Checking out files (large trees can take a while)…")
                            git.reset()
                                .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                                .setRef("HEAD")
                                .setProgressMonitor(TextProgress(onProgress))
                                .call()
                            onProgress("Working tree ready")
                        }
                    } finally {
                        git.close()
                    }
                }
                ensureMobileRepoConfig(destination.absolutePath)
                // Prefer the remote's actual default branch (origin/HEAD) over JGit/legacy
                // fallbacks like "master" when that branch doesn't exist on the remote.
                alignToRemoteDefaultBranch(destination)
                val lfsMsg = maybeFetchLfs(destination.absolutePath, cloneUrl, onProgress)
                logIfDirtyAfterClone(destination.absolutePath)
                AppLog.i(TAG, "clone succeeded: ${destination.absolutePath}" + (lfsMsg?.let { " ($it)" } ?: ""))
                return GitOpResult.Success
            } catch (e: org.eclipse.jgit.api.errors.TransportException) {
                // TransportException can wrap Inflater races from the pack path; retry those.
                if (isInflaterRace(e) && attempt < maxAttempts) {
                    lastError = e
                    AppLog.w(TAG, "clone inflater race (transport) on $label — will retry")
                    continue
                }
                cleanUpFailedCloneDestination(destination, alreadyExisted = true)
                AppLog.e(TAG, "clone failed (transport): $label", e)
                return if (isAuthFailure(e)) GitOpResult.AuthRequired(cloneUrl)
                else GitOpResult.Error(e.message ?: "Transport error", e)
            } catch (e: OutOfMemoryError) {
                cleanUpFailedCloneDestination(destination, alreadyExisted = true)
                System.gc()
                AppLog.e(TAG, "clone OOM: $label", e)
                return GitOpResult.Error(
                    "Not enough memory to clone this repository. Try a smaller repo, free RAM, or clone on a desktop and copy the folder in.",
                    e
                )
            } catch (e: Exception) {
                lastError = e
                if (isInflaterRace(e) && attempt < maxAttempts) {
                    AppLog.w(TAG, "clone inflater race on $label — will retry")
                    continue
                }
                if (isLockFileCommitRace(e) && attempt < maxAttempts) {
                    // .git/config (or another JGit lock file) failed one non-retried atomic
                    // rename. hasPartialGit will be true next loop, so we skip the transport
                    // phase and just resume from the checkout/config step — no re-download.
                    AppLog.w(TAG, "clone lock-file commit race on $label — will retry")
                    continue
                }
                // Fallback: some JGit paths throw "Invalid ref name: HEAD" on truly empty
                // remotes. If a .git directory was created, treat it as a successful empty clone
                // instead of wiping the destination.
                if (isEmptyRepoCloneError(e) && isValidGitDir(destination)) {
                    ensureMobileRepoConfig(destination.absolutePath)
                    alignToRemoteDefaultBranch(destination)
                    AppLog.i(TAG, "clone of empty repo (via exception fallback): ${destination.absolutePath}")
                    return GitOpResult.Success
                }
                cleanUpFailedCloneDestination(destination, alreadyExisted = true)
                AppLog.e(TAG, "clone failed: $label", e)
                return GitOpResult.Error(e.message ?: "Clone failed", e)
            }
        }
        cleanUpFailedCloneDestination(destination, alreadyExisted = true)
        return GitOpResult.Error(
            lastError?.message
                ?: "Clone failed after $maxAttempts attempts (often memory pressure on large repos)",
            lastError
        )
    }

    /** Normalize clone URL (identity for GitHub/GitLab; kept for call-site compatibility). */
    private fun normalizeCloneUrl(url: String): String = url

    /** True when the exception is the classic empty-repo "Invalid ref name: HEAD" (or close variants). */
    private fun isEmptyRepoCloneError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val msg = t.message ?: ""
            if (msg.contains("Invalid ref name: HEAD", ignoreCase = true) ||
                msg.contains("Invalid ref name HEAD", ignoreCase = true) ||
                (msg.contains("HEAD", ignoreCase = true) && msg.contains("invalid ref", ignoreCase = true))
            ) {
                return true
            }
            t = t.cause
        }
        return false
    }

    /** Quick check that a directory looks like a usable git repo (has .git or is a bare repo). */
    private fun isValidGitDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val dotGit = File(dir, ".git")
        return when {
            dotGit.isDirectory -> File(dotGit, "config").isFile || File(dotGit, "HEAD").exists()
            File(dir, "HEAD").exists() && File(dir, "config").isFile -> true // bare
            else -> false
        }
    }

    /**
     * On a failed clone: if we created the destination folder ourselves, remove it entirely.
     * If the user picked a folder that already existed (SAF flow), leave it in place — just
     * clear out whatever the partial clone wrote — since it's not ours to delete.
     */
    private fun cleanUpFailedCloneDestination(destination: File, alreadyExisted: Boolean) {
        if (alreadyExisted) {
            destination.listFiles()?.forEach { it.deleteRecursively() }
        } else {
            destination.deleteRecursively()
        }
    }

    fun openGit(path: String): Git = Git.open(File(path))

    /**
     * Android storage often does not preserve Unix executable bits the way desktop
     * filesystems do. With the default core.filemode=true, a fresh clone then looks
     * "dirty" (every executable script shows as modified) even though the user changed
     * nothing. Disable filemode tracking for local mobile working trees.
     *
     * Also force autocrlf=false so checkout does not rewrite line endings and create
     * spurious diffs on mixed CRLF/LF repos.
     */

    /**
     * After clone: cheap working-tree probe first (avoids full `git status` on kernel-scale
     * trees). Only if sampled index paths are missing on disk do we hard-reset and log a
     * full status — the usual "everything deleted" failure mode.
     */
    private fun logIfDirtyAfterClone(path: String) {
        try {
            openGit(path).use { git ->
                val repo = git.repository
                val root = repo.workTree ?: File(path)
                // Sample up to 24 index paths — O(1) disk checks instead of a full tree walk.
                val sampleMissing = sampleMissingFromIndex(repo, root, maxSamples = 24)
                if (sampleMissing == 0) {
                    AppLog.i(TAG, "post-clone probe: working tree looks present (sampled index paths exist)")
                    return
                }
                AppLog.w(
                    TAG,
                    "post-clone probe: $sampleMissing sampled path(s) missing — repair hard-reset"
                )
                try {
                    git.reset()
                        .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                        .setRef("HEAD")
                        .call()
                } catch (repairEx: Exception) {
                    AppLog.w(TAG, "post-clone repair hard-reset failed: ${repairEx.message}")
                }
                // Optional diagnostic status — skip on enormous trees to avoid multi-minute stalls.
                val indexEntries = try {
                    repo.readDirCache().entryCount
                } catch (_: Exception) {
                    0
                }
                if (indexEntries > 50_000) {
                    AppLog.i(TAG, "post-clone: skipped full status ($indexEntries index entries)")
                    return
                }
                val s = git.status().call()
                if (s.isClean) {
                    AppLog.i(TAG, "post-clone status: clean after repair")
                    return
                }
                val samples = (
                    s.modified.take(8).map { "M $it" } +
                        s.untracked.take(8).map { "? $it" } +
                        s.missing.take(8).map { "D $it" } +
                        s.changed.take(8).map { "C $it" } +
                        s.added.take(8).map { "A $it" }
                    ).take(12)
                AppLog.w(
                    TAG,
                    "post-clone status NOT clean: modified=${s.modified.size} untracked=${s.untracked.size} " +
                        "missing=${s.missing.size} changed=${s.changed.size} added=${s.added.size} " +
                        "sample=${samples.joinToString()}"
                )
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "post-clone status check failed: ${e.message}")
        }
    }

    /** How many of up to [maxSamples] index paths are missing on disk (0 = look OK). */
    private fun sampleMissingFromIndex(
        repo: org.eclipse.jgit.lib.Repository,
        workTree: File,
        maxSamples: Int
    ): Int {
        return try {
            val dc = repo.readDirCache()
            val total = dc.entryCount
            if (total == 0) return 0
            var missing = 0
            var checked = 0
            val step = (total / maxSamples).coerceAtLeast(1)
            var i = 0
            while (i < total && checked < maxSamples) {
                val entry = dc.getEntry(i)
                // DirCacheEntry has no isDirectory; skip pure gitlink/tree modes if present.
                if (entry != null &&
                    entry.fileMode != org.eclipse.jgit.lib.FileMode.TREE &&
                    entry.fileMode != org.eclipse.jgit.lib.FileMode.GITLINK
                ) {
                    val f = File(workTree, entry.pathString)
                    if (!f.exists()) missing++
                    checked++
                }
                i += step
            }
            missing
        } catch (e: Exception) {
            AppLog.w(TAG, "sampleMissingFromIndex failed: ${e.message}")
            0
        }
    }

    fun ensureMobileRepoConfig(path: String) {
        try {
            openGit(path).use { git -> applyMobileRepoConfig(git) }
        } catch (e: Exception) {
            AppLog.w(TAG, "ensureMobileRepoConfig failed for $path: ${e.message}")
        }
    }

    /**
     * Android-friendly local git config. Must run before first checkout when possible.
     * - filemode=false: storage often cannot preserve +x bits
     * - autocrlf=false: avoid rewriting line endings on checkout
     * - symlinks=false: Android usually cannot create real symlinks in app storage
     */
    private fun applyMobileRepoConfig(git: Git) {
        try {
            val cfg = git.repository.config
            var changed = false
            try {
                if (cfg.getBoolean("core", null, "filemode", true)) {
                    cfg.setBoolean("core", null, "filemode", false)
                    changed = true
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "set core.filemode failed: ${e.message}")
            }
            try {
                val acrlf = cfg.getString("core", null, "autocrlf")
                if (acrlf == null || !acrlf.equals("false", ignoreCase = true)) {
                    cfg.setString("core", null, "autocrlf", "false")
                    changed = true
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "set core.autocrlf failed: ${e.message}")
            }
            try {
                if (cfg.getBoolean("core", null, "symlinks", true)) {
                    cfg.setBoolean("core", null, "symlinks", false)
                    changed = true
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "set core.symlinks failed: ${e.message}")
            }
            // Do not set core.checkstat — JGit rejects "minimum" with Invalid value.
            if (changed) {
                cfg.save()
                AppLog.i(TAG, "mobile git config: filemode=false autocrlf=false symlinks=false")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "applyMobileRepoConfig failed: ${e.message}")
        }
    }

    /**
     * After LFS smudge, the working tree has real blobs while the index still points at
     * pointer files — native git-lfs hides that via filters; JGit does not. Mark those
     * paths assume-valid so status stays clean until the user really edits them.
     */
    fun markAssumeValid(path: String, relativePaths: List<String>) {
        if (relativePaths.isEmpty()) return
        try {
            openGit(path).use { git ->
                val repo = git.repository
                val dc = repo.lockDirCache()
                try {
                    var n = 0
                    for (rel in relativePaths) {
                        val idx = dc.findEntry(rel)
                        if (idx < 0) continue
                        val entry = dc.getEntry(idx)
                        if (!entry.isAssumeValid) {
                            entry.isAssumeValid = true
                            n++
                        }
                    }
                    if (n > 0) {
                        dc.write()
                        if (!dc.commit()) {
                            AppLog.w(TAG, "markAssumeValid: DirCache.commit() returned false")
                        }
                    }
                    AppLog.i(TAG, "markAssumeValid: $n path(s) in $path")
                } finally {
                    dc.unlock()
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "markAssumeValid failed: ${e.message}")
        }
    }


    // ---------------- Status / staging ----------------


    /** Human-readable `git status` style summary for snackbars / status dialog. */
    fun gitStatusSummary(path: String): String {
        ensureMobileRepoConfig(path)
        openGit(path).use { git ->
            val s = git.status().call()
            val branch = git.repository.branch ?: "(detached)"
            return buildString {
                append("On branch $branch")
                if (s.isClean) {
                    append("\nNothing to commit, working tree clean")
                    return@buildString
                }
                if (s.conflicting.isNotEmpty()) {
                    append("\nUnmerged paths (${s.conflicting.size}):")
                    s.conflicting.sorted().take(12).forEach { append("\n  both modified: $it") }
                }
                val staged = s.added.size + s.changed.size + s.removed.size
                if (staged > 0) {
                    append("\nChanges to be committed ($staged):")
                    s.added.sorted().take(8).forEach { append("\n  new file: $it") }
                    s.changed.sorted().take(8).forEach { append("\n  modified: $it") }
                    s.removed.sorted().take(8).forEach { append("\n  deleted: $it") }
                }
                val unstaged = s.modified.size + s.missing.size
                if (unstaged > 0) {
                    append("\nChanges not staged ($unstaged):")
                    s.modified.sorted().take(8).forEach { append("\n  modified: $it") }
                    s.missing.sorted().take(8).forEach { append("\n  deleted: $it") }
                }
                if (s.untracked.isNotEmpty()) {
                    append("\nUntracked files (${s.untracked.size}):")
                    s.untracked.sorted().take(8).forEach { append("\n  $it") }
                }
            }
        }
    }

    fun getStatus(path: String): RepoStatus {
        ensureMobileRepoConfig(path)
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

    fun stage(path: String, files: List<String>): GitOpResult = withRepoLock(path) {
        AppLog.i(TAG, "stage: $files")
        openGit(path).use { git ->
            // JGit's AddCommand mirrors old git semantics: it happily stages new/modified
            // content but silently skips paths that no longer exist on disk, so deletions
            // never make it into the index. Route those through RmCommand(cached) instead.
            val status = git.status().call()
            val missing = files.filter { it in status.missing || it in status.removed }
            val present = files - missing.toSet()

            if (present.isNotEmpty()) {
                // LFS clean: convert tracked large files to pointers before indexing
                present.forEach { rel ->
                    try {
                        LfsSupport.cleanIfNeeded(File(path), rel)
                    } catch (e: Exception) {
                        AppLog.w(TAG, "lfs clean skipped for $rel: ${e.message}")
                    }
                }
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
        GitOpResult.Success
    }

    fun unstage(path: String, files: List<String>): GitOpResult = withRepoLock(path) {
        AppLog.i(TAG, "unstage: $files")
        openGit(path).use { git ->
            val cmd = git.reset()
            files.forEach { cmd.addPath(it) }
            cmd.call()
        }
        GitOpResult.Success
    }

    fun stageAll(path: String): GitOpResult = withRepoLock(path) {
        AppLog.i(TAG, "stageAll: $path")
        openGit(path).use { git ->
            // LFS clean for any path that matches track patterns before indexing
            try {
                val s = git.status().call()
                val candidates = (s.untracked + s.modified + s.untrackedFolders).distinct()
                candidates.forEach { rel ->
                    try {
                        LfsSupport.cleanIfNeeded(File(path), rel)
                    } catch (e: Exception) {
                        AppLog.w(TAG, "lfs clean skipped for $rel: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "lfs clean pre-scan failed: ${e.message}")
            }
            git.add().addFilepattern(".").call()
            // Same deletion gap as stage() above — catch any remaining missing paths.
            val missing = git.status().call().missing
            if (missing.isNotEmpty()) {
                val cmd = git.rm().setCached(true)
                missing.forEach { cmd.addFilepattern(it) }
                cmd.call()
            }
        }
        GitOpResult.Success
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

    fun unstageAll(path: String): GitOpResult = withRepoLock(path) {
        openGit(path).use { git ->
            val status = git.status().call()
            val staged = (status.added + status.changed + status.removed).distinct()
            AppLog.i(TAG, "unstageAll: ${staged.size} path(s)")
            if (staged.isEmpty()) return@withRepoLock GitOpResult.Success
            val cmd = git.reset()
            staged.forEach { cmd.addPath(it) }
            cmd.call()
        }
        GitOpResult.Success
    }

    // ---------------- Commit ----------------

    fun commit(
        path: String,
        message: String,
        authorName: String,
        authorEmail: String,
        signOff: Boolean = isSignOffEnabled(),
        amend: Boolean = false
    ): GitOpResult =
        withRepoLock(path) {
            val shouldSign = isGpgSigningEnabled() && credentialStore.hasGpgKey()
            val gpgKey = if (shouldSign) credentialStore.getGpgPrivateKey() else null
            val gpgPass = if (shouldSign) credentialStore.getGpgPassphrase() else null
            val fullMessage = if (signOff) appendSignOff(message, authorName, authorEmail) else message
            openGit(path).use { git ->
                if (amend) {
                    val head = git.repository.resolve("HEAD")
                    if (head == null) {
                        return@withRepoLock GitOpResult.Error("Nothing to amend — no commits yet")
                    }
                }
                val doCommit = {
                    val cmd = git.commit()
                        .setMessage(fullMessage)
                        .setAuthor(PersonIdent(authorName, authorEmail))
                        .setCommitter(PersonIdent(authorName, authorEmail))
                        .setAmend(amend)
                    if (shouldSign && gpgKey != null) {
                        cmd.setSign(true)
                        AppLog.i(TAG, "commit: OpenPGP signing enabled" + if (amend) " (amend)" else "")
                    } else {
                        cmd.setSign(false)
                    }
                    cmd.call()
                }
                if (shouldSign && gpgKey != null) {
                    GpgSupport.withStoredKeySigner(gpgKey, gpgPass) { doCommit() }
                } else {
                    doCommit()
                }
            }
            AppLog.i(
                TAG,
                "commit succeeded: ${message.take(50)}" +
                    (if (amend) " (amended)" else "") +
                    (if (shouldSign) " (gpg-signed)" else "") +
                    (if (signOff) " (signed-off)" else "")
            )
            GitOpResult.Success
        }

    /**
     * Suggest a GitHub-style default commit message from the current index/working tree.
     * Examples: "Delete foo.txt", "Rename a.kt to b.kt", "Add README.md", "Update Main.kt".
     */
    fun suggestCommitMessage(path: String): String {
        return try {
            openGit(path).use { git ->
                val s = git.status().call()
                val added = (s.added + s.untracked).sorted()
                val deleted = (s.removed + s.missing).sorted()
                val modified = (s.changed + s.modified).sorted()
                if (deleted.size == 1 && added.size == 1) {
                    val from = deleted.first().substringAfterLast('/')
                    val to = added.first().substringAfterLast('/')
                    if (from != to) return "Rename $from to $to"
                }
                when {
                    added.size == 1 && deleted.isEmpty() && modified.isEmpty() ->
                        "Add ${added.first().substringAfterLast('/')}"
                    deleted.size == 1 && added.isEmpty() && modified.isEmpty() ->
                        "Delete ${deleted.first().substringAfterLast('/')}"
                    modified.size == 1 && added.isEmpty() && deleted.isEmpty() ->
                        "Update ${modified.first().substringAfterLast('/')}"
                    added.isNotEmpty() && deleted.isEmpty() && modified.isEmpty() ->
                        if (added.size <= 3) "Add ${added.joinToString(", ") { it.substringAfterLast('/') }}"
                        else "Add ${added.size} files"
                    deleted.isNotEmpty() && added.isEmpty() && modified.isEmpty() ->
                        if (deleted.size <= 3) "Delete ${deleted.joinToString(", ") { it.substringAfterLast('/') }}"
                        else "Delete ${deleted.size} files"
                    modified.isNotEmpty() && added.isEmpty() && deleted.isEmpty() ->
                        if (modified.size <= 3) "Update ${modified.joinToString(", ") { it.substringAfterLast('/') }}"
                        else "Update ${modified.size} files"
                    else -> {
                        val parts = mutableListOf<String>()
                        if (added.isNotEmpty()) parts += "add ${added.size}"
                        if (modified.isNotEmpty()) parts += "update ${modified.size}"
                        if (deleted.isNotEmpty()) parts += "delete ${deleted.size}"
                        parts.joinToString(", ").replaceFirstChar { it.uppercase() }
                    }
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** List configured remotes as name → URL. */
    fun listRemotes(path: String): Map<String, String> {
        return try {
            openGit(path).use { git ->
                val cfg = git.repository.config
                cfg.getSubsections("remote").associateWith { name ->
                    cfg.getString("remote", name, "url") ?: ""
                }.filterValues { it.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Add or update a remote. */
    fun addOrSetRemote(path: String, name: String, url: String): GitOpResult {
        val n = name.trim()
        val u = url.trim()
        if (n.isBlank() || u.isBlank()) return GitOpResult.Error("Remote name and URL are required")
        return try {
            openGit(path).use { git ->
                val cfg = git.repository.config
                cfg.setString("remote", n, "url", u)
                if (cfg.getString("remote", n, "fetch") == null) {
                    cfg.setString("remote", n, "fetch", "+refs/heads/*:refs/remotes/$n/*")
                }
                cfg.save()
            }
            AppLog.i(TAG, "remote set: $n -> $u")
            GitOpResult.Success
        } catch (e: Exception) {
            GitOpResult.Error(e.message ?: "Failed to set remote", e)
        }
    }

    fun removeRemote(path: String, name: String): GitOpResult {
        val n = name.trim()
        if (n.isBlank()) return GitOpResult.Error("Remote name required")
        return try {
            openGit(path).use { git ->
                val cfg = git.repository.config
                cfg.unsetSection("remote", n)
                cfg.save()
            }
            AppLog.i(TAG, "remote removed: $n")
            GitOpResult.Success
        } catch (e: Exception) {
            GitOpResult.Error(e.message ?: "Failed to remove remote", e)
        }
    }

    /** Appends a Signed-off-by trailer if one is not already present (DCO / git commit -s). */
    private fun appendSignOff(message: String, name: String, email: String): String {
        val trailer = "Signed-off-by: $name <$email>"
        val trimmed = message.trimEnd()
        if (trimmed.lines().any { it.trim().equals(trailer, ignoreCase = true) }) {
            return trimmed
        }
        // Ensure a blank line before the trailer when the body is non-empty
        return if (trimmed.isEmpty()) trailer else "$trimmed\n\n$trailer"
    }

    // ---------------- Push / Pull / Fetch ----------------

    /**
     * Push to a remote (default origin).
     * - [remote]: remote name (origin, upstream, …)
     * - [localBranch]/[remoteBranch]: optional ref mapping; null = current branch defaults
     * - [forceWithLease]: only overwrite remote if it still matches our remote-tracking tip
     * - [force]: unconditional overwrite (`--force`). Prefer lease unless you know you need this.
     * If both force flags are true, lease wins.
     */
    fun push(
        path: String,
        force: Boolean = false,
        forceWithLease: Boolean = false,
        remote: String = "origin",
        localBranch: String? = null,
        remoteBranch: String? = null,
        onProgress: (String) -> Unit = {}
    ): GitOpResult {
        val mode = when {
            forceWithLease -> "force-with-lease"
            force -> "force"
            else -> "normal"
        }
        val remoteName = remote.ifBlank { "origin" }
        AppLog.i(TAG, "push: $path remote=$remoteName mode=$mode")
        return try {
            openGit(path).use { git ->
                val remoteUrl = git.repository.config.getString("remote", remoteName, "url") ?: ""
                if (remoteUrl.isBlank()) {
                    return@use GitOpResult.Error("No URL configured for remote '$remoteName'")
                }
                maybeUploadLfs(path, remoteUrl)?.let { AppLog.i(TAG, it) }

                if (forceWithLease || force) {
                    onProgress("Force pushing…")
                    pushForced(git, remoteUrl, withLease = forceWithLease)
                } else {
                    onProgress("Pushing…")
                    val cmd = git.push()
                        .setRemote(remoteName)
                        .setProgressMonitor(TextProgress(onProgress))
                    // Always push a single branch — never all local branches. Local-only
                    // branches stay local until the user explicitly pushes them.
                    val current = try { git.repository.branch } catch (_: Exception) { null }
                    val lb = localBranch?.takeIf { it.isNotBlank() } ?: current
                    val rb = remoteBranch?.takeIf { it.isNotBlank() } ?: lb
                    if (lb.isNullOrBlank()) {
                        return@use GitOpResult.Error("Detached HEAD — check out a branch before push")
                    }
                    cmd.setRefSpecs(org.eclipse.jgit.transport.RefSpec("refs/heads/$lb:refs/heads/$rb"))
                    applyTransportConfig(cmd, remoteUrl)
                    val results = cmd.call()
                    val rejected = results.flatMap { it.remoteUpdates }
                        .filter { it.status.name.contains("REJECTED") || it.status.name.contains("NON_EXISTING") }
                    if (rejected.isNotEmpty()) {
                        val details = rejected.joinToString("; ") { upd ->
                            val msg = upd.message?.takeIf { it.isNotBlank() }
                            val status = upd.status?.name ?: "REJECTED"
                            when {
                                status.contains("NONFASTFORWARD") ->
                                    "non-fast-forward (pull/rebase first)" + (msg?.let { ": $it" } ?: "")
                                status.contains("OTHER") || status.contains("REMOTE_CHANGED") ->
                                    msg ?: status
                                else -> msg ?: status
                            }
                        }
                        AppLog.w(TAG, "push rejected: $details")
                        // GitHub rejects workflow-file updates without the `workflow` scope with a
                        // clear message in RemoteRefUpdate.message; surface it instead of always
                        // blaming a missing pull.
                        val hint = when {
                            details.contains("workflow", ignoreCase = true) ||
                                details.contains("refusing to allow", ignoreCase = true) ->
                                "Push rejected (token may lack the workflow scope): $details"
                            details.contains("non-fast-forward", ignoreCase = true) ->
                                "Push rejected — pull first: $details"
                            else -> "Push rejected: $details"
                        }
                        GitOpResult.Error(hint)
                    } else {
                        AppLog.i(TAG, "push succeeded")
                        GitOpResult.Success
                    }
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

    /**
     * Force / force-with-lease push of the current branch via [RemoteRefUpdate].
     * Lease uses the remote-tracking ref (`refs/remotes/origin/<branch>`) as the expected
     * remote tip — same as `git push --force-with-lease`.
     */
    private fun pushForced(git: Git, remoteUrl: String, withLease: Boolean): GitOpResult {
        val repo = git.repository
        val branch = repo.branch
            ?: return GitOpResult.Error("Detached HEAD — check out a branch before force push")
        val localRef = "refs/heads/$branch"
        val remoteRef = "refs/heads/$branch"
        val trackingRef = "refs/remotes/origin/$branch"
        val localId = repo.resolve(localRef)
            ?: return GitOpResult.Error("No local branch $branch")

        val expectedRemoteId: ObjectId? = if (withLease) {
            val id = repo.resolve(trackingRef)
            if (id == null) {
                return GitOpResult.Error(
                    "No remote-tracking branch origin/$branch — fetch first before force-with-lease"
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
            configureTransport(transport, remoteUrl)
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
                return GitOpResult.Error("$leaseHint ($statuses)")
            }
        }
        AppLog.i(TAG, "push succeeded (${if (withLease) "force-with-lease" else "force"})")
        return GitOpResult.Success
    }

    private fun configureTransport(transport: Transport, url: String) {
        if (url.startsWith("https://")) {
            val host = CredentialStore.hostOf(url)
            val user = credentialStore.getHttpsUsername(host)
            val token = credentialStore.getHttpsToken(host)
            if (token != null) {
                transport.credentialsProvider =
                    UsernamePasswordCredentialsProvider(user ?: "x-access-token", token)
            }
        } else if (url.startsWith("git@") || url.startsWith("ssh://")) {
            if (transport is SshTransport) {
                transport.sshSessionFactory = sshFactory
            }
        }
    }

    fun pull(path: String, onProgress: (String) -> Unit = {}): GitOpResult {
        AppLog.i(TAG, "pull: $path")
        return try {
            openGit(path).use { git ->
                val repoState = git.repository.repositoryState
                if (repoState != RepositoryState.SAFE) {
                    // JGit's PullCommand throws WrongRepositoryStateException for any state
                    // other than SAFE (mid-merge, mid-revert, mid-cherry-pick...) — most often
                    // MERGING_RESOLVED, where conflicts were resolved but the merge commit was
                    // never made. Route to the merge screen instead of surfacing the raw
                    // exception; "Complete merge" there is already enabled once conflicts are
                    // resolved, so this is a one-tap fix for the user.
                    AppLog.w(TAG, "pull blocked: repository state is $repoState")
                    val unresolved = git.status().call().conflicting.toList().sorted()
                    GitOpResult.Conflict(unresolved)
                } else {
                    val remoteUrl = git.repository.config.getString("remote", "origin", "url") ?: ""
                    onProgress("Pulling…")
                    val cmd = git.pull()
                        .setProgressMonitor(TextProgress(onProgress))
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
                            maybeFetchLfs(path, remoteUrl) {}
                            GitOpResult.UpToDate()
                        }
                        result.isSuccessful -> {
                            maybeFetchLfs(path, remoteUrl) {}?.let { AppLog.i(TAG, it) }
                            AppLog.i(TAG, "pull succeeded")
                            GitOpResult.Success
                        }
                        else -> {
                            AppLog.w(TAG, "pull incomplete: ${result.mergeResult?.mergeStatus}")
                            GitOpResult.Error("Pull did not complete: ${result.mergeResult?.mergeStatus}")
                        }
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

    /**
     * Fetches a single ref from origin into a local branch and checks it out — used to pull
     * down a GitHub PR's actual commits (refs/pull/<n>/head) for local review/testing, since
     * the REST API alone can only inspect metadata, not run the code.
     */
    fun fetchAndCheckoutRef(path: String, refSpec: String, localBranch: String): GitOpResult {
        AppLog.i(TAG, "fetchAndCheckoutRef: $refSpec -> $localBranch")
        return try {
            openGit(path).use { git ->
                val remoteUrl = git.repository.config.getString("remote", "origin", "url") ?: ""
                val fetchCmd = git.fetch().setRemote("origin").setRefSpecs(RefSpec(refSpec))
                applyTransportConfig(fetchCmd, remoteUrl)
                fetchCmd.call()

                val existing = git.repository.findRef(localBranch)
                if (existing != null) {
                    git.checkout().setName(localBranch).call()
                    // Fast-forward the existing local branch to the freshly fetched tip.
                    git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("FETCH_HEAD").call()
                } else {
                    git.checkout().setCreateBranch(true).setName(localBranch).setStartPoint("FETCH_HEAD").call()
                }
            }
            AppLog.i(TAG, "fetchAndCheckoutRef succeeded: $localBranch")
            GitOpResult.Success
        } catch (e: org.eclipse.jgit.api.errors.TransportException) {
            AppLog.e(TAG, "fetchAndCheckoutRef failed (transport)", e)
            if (isAuthFailure(e)) {
                val url = openGit(path).use { it.repository.config.getString("remote", "origin", "url") } ?: ""
                GitOpResult.AuthRequired(url)
            } else GitOpResult.Error(e.message ?: "Fetch failed", e)
        } catch (e: Exception) {
            AppLog.e(TAG, "fetchAndCheckoutRef failed", e)
            GitOpResult.Error(e.message ?: "Checkout failed", e)
        }
    }

    /** Fetches all refs from the named remote, updating its remote-tracking branches. */
    fun fetchRemote(path: String, remoteName: String, onProgress: (String) -> Unit = {}): GitOpResult {
        AppLog.i(TAG, "fetchRemote: $remoteName")
        return try {
            openGit(path).use { git ->
                val remoteUrl = git.repository.config.getString("remote", remoteName, "url") ?: ""
                onProgress("Fetching $remoteName…")
                val fetchCmd = git.fetch()
                    .setRemote(remoteName)
                    .setProgressMonitor(TextProgress(onProgress))
                applyTransportConfig(fetchCmd, remoteUrl)
                fetchCmd.call()
            }
            AppLog.i(TAG, "fetchRemote succeeded: $remoteName")
            GitOpResult.Success
        } catch (e: org.eclipse.jgit.api.errors.TransportException) {
            AppLog.e(TAG, "fetchRemote failed (transport): $remoteName", e)
            if (isAuthFailure(e)) {
                val url = openGit(path).use { it.repository.config.getString("remote", remoteName, "url") } ?: ""
                GitOpResult.AuthRequired(url)
            } else GitOpResult.Error(e.message ?: "Fetch failed", e)
        } catch (e: Exception) {
            AppLog.e(TAG, "fetchRemote failed: $remoteName", e)
            GitOpResult.Error(e.message ?: "Fetch failed", e)
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

    /**
     * Runs a git write operation for [path], serialized per-repo (concurrent operations on the
     * same repo race for JGit's DirCache lock) and clearing a stale `.git/index.lock` left by a
     * previous crashed/interrupted operation, same as [discardChanges].
     *
     * Also the fix for a real crash: JGit throws `JGitInternalException` — a plain
     * `RuntimeException`, not `GitAPIException` — for lock failures, so callers that only
     * caught `GitAPIException` let it escape uncaught and crash the app. Catching `Exception`
     * broadly here avoids that regardless of which JGit exception type is thrown.
     */
    private fun withRepoLock(path: String, op: () -> GitOpResult): GitOpResult {
        val operationLock = repoOperationLocks.getOrPut(path) { Any() }
        return synchronized(operationLock) {
            clearStaleIndexLock(path)
            try {
                op()
            } catch (e: Exception) {
                // Second chance: a leftover lock from a prior crash often causes LockFailedException.
                val msg = e.message.orEmpty()
                if ("index.lock" in msg || e.javaClass.simpleName.contains("LockFailed", ignoreCase = true)) {
                    AppLog.w(TAG, "retrying after index.lock failure: $path")
                    clearStaleIndexLock(path)
                    try {
                        return@synchronized op()
                    } catch (e2: Exception) {
                        AppLog.e(TAG, "git operation failed after lock retry: $path", e2)
                        return@synchronized GitOpResult.Error(
                            e2.message ?: e2.javaClass.simpleName, e2
                        )
                    }
                }
                AppLog.e(TAG, "git operation failed: $path", e)
                GitOpResult.Error(e.message ?: e.javaClass.simpleName, e)
            }
        }
    }

    /** Removes a leftover `.git/index.lock` from a crashed/interrupted write, if present. */
    private fun clearStaleIndexLock(path: String) {
        val indexLock = File(path, ".git/index.lock")
        if (!indexLock.exists()) return
        val ageMs = System.currentTimeMillis() - indexLock.lastModified()
        AppLog.w(TAG, "removing stale index.lock (age=${ageMs}ms) at ${indexLock.absolutePath}")
        if (!indexLock.delete()) {
            AppLog.w(TAG, "could not delete index.lock — operation may still fail")
        }
    }

    /**
     * After a clone, make sure the local HEAD is the remote's real default branch.
     *
     * JGit (and some remotes) still fall back to `master` when advertising is incomplete
     * or the remote is empty. GitHub/GitLab default to `main`. This:
     * 1) Reads `refs/remotes/origin/HEAD` (symbolic) when present and checks out that branch.
     * 2) For empty repos with no commits, rewrites unborn HEAD from `master` → `main`.
     * 3) Never invents a `master` branch that does not exist on the remote.
     */
    private fun alignToRemoteDefaultBranch(destination: File) {
        try {
            openGit(destination.absolutePath).use { git ->
                val repo = git.repository
                val originHead = repo.findRef("refs/remotes/origin/HEAD")
                val remoteDefault = when {
                    originHead != null && originHead.isSymbolic ->
                        originHead.target.name.removePrefix("refs/remotes/origin/")
                    originHead != null ->
                        originHead.name.removePrefix("refs/remotes/origin/")
                    else -> null
                }?.takeIf { it.isNotBlank() && it != "HEAD" }

                if (remoteDefault != null) {
                    val localRef = repo.findRef("refs/heads/$remoteDefault")
                    val remoteRef = repo.findRef("refs/remotes/origin/$remoteDefault")
                    val current = try { repo.branch } catch (_: Exception) { null }
                    if (current != remoteDefault) {
                        when {
                            localRef != null -> {
                                git.checkout().setName(remoteDefault).call()
                                AppLog.i(TAG, "clone: checked out remote default branch '$remoteDefault'")
                            }
                            remoteRef != null -> {
                                git.checkout()
                                    .setCreateBranch(true)
                                    .setName(remoteDefault)
                                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                                    .setStartPoint(remoteRef.name)
                                    .call()
                                AppLog.i(TAG, "clone: created tracking branch for remote default '$remoteDefault'")
                            }
                            else -> {
                                // Remote advertised default name but no objects yet (empty repo).
                                setUnbornHead(repo, remoteDefault)
                                AppLog.i(TAG, "clone: empty repo — unborn HEAD set to '$remoteDefault'")
                            }
                        }
                    }
                    return
                }

                // No origin/HEAD. Empty clone often leaves "ref: refs/heads/master" with no
                // master ref — prefer modern default `main`.
                val headId = try { repo.resolve(org.eclipse.jgit.lib.Constants.HEAD) } catch (_: Exception) { null }
                if (headId == null) {
                    val headRef = repo.exactRef(org.eclipse.jgit.lib.Constants.HEAD)
                    val target = headRef?.target?.name ?: ""
                    if (target.endsWith("/master") || target.isBlank()) {
                        setUnbornHead(repo, "main")
                        AppLog.i(TAG, "clone: empty repo with no origin/HEAD — unborn HEAD set to 'main'")
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "alignToRemoteDefaultBranch failed (non-fatal): ${e.message}")
        }
    }

    /** Points HEAD at an unborn branch (no commits yet), e.g. `ref: refs/heads/main`. */
    private fun setUnbornHead(repo: Repository, branchName: String) {
        val safe = branchName.trim().ifBlank { "main" }
        val refUpdate = repo.updateRef(org.eclipse.jgit.lib.Constants.HEAD)
        refUpdate.link("refs/heads/$safe")
    }

    /**
     * Creates a **local-only** git repository under [reposRoot] (no remote).
     * Initial branch defaults to `main`. Nothing is pushed until the user adds a remote and pushes.
     */
    fun initLocalRepo(folderName: String, initialBranch: String = "main"): GitOpResult {
        val name = folderName.trim().ifBlank { return GitOpResult.Error("Folder name required") }
        val branch = initialBranch.trim().ifBlank { "main" }
        val destination = File(reposRoot, name)
        if (destination.exists() && destination.listFiles()?.isNotEmpty() == true) {
            return GitOpResult.Error("'$name' already exists and isn't empty")
        }
        return try {
            destination.mkdirs()
            Git.init().setDirectory(destination).call().use { git ->
                applyMobileRepoConfig(git)
                setUnbornHead(git.repository, branch)
            }
            ensureMobileRepoConfig(destination.absolutePath)
            AppLog.i(TAG, "initLocalRepo: ${destination.absolutePath} branch=$branch")
            GitOpResult.Success
        } catch (e: Exception) {
            AppLog.e(TAG, "initLocalRepo failed: $name", e)
            destination.deleteRecursively()
            GitOpResult.Error(e.message ?: "Failed to create local repository", e)
        }
    }

    /**
     * Creates a **local** branch only. It is not published to any remote until the user pushes.
     * Optional [checkout] switches HEAD to the new branch after creation.
     */
    fun createBranch(path: String, name: String, checkout: Boolean): GitOpResult = withRepoLock(path) {
        openGit(path).use { git ->
            git.branchCreate().setName(name).call()
            if (checkout) git.checkout().setName(name).call()
        }
        GitOpResult.Success
    }

    fun checkoutBranch(path: String, name: String): GitOpResult = withRepoLock(path) {
        openGit(path).use { git ->
            val repo = git.repository
            // `name` is a bare local branch name (e.g. "feature-x") for an existing local
            // branch, or a remote-tracking name like "origin/feature-x" — as shown for
            // remote entries in the branch list — for one that hasn't been checked out
            // locally yet. `findRef(name)` alone can't tell these apart: its search path
            // also matches under refs/remotes/, so it treated a remote-only branch as
            // already "local" and ran a plain checkout of the remote-tracking ref, which
            // JGit does as a detached HEAD rather than creating a tracking branch. Check
            // the exact ref paths instead.
            val remoteRef = repo.findRef("refs/remotes/$name")
            val shortName = if (remoteRef != null) name.substringAfter('/') else name
            val localRef = repo.findRef("refs/heads/$shortName")

            when {
                localRef != null -> {
                    git.checkout().setName(shortName).call()
                    GitOpResult.Success
                }
                remoteRef != null -> {
                    git.checkout()
                        .setCreateBranch(true)
                        .setName(shortName)
                        .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                        .setStartPoint(remoteRef.name)
                        .call()
                    GitOpResult.Success
                }
                else -> GitOpResult.Error("Branch not found: $name")
            }
        }
    }

    fun deleteBranch(path: String, name: String, force: Boolean): GitOpResult = withRepoLock(path) {
        openGit(path).use { git ->
            val cmd = git.branchDelete().setBranchNames(name)
            if (force) cmd.setForce(true)
            cmd.call()
        }
        GitOpResult.Success
    }

    // ---------------- History ----------------

    fun getLog(path: String, maxCount: Int = 100, startRef: String? = null): List<CommitInfo> {
        openGit(path).use { git ->
            val log = git.log().setMaxCount(maxCount)
            if (startRef != null) {
                val start = git.repository.resolve(startRef)
                    ?: throw IllegalArgumentException("Ref not found: $startRef")
                log.add(start)
            }
            return log.call().map { it.toCommitInfo() }
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

    /**
     * Cherry-picks a single commit onto the current branch. Surfaces a [GitOpResult.Conflict]
     * with the affected paths if the cherry-pick cannot be applied cleanly.
     */
    fun cherryPick(path: String, commitHash: String): GitOpResult = try {
        AppLog.i(TAG, "cherryPick: $commitHash")
        openGit(path).use { git ->
            val repository = git.repository
            val objectId = repository.resolve(commitHash)
                ?: return GitOpResult.Error("Commit not found: $commitHash")

            RevWalk(repository).use { walk ->
                val commit = walk.parseCommit(objectId)
                val result = git.cherryPick().include(commit).call()

                when (result.status) {
                    org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.OK -> {
                        AppLog.i(TAG, "cherryPick succeeded: $commitHash")
                        GitOpResult.Success
                    }
                    org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.CONFLICTING -> {
                        val conflicts = git.status().call().conflicting.toList().sorted()
                        AppLog.w(TAG, "cherryPick conflict: $commitHash, ${conflicts.size} path(s)")
                        GitOpResult.Conflict(conflicts)
                    }
                    else -> {
                        AppLog.w(TAG, "cherryPick failed: $commitHash, status=${result.status}")
                        GitOpResult.Error("Could not cherry-pick commit ${commitHash.take(7)}")
                    }
                }
            }
        }
    } catch (e: GitAPIException) {
        AppLog.e(TAG, "cherryPick failed: $commitHash", e)
        GitOpResult.Error(e.message ?: "Cherry-pick failed", e)
    } catch (e: Exception) {
        AppLog.e(TAG, "cherryPick failed: $commitHash", e)
        GitOpResult.Error(e.message ?: "Cherry-pick failed", e)
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

    fun continueMergeAsCommit(path: String, message: String, authorName: String, authorEmail: String): GitOpResult {
        AppLog.i(TAG, "finishMerge: \"${message.take(50)}\"")
        return commit(path, message, authorName, authorEmail)
    }

    fun abortMerge(path: String): GitOpResult = try {
        AppLog.i(TAG, "abortMerge: $path")
        openGit(path).use { git ->
            // A real merge abort must restore working tree AND index to HEAD as it was
            // before the merge started. The previous implementation did
            // checkout().setAllPaths(true) with no start point, which checks out from
            // the *current index* — mid-merge (especially MERGING_RESOLVED, where
            // conflicts were staged but never committed) that index is a half-resolved,
            // unreliable snapshot, not HEAD. Any path missing or mis-staged in that
            // index silently vanished from the working tree instead of being restored.
            // A hard reset to HEAD rebuilds both from the last real commit instead.
            git.reset()
                .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                .setRef(org.eclipse.jgit.lib.Constants.HEAD)
                .call()
            git.repository.writeMergeCommitMsg(null)
            git.repository.writeMergeHeads(null)
        }
        AppLog.i(TAG, "abortMerge succeeded")
        GitOpResult.Success
    } catch (e: Exception) {
        AppLog.e(TAG, "abortMerge failed", e)
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
        // Reject obvious binaries by sampling NUL bytes.
        // Avoid FileInputStream.readNBytes() — it requires API 33 and crashes on
        // older devices (e.g. Amazon Fire tablets on API 30).
        val sample = file.inputStream().use { input ->
            val buf = ByteArray(8192)
            val n = input.read(buf)
            if (n <= 0) ByteArray(0) else if (n == buf.size) buf else buf.copyOf(n)
        }
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

    /**
     * Deletes a file or directory in the working tree (not under `.git`).
     * Directories are removed recursively. Does not stage the deletion — stage
     * via the Changes UI after delete if you want it in the next commit.
     */
    fun deleteWorkingPath(repoPath: String, relativePath: String) {
        val cleaned = relativePath.trim().trimStart('/').replace("\\", "/")
        if (cleaned.isBlank()) throw IllegalArgumentException("Path is required")
        if (cleaned.contains("..")) throw IllegalArgumentException("Invalid path")
        if (cleaned == ".git" || cleaned.startsWith(".git/")) {
            throw IllegalArgumentException("Refusing to delete .git")
        }
        val root = File(repoPath).canonicalFile
        val target = File(repoPath, cleaned).canonicalFile
        if (!target.path.startsWith(root.path)) {
            throw IllegalArgumentException("Path escapes repository")
        }
        if (!target.exists()) throw IllegalArgumentException("Not found: $cleaned")
        val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
        if (!ok) throw IllegalStateException("Could not delete: $cleaned")
        AppLog.i(TAG, "deleted working path: $cleaned")
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


    private fun originUrlOrError(path: String): Pair<String?, GitOpResult?> {
        val url = openGit(path).use {
            it.repository.config.getString("remote", "origin", "url")
        }
        if (url.isNullOrBlank()) return null to GitOpResult.Error("No origin remote configured")
        if (!LfsSupport.isSupportedRemote(url)) {
            return null to GitOpResult.Error("Git LFS is only supported for GitHub and GitLab remotes")
        }
        return url to null
    }

    private fun lfsAuth(remoteUrl: String): Triple<String?, String?, GitOpResult?> {
        val host = CredentialStore.hostOf(remoteUrl)
        val user = credentialStore.getHttpsUsername(host)
        val token = credentialStore.getHttpsToken(host)
        if (token.isNullOrBlank() && (remoteUrl.startsWith("https://") || remoteUrl.startsWith("http://"))) {
            return Triple(user, token, GitOpResult.AuthRequired(remoteUrl))
        }
        return Triple(user, token, null)
    }

    /** `git lfs install` (repo-local). */
    fun lfsInstall(path: String): GitOpResult = try {
        val msg = LfsSupport.install(File(path))
        AppLog.i(TAG, msg)
        GitOpResult.Success
    } catch (e: Exception) {
        GitOpResult.Error(e.message ?: "LFS install failed", e)
    }

    /** `git lfs track <pattern>`. */
    fun lfsTrack(path: String, pattern: String): GitOpResult = try {
        val msg = LfsSupport.track(File(path), pattern)
        AppLog.i(TAG, msg)
        GitOpResult.Success
    } catch (e: Exception) {
        GitOpResult.Error(e.message ?: "LFS track failed", e)
    }

    /** `git lfs untrack <pattern>`. */
    fun lfsUntrack(path: String, pattern: String): GitOpResult = try {
        val msg = LfsSupport.untrack(File(path), pattern)
        AppLog.i(TAG, msg)
        GitOpResult.Success
    } catch (e: Exception) {
        GitOpResult.Error(e.message ?: "LFS untrack failed", e)
    }

    /** `git lfs status` summary text. */
    fun lfsStatus(path: String): Pair<LfsSupport.LfsStatus?, GitOpResult> = try {
        val st = LfsSupport.status(File(path))
        AppLog.i(TAG, st.message)
        st to GitOpResult.Success
    } catch (e: Exception) {
        null to GitOpResult.Error(e.message ?: "LFS status failed", e)
    }

    /**
     * Download Git LFS objects for pointer files (`git lfs pull` / fetch).
     * GitHub and GitLab only.
     */
    fun fetchLfs(path: String, onProgress: (String) -> Unit = {}): GitOpResult {
        return try {
            val (remoteUrl, err) = originUrlOrError(path)
            if (err != null) return err
            val (user, token, authErr) = lfsAuth(remoteUrl!!)
            if (authErr != null) return authErr
            onProgress("Scanning for LFS pointers…")
            val result = LfsSupport.fetchAndSmudge(
                repoRoot = File(path),
                remoteUrl = remoteUrl,
                username = user,
                token = token,
                onProgress = onProgress
            )
            if (result.smudgedPaths.isNotEmpty()) markAssumeValid(path, result.smudgedPaths)
            AppLog.i(TAG, "fetchLfs: ${result.message}")
            if (result.failed > 0 && result.downloaded == 0 && result.alreadyPresent == 0) {
                GitOpResult.Error(result.message)
            } else GitOpResult.Success
        } catch (e: Exception) {
            AppLog.e(TAG, "fetchLfs failed", e)
            GitOpResult.Error(e.message ?: "LFS fetch failed", e)
        }
    }

    /**
     * Upload local LFS objects to the remote (`git lfs push`).
     * GitHub and GitLab only. Called automatically before git push; also available manually.
     */
    fun pushLfs(path: String, onProgress: (String) -> Unit = {}): GitOpResult {
        return try {
            val (remoteUrl, err) = originUrlOrError(path)
            if (err != null) return err
            val (user, token, authErr) = lfsAuth(remoteUrl!!)
            if (authErr != null) return authErr
            onProgress("Uploading LFS objects…")
            val result = LfsSupport.uploadLocalObjects(
                repoRoot = File(path),
                remoteUrl = remoteUrl,
                username = user,
                token = token,
                onProgress = onProgress
            )
            AppLog.i(TAG, "pushLfs: ${result.message}")
            if (result.failed > 0 && result.downloaded == 0) {
                GitOpResult.Error(result.message)
            } else GitOpResult.Success
        } catch (e: Exception) {
            AppLog.e(TAG, "pushLfs failed", e)
            GitOpResult.Error(e.message ?: "LFS push failed", e)
        }
    }


    private fun maybeFetchLfs(path: String, remoteUrl: String, onProgress: (String) -> Unit = {}): String? {
        return try {
            if (!LfsSupport.isSupportedRemote(remoteUrl)) return null
            val host = CredentialStore.hostOf(remoteUrl)
            val result = LfsSupport.fetchAndSmudge(
                repoRoot = File(path),
                remoteUrl = remoteUrl,
                username = credentialStore.getHttpsUsername(host),
                token = credentialStore.getHttpsToken(host),
                onProgress = onProgress
            )
            if (result.smudgedPaths.isNotEmpty()) {
                markAssumeValid(path, result.smudgedPaths)
            }
            if (result.downloaded == 0 && result.alreadyPresent == 0 && result.failed == 0) null
            else result.message
        } catch (e: Exception) {
            AppLog.w(TAG, "LFS post-op skipped: ${e.message}")
            null
        }
    }

    private fun maybeUploadLfs(path: String, remoteUrl: String): String? {
        return try {
            if (!LfsSupport.isSupportedRemote(remoteUrl)) return null
            val host = CredentialStore.hostOf(remoteUrl)
            val result = LfsSupport.uploadLocalObjects(
                repoRoot = File(path),
                remoteUrl = remoteUrl,
                username = credentialStore.getHttpsUsername(host),
                token = credentialStore.getHttpsToken(host)
            )
            if (result.downloaded == 0 && result.alreadyPresent == 0 && result.failed == 0) null
            else result.message
        } catch (e: Exception) {
            AppLog.w(TAG, "LFS upload skipped: ${e.message}")
            null
        }
    }

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
        override fun onUpdate(taskName: String?, workCurr: Int) {
            onProgress("$taskName: $workCurr")
        }
        override fun onUpdate(taskName: String?, workCurr: Int, workTotal: Int, percentDone: Int) {
            onProgress("$taskName: $percentDone%")
        }
        override fun onEndTask(taskName: String?, workCurr: Int) {}
        override fun onEndTask(taskName: String?, workCurr: Int, workTotal: Int, percentDone: Int) {}
    }
}
