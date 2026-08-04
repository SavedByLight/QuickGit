package com.quickgit.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Wraps [content] with swipe-to-refresh behavior: dragging down from the top
 * triggers [onRefresh], and a spinner is shown at the top while [isRefreshing]
 * is true (whether that refresh was triggered by the gesture or by something
 * else, like an initial load).
 *
 * [modifier] controls the size/placement of the box (e.g. `Modifier.fillMaxSize()`
 * or `Modifier.weight(1f).fillMaxWidth()` inside a Column) — [content] is laid
 * out exactly as it would be inside a plain `Box` with that modifier.
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun PullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val pullRefreshState = rememberPullRefreshState(refreshing = isRefreshing, onRefresh = onRefresh)
    Box(modifier.pullRefresh(pullRefreshState)) {
        content()
        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}
