package com.quickgit.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.github.toPrOpResult
import com.quickgit.app.data.gitlab.GitLabApi
import com.quickgit.app.data.models.PrOpResult
import com.quickgit.app.data.models.Workflow
import com.quickgit.app.data.models.WorkflowJob
import com.quickgit.app.data.models.WorkflowRun
import com.quickgit.app.data.models.WorkflowStep

/**
 * GitHub Actions and GitLab CI pipelines, using the same HTTPS token as other API surfaces.
 * GitLab pipelines are mapped into the Workflow / WorkflowRun models so the existing UI works.
 */
class WorkflowManager(
    private val repoManager: RepoManager,
    private val credentialStore: CredentialStore
) {
    private val TAG = "WorkflowManager"

    data class ProjectRef(
        val host: String,
        val isGitLab: Boolean,
        val owner: String,
        val repo: String
    ) {
        val projectPath: String get() = "$owner/$repo"
    }

    fun projectFor(path: String): ProjectRef? {
        val remoteUrl = repoManager.openGit(path).use {
            it.repository.config.getString("remote", "origin", "url")
        } ?: return null
        val host = CredentialStore.hostOf(remoteUrl)
        val hostLower = host.lowercase()
        if (hostLower.contains("gitlab")) {
            val parsed = GitLabApi(host, null).parseOwnerProject(remoteUrl) ?: return null
            return ProjectRef(host, true, parsed.owner, parsed.project)
        }
        if (hostLower.contains("github.com")) {
            val parsed = GitHubApi(null).parseOwnerRepo(remoteUrl) ?: return null
            return ProjectRef(host, false, parsed.owner, parsed.repo)
        }
        return null
    }

    fun ownerRepoFor(path: String): GitHubApi.OwnerRepo? {
        val p = projectFor(path) ?: return null
        return GitHubApi.OwnerRepo(p.owner, p.repo)
    }

    private fun githubApi(): GitHubApi = GitHubApi(credentialStore.getHttpsToken("github.com"))

    /** PAT stored for github.com, if the user connected a GitHub account in Settings. */
    fun githubHttpsToken(): String? = credentialStore.getHttpsToken("github.com")

    private fun gitlabApi(host: String): GitLabApi =
        GitLabApi(host, credentialStore.getHttpsToken(host))

    /** Map GitHub Actions status filter → GitLab pipeline status where possible. */
    private fun mapStatusToGitLab(status: String?): String? = when (status?.lowercase()) {
        "completed" -> null // GitLab has no single "completed"; omit filter
        "in_progress" -> "running"
        "queued" -> "pending"
        else -> status
    }

    private fun GitLabApi.Pipeline.toRun(): WorkflowRun {
        val conclusion = when (status) {
            "success" -> "success"
            "failed", "canceled", "cancelled" -> if (status.startsWith("cancel")) "cancelled" else "failure"
            "skipped" -> "skipped"
            else -> null
        }
        val mappedStatus = when (status) {
            "pending", "created", "waiting_for_resource", "preparing", "scheduled" -> "queued"
            "running" -> "in_progress"
            "success", "failed", "canceled", "cancelled", "skipped", "manual" -> "completed"
            else -> status
        }
        return WorkflowRun(
            id = id,
            name = "Pipeline #$iid",
            displayTitle = "Pipeline #$iid" + (ref?.let { " ($it)" } ?: ""),
            workflowId = 0L,
            workflowName = "CI",
            status = mappedStatus,
            conclusion = conclusion,
            event = source ?: "pipeline",
            headBranch = ref,
            headSha = sha,
            actorLogin = null,
            runNumber = iid,
            runAttempt = 1,
            htmlUrl = webUrl,
            createdAt = createdAt,
            updatedAt = updatedAt,
            runStartedAt = createdAt
        )
    }

    private fun GitLabApi.PipelineJob.toJob(): WorkflowJob {
        val conclusion = when (status) {
            "success" -> "success"
            "failed" -> "failure"
            "canceled", "cancelled" -> "cancelled"
            "skipped" -> "skipped"
            else -> null
        }
        val mappedStatus = when (status) {
            "pending", "created" -> "queued"
            "running" -> "in_progress"
            else -> "completed"
        }
        return WorkflowJob(
            id = id,
            name = name + (stage?.let { " ($it)" } ?: ""),
            status = mappedStatus,
            conclusion = conclusion,
            startedAt = startedAt,
            completedAt = finishedAt,
            htmlUrl = webUrl,
            steps = emptyList()
        )
    }

    fun listWorkflows(ref: ProjectRef): Pair<List<Workflow>, PrOpResult> {
        return if (ref.isGitLab) {
            // GitLab has no workflow definitions list like Actions; present a single synthetic "CI"
            val synthetic = listOf(
                Workflow(
                    id = 0L,
                    name = "CI Pipelines",
                    path = ".gitlab-ci.yml",
                    state = "active",
                    badgeUrl = null,
                    htmlUrl = "https://${ref.host}/${ref.projectPath}/-/pipelines",
                    createdAt = "",
                    updatedAt = ""
                )
            )
            synthetic to PrOpResult.Success
        } else {
            val result = githubApi().listWorkflows(ref.owner, ref.repo)
            (result.getOrNull() ?: emptyList()) to result.toPrOpResult(ref.host)
        }
    }

    fun listRuns(
        ref: ProjectRef,
        workflowId: Long? = null,
        status: String? = null
    ): Pair<List<WorkflowRun>, PrOpResult> {
        return if (ref.isGitLab) {
            val result = gitlabApi(ref.host).listPipelines(ref.projectPath, mapStatusToGitLab(status))
            (result.getOrNull()?.map { it.toRun() } ?: emptyList()) to result.toPrOpResult(ref.host)
        } else {
            val result = githubApi().listWorkflowRuns(ref.owner, ref.repo, workflowId, status)
            (result.getOrNull() ?: emptyList()) to result.toPrOpResult(ref.host)
        }
    }

    fun getRun(ref: ProjectRef, runId: Long): Pair<WorkflowRun?, PrOpResult> {
        return if (ref.isGitLab) {
            val result = gitlabApi(ref.host).getPipeline(ref.projectPath, runId)
            result.getOrNull()?.toRun() to result.toPrOpResult(ref.host)
        } else {
            val result = githubApi().getWorkflowRun(ref.owner, ref.repo, runId)
            result.getOrNull() to result.toPrOpResult(ref.host)
        }
    }

    fun listJobs(ref: ProjectRef, runId: Long): Pair<List<WorkflowJob>, PrOpResult> {
        return if (ref.isGitLab) {
            val result = gitlabApi(ref.host).listPipelineJobs(ref.projectPath, runId)
            (result.getOrNull()?.map { it.toJob() } ?: emptyList()) to result.toPrOpResult(ref.host)
        } else {
            val result = githubApi().listJobsForRun(ref.owner, ref.repo, runId)
            (result.getOrNull() ?: emptyList()) to result.toPrOpResult(ref.host)
        }
    }

    /**
     * Full job log text + structured check-run annotations (GitHub Actions).
     * GitLab returns [PrOpResult.Error] for logs for now.
     *
     * GitHub returns HTTP 404 for `/actions/jobs/{id}/logs` until the runner has
     * produced log output (common while the job is still queued or just starting).
     * That case is treated as success with a null log so live watch can keep polling
     * without surfacing a hard error.
     */
    fun getJobLog(
        ref: ProjectRef,
        jobId: Long
    ): Triple<String?, List<com.quickgit.app.data.models.WorkflowAnnotation>, PrOpResult> {
        if (ref.isGitLab) {
            return Triple(
                null,
                emptyList(),
                PrOpResult.Error("Job log viewing is only available for GitHub Actions")
            )
        }
        AppLog.i(TAG, "getJobLog: ${ref.owner}/${ref.repo} job=$jobId")
        val logResult = githubApi().getJobLogs(ref.owner, ref.repo, jobId)
        val log = logResult.getOrNull()
        if (log == null) {
            val ex = logResult.exceptionOrNull()
            if (ex is com.quickgit.app.data.github.GitHubApi.HttpStatusException && ex.code == 404) {
                AppLog.i(TAG, "getJobLog: logs not ready yet (HTTP 404) for job=$jobId — will retry while live")
                return Triple(null, emptyList(), PrOpResult.Success)
            }
            val logOp = logResult.toPrOpResult(ref.host)
            return Triple(null, emptyList(), logOp)
        }
        val annotations = githubApi().getJobAnnotations(ref.owner, ref.repo, jobId)
            .getOrNull()
            .orEmpty()
        return Triple(log, annotations, PrOpResult.Success)
    }

    fun dispatch(
        ref: ProjectRef,
        workflowId: Long,
        refName: String,
        inputs: Map<String, String> = emptyMap()
    ): PrOpResult {
        AppLog.i(TAG, "dispatch: ${ref.projectPath} workflow=$workflowId ref=$refName inputs=${inputs.keys}")
        return if (ref.isGitLab) {
            // Triggering a new pipeline requires POST /projects/:id/pipeline — optional later
            PrOpResult.Error("Manual pipeline trigger is not supported yet on GitLab from QuickGit")
        } else {
            githubApi().dispatchWorkflow(ref.owner, ref.repo, workflowId, refName, inputs).toPrOpResult(ref.host)
        }
    }

    /** Parse workflow_dispatch inputs from the workflow YAML on the default branch. */
    fun listDispatchInputs(
        ref: ProjectRef,
        workflow: Workflow,
        branch: String = "main"
    ): List<com.quickgit.app.data.models.WorkflowInput> {
        if (ref.isGitLab) return emptyList()
        return githubApi()
            .listWorkflowDispatchInputs(ref.owner, ref.repo, workflow.path, ref = branch)
            .getOrElse { emptyList() }
    }

    /**
     * Local branch short names for the repo (suitable as workflow_dispatch refs),
     * current branch first when present.
     */
    fun listLocalBranchNames(path: String): List<String> {
        val branches = repoManager.listBranches(path).filter { !it.isRemote }
        val current = branches.firstOrNull { it.isCurrent }?.name
        val names = branches.map { it.name }.distinct()
        return if (current != null) {
            listOf(current) + names.filter { it != current }.sorted()
        } else {
            names.sorted()
        }
    }

    /** Current local branch short name, or null if detached / unknown. */
    fun currentBranchName(path: String): String? =
        repoManager.listBranches(path).firstOrNull { it.isCurrent && !it.isRemote }?.name

    fun cancelRun(ref: ProjectRef, runId: Long): PrOpResult {
        AppLog.i(TAG, "cancelRun: ${ref.projectPath} run=$runId")
        return if (ref.isGitLab) {
            gitlabApi(ref.host).cancelPipeline(ref.projectPath, runId).toPrOpResult(ref.host)
        } else {
            githubApi().cancelWorkflowRun(ref.owner, ref.repo, runId).toPrOpResult(ref.host)
        }
    }

    fun rerun(ref: ProjectRef, runId: Long): PrOpResult {
        AppLog.i(TAG, "rerun: ${ref.projectPath} run=$runId")
        return if (ref.isGitLab) {
            gitlabApi(ref.host).retryPipeline(ref.projectPath, runId).toPrOpResult(ref.host)
        } else {
            githubApi().rerunWorkflowRun(ref.owner, ref.repo, runId).toPrOpResult(ref.host)
        }
    }


    /**
     * Save job log text under public Downloads/QuickGit/
     * (e.g. /storage/emulated/0/Downloads/QuickGit/…).
     * Uses MediaStore on API 29+ so no extra storage permission is required.
     */
    fun saveJobLogToDownloads(
        context: Context,
        jobId: Long,
        jobName: String,
        logText: String
    ): Pair<String?, PrOpResult> {
        if (logText.isBlank()) {
            return null to PrOpResult.Error("No log text to save")
        }
        val safeName = jobName.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40).ifBlank { "job" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "actions-${safeName}-${jobId}-$stamp.log"
        return try {
            val savedPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/QuickGit"
                    )
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null to PrOpResult.Error("Could not create file in Downloads/QuickGit")
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(logText.toByteArray(Charsets.UTF_8))
                } ?: return null to PrOpResult.Error("Could not write log file")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "/storage/emulated/0/Downloads/QuickGit/$fileName"
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "QuickGit"
                )
                if (!dir.exists() && !dir.mkdirs()) {
                    return null to PrOpResult.Error("Could not create Downloads/QuickGit")
                }
                val file = File(dir, fileName)
                FileOutputStream(file).use { it.write(logText.toByteArray(Charsets.UTF_8)) }
                file.absolutePath
            }
            AppLog.i(TAG, "saveJobLogToDownloads: $savedPath")
            savedPath to PrOpResult.Success
        } catch (e: Exception) {
            AppLog.e(TAG, "saveJobLogToDownloads failed", e)
            null to PrOpResult.Error(e.message ?: "Failed to save log", e)
        }
    }

    // ---- Legacy overloads ----

    fun listWorkflows(owner: String, repo: String): Pair<List<Workflow>, PrOpResult> =
        listWorkflows(ProjectRef("github.com", false, owner, repo))

    fun listRuns(
        owner: String,
        repo: String,
        workflowId: Long? = null,
        status: String? = null
    ): Pair<List<WorkflowRun>, PrOpResult> =
        listRuns(ProjectRef("github.com", false, owner, repo), workflowId, status)

    fun getRun(owner: String, repo: String, runId: Long): Pair<WorkflowRun?, PrOpResult> =
        getRun(ProjectRef("github.com", false, owner, repo), runId)

    fun listJobs(owner: String, repo: String, runId: Long): Pair<List<WorkflowJob>, PrOpResult> =
        listJobs(ProjectRef("github.com", false, owner, repo), runId)

    fun dispatch(
        owner: String,
        repo: String,
        workflowId: Long,
        ref: String,
        inputs: Map<String, String> = emptyMap()
    ): PrOpResult = dispatch(ProjectRef("github.com", false, owner, repo), workflowId, ref, inputs)

    fun cancelRun(owner: String, repo: String, runId: Long): PrOpResult =
        cancelRun(ProjectRef("github.com", false, owner, repo), runId)

    fun rerun(owner: String, repo: String, runId: Long): PrOpResult =
        rerun(ProjectRef("github.com", false, owner, repo), runId)
}
