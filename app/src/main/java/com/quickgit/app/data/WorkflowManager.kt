package com.quickgit.app.data

import com.quickgit.app.data.github.GitHubApi
import com.quickgit.app.data.github.toPrOpResult
import com.quickgit.app.data.models.PrOpResult
import com.quickgit.app.data.models.Workflow
import com.quickgit.app.data.models.WorkflowJob
import com.quickgit.app.data.models.WorkflowRun

/**
 * GitHub Actions (workflows + runs) for github.com remotes, using the same HTTPS token
 * as the rest of the GitHub API surface.
 */
class WorkflowManager(
    private val repoManager: RepoManager,
    private val credentialStore: CredentialStore
) {
    private val TAG = "WorkflowManager"
    private val host = "github.com"

    private val api: GitHubApi get() = GitHubApi(credentialStore.getHttpsToken(host))

    fun ownerRepoFor(path: String): GitHubApi.OwnerRepo? {
        val remoteUrl = repoManager.openGit(path).use {
            it.repository.config.getString("remote", "origin", "url")
        }
        return GitHubApi(null).parseOwnerRepo(remoteUrl)
    }

    fun listWorkflows(owner: String, repo: String): Pair<List<Workflow>, PrOpResult> {
        val result = api.listWorkflows(owner, repo)
        return (result.getOrNull() ?: emptyList()) to result.toPrOpResult(host)
    }

    fun listRuns(
        owner: String,
        repo: String,
        workflowId: Long? = null,
        status: String? = null
    ): Pair<List<WorkflowRun>, PrOpResult> {
        val result = api.listWorkflowRuns(owner, repo, workflowId, status)
        return (result.getOrNull() ?: emptyList()) to result.toPrOpResult(host)
    }

    fun getRun(owner: String, repo: String, runId: Long): Pair<WorkflowRun?, PrOpResult> {
        val result = api.getWorkflowRun(owner, repo, runId)
        return result.getOrNull() to result.toPrOpResult(host)
    }

    fun listJobs(owner: String, repo: String, runId: Long): Pair<List<WorkflowJob>, PrOpResult> {
        val result = api.listJobsForRun(owner, repo, runId)
        return (result.getOrNull() ?: emptyList()) to result.toPrOpResult(host)
    }

    fun dispatch(
        owner: String,
        repo: String,
        workflowId: Long,
        ref: String,
        inputs: Map<String, String> = emptyMap()
    ): PrOpResult {
        AppLog.i(TAG, "dispatch: $owner/$repo workflow=$workflowId ref=$ref inputs=$inputs")
        return api.dispatchWorkflow(owner, repo, workflowId, ref, inputs).toPrOpResult(host)
    }

    fun cancelRun(owner: String, repo: String, runId: Long): PrOpResult {
        AppLog.i(TAG, "cancelRun: $owner/$repo run=$runId")
        return api.cancelWorkflowRun(owner, repo, runId).toPrOpResult(host)
    }

    fun rerun(owner: String, repo: String, runId: Long): PrOpResult {
        AppLog.i(TAG, "rerun: $owner/$repo run=$runId")
        return api.rerunWorkflowRun(owner, repo, runId).toPrOpResult(host)
    }
}
