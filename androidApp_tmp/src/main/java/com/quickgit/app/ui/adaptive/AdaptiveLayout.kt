package com.quickgit.app.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Provides the current [WindowSizeClass] (from Activity) to the composition tree.
 * Calculated in [com.quickgit.app.MainActivity] so it updates on resize / multi-window.
 */
val LocalWindowSizeClass = compositionLocalOf<WindowSizeClass> {
    error("LocalWindowSizeClass not provided — wrap content in MainActivity with WindowSizeClass")
}

/** True when width is at least Medium (≈ 600dp+), i.e. tablet / Chromebook / wide window. */
val WindowSizeClass.isMediumWidth: Boolean
    get() = widthSizeClass != WindowWidthSizeClass.Compact

/** True when width is Expanded (≈ 840dp+), i.e. large tablet landscape / desktop window. */
val WindowSizeClass.isExpandedWidth: Boolean
    get() = widthSizeClass == WindowWidthSizeClass.Expanded

/** True when height is Compact (≈ under 480dp) — typical phone landscape / short tablet landscape. */
val WindowSizeClass.isCompactHeight: Boolean
    get() = heightSizeClass == WindowHeightSizeClass.Compact

/**
 * Useful for deciding layout that should only change on real tablets / large windows,
 * not on phones in landscape. Medium/Expanded width is enough; phones stay Compact width
 * even when rotated.
 */
val WindowSizeClass.isTabletOrWider: Boolean
    get() = isMediumWidth

/**
 * Horizontal content padding that grows on larger windows so lists and forms
 * don't stretch edge-to-edge on tablets and Chromebooks.
 * On compact-height landscape, use slightly less padding so vertical space wins.
 */
@Composable
fun adaptiveContentPadding(): Dp {
    val wsc = LocalWindowSizeClass.current
    return when {
        wsc.widthSizeClass == WindowWidthSizeClass.Compact -> 0.dp
        wsc.isCompactHeight -> 16.dp
        wsc.widthSizeClass == WindowWidthSizeClass.Medium -> 24.dp
        else -> 48.dp // Expanded
    }
}

/**
 * Max width for primary reading / form content. Keeps lines readable on ultra-wide
 * Chromebook or desktop windows while still using available space on phones.
 */
@Composable
fun adaptiveMaxContentWidth(): Dp {
    val wsc = LocalWindowSizeClass.current
    return when (wsc.widthSizeClass) {
        WindowWidthSizeClass.Compact -> Dp.Unspecified
        WindowWidthSizeClass.Medium -> 840.dp
        else -> 1080.dp
    }
}

/**
 * Centers children and optionally constrains max width on medium/expanded screens.
 * Use as the outermost content wrapper inside a Scaffold's content slot so lists,
 * detail panes, and forms scale cleanly on tablets, Chromebooks, and freeform windows.
 *
 * Phone (Compact width) is unchanged: full width, no extra padding.
 *
 * @param constrainWidth when true (default), apply [adaptiveMaxContentWidth].
 * @param fillHeight when true, child fills available height (useful for LazyColumn).
 */
@Composable
fun AdaptiveContent(
    modifier: Modifier = Modifier,
    constrainWidth: Boolean = true,
    fillHeight: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val maxW = adaptiveMaxContentWidth()
    val hPad = adaptiveContentPadding()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
            .padding(horizontal = hPad),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (constrainWidth && maxW != Dp.Unspecified) Modifier.widthIn(max = maxW)
                    else Modifier
                )
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier),
            content = content
        )
    }
}

/**
 * Two-pane layout for list + detail on medium/expanded widths.
 * On compact (phone) only [list] is shown; caller is expected to navigate to detail.
 * On wider screens both panes are shown side-by-side.
 */
@Composable
fun ListDetailLayout(
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    showDetail: Boolean,
    listWeight: Float = 0.4f,
    modifier: Modifier = Modifier
) {
    val wsc = LocalWindowSizeClass.current
    if (wsc.isMediumWidth && showDetail) {
        Row(modifier.fillMaxSize()) {
            Box(Modifier.weight(listWeight).fillMaxHeight()) { list() }
            Box(Modifier.weight(1f - listWeight).fillMaxHeight()) { detail() }
        }
    } else {
        Box(modifier.fillMaxSize()) { list() }
    }
}

/**
 * Lays out action buttons in one horizontal row on tablet/wide windows, and falls
 * back to the caller's phone layout (typically 2×2) when width is Compact.
 * Phone portrait and landscape both stay Compact width, so behaviour is unchanged.
 */
@Composable
fun AdaptiveActionRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val wsc = LocalWindowSizeClass.current
    if (wsc.isMediumWidth) {
        Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            content = content
        )
    } else {
        // Phone: caller should still use its own 2×2 rows; this branch is unused when
        // screens branch on isMediumWidth themselves. Provided for completeness.
        Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            content = content
        )
    }
}
