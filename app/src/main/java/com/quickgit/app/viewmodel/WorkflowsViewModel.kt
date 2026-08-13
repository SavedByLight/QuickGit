package com.quickgit.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quickgit.app.data.GitProgressNotifier
import com.quickgit.app.data.WorkflowManager
import com.quickgit.app.data.models.PrOpResult
import com.quickgit.app.data.models.Workflow
import com.quickgit.app.data.models.WorkflowJob
import com.quickgit.app.data.models.WorkflowRun
import com.quickgit.app.data.models.WorkflowRunFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WorkflowsUiState(
    val supported: Boolean = true,
    val isGitLab: Boolean = false,
    val workflows: List<Workflow> = emptyList(),
    val runs: List<WorkflowRun> = emptyList(),
    val filter: WorkflowRunFilter = WorkflowRunFilter.ALL,
    val selectedWorkflowId: Long? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val authRequiredHost: String? = null,
    val statusMessage: String? = null,
    val selectedRun: WorkflowRun? = null,
    val jobs: List<WorkflowJob> = emptyList(),
    val detailLoading: Boolean = false,
    val watching: Boolean = false,
    val defaultBranch: String = "main",
    /** Job whose full Actions log is open. */
    val logJobId: Long? = null,
    val logJobName: String? = null,
    val logText: String? = null,
    val logAnnotations: List<com.quickgit.app.data.models.WorkflowAnnotation> = emptyList(),
    val logLoading: Boolean = false,
    /** True while auto-refreshing the open job log (job still running). */
    val logWatching: Boolean = false,
    val logJobStatus: String? = null,
    val logJobConclusion: String? = null,
    /** In-app WebView of the GitHub Actions job page (true live stream). */
    val livePageUrl: String? = null,
    val livePageTitle: String? = null,
    /** Connected GitHub PAT injected into the WebView for authenticated requests. */
    val livePageToken: String? = null
)

class WorkflowsViewModel(
    private val workflowManager: WorkflowManager,
    app: Application
) : ViewModel() {

    private val _state = MutableStateFlow(WorkflowsUiState())
    val state: StateFlow<WorkflowsUiState> = _state.asStateFlow()

    private lateinit var repoPath: String
    private var project: WorkflowManager.ProjectRef? = null
    private var pollJob: Job? = null
    private var logPollJob: Job? = null
    private val notifier = GitProgressNotifier(app)

    fun init(repoPath: String) {
        this.repoPath = repoPath
        viewModelScope.launch {
            val ref = withContext(Dispatchers.IO) { workflowManager.projectFor(repoPath) }
            if (ref == null) {
                _state.value = _state.value.copy(supported = false)
                return@launch
            }
            project = ref
            _state.value = _state.value.copy(supported = true, isGitLab = ref.isGitLab)
            refresh()
        }
    }

    fun setFilter(filter: WorkflowRunFilter) {
        _state.value = _state.value.copy(filter = filter)
        refreshRuns()
    }

    fun selectWorkflow(workflowId: Long?) {
        _state.value = _state.value.copy(selectedWorkflowId = workflowId)
        refreshRuns()
    }

    fun refresh() {
        val ref = project ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val (workflows, wfResult) = withContext(Dispatchers.IO) {
                workflowManager.listWorkflows(ref)
            }
            val (runs, runsResult) = withContext(Dispatchers.IO) {
                workflowManager.listRuns(
                    ref,
                    workflowId = _state.value.selectedWorkflowId,
                    status = _state.value.filter.apiValue
                )
            }
            val result = if (wfResult !is PrOpResult.Success) wfResult else runsResult
            _state.value = applyResult(
                _state.value.copy(loading = false, workflows = workflows, runs = runs),
                result
            )
        }
    }

    fun refreshRuns() {
        val ref = project ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val (runs, result) = withContext(Dispatchers.IO) {
                workflowManager.listRuns(
                    ref,
                    workflowId = _state.value.selectedWorkflowId,
                    status = _state.value.filter.apiValue
                )
            }
            _state.value = applyResult(_state.value.copy(loading = false, runs = runs), result)
        }
    }

    fun openRun(runId: Long) {
        val ref = project ?: return
        stopWatching()
        viewModelScope.launch {
            _state.value = _state.value.copy(
                detailLoading = true,
                selectedRun = null,
                jobs = emptyList(),
                watching = false
            )
            val (run, runResult) = withContext(Dispatchers.IO) { workflowManager.getRun(ref, runId) }
            val (jobs, jobsResult) = withContext(Dispatchers.IO) { workflowManager.listJobs(ref, runId) }
            val result = if (runResult !is PrOpResult.Success) runResult else jobsResult
            _state.value = applyResult(
                _state.value.copy(detailLoading = false, selectedRun = run, jobs = jobs),
                result
            )
            if (run != null && isLive(run.status)) {
                startWatching(runId)
            }
        }
    }

    fun closeDetail() {
        stopWatching()
        _state.value = _state.value.copy(
            selectedRun = null,
            jobs = emptyList(),
            watching = false
        )
    }

    fun startWatching(runId: Long) {
        stopWatching()
        val ref = project ?: return
        _state.value = _state.value.copy(watching = true)
        notifier.start(GitProgressNotifier.Kind.ACTIONS, "Actions run", "Watching run #$runId…")
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(2_000)
                val (run, _) = withContext(Dispatchers.IO) { workflowManager.getRun(ref, runId) }
                val (jobs, _) = withContext(Dispatchers.IO) { workflowManager.listJobs(ref, runId) }
                if (run != null) {
                    val jobSummary = jobs.joinToString(", ") {
                        "${it.name}: ${it.conclusion ?: it.status}"
                    }.ifBlank { run.status }
                    val title = run.name.ifBlank { "Run #$runId" }
                    notifier.start(GitProgressNotifier.Kind.ACTIONS, title, jobSummary)
                    _state.value = _state.value.copy(selectedRun = run, jobs = jobs)
                    if (!isLive(run.status)) {
                        _state.value = _state.value.copy(watching = false)
                        val conclusion = run.conclusion ?: run.status
                        when (conclusion.lowercase()) {
                            "success", "completed" ->
                                notifier.finish(GitProgressNotifier.Kind.ACTIONS, "$title · $conclusion")
                            "cancelled", "canceled", "skipped" ->
                                notifier.finish(GitProgressNotifier.Kind.ACTIONS, "$title · $conclusion")
                            else ->
                                notifier.finish(GitProgressNotifier.Kind.ACTIONS, "$title · $conclusion")
                        }
                        val finishedJob = jobs.firstOrNull { it.conclusion != null }
                            ?: jobs.firstOrNull()
                        if (finishedJob != null && _state.value.logJobId == null && !ref.isGitLab) {
                            loadJobLog(finishedJob.id, finishedJob.name)
                        }
                        break
                    }
                }
            }
        }
    }

    fun stopWatching() {
        pollJob?.cancel()
        pollJob = null
        if (_state.value.watching) {
            _state.value = _state.value.copy(watching = false)
            notifier.cancel(GitProgressNotifier.Kind.ACTIONS)
        }
    }

    fun dispatch(workflowId: Long, refName: String, inputs: Map<String, String> = emptyMap()) {
        val ref = project ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            notifier.start(
                GitProgressNotifier.Kind.ACTIONS,
                "Dispatch workflow",
                "Starting on $refName…"
            )
            val result = withContext(Dispatchers.IO) {
                workflowManager.dispatch(ref, workflowId, refName, inputs)
            }
            _state.value = applyResult(
                _state.value.copy(busy = false),
                result,
                successMessage = if (ref.isGitLab) null else "Workflow dispatched"
            )
            if (result is PrOpResult.Success) {
                notifier.update(GitProgressNotifier.Kind.ACTIONS, "Dispatched — waiting for run…")
                delay(1_500)
                refreshRuns()
                // Pick up the newest in-progress run and watch it with notifications.
                val newest = _state.value.runs.firstOrNull { isLive(it.status) }
                    ?: _state.value.runs.firstOrNull()
                if (newest != null && isLive(newest.status)) {
                    openRun(newest.id)
                } else {
                    notifier.finish(GitProgressNotifier.Kind.ACTIONS, "Workflow dispatched")
                }
            } else {
                notifier.cancel(GitProgressNotifier.Kind.ACTIONS)
            }
        }
    }

    fun cancelRun(runId: Long) {
        val ref = project ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            notifier.update(GitProgressNotifier.Kind.ACTIONS, "Cancel requested…")
            val result = withContext(Dispatchers.IO) { workflowManager.cancelRun(ref, runId) }
            _state.value = applyResult(
                _state.value.copy(busy = false),
                result,
                successMessage = "Cancel requested"
            )
            if (result is PrOpResult.Success) openRun(runId)
            else notifier.cancel(GitProgressNotifier.Kind.ACTIONS)
        }
    }

    fun rerun(runId: Long) {
        val ref = project ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            notifier.start(GitProgressNotifier.Kind.ACTIONS, "Re-run workflow", "Requesting re-run…")
            val result = withContext(Dispatchers.IO) { workflowManager.rerun(ref, runId) }
            _state.value = applyResult(
                _state.value.copy(busy = false),
                result,
                successMessage = "Re-run requested"
            )
            if (result is PrOpResult.Success) {
                delay(1_500)
                refreshRuns()
                val newest = _state.value.runs.firstOrNull { isLive(it.status) }
                if (newest != null) openRun(newest.id)
                else notifier.finish(GitProgressNotifier.Kind.ACTIONS, "Re-run requested")
            } else {
                notifier.cancel(GitProgressNotifier.Kind.ACTIONS)
            }
        }
    }

    fun loadJobLog(jobId: Long, jobName: String) {
        val ref = project ?: return
        if (ref.isGitLab) {
            _state.value = _state.value.copy(
                errorMessage = "Job logs are only available for GitHub Actions"
            )
            return
        }
        stopLogWatching()
        val knownJob = _state.value.jobs.find { it.id == jobId }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                logJobId = jobId,
                logJobName = jobName,
                logText = null,
                logAnnotations = emptyList(),
                logLoading = true,
                logWatching = false,
                logJobStatus = knownJob?.status,
                logJobConclusion = knownJob?.conclusion,
                errorMessage = null
            )
            fetchAndApplyJobLog(ref, jobId, initial = true)
            // Keep refreshing while the job is still running so the log streams live.
            val status = _state.value.logJobStatus
            if (status != null && isLive(status)) {
                startLogWatching(jobId)
            }
        }
    }

    /** Manual refresh of the open job log (also used by the UI refresh button). */
    fun refreshJobLog() {
        val ref = project ?: return
        val jobId = _state.value.logJobId ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(logLoading = true, errorMessage = null)
            fetchAndApplyJobLog(ref, jobId, initial = false)
            val status = _state.value.logJobStatus
            if (status != null && isLive(status) && !_state.value.logWatching) {
                startLogWatching(jobId)
            } else if (status != null && !isLive(status)) {
                stopLogWatching()
            }
        }
    }


    fun saveJobLog(context: Context) {
        val text = _state.value.logText
        val jobId = _state.value.logJobId
        val jobName = _state.value.logJobName ?: "job"
        if (text.isNullOrBlank() || jobId == null) {
            _state.value = _state.value.copy(errorMessage = "No log to save yet")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val (path, result) = withContext(Dispatchers.IO) {
                workflowManager.saveJobLogToDownloads(context, jobId, jobName, text)
            }
            _state.value = applyResult(
                _state.value.copy(busy = false),
                result,
                successMessage = path?.let { "Saved to $it" } ?: "Log saved to Downloads/QuickGit"
            )
        }
    }

    fun closeJobLog() {
        stopLogWatching()
        _state.value = _state.value.copy(
            logJobId = null,
            logJobName = null,
            logText = null,
            logAnnotations = emptyList(),
            logLoading = false,
            logWatching = false,
            logJobStatus = null,
            logJobConclusion = null
        )
    }

    fun startLogWatching(jobId: Long) {
        stopLogWatching()
        val ref = project ?: return
        _state.value = _state.value.copy(logWatching = true)
        logPollJob = viewModelScope.launch {
            while (isActive) {
                delay(4_000)
                // Quiet refresh — don't flash the full loading spinner on every poll.
                val (log, annotations, result) = withContext(Dispatchers.IO) {
                    workflowManager.getJobLog(ref, jobId)
                }
                val jobFromList = _state.value.jobs.find { it.id == jobId }
                // Also refresh the parent run's jobs so step status stays current.
                val runId = _state.value.selectedRun?.id
                val updatedJobs = if (runId != null) {
                    withContext(Dispatchers.IO) { workflowManager.listJobs(ref, runId).first }
                } else {
                    _state.value.jobs
                }
                val job = updatedJobs.find { it.id == jobId } ?: jobFromList
                val newStatus = job?.status
                val newConclusion = job?.conclusion
                if (result is PrOpResult.Success || log != null) {
                    _state.value = _state.value.copy(
                        logText = log ?: _state.value.logText,
                        logAnnotations = if (annotations.isNotEmpty()) annotations else _state.value.logAnnotations,
                        jobs = if (updatedJobs.isNotEmpty()) updatedJobs else _state.value.jobs,
                        logJobStatus = newStatus ?: _state.value.logJobStatus,
                        logJobConclusion = newConclusion ?: _state.value.logJobConclusion,
                        errorMessage = null
                    )
                }
                if (newStatus != null && !isLive(newStatus)) {
                    _state.value = _state.value.copy(logWatching = false)
                    break
                }
            }
        }
    }

    fun stopLogWatching() {
        logPollJob?.cancel()
        logPollJob = null
        if (_state.value.logWatching) {
            _state.value = _state.value.copy(logWatching = false)
        }
    }

    private suspend fun fetchAndApplyJobLog(
        ref: WorkflowManager.ProjectRef,
        jobId: Long,
        initial: Boolean
    ) {
        val (log, annotations, result) = withContext(Dispatchers.IO) {
            workflowManager.getJobLog(ref, jobId)
        }
        val job = _state.value.jobs.find { it.id == jobId }
        _state.value = applyResult(
            _state.value.copy(
                logLoading = false,
                logText = log,
                logAnnotations = annotations,
                logJobStatus = job?.status ?: _state.value.logJobStatus,
                logJobConclusion = job?.conclusion ?: _state.value.logJobConclusion
            ),
            result
        )
    }

    fun openLivePage(url: String, title: String) {
        if (url.isBlank()) return
        val token = workflowManager.githubHttpsToken()
        _state.value = _state.value.copy(
            livePageUrl = url,
            livePageTitle = title,
            livePageToken = token
        )
    }

    fun closeLivePage() {
        _state.value = _state.value.copy(
            livePageUrl = null,
            livePageTitle = null,
            livePageToken = null
        )
    }

    fun consumeMessages() {
        _state.value = _state.value.copy(
            errorMessage = null,
            statusMessage = null,
            authRequiredHost = null
        )
    }

    private fun isLive(status: String) =
        status == "queued" || status == "in_progress" || status == "requested" ||
            status == "waiting" || status == "pending" || status == "running"

    private fun applyResult(
        state: WorkflowsUiState,
        result: PrOpResult,
        successMessage: String? = null
    ): WorkflowsUiState = when (result) {
        is PrOpResult.Success -> state.copy(
            statusMessage = successMessage,
            errorMessage = null,
            authRequiredHost = null
        )
        is PrOpResult.Error -> state.copy(
            errorMessage = result.message,
            statusMessage = null
        )
        is PrOpResult.AuthRequired -> state.copy(
            authRequiredHost = result.host,
            errorMessage = null,
            statusMessage = null
        )
    }

    override fun onCleared() {
        stopWatching()
        stopLogWatching()
        super.onCleared()
    }
}
