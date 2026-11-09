package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.simpmusic.podcast.OpenPodcastShow
import com.maxrave.simpmusic.ui.component.EndOfPage
import com.maxrave.simpmusic.ui.component.OpenPodcastCover
import com.maxrave.simpmusic.ui.component.OpenPodcastNowPlayingCard
import com.maxrave.simpmusic.ui.component.OpenPodcastShowRow
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.Search
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.navigation.destination.list.OpenPodcastDestination
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.OpenPodcastLoad
import com.maxrave.simpmusic.viewModel.OpenPodcastViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.open_podcasts_error
import simpmusic.composeapp.generated.resources.open_podcasts_no_results
import simpmusic.composeapp.generated.resources.open_podcasts_no_shows
import simpmusic.composeapp.generated.resources.open_podcasts_search_hint
import simpmusic.composeapp.generated.resources.open_podcasts_source
import simpmusic.composeapp.generated.resources.open_podcasts_trending
import simpmusic.composeapp.generated.resources.open_podcasts_your_shows
import simpmusic.composeapp.generated.resources.retry

internal fun NavController.openPodcastShow(show: OpenPodcastShow) =
    navigate(OpenPodcastDestination(feedUrl = show.feedUrl, title = show.title, author = show.author, imageUrl = show.imageUrl))

/**
 * Home's Podcasts filter, from open sources only: the shows you follow, a search, and fyyd's
 * trending list. Episodes play from each show's RSS feed, never from YouTube.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomePodcastsView(
    navController: NavController,
    topPadding: Dp,
    viewModel: OpenPodcastViewModel = koinViewModel(),
) {
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val trending by viewModel.trending.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadTrending() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topPadding + 8.dp, bottom = 24.dp),
    ) {
        if (playerState.isActive) {
            stickyHeader {
                OpenPodcastNowPlayingCard(
                    state = playerState,
                    onToggle = viewModel.player::togglePlayPause,
                    onSeekBy = viewModel.player::seekBy,
                    onSeekTo = viewModel.player::seekTo,
                    onSetSpeed = viewModel.player::setSpeed,
                    onOpenShow =
                        playerState.feedUrl?.let { feed ->
                            {
                                navController.navigate(
                                    OpenPodcastDestination(feedUrl = feed, title = playerState.showTitle, imageUrl = playerState.imageUrl),
                                )
                            }
                        },
                    modifier = Modifier.background(MaterialTheme.colorScheme.background),
                )
            }
        }
        item {
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.search(it)
                },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.open_podcasts_search_hint)) },
                leadingIcon = { Icon(SimpIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            viewModel.search("")
                        }) { Icon(SimpIcons.Close, contentDescription = null) }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(12.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedIndicatorColor = MaterialTheme.colorScheme.background.copy(alpha = 0f),
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.background.copy(alpha = 0f),
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (query.isNotBlank()) {
            when (val r = results) {
                is OpenPodcastLoad.Ready -> {
                    if (r.value.isEmpty()) {
                        item { Hint(stringResource(Res.string.open_podcasts_no_results)) }
                    } else {
                        items(r.value, key = { "s" + it.feedUrl }) { show ->
                            OpenPodcastShowRow(show) { navController.openPodcastShow(show) }
                        }
                    }
                }

                OpenPodcastLoad.Failed -> item { ErrorRow { viewModel.search(query) } }
                else -> item { Loading() }
            }
            item { EndOfPage() }
            return@LazyColumn
        }

        item { SectionTitle(stringResource(Res.string.open_podcasts_your_shows)) }
        item {
            if (subscriptions.isEmpty()) {
                Hint(stringResource(Res.string.open_podcasts_no_shows))
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(subscriptions, key = { it.feedUrl }) { show ->
                        Column(
                            Modifier
                                .width(140.dp)
                                .clickable { navController.openPodcastShow(show) },
                        ) {
                            OpenPodcastCover(show.imageUrl, 140.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = show.title,
                                style = typo().titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
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
        }
        item {
            Spacer(Modifier.height(16.dp))
            SectionTitle(stringResource(Res.string.open_podcasts_trending))
        }
        when (val t = trending) {
            is OpenPodcastLoad.Ready ->
                itemsIndexed(t.value, key = { _, show -> "t" + show.feedUrl }) { index, show ->
                    OpenPodcastShowRow(show, rank = index + 1, onClick = { navController.openPodcastShow(show) })
                }

            OpenPodcastLoad.Failed -> item { ErrorRow { viewModel.loadTrending(force = true) } }
            else -> item { Loading() }
        }
        item {
            Text(
                text = stringResource(Res.string.open_podcasts_source),
                style = typo().bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
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
private fun Hint(text: String) {
    Text(
        text = text,
        style = typo().bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun ErrorRow(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(Res.string.open_podcasts_error),
            style = typo().bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.retry), color = MaterialTheme.colorScheme.primary) }
    }
}
