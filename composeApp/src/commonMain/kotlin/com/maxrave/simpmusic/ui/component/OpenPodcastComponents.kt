package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.simpmusic.podcast.OpenPodcastPlayerState
import com.maxrave.simpmusic.podcast.OpenPodcastShow
import com.maxrave.simpmusic.ui.icon.FastForward
import com.maxrave.simpmusic.ui.icon.FastRewind
import com.maxrave.simpmusic.ui.icon.Pause
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OpenPodcastCover(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
) {
    AsyncImage(
        model =
            ImageRequest
                .Builder(LocalPlatformContext.current)
                .data(url)
                .crossfade(300)
                .build(),
        placeholder = rememberHolderPainter(),
        error = rememberHolderPainter(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(corner)),
    )
}

/** One show in a list: cover, title, author. */
@Composable
fun OpenPodcastShowRow(
    show: OpenPodcastShow,
    rank: Int? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank != null) {
            Text(
                text = rank.toString(),
                style = typo().titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.width(28.dp),
            )
        }
        OpenPodcastCover(show.imageUrl, 56.dp, corner = 6.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = show.title,
                style = typo().titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (show.author.isNotBlank()) {
                Text(
                    text = show.author,
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The episode playing now: cover, titles, −15 s, play/pause, +30 s and a seek bar. */
@Composable
fun OpenPodcastNowPlayingCard(
    state: OpenPodcastPlayerState,
    onToggle: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onOpenShow: (() -> Unit)?,
    onSetSpeed: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OpenPodcastCover(
                state.imageUrl,
                44.dp,
                corner = 6.dp,
                modifier = if (onOpenShow != null) Modifier.clickable(onClick = onOpenShow) else Modifier,
            )
            Spacer(Modifier.width(10.dp))
            // Cycles 1x → 1.25x → 1.5x → 2x → 1x.
            val speeds = listOf(1f, 1.25f, 1.5f, 2f)
            TextButton(onClick = {
                val current = speeds.indexOf(state.playbackSpeed).takeIf { it >= 0 } ?: 0
                onSetSpeed(speeds[(current + 1) % speeds.size])
            }) {
                Text(
                    text = "${state.playbackSpeed.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}×",
                    style = typo().labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .then(if (onOpenShow != null) Modifier.clickable(onClick = onOpenShow) else Modifier),
            ) {
                Text(
                    text = state.episodeTitle,
                    style = typo().titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.showTitle,
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { onSeekBy(-15_000) }) {
                Icon(SimpIcons.FastRewind, contentDescription = "-15s", tint = MaterialTheme.colorScheme.onBackground)
            }
            IconButton(onClick = onToggle) {
                if (state.isBuffering && !state.isPlaying) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(
                        if (state.isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            IconButton(onClick = { onSeekBy(30_000) }) {
                Icon(SimpIcons.FastForward, contentDescription = "+30s", tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        val duration = state.durationMs.coerceAtLeast(1L)
        Slider(
            value = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeekTo((it * duration).toLong()) }
                dragging = null
            },
            enabled = state.durationMs > 0,
            colors =
                SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                ),
            modifier = Modifier.fillMaxWidth().height(24.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock(((dragging?.let { it * duration })?.toLong() ?: state.positionMs)), style = typo().bodySmall)
            Text(if (state.durationMs > 0) formatClock(state.durationMs) else "--:--", style = typo().bodySmall)
        }
    }
}

fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
}

/** "12 Mar 2026 · 1 h 5 min" */
fun formatEpisodeMeta(
    pubDateMillis: Long?,
    durationSeconds: Long?,
): String {
    val date = pubDateMillis?.let { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(it)) }
    val duration =
        durationSeconds?.let {
            val h = it / 3600
            val m = (it % 3600) / 60
            if (h > 0) "$h h $m min" else "${m.coerceAtLeast(1)} min"
        }
    return listOfNotNull(date, duration).joinToString(" · ")
}
