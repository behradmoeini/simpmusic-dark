package org.simpmusic.mcp

import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.models.AlbumItem
import com.maxrave.kotlinytmusicscraper.models.PlaylistItem
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.VideoItem
import com.maxrave.kotlinytmusicscraper.models.WatchEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class QueueTrack(
    val videoId: String,
    val title: String,
    val artists: String,
    val durationSeconds: Int?,
) {
    fun label(): String = if (artists.isBlank()) title else "$title — $artists"
}

data class SearchHit(
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val durationSeconds: Int? = null,
)

/**
 * The queue and everything that turns a YouTube Music id into sound: core's YouTube client finds
 * songs, builds radios and extracts the audio stream (the same extraction tiers the app uses), and
 * [Mpv] plays the URL. All queue changes happen under one lock.
 */
class MusicPlayer {
    private val youTube = YouTube()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val mpv = Mpv(onEndOfFile = { reason -> scope.launch { onTrackEnded(reason) } })

    private val queue = mutableListOf<QueueTrack>()
    private var index = -1

    // Radio: the endpoint and continuation that fetch the next batch once the queue runs low.
    private var radioEndpoint: WatchEndpoint? = null
    private var radioContinuation: String? = null
    private var visitorDataReady = false

    // A stream that fails mid-track is resolved again once before the track is skipped.
    private var retriedVideoId: String? = null

    val current: QueueTrack? get() = queue.getOrNull(index)

    suspend fun search(
        query: String,
        kind: String,
        limit: Int,
    ): List<SearchHit> {
        prepareClient()
        val filter =
            when (kind) {
                "videos" -> YouTube.SearchFilter.FILTER_VIDEO
                "albums" -> YouTube.SearchFilter.FILTER_ALBUM
                "playlists" -> YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST
                else -> YouTube.SearchFilter.FILTER_SONG
            }
        val items = youTube.search(query, filter).getOrThrow().items
        return items
            .mapNotNull { item ->
                when (item) {
                    is SongItem -> SearchHit("song", item.id, item.title, item.artists.joinToString { it.name }, item.duration)
                    is VideoItem -> SearchHit("video", item.id, item.title, item.artists.joinToString { it.name }, item.duration)
                    is AlbumItem -> SearchHit("album", item.playlistId, item.title, item.artists.orEmpty().joinToString { it.name })
                    is PlaylistItem -> SearchHit("playlist", item.id, item.title, item.author?.name.orEmpty())
                    else -> null
                }
            }.take(limit)
    }

    /** Plays one song (by id, or the best match for [query]) and, when [radio], a radio after it. */
    suspend fun play(
        query: String?,
        videoId: String?,
        radio: Boolean,
    ): String {
        prepareClient()
        val track =
            when {
                !videoId.isNullOrBlank() -> QueueTrack(videoId, "", "", null)
                !query.isNullOrBlank() ->
                    search(query, "songs", 1).firstOrNull()?.toTrack()
                        ?: search(query, "videos", 1).firstOrNull()?.toTrack()
                        ?: return "Nothing on YouTube Music matches \"$query\"."
                else -> return "Say what to play: a query or a video_id."
            }
        val started =
            lock.withLock {
                queue.clear()
                queue += track
                index = 0
                radioEndpoint = null
                radioContinuation = null
                startCurrentLocked()
            }
        if (!radio) return started
        val added = runCatching { extendRadio(WatchEndpoint(videoId = track.videoId), firstBatch = true) }.getOrDefault(0)
        return "$started Radio: $added similar songs queued after it."
    }

    /** Replaces the queue with a playlist or album (by its playlist id) and plays it from the top. */
    suspend fun playPlaylist(
        playlistId: String,
        shuffle: Boolean,
    ): String {
        prepareClient()
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        val tracks =
            youTube
                .getPlaylistFullTracks(browseId)
                .getOrThrow()
                .map { it.toTrack() }
                .let { if (shuffle) it.shuffled() else it }
        if (tracks.isEmpty()) return "That playlist has no playable songs."
        return lock.withLock {
            queue.clear()
            queue += tracks
            index = 0
            radioEndpoint = null
            radioContinuation = null
            "${startCurrentLocked()} ${tracks.size} songs in the queue."
        }
    }

    suspend fun addToQueue(
        query: String?,
        videoId: String?,
        playNext: Boolean,
    ): String {
        prepareClient()
        val track =
            when {
                !videoId.isNullOrBlank() -> QueueTrack(videoId, videoId, "", null)
                !query.isNullOrBlank() ->
                    search(query, "songs", 1).firstOrNull()?.toTrack()
                        ?: return "Nothing on YouTube Music matches \"$query\"."
                else -> return "Say what to add: a query or a video_id."
            }
        return lock.withLock {
            if (index < 0) {
                queue.clear()
                queue += track
                index = 0
                return@withLock startCurrentLocked()
            }
            if (playNext) queue.add(index + 1, track) else queue += track
            "Added ${track.label()} ${if (playNext) "to play next" else "to the end of the queue"}."
        }
    }

    suspend fun next(): String =
        lock.withLock {
            if (index < 0) return@withLock "Nothing is playing."
            if (index + 1 >= queue.size && !fetchMoreLocked()) return@withLock "This is the last song in the queue."
            index++
            startCurrentLocked()
        }

    suspend fun previous(): String =
        lock.withLock {
            if (index < 0) return@withLock "Nothing is playing."
            val position = mpv.property("time-pos").asDouble() ?: 0.0
            if (position > 5 || index == 0) {
                mpv.command("seek", 0, "absolute")
                return@withLock "Restarted ${current?.label()}."
            }
            index--
            startCurrentLocked()
        }

    suspend fun setPaused(paused: Boolean): String =
        lock.withLock {
            if (index < 0 || !mpv.isRunning) return@withLock "Nothing is playing."
            mpv.setProperty("pause", paused)
            if (paused) "Paused." else "Playing ${current?.label()}."
        }

    suspend fun stop(): String =
        lock.withLock {
            if (mpv.isRunning) mpv.command("stop")
            queue.clear()
            index = -1
            radioEndpoint = null
            radioContinuation = null
            "Stopped and cleared the queue."
        }

    suspend fun seek(seconds: Double): String =
        lock.withLock {
            if (index < 0) return@withLock "Nothing is playing."
            mpv.command("seek", seconds.coerceAtLeast(0.0), "absolute")
            "Jumped to ${formatTime(seconds)}."
        }

    suspend fun setVolume(percent: Int): String =
        lock.withLock {
            val volume = percent.coerceIn(0, 130)
            mpv.setProperty("volume", volume)
            "Volume $volume%."
        }

    suspend fun nowPlaying(): String =
        lock.withLock {
            val track = current ?: return@withLock "Nothing is playing."
            val position = mpv.property("time-pos").asDouble()
            val duration = mpv.property("duration").asDouble() ?: track.durationSeconds?.toDouble()
            val paused = mpv.property("pause").asBoolean() == true
            val volume = mpv.property("volume").asDouble()?.toInt()
            buildString {
                append(if (paused) "Paused: " else "Playing: ")
                append(track.label())
                if (position != null) append(" (${formatTime(position)}${duration?.let { " / ${formatTime(it)}" }.orEmpty()})")
                append("\nTrack ${index + 1} of ${queue.size}")
                if (volume != null) append(", volume $volume%")
                val upNext = queue.drop(index + 1).take(3)
                if (upNext.isNotEmpty()) append("\nUp next: ").append(upNext.joinToString("; ") { it.label() })
            }
        }

    suspend fun showQueue(limit: Int): String =
        lock.withLock {
            if (index < 0) return@withLock "The queue is empty."
            val shown = queue.withIndex().drop(index).take(limit)
            buildString {
                shown.forEach { (i, track) ->
                    append(if (i == index) "▶ " else "${i - index}. ")
                    append(track.label())
                    track.durationSeconds?.let { append(" (${formatTime(it.toDouble())})") }
                    append('\n')
                }
                val rest = queue.size - index - shown.size
                if (rest > 0) append("…and $rest more")
            }.trim()
        }

    fun shutdown() {
        runCatching { mpv.shutdown() }
    }

    private suspend fun onTrackEnded(reason: String) {
        lock.withLock {
            val track = current ?: return@withLock
            if (reason == "error" && retriedVideoId != track.videoId) {
                // Stream URLs expire and are refused now and then; one fresh one usually plays.
                retriedVideoId = track.videoId
                System.err.println("[simpmusic-mcp] stream failed for ${track.videoId}, resolving again")
                runCatching { startCurrentLocked() }
                return@withLock
            }
            if (index + 1 >= queue.size && !fetchMoreLocked()) {
                index = -1
                queue.clear()
                return@withLock
            }
            index++
            // A track that cannot be played is skipped rather than ending the queue.
            var attempts = 0
            while (attempts < 5) {
                val ok = runCatching { startCurrentLocked() }.isSuccess
                if (ok) break
                attempts++
                if (index + 1 >= queue.size) break
                index++
            }
        }
        // Keep a radio stocked: fetch the next batch while a couple of songs are still left.
        if (index >= 0 && radioEndpoint != null && queue.size - index <= 3) {
            runCatching { lock.withLock { fetchMoreLocked() } }
        }
    }

    /** Resolves the current track's stream and starts it. Must hold [lock]. */
    private suspend fun startCurrentLocked(): String {
        val track = current ?: return "Nothing to play."
        val stream = resolveStream(track.videoId)
        if (track.title.isBlank() || track.title == track.videoId) {
            queue[index] = track.copy(title = stream.title ?: track.videoId, artists = stream.author.orEmpty(), durationSeconds = stream.durationSeconds)
        }
        if (retriedVideoId != track.videoId) retriedVideoId = null
        mpv.command("loadfile", stream.url, "replace")
        mpv.setProperty("pause", false)
        return "Playing ${current?.label()}."
    }

    private suspend fun extendRadio(
        endpoint: WatchEndpoint,
        firstBatch: Boolean,
    ): Int {
        val result = youTube.next(endpoint, if (firstBatch) null else radioContinuation).getOrThrow()
        return lock.withLock {
            val known = queue.map { it.videoId }.toSet()
            val added = result.items.filter { it.id !in known }.map { it.toTrack() }
            queue += added
            radioEndpoint = result.endpoint
            radioContinuation = result.continuation
            added.size
        }
    }

    /** Fetches the radio's next batch. Must hold [lock]. Returns whether anything was added. */
    private suspend fun fetchMoreLocked(): Boolean {
        val endpoint = radioEndpoint ?: return false
        val continuation = radioContinuation ?: return false
        val result = runCatching { youTube.next(endpoint, continuation).getOrThrow() }.getOrNull() ?: return false
        val known = queue.map { it.videoId }.toSet()
        val added = result.items.filter { it.id !in known }.map { it.toTrack() }
        queue += added
        radioEndpoint = result.endpoint
        radioContinuation = result.continuation
        return added.isNotEmpty()
    }

    private class Stream(
        val url: String,
        val title: String?,
        val author: String?,
        val durationSeconds: Int?,
    )

    /**
     * The audio URL for [videoId], picked the way the app's StreamRepository picks it: Opus 251,
     * then AAC 140, then any audio-only format; a live broadcast plays from its HLS playlist.
     */
    private suspend fun resolveStream(videoId: String): Stream {
        val (_, response, _) = youTube.player(videoId).getOrThrow()
        val details = response.videoDetails
        val streaming = response.streamingData
        val url =
            if (details?.isLive == true) {
                streaming?.hlsManifestUrl
            } else {
                val formats = (streaming?.formats.orEmpty() + streaming?.adaptiveFormats.orEmpty()).filter { !it.url.isNullOrEmpty() }
                (
                    formats.firstOrNull { it.itag == 251 }
                        ?: formats.firstOrNull { it.itag == 140 }
                        ?: formats.filter { it.isAudio }.maxByOrNull { it.bitrate ?: 0 }
                        ?: formats.firstOrNull()
                )?.url
            }
        if (url.isNullOrEmpty()) {
            val reason = response.playabilityStatus?.reason
            throw IllegalStateException("YouTube gave no playable stream for $videoId${reason?.let { ": $it" }.orEmpty()}")
        }
        return Stream(url, details?.title, details?.author, details?.lengthSeconds?.toIntOrNull())
    }

    /** Anonymous visitor data, once: without it YouTube bot-checks every request. */
    private suspend fun prepareClient() {
        if (visitorDataReady) return
        visitorDataReady = true
        runCatching { youTube.visitorData() }.getOrNull()?.let { youTube.visitorData = it }
    }

    private fun SearchHit.toTrack() = QueueTrack(id, title, subtitle, durationSeconds)

    private fun SongItem.toTrack() = QueueTrack(id, title, artists.joinToString { it.name }, duration)
}

internal fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
