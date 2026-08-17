package com.quickgit.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/**
 * A circular GitHub avatar. Shows [avatarUrl] when it loads successfully, and
 * falls back to the first letter of [login] (uppercased) while loading, on a
 * load failure, or when [avatarUrl] is null/blank.
 */
@Composable
fun UserAvatar(
    avatarUrl: String?,
    login: String,
    size: Dp,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium
) {
    val initial = login.take(1).uppercase()
    if (avatarUrl.isNullOrBlank()) {
        InitialAvatar(initial, size, modifier, textStyle)
    } else {
        SubcomposeAsyncImage(
            model = avatarUrl,
            contentDescription = "$login's avatar",
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
            loading = { InitialAvatar(initial, size, textStyle = textStyle) },
            error = { InitialAvatar(initial, size, textStyle = textStyle) }
        )
    }
}

@Composable
private fun InitialAvatar(
    initial: String,
    size: Dp,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initial,
            style = textStyle,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
