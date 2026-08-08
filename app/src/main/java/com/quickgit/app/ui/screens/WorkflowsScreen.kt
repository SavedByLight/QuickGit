package com.quickgit.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.quickgit.app.data.models.Workflow
import com.quickgit.app.data.models.WorkflowAnnotation
import com.quickgit.app.data.models.WorkflowJob
import com.quickgit.app.data.models.WorkflowRun
import com.quickgit.app.data.models.WorkflowRunFilter
import com.quickgit.app.data.models.WorkflowStep
import com.quickgit.app.ui.theme.GitAmber
import com.quickgit.app.ui.theme.GitGreen
import com.quickgit.app.ui.theme.GitRed
import com.quickgit.app.viewmodel.WorkflowsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsScreen(
    vm: WorkflowsViewModel,
    onBack: () -> Unit,
    onNeedsAuth: (String) -> Unit
) {
    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var showDispatch by remember { mutableStateOf<Workflow?>(null) }

    LaunchedEffect(state.errorMessage, state.statusMessage, state.authRequiredHost) {
        state.errorMessage?.let { snackbarHost.showSnackbar(it); vm.consumeMessages() }
        state.statusMessage?.let { snackbarHost.showSnackbar(it); vm.consumeMessages() }
        state.authRequiredHost?.let { onNeedsAuth("https://$it/"); vm.consumeMessages() }
    }

    if (state.logJobId != null) {
        BackHandler { vm.closeJobLog() }
        JobLogContent(
            vm = vm,
            snackbarHost = snackbarHost,
            onBack = { vm.closeJobLog() }
        )
        return
    }

    if (state.selectedRun != null) {
        BackHandler { vm.closeDetail() }
        RunDetailContent(
            vm = vm,
            snackbarHost = snackbarHost,
            onBack = { vm.closeDetail() }
        )
        return
    }

    if (!state.supported) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (state.isGitLab) "Build" else "Actions") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
                )
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "CI is available for GitHub Actions and GitLab pipelines. " +
                        "This repo's origin isn't recognized as either.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.isGitLab) "Build" else "Actions") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Workflow filter chips
            if (state.workflows.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = state.selectedWorkflowId == null,
                            onClick = { vm.selectWorkflow(null) },
                            label = { Text("All workflows") }
                        )
                    }
                    items(state.workflows, key = { it.id }) { wf ->
                        FilterChip(
                            selected = state.selectedWorkflowId == wf.id,
                            onClick = { vm.selectWorkflow(wf.id) },
                            label = { Text(wf.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { showDispatch = wf },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, "Run", Modifier.size(16.dp))
                                }
                            }
                        )
                    }
                }
            }

            // Status filter
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WorkflowRunFilter.entries.forEach { f ->
                    FilterChip(
                        selected = state.filter == f,
                        onClick = { vm.setFilter(f) },
                        label = { Text(f.label) }
                    )
                }
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading && state.runs.isEmpty() -> {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    state.runs.isEmpty() -> {
                        Text(
                            "No workflow runs found.",
                            Modifier.align(Alignment.Center).padding(32.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.runs, key = { it.id }) { run ->
                                RunCard(run = run, onClick = { vm.openRun(run.id) })
                            }
                        }
                    }
                }
                if (state.loading && state.runs.isNotEmpty()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }

    showDispatch?.let { wf ->
        DispatchDialog(
            workflow = wf,
            busy = state.busy,
            defaultRef = state.defaultBranch,
            onDismiss = { showDispatch = null },
            onDispatch = { ref, inputs ->
                vm.dispatch(wf.id, ref, inputs)
                showDispatch = null
            }
        )
    }
}

@Composable
private fun RunCard(run: WorkflowRun, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(status = run.status, conclusion = run.conclusion)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    run.displayTitle.ifBlank { run.name },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append("#${run.runNumber}")
                        if (!run.headBranch.isNullOrBlank()) append(" · ${run.headBranch}")
                        if (!run.actorLogin.isNullOrBlank()) append(" · ${run.actorLogin}")
                        append(" · ${run.event}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusLabel(status = run.status, conclusion = run.conclusion)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunDetailContent(
    vm: WorkflowsViewModel,
    snackbarHost: SnackbarHostState,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsState()
    val run = state.selectedRun ?: return

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            run.displayTitle.ifBlank { run.name },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "Run #${run.runNumber}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    if (state.watching) {
                        IconButton(onClick = { vm.stopWatching() }) {
                            Icon(Icons.Default.Pause, "Stop watching")
                        }
                    } else if (isLiveStatus(run.status)) {
                        IconButton(onClick = { vm.startWatching(run.id) }) {
                            Icon(Icons.Default.PlayArrow, "Watch live")
                        }
                    }
                    if (isLiveStatus(run.status)) {
                        IconButton(onClick = { vm.cancelRun(run.id) }, enabled = !state.busy) {
                            Icon(Icons.Default.Cancel, "Cancel")
                        }
                    } else {
                        IconButton(onClick = { vm.rerun(run.id) }, enabled = !state.busy) {
                            Icon(Icons.Default.Replay, "Re-run")
                        }
                    }
                    IconButton(onClick = { vm.openRun(run.id) }, enabled = !state.detailLoading) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        if (state.detailLoading && state.jobs.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Header status
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status = run.status, conclusion = run.conclusion, size = 14.dp)
                Spacer(Modifier.width(8.dp))
                StatusLabel(status = run.status, conclusion = run.conclusion)
                if (state.watching) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "● Live",
                        color = GitAmber,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            InfoRow("Workflow", run.workflowName.ifBlank { run.name })
            InfoRow("Event", run.event)
            if (!run.headBranch.isNullOrBlank()) InfoRow("Branch", run.headBranch)
            if (!run.headSha.isNullOrBlank()) InfoRow("SHA", run.headSha.take(7))
            if (!run.actorLogin.isNullOrBlank()) InfoRow("Triggered by", run.actorLogin)
            InfoRow("Attempt", run.runAttempt.toString())
            if (!run.runStartedAt.isNullOrBlank()) InfoRow("Started", formatIso(run.runStartedAt))
            InfoRow("Updated", formatIso(run.updatedAt))

            Spacer(Modifier.height(20.dp))
            Text("Jobs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            if (state.jobs.isEmpty()) {
                Text(
                    "No jobs yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.jobs.forEach { job ->
                    JobCard(
                        job = job,
                        showLogButton = !state.isGitLab,
                        onViewLog = { vm.loadJobLog(job.id, job.name) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun JobCard(
    job: WorkflowJob,
    showLogButton: Boolean = false,
    onViewLog: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(true) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val currentStep = job.steps.firstOrNull { isLiveStatus(it.status) }
        ?: job.steps.lastOrNull { it.status == "completed" }
    val live = isLiveStatus(job.status)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(status = job.status, conclusion = job.conclusion)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        job.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    if (live && currentStep != null) {
                        Text(
                            "→ ${currentStep.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = GitAmber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (!job.runnerName.isNullOrBlank()) {
                        Text(
                            "Runner: ${job.runnerName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (live) {
                    Text(
                        "● LIVE",
                        color = GitAmber,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                }
                StatusLabel(status = job.status, conclusion = job.conclusion)
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }

            if (live || !job.runnerName.isNullOrBlank() || job.labels.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        if (!job.runnerName.isNullOrBlank()) append(job.runnerName)
                        if (!job.runnerGroupName.isNullOrBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(job.runnerGroupName)
                        }
                        if (job.labels.isNotEmpty()) {
                            if (isNotEmpty()) append(" · ")
                            append(job.labels.joinToString(", "))
                        }
                        if (live && isEmpty()) append("Waiting for runner…")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (showLogButton) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onViewLog,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Article, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (live) "Log (when ready)" else "View full log")
                    }
                    if (job.htmlUrl.isNotBlank()) {
                        TextButton(
                            onClick = { uriHandler.openUri(job.htmlUrl) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("GitHub live")
                        }
                    }
                }
            }
            if (expanded && job.steps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                job.steps.forEach { step ->
                    StepRow(step, highlight = isLiveStatus(step.status))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobLogContent(
    vm: WorkflowsViewModel,
    snackbarHost: SnackbarHostState,
    onBack: () -> Unit
) {
    val state by vm.state.collectAsState()
    val scroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when live log grows so you can follow progress.
    val logLength = state.logText?.length ?: 0
    LaunchedEffect(logLength, state.logWatching) {
        if (state.logWatching && logLength > 0) {
            scroll.animateScrollTo(scroll.maxValue)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.logJobName ?: "Job log",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    state.logWatching -> "Live log · updating…"
                                    state.logJobStatus != null ->
                                        "Full workflow log · ${state.logJobStatus}"
                                    else -> "Full workflow log"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (state.logWatching) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "● LIVE",
                                    color = GitAmber,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    // Copy entire log to clipboard
                    IconButton(
                        onClick = {
                            val text = state.logText
                            if (!text.isNullOrBlank()) {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                                scope.launch {
                                    snackbarHost.showSnackbar("Full log copied to clipboard")
                                }
                            }
                        },
                        enabled = !state.logText.isNullOrBlank()
                    ) {
                        Icon(Icons.Default.ContentCopy, "Copy entire log")
                    }
                    // Toggle live watching
                    if (state.logJobId != null) {
                        val status = state.logJobStatus
                        if (state.logWatching) {
                            IconButton(onClick = { vm.stopLogWatching() }) {
                                Icon(Icons.Default.Pause, "Stop live updates")
                            }
                        } else if (status != null && isLiveStatus(status)) {
                            IconButton(onClick = { vm.startLogWatching(state.logJobId!!) }) {
                                Icon(Icons.Default.PlayArrow, "Watch live")
                            }
                        }
                        IconButton(
                            onClick = { vm.refreshJobLog() },
                            enabled = !state.logLoading
                        ) {
                            Icon(Icons.Default.Refresh, "Reload log")
                        }
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.logLoading && state.logText == null -> {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Downloading log…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                ) {
                    if (state.logWatching || state.logLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (state.logAnnotations.isNotEmpty()) {
                        Text(
                            "Annotations (${state.logAnnotations.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        state.logAnnotations.forEach { ann ->
                            AnnotationCard(ann)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                    val text = state.logText
                    if (text.isNullOrBlank()) {
                        val waiting = state.logWatching ||
                            (state.logJobStatus != null && isLiveStatus(state.logJobStatus!!))
                        Text(
                            if (waiting)
                                "Waiting for log output… GitHub only publishes logs after the runner starts writing them. This will update automatically."
                            else
                                "No log text available for this job. Logs may still be uploading, may have expired, or the job never produced output.",
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .horizontalScroll(hScroll)
                                        .verticalScroll(scroll)
                                        .padding(12.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                        .padding(8.dp)
                                ) {
                                    text.lineSequence().forEach { line ->
                                        LogLine(line)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnotationCard(ann: WorkflowAnnotation) {
    val levelColor = when (ann.annotationLevel.lowercase()) {
        "failure", "error" -> GitRed
        "warning" -> GitAmber
        else -> MaterialTheme.colorScheme.primary
    }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ann.annotationLevel.uppercase(),
                    color = levelColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                if (!ann.title.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(ann.title, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (!ann.path.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(ann.path)
                        ann.startLine?.let { append(":$it") }
                        ann.endLine?.takeIf { it != ann.startLine }?.let { append("–$it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(ann.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LogLine(line: String) {
    val lower = line.lowercase()
    val color = when {
        "##[error]" in lower || line.contains("\u001b[31m") ||
            lower.contains("error:") || lower.startsWith("error ") -> GitRed
        "##[warning]" in lower || lower.contains("warning:") -> GitAmber
        "##[notice]" in lower || "##[group]" in lower -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        line.ifEmpty { " " },
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        maxLines = 1,
        softWrap = false
    )
}

@Composable
private fun StepRow(step: WorkflowStep, highlight: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp)
            .then(
                if (highlight) Modifier.background(
                    GitAmber.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.small
                ).padding(horizontal = 4.dp, vertical = 2.dp)
                else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(status = step.status, conclusion = step.conclusion, size = 8.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "${step.number}. ${step.name}",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlight) GitAmber else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (highlight) {
            Text(
                "running",
                style = MaterialTheme.typography.labelSmall,
                color = GitAmber,
                fontWeight = FontWeight.Bold
            )
        } else {
            StatusLabel(status = step.status, conclusion = step.conclusion, compact = true)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            Modifier.width(110.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusDot(
    status: String,
    conclusion: String?,
    size: androidx.compose.ui.unit.Dp = 10.dp
) {
    val color = statusColor(status, conclusion)
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun StatusLabel(
    status: String,
    conclusion: String?,
    compact: Boolean = false
) {
    val text = when {
        status == "completed" && !conclusion.isNullOrBlank() -> conclusion
        else -> status
    }.replace('_', ' ')
    val color = statusColor(status, conclusion)
    Text(
        text.replaceFirstChar { it.uppercase() },
        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.Medium
    )
}

private fun statusColor(status: String, conclusion: String?): Color = when {
    status == "completed" && conclusion == "success" -> GitGreen
    status == "completed" && (conclusion == "failure" || conclusion == "timed_out") -> GitRed
    status == "completed" && conclusion == "cancelled" -> Color.Gray
    status == "completed" && conclusion == "skipped" -> Color.Gray
    isLiveStatus(status) -> GitAmber
    else -> Color.Gray
}

private fun isLiveStatus(status: String) =
    status == "queued" || status == "in_progress" || status == "requested" ||
        status == "waiting" || status == "pending"

private fun formatIso(iso: String): String {
    // Keep it simple: strip Z and milliseconds for readability
    return iso.replace("T", " ").removeSuffix("Z").take(19)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DispatchDialog(
    workflow: Workflow,
    busy: Boolean,
    defaultRef: String,
    onDismiss: () -> Unit,
    onDispatch: (ref: String, inputs: Map<String, String>) -> Unit
) {
    var ref by remember { mutableStateOf(defaultRef) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run “${workflow.name}”") },
        text = {
            Column {
                Text(
                    "This triggers a workflow_dispatch event. Provide the branch, tag, or SHA to run against.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = ref,
                    onValueChange = { ref = it },
                    label = { Text("Ref (branch / tag / SHA)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (ref.isNotBlank()) onDispatch(ref.trim(), emptyMap()) },
                enabled = !busy && ref.isNotBlank()
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Run workflow")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
