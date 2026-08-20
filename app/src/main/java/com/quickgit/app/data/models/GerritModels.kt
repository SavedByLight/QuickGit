package com.quickgit.app.data.models

/** A Gerrit project (repository) as returned by the REST API. */
data class GerritProject(
    val id: String,              // URL-encoded id
    val name: String,            // project name / path
    val state: String?,          // ACTIVE | READ_ONLY | HIDDEN
    val description: String?,
    val webUrl: String
)
