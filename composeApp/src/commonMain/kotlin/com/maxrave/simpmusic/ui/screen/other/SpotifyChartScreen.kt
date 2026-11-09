package com.maxrave.simpmusic.ui.screen.other

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.maxrave.common.Config

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.toTrack
import com.maxrave.simpmusic.chart.SpotifySnapshot
import com.maxrave.simpmusic.chart.SpotifySnapshotTrack
import com.maxrave.simpmusic.chart.loadSpotifySnapshot
import com.maxrave.simpmusic.chart.mergedTracks
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SharedViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SpotifyChartScreen(
    innerPadding: PaddingValues,
    sharedViewModel: SharedViewModel = koinInject(),
    searchRepository: SearchRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<SpotifySnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        snapshot = loadSpotifySnapshot()
        failed = snapshot == null
        loading = false
    }

    val tracks = snapshot?.mergedTracks().orEmpty()

    when {
        loading ->
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

        failed || tracks.isEmpty() ->
            Box(Modifier.fillMaxSize().padding(innerPadding).padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (failed) "Could not load the Persian chart." else "The Persian chart is empty for now.",
                    style = typo().bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        else ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding(), bottom = 120.dp),
            ) {
                itemsIndexed(tracks, key = { index, track -> track.uri ?: "$index-${track.title}" }) { index, track ->
                    ChartTrackRow(
                        rank = index + 1,
                        track = track,
                        onClick = {
                            scope.launch { playFrom(index, tracks, searchRepository, sharedViewModel) }
                        },
                    )
                }
            }
    }
}

/**
 * Plays the chart from the tapped track to the end, in the chart's own order. The tapped track starts
 * at once; the rest are matched in order and added as one batch, so the queue keeps the chart's order.
 * A track with no YouTube Music match is skipped.
 */
private suspend fun playFrom(
    startIndex: Int,
    tracks: List<SpotifySnapshotTrack>,
    searchRepository: SearchRepository,
    sharedViewModel: SharedViewModel,
) {
    var started = false
    val rest = arrayListOf<Track>()
    for (i in startIndex until tracks.size) {
        val match = matchOnYouTube(tracks[i], searchRepository) ?: continue
        if (!started) {
            sharedViewModel.loadMediaItemFromTrack(match, Config.SONG_CLICK)
            started = true
        } else {
            rest += match
        }
    }
    if (rest.isNotEmpty()) sharedViewModel.addListToQueue(rest)
}

/** YouTube Music only plays by video id, so each Spotify track is matched by its title and artists. */
private suspend fun matchOnYouTube(
    track: SpotifySnapshotTrack,
    searchRepository: SearchRepository,
): Track? {
    val query = (listOf(track.title) + track.artists).joinToString(" ")
    val result = searchRepository.getSearchDataSong(query).first()
    return (result as? Resource.Success)?.data?.firstOrNull()?.toTrack()
}

@Composable
private fun ChartTrackRow(
    rank: Int,
    track: SpotifySnapshotTrack,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = rank.toString(),
            style = typo().titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.width(32.dp),
        )
        AsyncImage(
            model = track.artwork,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = typo().titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artists.joinToString(", "),
                style = typo().bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
