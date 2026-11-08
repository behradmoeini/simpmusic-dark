package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.model.home.chart.ItemArtist
import com.maxrave.domain.repository.PodcastRepository
import com.maxrave.simpmusic.ui.component.EndOfPage
import com.maxrave.simpmusic.ui.component.rememberHolderPainter
import com.maxrave.simpmusic.ui.navigation.destination.list.PodcastDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.HomeViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.favorite_podcasts
import simpmusic.composeapp.generated.resources.no_favorite_podcasts

/**
 * Home's Podcasts filter: the shows you saved, as a row of cover cards, then the weekly top shows as
 * a ranked list. Both open the existing podcast page, which lists and plays the episodes.
 */
@Composable
internal fun HomePodcastsView(
    viewModel: HomeViewModel,
    navController: NavController,
    topPadding: Dp,
    podcastRepository: PodcastRepository = koinInject(),
) {
    val savedFlow = remember(podcastRepository) { podcastRepository.getFavoritePodcasts() }
    val saved by savedFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val savedSorted = remember(saved) { saved.sortedByDescending { it.favoriteTime } }
    val topShows by viewModel.podcastChart.collectAsStateWithLifecycle()
    val loadingTop by viewModel.loadingPodcastChart.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadPodcastChart() }

    val openPodcast: (String) -> Unit = { id -> navController.navigate(PodcastDestination(podcastId = id)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding + 8.dp, bottom = 24.dp),
    ) {
        item {
            SectionTitle(stringResource(Res.string.favorite_podcasts))
        }
        item {
            if (savedSorted.isEmpty()) {
                Text(
                    text = stringResource(Res.string.no_favorite_podcasts),
                    style = typo().bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(savedSorted, key = { it.podcastId }) { podcast ->
                        SavedShowCard(podcast) { openPodcast(podcast.podcastId) }
                    }
                }
            }
        }
        val top = topShows
        if (top != null && top.shows.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                SectionTitle(top.title)
            }
            itemsIndexed(top.shows, key = { index, show -> show.browseId + index }) { index, show ->
                TopShowRow(rank = show.rank.ifBlank { (index + 1).toString() }, show = show) {
                    openPodcast(show.browseId)
                }
            }
        } else if (loadingTop) {
            item {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { EndOfPage() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = typo().titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun SavedShowCard(
    podcast: PodcastsEntity,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model =
                ImageRequest
                    .Builder(LocalPlatformContext.current)
                    .data(podcast.thumbnail)
                    .crossfade(300)
                    .build(),
            placeholder = rememberHolderPainter(),
            error = rememberHolderPainter(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = podcast.title,
            style = typo().titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = podcast.authorName,
            style = typo().bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TopShowRow(
    rank: String,
    show: ItemArtist,
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
        Text(
            text = rank,
            style = typo().titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.width(28.dp),
        )
        AsyncImage(
            model =
                ImageRequest
                    .Builder(LocalPlatformContext.current)
                    .data(show.thumbnails.lastOrNull()?.url)
                    .crossfade(300)
                    .build(),
            placeholder = rememberHolderPainter(),
            error = rememberHolderPainter(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = show.title,
                style = typo().titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // On the podcast chart this field carries the show's author.
            Text(
                text = show.subscribers,
                style = typo().bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
