package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.simpmusic.ui.component.OpenPodcastCover
import com.maxrave.simpmusic.ui.navigation.destination.list.AlbumDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.HomeViewModel
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.home_albums_you_like
import simpmusic.composeapp.generated.resources.home_mix_and_more
import simpmusic.composeapp.generated.resources.home_mix_name
import simpmusic.composeapp.generated.resources.home_more_like
import simpmusic.composeapp.generated.resources.home_new_releases
import simpmusic.composeapp.generated.resources.home_release_album
import simpmusic.composeapp.generated.resources.home_release_ep
import simpmusic.composeapp.generated.resources.home_release_single
import simpmusic.composeapp.generated.resources.home_top_mixes

/*
 * Fork: the Home shelves Spotify builds from the listener's history (HomeShelvesLoader). Each one
 * is hidden until it has cards, so a new install shows none of them.
 */

private val CARD = 150.dp

@Composable
private fun ShelfTitle(text: String) {
    Text(
        text = text,
        style = typo().headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
    )
}

/** A cover with up to three lines under it. [busy] lays a spinner over the cover while the card resolves. */
@Composable
private fun ShelfCard(
    cover: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    overline: String? = null,
    busy: Boolean = false,
    size: Dp = CARD,
    band: String? = null,
) {
    Column(Modifier.width(size).clickable(onClick = onClick)) {
        Box(Modifier.size(size).clip(RoundedCornerShape(8.dp))) {
            OpenPodcastCover(cover, size)
            if (band != null) {
                // Spotify's mix cards print the mix's name on a band across the artwork.
                Text(
                    text = band,
                    style = typo().titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = 12.dp)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            if (busy) {
                Box(Modifier.size(size).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (overline != null) {
            Text(overline, style = typo().bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = typo().titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = subtitle,
            style = typo().bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (title.isEmpty()) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Your top mixes": one station per artist the listener plays most. A tap builds and plays it. */
@Composable
internal fun TopMixesShelf(viewModel: HomeViewModel) {
    val shelves by viewModel.homeShelves.collectAsStateWithLifecycle()
    val busy by viewModel.shelfBusy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadHomeShelves() }
    if (shelves.mixes.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        ShelfTitle(stringResource(Res.string.home_top_mixes))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(shelves.mixes, key = { _, m -> m.artist.id }) { _, mix ->
                val names = (listOf(mix.artist.name) + mix.alsoPlays).joinToString()
                ShelfCard(
                    cover = mix.artist.picture,
                    title = "",
                    subtitle = if (mix.alsoPlays.isEmpty()) names else stringResource(Res.string.home_mix_and_more, names),
                    band = stringResource(Res.string.home_mix_name, mix.artist.name),
                    busy = busy == "mix:${mix.artist.id}",
                    onClick = { viewModel.playMix(mix) },
                )
            }
        }
    }
}

/** "More like <artist>": the best-known song of each artist close to one the listener plays. */
@Composable
internal fun MoreLikeShelfRow(viewModel: HomeViewModel) {
    val shelves by viewModel.homeShelves.collectAsStateWithLifecycle()
    val busy by viewModel.shelfBusy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadHomeShelves() }
    val shelf = shelves.moreLike ?: return
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)) {
            OpenPodcastCover(shelf.artist.picture, 48.dp, corner = 24.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(Res.string.home_more_like), style = typo().bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = shelf.artist.name,
                    style = typo().headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(shelf.tracks, key = { index, t -> "$index:${t.artist}:${t.title}" }) { index, track ->
                ShelfCard(
                    cover = track.cover,
                    title = track.title,
                    subtitle = track.artist,
                    busy = busy == "more:$index",
                    onClick = { viewModel.playMoreLike(index) },
                )
            }
        }
    }
}

/** "New releases for you": what the listener's artists put out lately. A tap opens the album. */
@Composable
internal fun NewReleasesShelf(
    viewModel: HomeViewModel,
    navController: NavController,
) {
    val shelves by viewModel.homeShelves.collectAsStateWithLifecycle()
    val busy by viewModel.shelfBusy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadHomeShelves() }
    if (shelves.newReleases.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        ShelfTitle(stringResource(Res.string.home_new_releases))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(shelves.newReleases, key = { index, a -> "$index:${a.artist}:${a.title}" }) { _, album ->
                ShelfCard(
                    cover = album.cover,
                    overline =
                        when (album.recordType) {
                            "single" -> stringResource(Res.string.home_release_single)
                            "ep" -> stringResource(Res.string.home_release_ep)
                            else -> stringResource(Res.string.home_release_album)
                        },
                    title = album.title,
                    subtitle = album.artist,
                    busy = busy == "release:${album.artist}:${album.title}",
                    onClick = { viewModel.openRelease(album) { browseId -> navController.navigate(AlbumDestination(browseId)) } },
                )
            }
        }
    }
}

/** "Albums featuring songs you like": the albums behind the listener's most played songs. */
@Composable
internal fun LikedAlbumsShelf(
    viewModel: HomeViewModel,
    navController: NavController,
) {
    val shelves by viewModel.homeShelves.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadHomeShelves() }
    if (shelves.albums.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        ShelfTitle(stringResource(Res.string.home_albums_you_like))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(shelves.albums, key = { _, a -> a.browseId }) { _, album ->
                ShelfCard(
                    cover = album.thumbnail?.replace(Regex("=w\\d+-h\\d+"), "=w544-h544"),
                    title = album.title,
                    subtitle = album.artist,
                    onClick = { navController.navigate(AlbumDestination(album.browseId)) },
                )
            }
        }
    }
}
