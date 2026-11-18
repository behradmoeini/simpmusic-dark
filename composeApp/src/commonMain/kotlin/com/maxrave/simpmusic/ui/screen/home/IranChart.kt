package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.maxrave.domain.data.model.home.chart.ItemArtist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.simpmusic.chart.IranChart
import com.maxrave.simpmusic.chart.SpotifySnapshotPlaylist
import com.maxrave.simpmusic.expect.ui.HorizontalScrollBar
import com.maxrave.simpmusic.extension.mouseDragScroll
import com.maxrave.simpmusic.ui.component.ItemArtistChart
import com.maxrave.simpmusic.ui.navigation.destination.list.ArtistDestination
import com.maxrave.simpmusic.ui.navigation.destination.list.SpotifyChartDestination
import com.maxrave.simpmusic.ui.screen.other.playSpotifyTracksFrom
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.extension.getScreenSizeInfo
import com.maxrave.simpmusic.viewModel.SharedViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val IranLandscapeRowMaxWidth = 400.dp

/**
 * The Iran chart, laid out like YouTube's country charts beside it: a banner, the week's top songs
 * as a ranked grid, the Persian playlists as cards, and the artists with the most charted songs.
 * Songs play through their YouTube Music match, the same way the Persian library cards do.
 */
@Composable
fun IranChartData(
    chart: IranChart,
    navController: NavController,
    sharedViewModel: SharedViewModel = koinInject(),
    searchRepository: SearchRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var widthDp by remember { mutableStateOf(0.dp) }
    val isPortrait = getScreenSizeInfo().let { it.wDP < it.hDP }
    val rowWidth = if (isPortrait) widthDp else minOf(widthDp, IranLandscapeRowMaxWidth)

    Column(Modifier.onGloballyPositioned { widthDp = with(density) { it.size.width.toDp() } }) {
        IranBanner(chart)

        SectionTitle("Top songs this week")
        val songsState = rememberLazyGridState()
        val songsFling = rememberSnapFlingBehavior(SnapLayoutInfoProvider(lazyGridState = songsState, snapPosition = SnapPosition.Start))
        LazyHorizontalGrid(
            rows = GridCells.Fixed(4),
            modifier = Modifier.height(320.dp).mouseDragScroll(songsState, songsFling),
            state = songsState,
            flingBehavior = songsFling,
        ) {
            items(chart.topSongs.size, key = { index -> (chart.topSongs[index].uri ?: chart.topSongs[index].title) + index }) { index ->
                val track = chart.topSongs[index]
                ItemArtistChart(
                    onClick = { scope.launch { playSpotifyTracksFrom(index, chart.topSongs, searchRepository, sharedViewModel) } },
                    data = chartRow(rank = index + 1, title = track.title, artwork = track.artwork),
                    widthDp = rowWidth,
                    thumbnailShape = RoundedCornerShape(8.dp),
                    subtitle = track.artists.joinToString(", "),
                )
            }
        }
        HorizontalScrollBar(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), scrollState = songsState, flingBehavior = songsFling)

        SectionTitle("Persian playlists")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(chart.playlists, key = { it.id }) { playlist ->
                PlaylistMosaicCard(playlist) { navController.navigate(SpotifyChartDestination(playlistId = playlist.id)) }
            }
        }

        if (chart.artists.isNotEmpty()) {
            SectionTitle("Top artists")
            val artistsState = rememberLazyGridState()
            val artistsFling = rememberSnapFlingBehavior(SnapLayoutInfoProvider(lazyGridState = artistsState, snapPosition = SnapPosition.Start))
            LazyHorizontalGrid(
                rows = GridCells.Fixed(3),
                modifier = Modifier.height(240.dp).mouseDragScroll(artistsState, artistsFling),
                state = artistsState,
                flingBehavior = artistsFling,
            ) {
                items(chart.artists.size, key = { index -> chart.artists[index].name + index }) { index ->
                    val artist = chart.artists[index]
                    ItemArtistChart(
                        onClick = {
                            // The snapshot names artists, not channels: find the channel by name.
                            scope.launch {
                                val found =
                                    (searchRepository.getSearchDataArtist(artist.name).first() as? Resource.Success)
                                        ?.data
                                        ?.firstOrNull()
                                        ?.browseId
                                if (found != null) navController.navigate(ArtistDestination(channelId = found))
                            }
                        },
                        data = chartRow(rank = index + 1, title = artist.name, artwork = artist.artwork),
                        widthDp = rowWidth,
                        subtitle = if (artist.songCount == 1) "1 song on the charts" else "${artist.songCount} songs on the charts",
                    )
                }
            }
            HorizontalScrollBar(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), scrollState = artistsState, flingBehavior = artistsFling)
        }
    }
}

/** The chart's header: the week's number-one cover behind a dark wash, with the Lion and Sun. */
@Composable
private fun IranBanner(chart: IranChart) {
    val lead = chart.topSongs.firstOrNull()
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .height(132.dp)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        AsyncImage(
            model = lead?.artwork,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.88f), Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.25f)),
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 18.dp),
        ) {
            Text(
                text = "🦁☀️  Iran",
                style = typo().headlineMedium,
                color = Color.White,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            lead?.let {
                Text(
                    text = "#1  ${it.title} — ${it.artists.joinToString(", ")}",
                    style = typo().bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (chart.updated.isNotBlank()) {
                Text(
                    text = "Updated ${chart.updated} · from Spotify's Persian charts",
                    style = typo().bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A playlist as a 2×2 mosaic of its first covers, its name and its length. */
@Composable
private fun PlaylistMosaicCard(
    playlist: SpotifySnapshotPlaylist,
    onClick: () -> Unit,
) {
    val size: Dp = 160.dp
    val covers = playlist.tracks.mapNotNull { it.artwork }.distinct().take(4)
    Column(
        Modifier
            .width(size)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (covers.size >= 4) {
                Column {
                    covers.chunked(2).forEach { pair ->
                        Row {
                            pair.forEach { url ->
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(size / 2),
                                )
                            }
                        }
                    }
                }
            } else {
                AsyncImage(
                    model = covers.firstOrNull(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            // Spotify's names carry a Persian half after " | "; the card keeps the English one.
            text = playlist.name.substringBefore(" | ").trim(),
            style = typo().titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "Playlist • ${playlist.tracks.size} songs",
            style = typo().bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = typo().headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
    )
}

private fun chartRow(
    rank: Int,
    title: String,
    artwork: String?,
) = ItemArtist(
    browseId = "",
    rank = rank.toString(),
    subscribers = "",
    thumbnails = listOfNotNull(artwork?.let { Thumbnail(height = 300, url = it, width = 300) }),
    title = title,
    trend = "",
)
