package com.quickgit.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val workflows: List<Workflow> = emptyList(),
    val runs: List<WorkflowRun> = emptyList(),
    val filter: WorkflowRunFilter = WorkflowRunFilter.ALL,
    val selectedWorkflowId: Long? = null,   // null = all workflows
    val loading: Boolean = false,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val authRequiredHost: String? = null,
    val statusMessage: String? = null,
    // Detail of a single run
    val selectedRun: WorkflowRun? = null,
    val jobs: List<WorkflowJob> = emptyList(),
    val detailLoading: Boolean = false,
    val watching: Boolean = false,          // live polling active
    // Dispatch dialog helpers
    val defaultBranch: String = "main"
)

class WorkflowsViewModel(private val workflowManager: WorkflowManager) : ViewModel() {

    private val _state = MutableStateFlow(WorkflowsUiState())
    val state: StateFlow<WorkflowsUiState> = _state.asStateFlow()

    private lateinit var repoPath: String
    private var owner: String? = null
    private var repo: String? = null
    private var pollJob: Job? = null

    fun init(repoPath: String) {
        this.repoPath = repoPath
        viewModelScope.launch {
            val ownerRepo = withContext(Dispatchers.IO) { workflowManager.ownerRepoFor(repoPath) }
            if (ownerRepo == null) {
                _state.value = _state.value.copy(supported = false)
                return@launch
            }
            owner = ownerRepo.owner
            repo = ownerRepo.repo
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
        val o = owner ?: return
        val r = repo ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val (workflows, wfResult) = withContext(Dispatchers.IO) {
                workflowManager.listWorkflows(o, r)
            }
            val (runs, runsResult) = withContext(Dispatchers.IO) {
                workflowManager.listRuns(
                    o, r,
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
        val o = owner ?: return
        val r = repo ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val (runs, result) = withContext(Dispatchers.IO) {
                workflowManager.listRuns(
                    o, r,
                    workflowId = _state.value.selectedWorkflowId,
                    status = _state.value.filter.apiValue
                )
            }
            _state.value = applyResult(_state.value.copy(loading = false, runs = runs), result)
        }
    }

    fun openRun(runId: Long) {
        val o = owner ?: return
        val r = repo ?: return
        stopWatching()
        viewModelScope.launch {
            _state.value = _state.value.copy(
                detailLoading = true,
                selectedRun = null,
                jobs = emptyList(),
                watching = false
            )
            val (run, runResult) = withContext(Dispatchers.IO) { workflowManager.getRun(o, r, runId) }
            val (jobs, jobsResult) = withContext(Dispatchers.IO) { workflowManager.listJobs(o, r, runId) }
            val result = if (runResult !is PrOpResult.Success) runResult else jobsResult
            _state.value = applyResult(
                _state.value.copy(detailLoading = false, selectedRun = run, jobs = jobs),
                result
            )
            // Auto-start live watch if still running
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
        val o = owner ?: return
        val r = repo ?: return
        _state.value = _state.value.copy(watching = true)
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(3_000)
                val (run, _) = withContext(Dispatchers.IO) { workflowManager.getRun(o, r, runId) }
                val (jobs, _) = withContext(Dispatchers.IO) { workflowManager.listJobs(o, r, runId) }
                if (run != null) {
                    _state.value = _state.value.copy(selectedRun = run, jobs = jobs)
                    if (!isLive(run.status)) {
                        _state.value = _state.value.copy(watching = false)
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
        }
    }

    fun dispatch(workflowId: Long, ref: String, inputs: Map<String, String> = emptyMap()) {
        val o = owner ?: return
        val r = repo ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) {
                workflowManager.dispatch(o, r, workflowId, ref, inputs)
            }
            _state.value = applyResult(
                _state.value.copy(busy = false),
                result,
                successMessage = "Workflow dispatched"
            )
            if (result is PrOpResult.Success) {
                delay(1_500) // give GitHub a moment to create the run
                refreshRuns()
            }
        }
    }

    fun cancelRun(runId: Long) {
        val o = owner ?: return
        val r = repo ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { workflowManager.cancelRun(o, r, runId) }
            _state.value = applyResult(
                _state.value.copy(busy = false),
                result,
                successMessage = "Cancel requested"
            )
            if (result is PrOpResult.Success) openRun(runId)
        }
    }

    fun rerun(runId: Long) {
        val o = owner ?: return
        val r = repo ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = withContext(Dispatchers.IO) { workflowManager.rerun(o, r, runId) }
            _state.value = applyResult(
                _state.value.copy(busy = false),
                result,
                successMessage = "Re-run requested"
            )
            if (result is PrOpResult.Success) {
                delay(1_500)
                refreshRuns()
            }
        }
    }

    fun consumeMessages() {
        _state.value = _state.value.copy(
            errorMessage = null,
            statusMessage = null,
            authRequiredHost = null
        )
    }

    private fun isLive(status: String) =
        status == "queued" || status == "in_progress" || status == "requested" || status == "waiting" || status == "pending"

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
        super.onCleared()
    }
}
