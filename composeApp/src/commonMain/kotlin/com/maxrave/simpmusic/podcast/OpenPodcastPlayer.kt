package com.maxrave.simpmusic.podcast

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.player.DirectAudio
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatform.getKoin

/**
 * Plays RSS podcast episodes through the music player, so an episode gets the same mini player,
 * Now Playing screen, queue, notification and lock-screen controls as a song, on Android and
 * Desktop alike. The track id carries the episode's audio URL ([DirectAudio]); core resolves it to
 * that URL and skips everything YouTube-only for it.
 *
 * This class only adds what a podcast needs on top: it remembers where each episode was left and
 * starts it there again.
 */
interface OpenPodcastPlayer {
    val state: StateFlow<OpenPodcastPlayerState>

    fun play(
        show: OpenPodcastShow,
        episode: OpenPodcastEpisode,
    )

    fun togglePlayPause()

    /** Where the listener left [episode], in ms, or 0. */
    fun savedPosition(episode: OpenPodcastEpisode): Long
}

data class OpenPodcastPlayerState(
    /** [DirectAudio] id of the episode the music player holds now, or null when it holds a song. */
    val audioId: String? = null,
    val isPlaying: Boolean = false,
) {
    fun isEpisode(episode: OpenPodcastEpisode): Boolean = audioId != null && audioId == DirectAudio.idFor(episode.audioUrl)
}

private class MusicQueuePodcastPlayer(
    private val handler: MediaPlayerHandler,
    private val dataStore: DataStoreManager,
) : OpenPodcastPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Saved positions, read once and then kept in memory; DataStore is written as they change.
    private val positions = LinkedHashMap<String, Long>()
    private var lastSavedAt = 0L

    override val state: StateFlow<OpenPodcastPlayerState> =
        combine(handler.nowPlaying, handler.controlState) { item, control ->
            val id = item?.mediaId?.takeIf { DirectAudio.isDirect(it) }
            OpenPodcastPlayerState(audioId = id, isPlaying = id != null && control.isPlaying)
        }.stateIn(scope, SharingStarted.Eagerly, OpenPodcastPlayerState())

    init {
        scope.launch {
            dataStore.getString(POSITIONS_KEY).first()?.let { stored ->
                stored.lineSequence().forEach { line ->
                    val tab = line.lastIndexOf('\t')
                    if (tab > 0) line.substring(tab + 1).toLongOrNull()?.let { positions[line.substring(0, tab)] = it }
                }
            }
        }
        // Remember the position of the episode playing, every few seconds.
        scope.launch {
            handler.simpleMediaState.collect { media ->
                if (media !is SimpleMediaState.Progress) return@collect
                val id = handler.nowPlaying.value?.mediaId?.takeIf { DirectAudio.isDirect(it) } ?: return@collect
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                if (now - lastSavedAt < SAVE_EVERY_MS) return@collect
                lastSavedAt = now
                storePosition(id, media.progress, handler.player.duration)
            }
        }
    }

    override fun play(
        show: OpenPodcastShow,
        episode: OpenPodcastEpisode,
    ) {
        val id = DirectAudio.idFor(episode.audioUrl)
        val image = episode.imageUrl ?: show.imageUrl
        val track =
            Track(
                album = null,
                artists = listOf(Artist(id = null, name = show.title)),
                duration = null,
                durationSeconds = episode.durationSeconds?.toInt(),
                isAvailable = true,
                isExplicit = false,
                likeStatus = null,
                thumbnails = image?.let { listOf(Thumbnail(height = 544, url = it, width = 544)) },
                title = episode.title,
                videoId = id,
                videoType = PODCAST_EPISODE_TYPE,
                category = null,
                feedbackTokens = null,
                resultType = null,
            )
        val start = positions[id] ?: 0L
        scope.launch {
            handler.reset()
            handler.setQueueData(
                QueueData.Data(
                    listTracks = arrayListOf(track),
                    firstPlayedTrack = track,
                    playlistId = null,
                    playlistName = show.title,
                    playlistType = null,
                    continuation = null,
                ),
            )
            handler.loadMediaItem(track, PODCAST_CLICK, 0)
            if (start > 0) {
                // Seek once the player has the episode: it ignores a seek before it is ready.
                val ready =
                    withTimeoutOrNull(20_000L) {
                        handler.nowPlaying.first { it?.mediaId == id }
                        handler.simpleMediaState.first { it is SimpleMediaState.Ready || it is SimpleMediaState.Progress }
                    }
                if (ready != null && handler.nowPlaying.value?.mediaId == id) handler.player.seekTo(start)
            }
            Logger.i(TAG, "Playing episode ${episode.title} of ${show.title} from ${start}ms")
        }
    }

    override fun togglePlayPause() {
        scope.launch { handler.onPlayerEvent(PlayerEvent.PlayPause) }
    }

    override fun savedPosition(episode: OpenPodcastEpisode): Long = positions[DirectAudio.idFor(episode.audioUrl)] ?: 0L

    private fun storePosition(
        id: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        // Within 30 s of the end counts as finished: the next play starts from the top.
        // Re-inserted, so the map runs from the episode left longest ago to the latest.
        positions.remove(id)
        if (durationMs <= 0 || positionMs < durationMs - 30_000) positions[id] = positionMs
        // Keep the newest few hundred, so the stored value cannot grow without end.
        while (positions.size > MAX_SAVED) positions.remove(positions.keys.first())
        val text = positions.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        scope.launch { dataStore.putString(POSITIONS_KEY, text) }
    }

    companion object {
        private const val TAG = "OpenPodcast"
        private const val POSITIONS_KEY = "open_podcast_positions"
        private const val SAVE_EVERY_MS = 5_000L
        private const val MAX_SAVED = 300

        /** Starts no radio and no playlist load in the handler: the queue is the one episode. */
        const val PODCAST_CLICK = "PODCAST_CLICK"
        const val PODCAST_EPISODE_TYPE = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"
    }
}

private val podcastPlayerInstance: OpenPodcastPlayer by lazy {
    MusicQueuePodcastPlayer(getKoin().get(), getKoin().get())
}

/** One player per process. */
fun openPodcastPlayer(): OpenPodcastPlayer = podcastPlayerInstance
