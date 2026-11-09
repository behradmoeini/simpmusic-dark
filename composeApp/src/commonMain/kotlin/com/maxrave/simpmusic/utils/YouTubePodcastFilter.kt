package com.maxrave.simpmusic.utils

import com.maxrave.domain.data.model.home.Content
import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.utils.MusicVideoType

/*
 * Fork: podcasts come from open sources (see the podcast package), so YouTube Music's own podcast
 * cards are taken out of Home. YouTube marks most of them, and the rest are caught by shape:
 *
 * - an episode carries a MUSIC_VIDEO_TYPE_PODCAST_* type, a show opens an "MPSP…" page and an
 *   episode page is "MPED…";
 * - episode rows sent as musicMultiRowListItemRenderer reach Content with a videoId, a
 *   description, no artists and no type at all (HomeParser's multi-row branch is the only one
 *   that builds that shape);
 * - long-form talk uploaded as an ordinary video ("Keep listening", interview clips) carries no
 *   podcast type, so a video (not a song) of LONG_VIDEO_SECONDS or more is dropped too. That also
 *   drops hour-long DJ sets and concerts from Home; songs and anything YouTube marks as audio are
 *   never touched.
 *
 * A shelf that loses half or more of its cards was a podcast shelf with a few stragglers, and goes.
 */

const val LONG_VIDEO_SECONDS = 20 * 60

/**
 * Spotify-style Home is audio-only: music videos (OMV, UGC) are not mixed into the feed. A card YouTube
 * gives no type for stays, so a song is never dropped by mistake.
 */
fun Content.isMusicVideo(): Boolean = MusicVideoType.isVideoSong(videoType)

fun Content.isYouTubePodcast(): Boolean =
    MusicVideoType.isPodcast(videoType) ||
        MusicVideoType.isPodcast(playEndpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType) ||
        browseId.isPodcastPageId() ||
        playlistId.isPodcastPageId() ||
        isMultiRowEpisode() ||
        isLongTalkVideo()

private fun String?.isPodcastPageId(): Boolean = this != null && (startsWith("MPSP") || startsWith("MPED"))

private fun Content.isMultiRowEpisode(): Boolean =
    !videoId.isNullOrEmpty() && videoType == null && artists.isNullOrEmpty() && !description.isNullOrBlank()

private fun Content.isLongTalkVideo(): Boolean =
    !videoId.isNullOrEmpty() &&
        !MusicVideoType.isAudio(videoType) &&
        (durationSeconds ?: 0) >= LONG_VIDEO_SECONDS

/**
 * A plain YouTube playlist card (no song, no duration, often hours long). Albums (OLAK / MPRE),
 * artists (UC), the app's own radios and mixes (RD) are kept, so only playlists that are not songs go.
 */
fun Content.isYouTubePlaylistCard(): Boolean {
    if (!videoId.isNullOrEmpty()) return false
    if (browseId?.startsWith("UC") == true || browseId?.startsWith("MPRE") == true) return false
    val id = playlistId ?: return false
    return !(id.startsWith("OLAK") || id.startsWith("RD") || id.startsWith("VLOLAK") || id.startsWith("VLRD"))
}

/** Drops podcast cards, and any shelf left mostly empty or whose "More" opens a podcast page. */
fun List<HomeItem>.withoutYouTubePodcasts(): List<HomeItem> =
    mapNotNull { item ->
        if (item.moreEndpoint?.pageType == HomeItem.MoreEndpoint.PAGE_TYPE_PODCAST) return@mapNotNull null
        val cards = item.contents.filterNotNull()
        val kept = cards.filterNot { it.isYouTubePodcast() || it.isMusicVideo() || it.isYouTubePlaylistCard() }
        when {
            kept.size == cards.size -> item
            kept.isEmpty() || kept.size * 2 <= cards.size -> null
            else -> item.copy(contents = kept)
        }
    }
