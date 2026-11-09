package com.maxrave.simpmusic.podcast

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.logger.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform.getKoin

/**
 * The podcast session. Separate from the music service: its own ExoPlayer, plain HTTP (no YouTube
 * resolver, no cache), speech audio attributes, and a notification id of its own so the two
 * notifications never replace each other.
 */
@OptIn(UnstableApi::class)
class OpenPodcastPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player =
            ExoPlayer
                .Builder(this)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    true,
                ).setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setSeekBackIncrementMs(15_000)
                .setSeekForwardIncrementMs(30_000)
                .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider
                .Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .build(),
        )
        val builder = MediaSession.Builder(this, player).setId(SESSION_ID)
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val NOTIFICATION_ID = 0x5D0C
        const val CHANNEL_ID = "simpmusic_open_podcast"
        const val SESSION_ID = "simpmusic_open_podcast"
    }
}

@OptIn(UnstableApi::class)
private class AndroidOpenPodcastPlayer(
    private val context: Context,
) : OpenPodcastPlayer {
    private val scope = MainScope()
    private val prefs = context.getSharedPreferences("open_podcast_positions", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(OpenPodcastPlayerState())
    override val state: StateFlow<OpenPodcastPlayerState> = _state
    override val isAvailable: Boolean = true

    private var controller: MediaController? = null
    private var connecting = false
    private val pendingActions = ArrayList<(MediaController) -> Unit>()
    private var pollJob: Job? = null
    private var current: Pair<OpenPodcastShow, OpenPodcastEpisode>? = null
    private var lastSavedAt = 0L

    init {
        // Music starting stops the podcast; the reverse is in play().
        scope.launch {
            runCatching { getKoin().get<MediaPlayerHandler>() }.getOrNull()?.controlState
                ?.distinctUntilChangedBy { it.isPlaying }
                ?.collect { if (it.isPlaying) controller?.takeIf { c -> c.isPlaying }?.pause() }
        }
    }

    private fun withController(action: (MediaController) -> Unit) {
        // The session goes away with its service (notification dismissed, task removed while
        // paused); a dead controller is dropped and the next call connects again.
        controller?.takeIf { !it.isConnected }?.let {
            it.release()
            controller = null
            pollJob?.cancel()
        }
        controller?.let {
            action(it)
            return
        }
        pendingActions += action
        if (connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, OpenPodcastPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val built = runCatching { future.get() }.getOrNull()
            if (built == null) {
                Logger.w(TAG, "Could not connect to the podcast session")
                pendingActions.clear()
                return@addListener
            }
            controller = built
            built.addListener(
                object : Player.Listener {
                    override fun onEvents(
                        player: Player,
                        events: Player.Events,
                    ) = publish()
                },
            )
            pendingActions.toList().forEach { it(built) }
            pendingActions.clear()
            startPolling()
        }, ContextCompat.getMainExecutor(context))
    }

    override fun play(
        show: OpenPodcastShow,
        episode: OpenPodcastEpisode,
    ) {
        saveCurrentPosition()
        current = show to episode
        pauseMusic()
        val item =
            MediaItem
                .Builder()
                .setMediaId(episode.id)
                .setUri(episode.audioUrl)
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(episode.title)
                        .setArtist(show.title)
                        .setAlbumTitle(show.title)
                        .setArtworkUri((episode.imageUrl ?: show.imageUrl)?.toUri())
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build(),
                ).build()
        val start = savedPosition(episode.id)
        _state.value =
            OpenPodcastPlayerState(
                episodeId = episode.id,
                episodeTitle = episode.title,
                showTitle = show.title,
                feedUrl = show.feedUrl,
                imageUrl = episode.imageUrl ?: show.imageUrl,
                isBuffering = true,
                positionMs = start,
                durationMs = (episode.durationSeconds ?: 0L) * 1000,
            )
        withController {
            it.setMediaItem(item, start)
            it.prepare()
            it.play()
        }
    }

    private fun pauseMusic() {
        val handler = runCatching { getKoin().get<MediaPlayerHandler>() }.getOrNull() ?: return
        if (handler.controlState.value.isPlaying) {
            scope.launch { handler.onPlayerEvent(PlayerEvent.PlayPause) }
        }
    }

    override fun togglePlayPause() {
        withController {
            if (it.isPlaying) {
                it.pause()
                saveCurrentPosition()
            } else {
                pauseMusic()
                it.play()
            }
        }
    }

    override fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0L)) }

    override fun seekBy(deltaMs: Long) = withController { it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0L)) }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3f)
        withController { it.setPlaybackSpeed(clamped) }
        _state.value = _state.value.copy(playbackSpeed = clamped)
    }

    override fun stop() {
        saveCurrentPosition()
        controller?.run {
            stop()
            clearMediaItems()
        }
        current = null
        _state.value = OpenPodcastPlayerState()
    }

    override fun savedPosition(episodeId: String): Long = prefs.getLong(episodeId, 0L)

    private fun saveCurrentPosition() {
        val c = controller ?: return
        val id = current?.second?.id ?: return
        val position = c.currentPosition
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        prefs.edit {
            // Within 30 s of the end counts as finished: the next play starts from the top.
            if (duration > 0 && position >= duration - 30_000) remove(id) else putLong(id, position)
        }
        lastSavedAt = System.currentTimeMillis()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob =
            scope.launch {
                while (isActive) {
                    publish()
                    if (controller?.isPlaying == true && System.currentTimeMillis() - lastSavedAt > 5_000) saveCurrentPosition()
                    delay(500)
                }
            }
    }

    private fun publish() {
        val c = controller ?: return
        val (show, episode) = current ?: return
        val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: ((episode.durationSeconds ?: 0L) * 1000)
        _state.value =
            _state.value.copy(
                episodeId = episode.id,
                episodeTitle = episode.title,
                showTitle = show.title,
                feedUrl = show.feedUrl,
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                playbackSpeed = c.playbackParameters.speed,
            )
    }

    private companion object {
        const val TAG = "OpenPodcast"
    }
}

private val podcastPlayerInstance: OpenPodcastPlayer by lazy { AndroidOpenPodcastPlayer(getKoin().get<Context>().applicationContext) }

actual fun openPodcastPlayer(): OpenPodcastPlayer = podcastPlayerInstance
