package com.maxrave.simpmusic.chart

import com.maxrave.domain.data.type.ChartItem

/** Name shown in Your library. */
const val PERSIAN_CHART_NAME = "Trending Weekly - Persian"

/**
 * The YouTube Music playlist that backs the Persian card. It is chosen by hand, so the card shows a
 * real Persian ranking instead of whatever a search returns. Empty until a playlist is set, and the
 * card stays hidden while it is empty.
 */
const val PERSIAN_PLAYLIST_ID = ""

class PersianTrendingChart {
    fun load(): ChartItem? =
        PERSIAN_PLAYLIST_ID
            .takeIf { it.isNotBlank() }
            ?.let { ChartItem(name = PERSIAN_CHART_NAME, ytPlaylistId = it.removePrefix("VL")) }
}
