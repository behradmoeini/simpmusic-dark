package com.maxrave.simpmusic.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.domain.data.model.home.Content
import com.maxrave.simpmusic.ui.theme.typo

/*
 * The hero at the top of Home on a TV: the card the remote is on, drawn large (cover, title, the
 * line under it), the way Spotify's TV app does. From a sofa a 160dp card's two lines of text are
 * hard to read; the hero is where the eye goes to see what is about to be opened.
 *
 * Home provides a [TvHeroState] through [LocalTvHero] on a TV only. A card reports itself with
 * [tvHero] when it takes focus; anywhere the local is absent (a phone, the Desktop, every other
 * screen) that modifier does nothing.
 */

/** What the hero shows for one card. */
data class TvHeroItem(
    val title: String,
    val subtitle: String?,
    val artwork: String?,
    /** Set when the card is a song: its stream is resolved while the remote rests on it. */
    val videoId: String? = null,
)

@Stable
class TvHeroState {
    var item by mutableStateOf<TvHeroItem?>(null)

    /** Told the song the remote has landed on, so Home can get it ready to start. */
    var onSong: ((String) -> Unit)? = null

    fun focus(focused: TvHeroItem) {
        item = focused
        focused.videoId?.let { onSong?.invoke(it) }
    }
}

val LocalTvHero = staticCompositionLocalOf<TvHeroState?> { null }

/** The hero's height: Home's lists keep the focused shelf this much further down. */
val TvHeroHeight: Dp = 120.dp

fun Content.toTvHero(): TvHeroItem =
    TvHeroItem(
        title = title,
        subtitle = artists?.joinToString { it.name }?.takeIf { it.isNotBlank() } ?: description,
        artwork = thumbnails.lastOrNull()?.url,
        videoId = videoId?.takeIf { it.isNotBlank() },
    )

/** Reports this card to Home's hero when the remote lands on it. Goes before the card's clickable. */
@Composable
fun Modifier.tvHero(item: TvHeroItem?): Modifier {
    val hero = LocalTvHero.current
    if (hero == null || item == null) return this
    return onFocusChanged { if (it.hasFocus) hero.focus(item) }
}

/**
 * The hero itself. Nothing but a cover and two texts on the page's own colour: Home's old glow
 * (see-through layers the width of the screen) is what made the Google TV Streamer take ~45 ms a
 * frame, so no backdrop of the artwork here.
 */
@Composable
fun TvHomeHero(
    item: TvHeroItem?,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = item,
        animationSpec = tween(180),
        modifier = modifier.fillMaxWidth().height(TvHeroHeight),
    ) { shown ->
        if (shown == null) return@Crossfade
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalPlatformContext.current)
                        .data(shown.artwork)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .diskCacheKey(shown.artwork)
                        .crossfade(false)
                        .build(),
                placeholder = rememberHolderPainter(),
                error = rememberHolderPainter(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(TvHeroHeight - 16.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = shown.title,
                    style = typo().titleLarge.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!shown.subtitle.isNullOrBlank()) {
                    Text(
                        text = shown.subtitle,
                        style = typo().bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
