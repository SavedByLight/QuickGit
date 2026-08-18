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
import androidx.compose.ui.platform.LocalContext
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
import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import com.quickgit.app.ui.adaptive.AdaptiveContent
import com.quickgit.app.viewmodel.WorkflowsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsScreen(
    vm: WorkflowsViewModel,
    onBack: (() -> Unit)? = null,
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

    if (state.livePageUrl != null) {
        BackHandler { vm.closeLivePage() }
        LiveJobWebContent(
            url = state.livePageUrl!!,
            title = state.livePageTitle ?: "Live job",
            token = state.livePageToken,
            snackbarHost = snackbarHost,
            onBack = { vm.closeLivePage() }
        )
        return
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
                    navigationIcon = {
                        onBack?.let { back ->
                            IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Back") }
                        }
                    }
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
                navigationIcon = {
                    onBack?.let { back ->
                        IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Back") }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        AdaptiveContent(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize()) {
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
                        onViewLog = { vm.loadJobLog(job.id, job.name) },
                        onOpenLive = { url, title -> vm.openLivePage(url, title) }
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
    onViewLog: () -> Unit = {},
    onOpenLive: (url: String, title: String) -> Unit = { _, _ -> }
) {
    var expanded by remember { mutableStateOf(true) }
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
                            onClick = { onOpenLive(job.htmlUrl, job.name) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Live feed")
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
    val context = LocalContext.current

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
                    // Save to /storage/emulated/0/Downloads/QuickGit/
                    IconButton(
                        onClick = { vm.saveJobLog(context.applicationContext) },
                        enabled = !state.logText.isNullOrBlank() && !state.busy
                    ) {
                        Icon(Icons.Default.Download, "Save log to Downloads/QuickGit")
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


@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveJobWebContent(
    url: String,
    title: String,
    token: String?,
    snackbarHost: SnackbarHostState,
    onBack: () -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var pageTitle by remember { mutableStateOf(title) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val authHeaders = remember(token) {
        if (token.isNullOrBlank()) emptyMap()
        else mapOf("Authorization" to "Bearer $token")
    }

    /** Injects the app's PAT into fetch / XHR so the Actions SPA can call GitHub APIs
     *  as the linked account without a separate web login. WebSockets still rely on
     *  cookies if present; CookieManager keeps any session from a one-time sign-in. */
    fun injectAuthScript(view: WebView?) {
        if (view == null || token.isNullOrBlank()) return
        // Escape for a single-quoted JS string (token is base64-ish / alphanumeric + _-)
        val safeToken = token
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "")
            .replace("\r", "")
        val script = """
            (function() {
              if (window.__quickgitAuthInjected) return;
              window.__quickgitAuthInjected = true;
              var token = '$safeToken';
              function needsAuth(url) {
                if (!url) return false;
                try {
                  var u = new URL(url, location.href);
                  return u.hostname === 'api.github.com' ||
                         u.hostname === 'github.com' ||
                         u.hostname.endsWith('.github.com');
                } catch (e) { return false; }
              }
              var origFetch = window.fetch;
              window.fetch = function(input, init) {
                init = init || {};
                var url = (typeof input === 'string') ? input : (input && input.url);
                if (needsAuth(url)) {
                  var headers = new Headers(init.headers || {});
                  if (!headers.has('Authorization')) {
                    headers.set('Authorization', 'Bearer ' + token);
                  }
                  init = Object.assign({}, init, { headers: headers });
                }
                return origFetch.call(this, input, init);
              };
              var origOpen = XMLHttpRequest.prototype.open;
              var origSend = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function(method, url) {
                this.__qgUrl = url;
                return origOpen.apply(this, arguments);
              };
              XMLHttpRequest.prototype.send = function(body) {
                try {
                  if (needsAuth(this.__qgUrl) && this.setRequestHeader) {
                    this.setRequestHeader('Authorization', 'Bearer ' + token);
                  }
                } catch (e) {}
                return origSend.apply(this, arguments);
              };
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(pageTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (!token.isNullOrBlank()) "Live feed · using linked GitHub account"
                            else "Live feed · connect GitHub in Settings for auth",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val wv = webView
                            if (wv != null) {
                                if (authHeaders.isNotEmpty()) wv.loadUrl(url, authHeaders)
                                else wv.reload()
                            }
                        },
                        enabled = !loading
                    ) {
                        Icon(Icons.Default.Refresh, "Reload")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (token.isNullOrBlank()) {
                Text(
                    "No GitHub token in the app. Open Settings → connect GitHub, or sign in inside this page once (session is saved).",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Authenticated with the account linked in QuickGit. Job data and API calls use your token; for full live streaming you can also sign in once on this page (cookies are kept).",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.cacheMode = WebSettings.LOAD_DEFAULT
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            // Prefer a desktop-ish UA so Actions UI is fully featured.
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loading = newProgress < 100
                                }
                                override fun onReceivedTitle(view: WebView?, t: String?) {
                                    if (!t.isNullOrBlank()) pageTitle = t
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val next = request?.url?.toString() ?: return false
                                    if (authHeaders.isNotEmpty()) {
                                        view?.loadUrl(next, authHeaders)
                                        return true
                                    }
                                    return false
                                }

                                /**
                                 * Attach the app's GitHub PAT to same-site requests so API calls
                                 * from the Actions page can authenticate. Session cookies from a
                                 * one-time web login (if needed) are kept by CookieManager.
                                 */
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    if (request == null || token.isNullOrBlank()) {
                                        return super.shouldInterceptRequest(view, request)
                                    }
                                    val host = request.url.host.orEmpty()
                                    val path = request.url.path.orEmpty()
                                    // Proxy API-style GETs. Static assets and the HTML document
                                    // stay on WebView's stack so streaming/WebSocket work.
                                    val isApi =
                                        host == "api.github.com" ||
                                            (host == "github.com" && (
                                                path.startsWith("/api/") ||
                                                    path.startsWith("/graphql") ||
                                                    path.contains("/_graphql") ||
                                                    path.startsWith("/login/oauth")
                                            ))
                                    if (!isApi || request.method != "GET") {
                                        return super.shouldInterceptRequest(view, request)
                                    }
                                    // Skip non-http schemes and blob/data
                                    val scheme = request.url.scheme.orEmpty()
                                    if (scheme != "https" && scheme != "http") {
                                        return super.shouldInterceptRequest(view, request)
                                    }
                                    return try {
                                        val conn = (URL(request.url.toString()).openConnection() as HttpURLConnection).apply {
                                            instanceFollowRedirects = false
                                            requestMethod = "GET"
                                            connectTimeout = 20_000
                                            readTimeout = 60_000
                                            setRequestProperty("Authorization", "Bearer $token")
                                            // Forward Accept / common headers from the WebView request.
                                            request.requestHeaders.forEach { (k, v) ->
                                                if (!k.equals("Authorization", ignoreCase = true)) {
                                                    setRequestProperty(k, v)
                                                }
                                            }
                                            if (getRequestProperty("Accept").isNullOrBlank()) {
                                                setRequestProperty(
                                                    "Accept",
                                                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                                                )
                                            }
                                        }
                                        val code = conn.responseCode
                                        // Let the WebView handle redirects itself with auth headers.
                                        if (code in 300..399) {
                                            conn.disconnect()
                                            return super.shouldInterceptRequest(view, request)
                                        }
                                        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                                        val mime = conn.contentType
                                            ?.substringBefore(';')
                                            ?.trim()
                                            ?.ifBlank { null }
                                            ?: "text/html"
                                        val encoding = conn.contentEncoding ?: "utf-8"
                                        val headers = conn.headerFields
                                            ?.filterKeys { it != null }
                                            ?.mapKeys { it.key!! }
                                            ?.mapValues { it.value.joinToString(",") }
                                            ?: emptyMap()
                                        // Propagate Set-Cookie into CookieManager for session continuity.
                                        conn.headerFields?.get("Set-Cookie")?.forEach { cookie ->
                                            CookieManager.getInstance().setCookie(request.url.toString(), cookie)
                                        }
                                        WebResourceResponse(
                                            mime,
                                            encoding,
                                            code,
                                            conn.responseMessage ?: "OK",
                                            headers,
                                            stream ?: ByteArrayInputStream(ByteArray(0))
                                        )
                                    } catch (_: Exception) {
                                        super.shouldInterceptRequest(view, request)
                                    }
                                }

                                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                    loading = false
                                    CookieManager.getInstance().flush()
                                    // Re-inject after every navigation so SPA route changes keep the token.
                                    injectAuthScript(view)
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    // Inject early so the first SPA bootstrap requests are covered.
                                    injectAuthScript(view)
                                }
                            }
                            if (authHeaders.isNotEmpty()) loadUrl(url, authHeaders)
                            else loadUrl(url)
                            webView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { /* retain WebView instance */ }
                )
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }
}
