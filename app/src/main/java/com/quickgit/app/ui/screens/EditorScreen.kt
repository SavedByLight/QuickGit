package com.quickgit.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickgit.app.ui.components.SyntaxHighlight
import com.quickgit.app.ui.components.rememberSyntaxPalette
import com.quickgit.app.ui.theme.GitGreen
import com.quickgit.app.viewmodel.EditorViewModel
import kotlinx.coroutines.launch

/**
 * Mobile-first text editor for repo files.
 *
 * Intentionally not a VS Code clone: Material 3 chrome, QuickGit accents,
 * bottom save bar, find-in-file, no activity bar / minimap / command palette.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    repoPath: String,
    relativePath: String,
    vm: EditorViewModel,
    onBack: () -> Unit
) {
    LaunchedEffect(repoPath, relativePath) { vm.load(repoPath, relativePath) }
    val state by vm.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var findOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.savedMessage, state.error) {
        state.savedMessage?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeMessage()
        }
        state.error?.let {
            if (!state.loading) {
                snackbarHost.showSnackbar(it)
                vm.consumeMessage()
            }
        }
    }

    BackHandler(enabled = findOpen) { findOpen = false }

    // enableEdgeToEdge() means adjustResize alone does not lift content above the
    // IME. imePadding() on the Scaffold shrinks the layout when the keyboard is
    // visible so the bottom of the text and the save bar stay reachable.
    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            relativePath.substringAfterLast('/'),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            relativePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    if (!state.loading && state.error == null) {
                        IconButton(onClick = { findOpen = !findOpen }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = if (findOpen) "Close find" else "Find"
                            )
                        }
                    }
                    if (state.isDirty) {
                        Text(
                            "edited",
                            color = GitGreen,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (!state.loading && state.error == null) {
                Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (state.isDirty) "Unsaved changes" else "No changes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { vm.save() },
                            enabled = state.isDirty && !state.saving
                        ) {
                            if (state.saving) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Save")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.content.isEmpty() -> {
                    Text(
                        state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    )
                }
                else -> CodeEditor(
                    text = state.content,
                    onTextChange = vm::setContent,
                    filePath = relativePath,
                    findOpen = findOpen,
                    onFindClose = { findOpen = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun CodeEditor(
    text: String,
    onTextChange: (String) -> Unit,
    filePath: String,
    findOpen: Boolean,
    onFindClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Keep a TextFieldValue so we can move the selection to search matches.
    var fieldValue by remember { mutableStateOf(TextFieldValue(text)) }
    // Sync external content changes (load / undo from VM) without wiping selection
    // when the user is typing (same string).
    LaunchedEffect(text) {
        if (text != fieldValue.text) {
            fieldValue = TextFieldValue(text, TextRange(text.length))
        }
    }

    var query by remember { mutableStateOf("") }
    var matchIndex by remember { mutableIntStateOf(0) }
    val findFocus = remember { FocusRequester() }

    val matches = remember(fieldValue.text, query) {
        findMatches(fieldValue.text, query)
    }
    // Clamp index when the match list shrinks.
    LaunchedEffect(matches.size) {
        if (matches.isEmpty()) matchIndex = 0
        else if (matchIndex >= matches.size) matchIndex = matches.size - 1
    }

    fun goToMatch(index: Int) {
        if (matches.isEmpty()) return
        val i = ((index % matches.size) + matches.size) % matches.size
        matchIndex = i
        val (start, end) = matches[i]
        fieldValue = fieldValue.copy(selection = TextRange(start, end))
        // Approximate vertical scroll so the match line is in view.
        val line = fieldValue.text.substring(0, start).count { it == '\n' }
        val lineHeightPx = with(density) { 20.sp.toPx() }
        val target = (line * lineHeightPx).toInt().coerceAtLeast(0)
        scope.launch {
            vScroll.animateScrollTo(target.coerceAtMost(vScroll.maxValue))
        }
    }

    LaunchedEffect(findOpen) {
        if (findOpen) {
            try {
                findFocus.requestFocus()
            } catch (_: Exception) { /* not yet attached */ }
            if (query.isNotEmpty() && matches.isNotEmpty()) goToMatch(matchIndex)
        }
    }

    // When query changes and there are matches, jump to the first one.
    LaunchedEffect(query, matches) {
        if (findOpen && query.isNotEmpty() && matches.isNotEmpty()) {
            goToMatch(0)
        }
    }

    val lineCount = remember(fieldValue.text) { fieldValue.text.count { it == '\n' } + 1 }
    val gutterWidth = when {
        lineCount < 100 -> 40.dp
        lineCount < 1000 -> 48.dp
        else -> 56.dp
    }
    val baseColor = MaterialTheme.colorScheme.onSurface
    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = baseColor
    )
    val gutterBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val gutterFg = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val language = remember(filePath) { SyntaxHighlight.languageFromPath(filePath) }
    val palette = rememberSyntaxPalette(baseColor)
    val highlightTransform = remember(language, palette) {
        SyntaxHighlight.visualTransformation(language, palette)
    }
    val lineHeightDp = with(density) { 20.sp.toDp() }

    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        if (findOpen) {
            FindBar(
                query = query,
                onQueryChange = { query = it },
                matchIndex = matchIndex,
                matchCount = matches.size,
                onNext = { goToMatch(matchIndex + 1) },
                onPrev = { goToMatch(matchIndex - 1) },
                onClose = onFindClose,
                focusRequester = findFocus
            )
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Line-number gutter — fixed width, scrolls with content vertically.
            // Gutter and editor share the same vertical scroll + identical lineHeight so
            // line numbers stay aligned with the corresponding code lines.
            Column(
                Modifier
                    .width(gutterWidth)
                    .fillMaxHeight()
                    .background(gutterBg)
                    .verticalScroll(vScroll)
                    .padding(top = 12.dp, bottom = 12.dp, end = 6.dp),
                horizontalAlignment = Alignment.End
            ) {
                for (i in 1..lineCount) {
                    Text(
                        text = i.toString(),
                        style = codeStyle.copy(
                            color = gutterFg,
                            fontSize = 11.sp,
                            lineHeight = 20.sp
                        ),
                        modifier = Modifier
                            .height(lineHeightDp)
                            .wrapContentHeight(align = Alignment.CenterVertically),
                        maxLines = 1
                    )
                }
            }
            // Editable body with language-colored tokens
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll)
                    .padding(12.dp)
            ) {
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { newValue ->
                        fieldValue = newValue
                        if (newValue.text != text) onTextChange(newValue.text)
                    },
                    textStyle = codeStyle,
                    cursorBrush = SolidColor(GitGreen),
                    visualTransformation = highlightTransform,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchIndex: Int,
    matchCount: Int,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester
) {
    Surface(tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text("Find") },
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GitGreen,
                    cursorColor = GitGreen
                )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = when {
                    query.isEmpty() -> ""
                    matchCount == 0 -> "0/0"
                    else -> "${matchIndex + 1}/$matchCount"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (query.isNotEmpty() && matchCount == 0)
                    MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 40.dp)
            )
            IconButton(
                onClick = onPrev,
                enabled = matchCount > 0
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match")
            }
            IconButton(
                onClick = onNext,
                enabled = matchCount > 0
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close find")
            }
        }
    }
}

/** Case-insensitive substring matches as (start, end) exclusive ranges. */
private fun findMatches(text: String, query: String): List<Pair<Int, Int>> {
    if (query.isEmpty()) return emptyList()
    val hay = text.lowercase()
    val needle = query.lowercase()
    if (needle.isEmpty()) return emptyList()
    val out = ArrayList<Pair<Int, Int>>()
    var from = 0
    while (from <= hay.length - needle.length) {
        val idx = hay.indexOf(needle, from)
        if (idx < 0) break
        out.add(idx to (idx + needle.length))
        from = idx + 1
        // Cap pathological cases (e.g. searching "a" in a huge file)
        if (out.size >= 10_000) break
    }
    return out
}
