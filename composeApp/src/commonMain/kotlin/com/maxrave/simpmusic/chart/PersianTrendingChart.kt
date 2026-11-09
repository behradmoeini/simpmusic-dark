package com.maxrave.simpmusic.chart

import com.maxrave.domain.data.type.ChartItem

/** Name shown in Your library. */
const val PERSIAN_CHART_NAME = "Trending Weekly - Persian"

/** Marks the Persian card. Its tracks come from the Spotify snapshot, not a YouTube playlist. */
const val PERSIAN_CHART_ID = "spotify-persian"

class PersianTrendingChart {
    fun load(): ChartItem = ChartItem(name = PERSIAN_CHART_NAME, ytPlaylistId = PERSIAN_CHART_ID)
}
