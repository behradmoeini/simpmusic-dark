package com.maxrave.simpmusic.ui.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.simpmusic.extension.KeepScreenOn
import com.maxrave.simpmusic.extension.formatDuration
import com.maxrave.simpmusic.recommendation.engine.RecommendationEngine
import com.maxrave.simpmusic.ui.component.LyricsView
import com.maxrave.simpmusic.ui.component.rememberHolderPainter
import com.maxrave.simpmusic.ui.icon.Favorite
import com.maxrave.simpmusic.ui.icon.FavoriteBorder
import com.maxrave.simpmusic.ui.icon.Pause
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Repeat
import com.maxrave.simpmusic.ui.icon.RepeatOne
import com.maxrave.simpmusic.ui.icon.Shuffle
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.icon.SkipNext
import com.maxrave.simpmusic.ui.icon.SkipPrevious
import com.maxrave.simpmusic.ui.icon.ThumbDown
import com.maxrave.simpmusic.ui.theme.ForceDarkContent
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SharedViewModel
import com.maxrave.simpmusic.viewModel.UIEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.hide_artist
import simpmusic.composeapp.generated.resources.hide_song

/*
 * Now Playing on a TV.
 *
 * The phone's player is a tall page of small controls, and the tablet's is a narrow side panel; on
 * a 960x540dp TV screen the panel's transport buttons fell off the bottom and nothing showed which
 * one the remote was on. This is a screen of its own, laid out for a remote:
 *
 * - Left: the cover, the title, the seek bar, one row of transport buttons, one row of opinions
 *   (like, don't recommend). Every control is a large round button, and the one the remote is on
 *   is filled with the theme's accent, so there is never a question of where the focus is.
 * - Right: the lyrics, always, at reading size.
 * - It opens with the focus on Play/Pause. Left and Right on the seek bar skip back and forward.
 * - Left alone while music plays, the controls slide away and the cover and lyrics take the
 *   screen (an "ambient" view, the app's own screensaver); any key brings the controls back. The
 *   screen is kept on while it shows, so the system screensaver does not cover it.
 *
 * It is a Dialog, a window of its own: the remote cannot wander onto the page behind it, and Back
 * closes it.
 */

/** With no key pressed for this long while playing, the controls slide away. */
private const val AMBIENT_AFTER_MS = 20_000L

@Composable
fun TvNowPlaying(
    sharedViewModel: SharedViewModel,
    onDismiss: () -> Unit,
) {
    val data by sharedViewModel.nowPlayingScreenData.collectAsStateWithLifecycle()
    val control by sharedViewModel.controllerState.collectAsStateWithLifecycle()
    val timeline by sharedViewModel.timeline.collectAsStateWithLifecycle()
    val nowPlaying by sharedViewModel.nowPlayingState.collectAsStateWithLifecycle()
    val engine: RecommendationEngine = koinInject()
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary
    val playFocus = remember { FocusRequester() }
    var lastKeyAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var ambient by remember { mutableStateOf(false) }
    var hideMenu by remember { mutableStateOf(false) }

    LaunchedEffect(control.isPlaying) {
        while (true) {
            delay(2_000)
            val idle = System.currentTimeMillis() - lastKeyAt
            if (control.isPlaying && idle >= AMBIENT_AFTER_MS && !hideMenu) ambient = true
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        // Back is handled below, on release. Left to the dialog it closed on the press, and the
        // release then reached the screen behind and closed the app as well.
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, dismissOnBackPress = false),
    ) {
        ForceDarkContent {
            KeepScreenOn()
            Row(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0B0B0B))
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Back || event.key == Key.Escape) {
                            if (event.type == KeyEventType.KeyUp && !hideMenu) onDismiss()
                            return@onPreviewKeyEvent true
                        }
                        if (event.type == KeyEventType.KeyDown) {
                            lastKeyAt = System.currentTimeMillis()
                            if (ambient) {
                                // The first key only wakes the controls; it does not also press one.
                                ambient = false
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }.padding(horizontal = 40.dp, vertical = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The cover stays in both views; in the ambient one it is all the left side holds.
                Column(
                    Modifier.fillMaxHeight().width(if (ambient) 300.dp else 380.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = if (ambient) Alignment.CenterHorizontally else Alignment.Start,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model =
                                ImageRequest
                                    .Builder(LocalPlatformContext.current)
                                    .data(data.thumbnailURL)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .diskCacheKey(data.thumbnailURL)
                                    .crossfade(300)
                                    .build(),
                            placeholder = rememberHolderPainter(),
                            error = rememberHolderPainter(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(if (ambient) 260.dp else 132.dp).clip(RoundedCornerShape(12.dp)),
                        )
                        if (!ambient) {
                            Spacer(Modifier.width(20.dp))
                            TrackNames(data.nowPlayingTitle, data.artistName, TextAlign.Start)
                        }
                    }
                    if (ambient) {
                        Spacer(Modifier.height(20.dp))
                        TrackNames(data.nowPlayingTitle, data.artistName, TextAlign.Center)
                    }
                    AnimatedVisibility(!ambient, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                        Column {
                            Spacer(Modifier.height(24.dp))
                            SeekBar(
                                current = timeline.current,
                                total = timeline.total,
                                accent = accent,
                                onBack = { sharedViewModel.onUIEvent(UIEvent.Backward) },
                                onForward = { sharedViewModel.onUIEvent(UIEvent.Forward) },
                            )
                            Spacer(Modifier.height(20.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                PlayerButton(SimpIcons.Shuffle, accent, active = control.isShuffle) { sharedViewModel.onUIEvent(UIEvent.Shuffle) }
                                PlayerButton(SimpIcons.SkipPrevious, accent) { sharedViewModel.onUIEvent(UIEvent.Previous) }
                                PlayerButton(
                                    if (control.isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                                    accent,
                                    size = 68.dp,
                                    primary = true,
                                    modifier = Modifier.focusRequester(playFocus),
                                ) { sharedViewModel.onUIEvent(UIEvent.PlayPause) }
                                PlayerButton(SimpIcons.SkipNext, accent) { sharedViewModel.onUIEvent(UIEvent.Next) }
                                PlayerButton(
                                    if (control.repeatState == RepeatState.One) SimpIcons.RepeatOne else SimpIcons.Repeat,
                                    accent,
                                    active = control.repeatState != RepeatState.None,
                                ) { sharedViewModel.onUIEvent(UIEvent.Repeat) }
                            }
                            Spacer(Modifier.height(14.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                PlayerButton(
                                    if (control.isLiked) SimpIcons.Favorite else SimpIcons.FavoriteBorder,
                                    accent,
                                    active = control.isLiked,
                                ) { sharedViewModel.onUIEvent(UIEvent.ToggleLike) }
                                PlayerButton(SimpIcons.ThumbDown, accent) { hideMenu = true }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(40.dp))
                Box(Modifier.fillMaxHeight().weight(1f), contentAlignment = Alignment.Center) {
                    val lyrics = data.lyricsData
                    if (lyrics != null) {
                        LyricsView(
                            lyricsData = lyrics,
                            timeLine = sharedViewModel.timeline,
                            onLineClick = { sharedViewModel.onUIEvent(UIEvent.UpdateProgress(it)) },
                            modifier = Modifier.fillMaxSize(),
                            backgroundColor = Color(0xFF0B0B0B),
                        )
                    } else {
                        Text(
                            text = "♪",
                            style = typo().titleLarge.copy(fontSize = 72.sp),
                            color = Color.White.copy(alpha = 0.12f),
                        )
                    }
                }
            }

            if (hideMenu) {
                val song = nowPlaying?.songEntity
                val title = song?.title ?: data.nowPlayingTitle
                val artist = song?.artistName?.firstOrNull() ?: data.artistName
                AlertDialog(
                    onDismissRequest = { hideMenu = false },
                    title = { Text(title, style = typo().titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    text = {
                        Column {
                            TextButton(onClick = {
                                hideMenu = false
                                scope.launch {
                                    engine.hideSong(song?.videoId.orEmpty(), title, artist)
                                    sharedViewModel.onUIEvent(UIEvent.Next)
                                }
                            }) { Text(stringResource(Res.string.hide_song)) }
                            if (artist.isNotBlank()) {
                                TextButton(onClick = {
                                    hideMenu = false
                                    scope.launch {
                                        engine.hideArtist(artist)
                                        // The queue was made before this and may hold more of
                                        // theirs (the next song was the same artist's, seen
                                        // 2026-11-23): step past each one, not just this song.
                                        var steps = 0
                                        do {
                                            val before = sharedViewModel.nowPlayingState.value?.songEntity?.videoId
                                            sharedViewModel.onUIEvent(UIEvent.Next)
                                            kotlinx.coroutines.withTimeoutOrNull(4_000) {
                                                while (sharedViewModel.nowPlayingState.value?.songEntity?.videoId == before) delay(100)
                                            }
                                            val now = sharedViewModel.nowPlayingState.value?.songEntity
                                            val theirs =
                                                now?.videoId != before &&
                                                    now?.artistName.orEmpty().any { it.trim().equals(artist.trim(), ignoreCase = true) }
                                        } while (++steps < 20 && theirs)
                                    }
                                }) { Text(stringResource(Res.string.hide_artist, artist)) }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { hideMenu = false }) { Text(stringResource(Res.string.cancel)) } },
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        // The dialog's content is placed a frame or two after it opens.
        delay(250)
        runCatching { playFocus.requestFocus() }
    }
}

@Composable
private fun TrackNames(
    title: String,
    artist: String,
    align: TextAlign,
) {
    Column(horizontalAlignment = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start) {
        Text(
            text = title,
            style = typo().titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = align,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = artist,
            style = typo().bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = align,
        )
    }
}

/**
 * A round player button. Its own look says where the remote is: focused, it is filled with the
 * accent and its icon turns dark, far easier to find from a sofa than an outline. [active] (shuffle
 * on, liked) tints the icon in the accent while it is not focused.
 */
@Composable
private fun PlayerButton(
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    active: Boolean = false,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(
                when {
                    focused -> accent
                    primary -> Color.White
                    else -> Color.White.copy(alpha = 0.08f)
                },
            ).onFocusChanged { focused = it.isFocused }
            // No indication of its own: the fill above is the indication.
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint =
                when {
                    focused || primary -> Color.Black
                    active -> accent
                    else -> Color.White
                },
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** The seek bar: a line and two times. Focused, it turns the accent and Left/Right skip. */
@Composable
private fun SeekBar(
    current: Long,
    total: Long,
    accent: Color,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val share = if (total > 0 && current >= 0) (current.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onBack()
                        true
                    }

                    Key.DirectionRight -> {
                        onForward()
                        true
                    }

                    else -> {
                        false
                    }
                }
            }.focusable()
            .then(if (focused) Modifier.border(2.dp, accent, RoundedCornerShape(8.dp)) else Modifier)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(if (focused) 8.dp else 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(share).background(if (focused) accent else Color.White))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(current), style = typo().bodySmall, color = Color.White.copy(alpha = 0.7f))
            Text(formatDuration(total), style = typo().bodySmall, color = Color.White.copy(alpha = 0.7f))
        }
    }
}
