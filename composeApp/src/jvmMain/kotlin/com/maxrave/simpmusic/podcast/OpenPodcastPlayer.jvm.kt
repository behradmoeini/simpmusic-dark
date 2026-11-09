package com.maxrave.simpmusic.podcast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** This fork ships Android only; Desktop lists podcasts but has no player for them. */
private object NoPodcastPlayer : OpenPodcastPlayer {
    override val state: StateFlow<OpenPodcastPlayerState> = MutableStateFlow(OpenPodcastPlayerState())
    override val isAvailable: Boolean = false

    override fun play(
        show: OpenPodcastShow,
        episode: OpenPodcastEpisode,
    ) = Unit

    override fun togglePlayPause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun seekBy(deltaMs: Long) = Unit

    override fun stop() = Unit

    override fun savedPosition(episodeId: String): Long = 0L
}

actual fun openPodcastPlayer(): OpenPodcastPlayer = NoPodcastPlayer
