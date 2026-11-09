package com.maxrave.simpmusic.chart

import com.maxrave.domain.data.type.ChartItem
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.PlaylistItem

/** Name shown in Your library, and the search that finds the Persian playlist on YouTube Music. */
const val PERSIAN_CHART_NAME = "Trending Weekly - Persian"
private const val PERSIAN_SEARCH = "Persian trending songs"

/**
 * The Persian trending card. The chart server has no Persian chart, so the top Persian playlist
 * from a YouTube Music search stands in for it. It is a live playlist its owner keeps updated.
 * Null when the search fails or finds no playlist.
 */
class PersianTrendingChart(
    private val youTube: YouTube,
) {
    suspend fun load(): ChartItem? =
        youTube
            .search(PERSIAN_SEARCH, YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST)
            .getOrNull()
            ?.items
            ?.filterIsInstance<PlaylistItem>()
            ?.firstOrNull()
            ?.let { ChartItem(name = PERSIAN_CHART_NAME, ytPlaylistId = it.id.removePrefix("VL")) }
}
