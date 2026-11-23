package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.simpmusic.expect.ui.isTelevision
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.HomeViewModel
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.hidden_empty
import simpmusic.composeapp.generated.resources.hidden_hint
import simpmusic.composeapp.generated.resources.hidden_manage
import simpmusic.composeapp.generated.resources.hidden_title
import simpmusic.composeapp.generated.resources.hide_artist
import simpmusic.composeapp.generated.resources.hide_song

/*
 * "Don't recommend this": a long press on a recommended song (holding OK on a TV remote) opens a
 * short menu to hide the song or its artist from everything the recommender builds, and to bring
 * hidden ones back.
 */

/** For a remote that sends no repeats: held this long, a release counts as a long press. */
private const val HOLD_MS = 550L

/** Key repeats arrive tens of milliseconds apart; a longer gap is a new press. */
private const val NEW_PRESS_GAP_MS = 700L

/**
 * A recommended song's card: [onClick] plays it, [onLongClick] opens the menu.
 *
 * A phone gets the usual long press. A TV remote has no such thing in Compose's clickable, so the
 * OK key is read here: a short press clicks when the key comes up; a held one (the key repeats,
 * or is released after [HOLD_MS]) opens the menu and does not click.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.songCardActions(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier {
    if (!isTelevision()) return combinedClickable(onClick = onClick, onLongClick = onLongClick)
    val press = remember { OkPress() }
    return onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
        val now = System.currentTimeMillis()
        when (event.type) {
            KeyEventType.KeyDown -> {
                val repeat = press.downAt != 0L && now - press.lastEventAt <= NEW_PRESS_GAP_MS
                press.lastEventAt = now
                if (!repeat) {
                    press.downAt = now
                    press.held = false
                } else if (!press.held) {
                    // A second key-down with no key-up between is the remote repeating a held
                    // key, which is how Android itself tells a long press on OK.
                    press.held = true
                    onLongClick()
                }
                true
            }

            KeyEventType.KeyUp -> {
                // A key-up with no key-down here (the press began on a dialog or another card) is nothing.
                // Some remotes send no repeats while a key is held, so the hold is also judged on
                // release: long enough is the menu, shorter is a click.
                val began = press.downAt
                val alreadyHeld = press.held
                press.downAt = 0L
                press.held = false
                if (began != 0L && !alreadyHeld) {
                    if (now - began >= HOLD_MS) onLongClick() else onClick()
                }
                true
            }

            else -> {
                false
            }
        }
    }.clickable(onClick = onClick)
}

/** Repeats of a held key arrive closer together than this; a key-down after a longer quiet is a new press. */
private const val REPEAT_GAP_MS = 300L

/**
 * For a menu opened by holding OK: the key is usually still down when the menu appears, and the
 * menu's first row would take its repeats and its release as a click. OK is swallowed until the
 * held key comes up, or until a key-down arrives after a quiet gap (a remote that sent no
 * repeats opened the menu on release, so its next key-down is already a new press).
 */
@Composable
internal fun Modifier.ignoreHeldOk(): Modifier {
    val guard = remember { HeldOk(System.currentTimeMillis()) }
    return onPreviewKeyEvent { event ->
        if (guard.released) return@onPreviewKeyEvent false
        if (event.key != Key.DirectionCenter && event.key != Key.Enter && event.key != Key.NumPadEnter) return@onPreviewKeyEvent false
        val now = System.currentTimeMillis()
        val quiet = now - guard.lastEventAt > REPEAT_GAP_MS
        guard.lastEventAt = now
        when {
            event.type == KeyEventType.KeyDown && quiet -> {
                guard.released = true
                false
            }

            event.type == KeyEventType.KeyUp -> {
                guard.released = true
                true
            }

            else -> {
                true
            }
        }
    }
}

private class HeldOk(
    var lastEventAt: Long,
) {
    var released = false
}

private class OkPress {
    var downAt = 0L
    var lastEventAt = 0L
    var held = false
}

/** The menu for one recommended song. */
@Composable
internal fun HideMenu(
    track: Track,
    viewModel: HomeViewModel,
    onDismiss: () -> Unit,
) {
    val hidden by viewModel.hidden.collectAsStateWithLifecycle()
    var managing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.loadHidden() }
    val artist = track.artists?.firstOrNull()?.name.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.ignoreHeldOk(),
        title = {
            Text(
                text = if (managing) stringResource(Res.string.hidden_title) else track.title,
                style = typo().titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                if (!managing) {
                    TextButton(onClick = {
                        viewModel.hideSong(track)
                        onDismiss()
                    }) { Text(stringResource(Res.string.hide_song)) }
                    if (artist.isNotBlank()) {
                        TextButton(onClick = {
                            viewModel.hideArtist(artist)
                            onDismiss()
                        }) { Text(stringResource(Res.string.hide_artist, artist)) }
                    }
                    TextButton(onClick = { managing = true }) { Text(stringResource(Res.string.hidden_manage, hidden.size.toString())) }
                } else if (hidden.isEmpty()) {
                    Text(stringResource(Res.string.hidden_empty), style = typo().bodyMedium)
                } else {
                    Text(stringResource(Res.string.hidden_hint), style = typo().bodySmall)
                    hidden.forEach { item ->
                        TextButton(onClick = { viewModel.showAgain(item) }) { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
