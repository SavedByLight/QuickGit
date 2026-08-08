package com.quickgit.app.data.models

/** Filter for workflow runs by status / conclusion. */
enum class WorkflowRunFilter(val apiValue: String?, val label: String) {
    ALL(null, "All"),
    COMPLETED("completed", "Completed"),
    IN_PROGRESS("in_progress", "In progress"),
    QUEUED("queued", "Queued")
}

data class Workflow(
    val id: Long,
    val name: String,
    val path: String,           // e.g. ".github/workflows/ci.yml"
    val state: String,          // "active" | "disabled_manually" | ...
    val badgeUrl: String?,
    val htmlUrl: String,
    val createdAt: String,
    val updatedAt: String
)

data class WorkflowRun(
    val id: Long,
    val name: String,               // workflow name or display title
    val displayTitle: String,
    val workflowId: Long,
    val workflowName: String,
    val status: String,             // queued | in_progress | completed | ...
    val conclusion: String?,        // success | failure | cancelled | skipped | null while running
    val event: String,              // push | pull_request | workflow_dispatch | ...
    val headBranch: String?,
    val headSha: String?,
    val actorLogin: String?,
    val runNumber: Int,
    val runAttempt: Int,
    val htmlUrl: String,
    val createdAt: String,
    val updatedAt: String,
    val runStartedAt: String?
)

data class WorkflowJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val startedAt: String?,
    val completedAt: String?,
    val htmlUrl: String,
    val steps: List<WorkflowStep>
)

data class WorkflowStep(
    val name: String,
    val status: String,
    val conclusion: String?,
    val number: Int,
    val startedAt: String?,
    val completedAt: String?
)

/** Input definition for workflow_dispatch (from the workflow YAML, approximated via API when available). */
data class WorkflowInput(
    val name: String,
    val description: String?,
    val required: Boolean,
    val default: String?,
    val type: String = "string"     // string | boolean | choice | environment
)

/** GitHub check-run annotation (error/warning/notice) for a workflow job. */
data class WorkflowAnnotation(
    val path: String?,
    val startLine: Int?,
    val endLine: Int?,
    val startColumn: Int?,
    val endColumn: Int?,
    val annotationLevel: String, // failure | warning | notice
    val message: String,
    val title: String?
)
