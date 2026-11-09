package com.maxrave.simpmusic.podcast

import kotlinx.coroutines.flow.StateFlow

/**
 * Plays RSS podcast episodes. The music player only understands YouTube video ids (its stream
 * resolver lives in the upstream `core` module), so podcasts get a player of their own: on Android
 * a separate Media3 session with its own notification and lock-screen controls.
 *
 * Starting an episode pauses the music, and music starting pauses the episode.
 */
interface OpenPodcastPlayer {
    val state: StateFlow<OpenPodcastPlayerState>

    /** False where there is no podcast player (Desktop): the UI hides play buttons. */
    val isAvailable: Boolean

    fun play(
        show: OpenPodcastShow,
        episode: OpenPodcastEpisode,
    )

    fun togglePlayPause()

    fun seekTo(positionMs: Long)

    fun seekBy(deltaMs: Long)

    /** Playback speed, for example 1.25f. Kept on the player, so the next episode keeps it. */
    fun setSpeed(speed: Float)

    fun stop()

    /** Where the listener left [episodeId], in ms, or 0. */
    fun savedPosition(episodeId: String): Long
}

data class OpenPodcastPlayerState(
    val episodeId: String? = null,
    val episodeTitle: String = "",
    val showTitle: String = "",
    val feedUrl: String? = null,
    val imageUrl: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
) {
    val isActive: Boolean get() = episodeId != null
}

/** One player per process. */
expect fun openPodcastPlayer(): OpenPodcastPlayer
