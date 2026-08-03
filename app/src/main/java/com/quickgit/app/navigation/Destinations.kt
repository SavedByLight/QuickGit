package com.quickgit.app.navigation

object Dest {
    const val REPO_LIST = "repo_list"
    const val CLONE = "clone"
    const val REPO_DETAIL = "repo_detail/{repoPath}"
    const val HISTORY = "history/{repoPath}"
    const val BRANCHES = "branches/{repoPath}"
    const val DIFF = "diff/{repoPath}/{filePath}/{mode}" // mode = working|staged|commit:<id>
    const val MERGE = "merge/{repoPath}"
    const val SETTINGS = "settings"
    const val FILES = "files/{repoPath}"
    const val EDITOR = "editor/{repoPath}/{filePath}"

    fun repoDetail(path: String) = "repo_detail/${encode(path)}"
    fun history(path: String) = "history/${encode(path)}"
    fun branches(path: String) = "branches/${encode(path)}"
    fun diff(path: String, filePath: String, mode: String) =
        "diff/${encode(path)}/${encode(filePath)}/${encode(mode)}"
    fun merge(path: String) = "merge/${encode(path)}"
    fun files(path: String) = "files/${encode(path)}"
    fun editor(path: String, filePath: String) =
        "editor/${encode(path)}/${encode(filePath)}"

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    fun decode(s: String) = java.net.URLDecoder.decode(s, "UTF-8")
}
