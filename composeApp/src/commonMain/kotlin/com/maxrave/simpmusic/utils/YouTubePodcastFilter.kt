package com.maxrave.simpmusic.utils

import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.utils.MusicVideoType

/*
 * Fork: podcasts come from open sources (see the podcast package), so YouTube Music's own podcast
 * cards are taken out of Home. A card is a podcast when YouTube says so: an episode carries a
 * MUSIC_VIDEO_TYPE_PODCAST_* type, a show opens an "MPSP…" page, an episode page is "MPED…".
 */

fun Content.isYouTubePodcast(): Boolean =
    MusicVideoType.isPodcast(videoType) ||
        MusicVideoType.isPodcast(playEndpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig.musicVideoType) ||
        browseId.isPodcastPageId() ||
        playlistId.isPodcastPageId()

private fun String?.isPodcastPageId(): Boolean = this != null && (startsWith("MPSP") || startsWith("MPED"))

/** Drops podcast cards, and any shelf left with nothing else or whose "More" opens a podcast page. */
fun List<HomeItem>.withoutYouTubePodcasts(): List<HomeItem> =
    mapNotNull { item ->
        if (item.moreEndpoint?.pageType == HomeItem.MoreEndpoint.PAGE_TYPE_PODCAST) return@mapNotNull null
        val kept = item.contents.filter { it != null && !it.isYouTubePodcast() }
        when {
            kept.isEmpty() -> null
            kept.size == item.contents.size -> item
            else -> item.copy(contents = kept)
        }
    }
