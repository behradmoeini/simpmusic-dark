package com.maxrave.simpmusic.ui.navigation.destination.list

import kotlinx.serialization.Serializable

/** A show from the open podcast directory, identified by its RSS feed. Not a YouTube page. */
@Serializable
data class OpenPodcastDestination(
    val feedUrl: String,
    val title: String,
    val author: String = "",
    val imageUrl: String? = null,
)
