package com.maxrave.simpmusic.chart

import com.maxrave.domain.data.type.ChartItem

/** Prefix on a chart item's id that marks a Spotify playlist from the snapshot. */
const val SPOTIFY_CHART_PREFIX = "spotify:"

/**
 * One library card per Spotify playlist in the daily snapshot, named as the playlist is named on
 * Spotify. Each card opens the playlist in its own order.
 */
class PersianTrendingChart {
    suspend fun loadItems(): List<ChartItem> =
        loadSpotifySnapshot()
            ?.playlists
            ?.filter { it.tracks.isNotEmpty() }
            ?.map { ChartItem(name = it.name, ytPlaylistId = SPOTIFY_CHART_PREFIX + it.id) }
            .orEmpty()
}
