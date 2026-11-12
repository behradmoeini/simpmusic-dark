package com.maxrave.simpmusic.ui.navigation.destination.list

import kotlinx.serialization.Serializable

/** One Spotify playlist from the daily snapshot, played in its own order. */
@Serializable
data class SpotifyChartDestination(
    val playlistId: String,
)
