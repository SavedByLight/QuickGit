package com.quickgit.wear.data

import android.content.Context
import java.io.File

/**
 * Discovers local git repos under common QuickGit roots on the watch.
 * Wear devices rarely host full clones; this still scans so a developer
 * watch image or synced folder can show up.
 */
class WearRepoRepository(private val context: Context) {

    fun listRepos(): List<WearRepoSummary> {
        val roots = candidateRoots()
        val dirs = roots.flatMap { root ->
            if (!root.isDirectory) return@flatMap emptyList()
            root.listFiles { f -> f.isDirectory && File(f, ".git").exists() }?.toList().orEmpty()
        }.distinctBy { it.absolutePath }

        return dirs.mapNotNull { dir ->
            runCatching { summaryFor(dir) }.getOrNull()
        }.sortedBy { it.name.lowercase() }
    }

    fun getRepo(path: String): WearRepoSummary? {
        val dir = File(path)
        if (!dir.isDirectory || !File(dir, ".git").exists()) return null
        return runCatching { summaryFor(dir) }.getOrNull()
    }

    private fun candidateRoots(): List<File> {
        val list = mutableListOf<File>()
        // App-specific external (same idea as phone fallback)
        context.getExternalFilesDir(null)?.let { list += File(it, "QuickGit") }
        context.filesDir?.let { list += File(it, "QuickGit") }
        // Shared Documents path if present on the device image
        File("/storage/emulated/0/Documents/QuickGit").let { if (it.exists()) list += it }
        return list
    }

    private fun summaryFor(dir: File): WearRepoSummary {
        val gitDir = File(dir, ".git")
        // Minimal HEAD parse without pulling in JGit (keeps wear APK small).
        var branch = "(unknown)"
        val headFile = if (gitDir.isDirectory) File(gitDir, "HEAD") else null
        if (headFile != null && headFile.isFile) {
            val head = headFile.readText().trim()
            branch = when {
                head.startsWith("ref: refs/heads/") -> head.removePrefix("ref: refs/heads/")
                head.startsWith("ref: ") -> head.removePrefix("ref: ").substringAfterLast('/')
                else -> "(detached)"
            }
        }
        val remote = runCatching {
            val config = File(gitDir, "config")
            if (!config.isFile) return@runCatching null
            val text = config.readText()
            val urlLine = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("url") && it.contains("=") }
            urlLine?.substringAfter("=")?.trim()
        }.getOrNull()

        // Dirty check without JGit: compare presence of index lock only (best-effort).
        val dirty = File(gitDir, "index.lock").exists()

        return WearRepoSummary(
            name = dir.name,
            path = dir.absolutePath,
            branch = branch,
            dirty = dirty,
            remoteUrl = remote
        )
    }
}
