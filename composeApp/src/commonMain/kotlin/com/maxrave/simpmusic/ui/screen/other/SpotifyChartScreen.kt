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
import com.maxrave.simpmusic.recommendation.SimilarMix
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SharedViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.koin.compose.koinInject
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.home_shelf_not_found

@Composable
fun SpotifyChartScreen(
    innerPadding: PaddingValues,
    playlistId: String,
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

    // The playlist's own order, as Spotify lists it.
    val tracks = snapshot?.playlists?.firstOrNull { it.id == playlistId }?.tracks.orEmpty()

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
                            scope.launch { playSpotifyTracksFrom(index, tracks, searchRepository, sharedViewModel) }
                        },
                    )
                }
            }
    }
}

/**
 * Plays the chart from the tapped track to the end, in the chart's own order. The tapped track starts
 * at once; the rest are matched in order and added as one batch, so the queue keeps the chart's order.
 * A later track with no YouTube Music match is skipped. When the tapped track itself has none,
 * nothing plays and a message says so: starting the next track instead plays a song nobody picked.
 */
internal suspend fun playSpotifyTracksFrom(
    startIndex: Int,
    tracks: List<SpotifySnapshotTrack>,
    searchRepository: SearchRepository,
    sharedViewModel: SharedViewModel,
) {
    val picked = tracks.getOrNull(startIndex) ?: return
    val first = matchOnYouTube(picked, searchRepository)
    if (first == null) {
        sharedViewModel.makeToast(getString(Res.string.home_shelf_not_found))
        return
    }
    sharedViewModel.loadMediaItemFromTrack(first, Config.SONG_CLICK)
    val rest = arrayListOf<Track>()
    for (i in startIndex + 1 until tracks.size) {
        rest += matchOnYouTube(tracks[i], searchRepository) ?: continue
    }
    if (rest.isNotEmpty()) sharedViewModel.addListToQueue(rest)
}

/**
 * YouTube Music only plays by video id, so each Spotify track is matched by name. The result is
 * chosen by title, artist and running time ([SimilarMix.confidentMatch]), never by search position
 * alone: Spotify writes Persian names in Latin letters, and the first result for such a query is
 * often another song. Null when no result can be trusted to be the track.
 */
internal suspend fun matchOnYouTube(
    track: SpotifySnapshotTrack,
    searchRepository: SearchRepository,
): Track? {
    val durationSeconds = track.durationMs?.let { (it / 1000).toInt() }
    val queries =
        listOf(
            (listOf(track.title) + track.artists.take(1)).joinToString(" "),
            (listOf(track.title) + track.artists).joinToString(" "),
        ).distinct()
    for (query in queries) {
        val results =
            (searchRepository.getSearchDataSong(query).first() as? Resource.Success)
                ?.data
                .orEmpty()
                .filter { it.videoId.isNotBlank() }
                .take(MATCH_POOL)
        val index =
            SimilarMix.confidentMatch(
                track.artists,
                track.title,
                durationSeconds,
                results.map { SimilarMix.NamedResult(it.title.orEmpty(), it.artists.orEmpty().joinToString(" ") { a -> a.name }, it.durationSeconds) },
            )
        Logger.i("SpotifyMatch", "\"$query\": ${results.size} results, picked ${results.getOrNull(index)?.videoId ?: "none"}")
        results.getOrNull(index)?.let { return it.toTrack() }
    }
    return null
}

private const val MATCH_POOL = 8

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
