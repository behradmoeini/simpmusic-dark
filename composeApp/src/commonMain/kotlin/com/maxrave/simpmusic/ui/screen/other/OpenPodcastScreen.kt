package com.maxrave.simpmusic.ui.screen.other

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.simpmusic.podcast.OpenPodcastEpisode
import com.maxrave.simpmusic.podcast.OpenPodcastShow
import com.maxrave.simpmusic.ui.component.EndOfPage
import com.maxrave.simpmusic.ui.component.OpenPodcastCover
import com.maxrave.simpmusic.ui.component.OpenPodcastNowPlayingCard
import com.maxrave.simpmusic.ui.component.formatEpisodeMeta
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.Check
import com.maxrave.simpmusic.ui.icon.Pause
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.screen.home.ErrorRow
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.OpenPodcastLoad
import com.maxrave.simpmusic.viewModel.OpenPodcastViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.follow
import simpmusic.composeapp.generated.resources.open_podcasts_android_only
import simpmusic.composeapp.generated.resources.open_podcasts_episodes
import simpmusic.composeapp.generated.resources.open_podcasts_following
import simpmusic.composeapp.generated.resources.open_podcasts_no_episodes

/** A show read straight from its RSS feed: header, follow, episodes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OpenPodcastScreen(
    feedUrl: String,
    title: String,
    author: String,
    imageUrl: String?,
    navController: NavController,
    viewModel: OpenPodcastViewModel = koinViewModel(),
) {
    val initial = remember(feedUrl) { OpenPodcastShow(feedUrl = feedUrl, title = title, author = author, imageUrl = imageUrl) }
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(feedUrl) { viewModel.loadFeed(initial) }

    val show = (feed as? OpenPodcastLoad.Ready)?.value?.show ?: initial
    val following = subscriptions.any { it.feedUrl == feedUrl }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item {
            Box(Modifier.statusBarsPadding().padding(4.dp)) {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(SimpIcons.ArrowBackIosNew, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
        if (playerState.isActive) {
            stickyHeader {
                OpenPodcastNowPlayingCard(
                    state = playerState,
                    onToggle = viewModel.player::togglePlayPause,
                    onSeekBy = viewModel.player::seekBy,
                    onSeekTo = viewModel.player::seekTo,
                    onOpenShow = null,
                    modifier = Modifier.background(MaterialTheme.colorScheme.background),
                )
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                OpenPodcastCover(show.imageUrl, 180.dp, corner = 12.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = show.title,
                    style = typo().titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (show.author.isNotBlank()) {
                    Text(text = show.author, style = typo().bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                if (following) {
                    OutlinedButton(onClick = { viewModel.toggleSubscription(show) }) {
                        Icon(SimpIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.open_podcasts_following), color = MaterialTheme.colorScheme.onBackground)
                    }
                } else {
                    Button(
                        onClick = { viewModel.toggleSubscription(show) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Text(stringResource(Res.string.follow), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                show.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = description,
                        style = typo().bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                    )
                }
                if (!viewModel.player.isAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(Res.string.open_podcasts_android_only), style = typo().bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            Text(
                text = stringResource(Res.string.open_podcasts_episodes),
                style = typo().titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
            )
        }
        when (val f = feed) {
            is OpenPodcastLoad.Ready -> {
                if (f.value.episodes.isEmpty()) {
                    item {
                        Text(
                            stringResource(Res.string.open_podcasts_no_episodes),
                            style = typo().bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(f.value.episodes, key = { it.id }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        isCurrent = playerState.episodeId == episode.id,
                        isPlaying = playerState.episodeId == episode.id && playerState.isPlaying,
                        savedMs = remember(episode.id, playerState.episodeId) { viewModel.player.savedPosition(episode.id) },
                        enabled = viewModel.player.isAvailable,
                        onPlay = { viewModel.play(f.value.show, episode) },
                    )
                }
            }

            OpenPodcastLoad.Failed -> item { ErrorRow { viewModel.loadFeed(initial) } }
            else ->
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
private fun EpisodeRow(
    episode: OpenPodcastEpisode,
    isCurrent: Boolean,
    isPlaying: Boolean,
    savedMs: Long,
    enabled: Boolean,
    onPlay: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onPlay)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = episode.title,
                style = typo().titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = formatEpisodeMeta(episode.pubDateMillis, episode.durationSeconds)
            if (meta.isNotEmpty()) {
                Text(text = meta, style = typo().bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val total = (episode.durationSeconds ?: 0L) * 1000
            if (savedMs > 0 && total > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (savedMs.toFloat() / total).coerceIn(0f, 1f) },
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(0.5f).height(3.dp),
                )
            }
        }
        if (enabled) {
            IconButton(onClick = onPlay) {
                Icon(
                    if (isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
