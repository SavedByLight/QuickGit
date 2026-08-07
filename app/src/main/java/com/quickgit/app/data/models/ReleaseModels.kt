package com.quickgit.app.data.models

data class Release(
    val id: Long,
    val tagName: String,
    val name: String,
    val body: String?,
    val draft: Boolean,
    val prerelease: Boolean,
    val authorLogin: String?,
    val htmlUrl: String,
    val tarballUrl: String?,
    val zipballUrl: String?,
    val createdAt: String,
    val publishedAt: String?,
    val assets: List<ReleaseAsset>
)

data class ReleaseAsset(
    val id: Long,
    val name: String,
    val size: Long,
    val downloadCount: Int,
    val contentType: String?,
    val browserDownloadUrl: String,
    val createdAt: String,
    val updatedAt: String
)
