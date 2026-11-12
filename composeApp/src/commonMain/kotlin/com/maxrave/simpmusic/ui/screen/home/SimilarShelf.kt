package com.maxrave.simpmusic.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.simpmusic.ui.component.OpenPodcastCover
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Refresh
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.HomeViewModel
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.discover_mix_refresh
import simpmusic.composeapp.generated.resources.similar_mix_play
import simpmusic.composeapp.generated.resources.similar_mix_subtitle
import simpmusic.composeapp.generated.resources.similar_mix_title

/** Home shelf "Like what you play": Last.fm similar songs the listener has not played yet. Hidden until it has tracks. */
@Composable
internal fun SimilarShelf(viewModel: HomeViewModel) {
    val tracks by viewModel.similar.collectAsStateWithLifecycle()
    val loading by viewModel.similarLoading.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadSimilar() }
    if (tracks.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.similar_mix_title),
                    style = typo().headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.similar_mix_subtitle),
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            IconButton(onClick = { viewModel.loadSimilar(newVariant = true) }, enabled = !loading) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(SimpIcons.Refresh, contentDescription = stringResource(Res.string.discover_mix_refresh), tint = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { viewModel.playSimilar() },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Icon(SimpIcons.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.similar_mix_play), color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            itemsIndexed(tracks, key = { _, t -> t.videoId }) { index, track ->
                Column(
                    Modifier
                        .width(140.dp)
                        .clickable { viewModel.playSimilar(index) },
                ) {
                    Box {
                        OpenPodcastCover(track.thumbnails?.maxByOrNull { it.width }?.url?.replace(Regex("=w\\d+-h\\d+"), "=w544-h544"), 140.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = track.title,
                        style = typo().titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artists?.joinToString { it.name }.orEmpty(),
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
