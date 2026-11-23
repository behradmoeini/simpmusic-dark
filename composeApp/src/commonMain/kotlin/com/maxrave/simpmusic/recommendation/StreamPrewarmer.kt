package com.maxrave.simpmusic.recommendation

import com.maxrave.domain.data.player.DirectAudio
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Resolves a song's stream before it is pressed.
 *
 * Starting a song has two waits: finding its stream URL on YouTube (several requests and a
 * signature to work out: seconds on a slow device), then the first bytes. The first wait does not
 * need the press. A resolved stream is stored with its expiry (about six hours) and the player
 * uses a stored one that is still good, so a song resolved ahead starts after the second wait only.
 *
 * Callers pass the songs most likely to be pressed next: the first cards of a shelf when it
 * appears, and the card the TV remote is resting on. Two at a time, and each song once per
 * [REPEAT_AFTER_MS], so a page of cards does not turn into a burst of requests.
 */
class StreamPrewarmer(
    private val streamRepository: StreamRepository,
    private val dataStoreManager: DataStoreManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(PARALLEL)
    private val asked = HashMap<String, Long>()

    fun prewarm(videoIds: List<String>) {
        val nowMs = System.currentTimeMillis()
        val wanted =
            synchronized(asked) {
                videoIds
                    .filter { it.isNotBlank() && !DirectAudio.isDirect(it) }
                    .distinct()
                    .filter { nowMs - (asked[it] ?: 0L) > REPEAT_AFTER_MS }
                    .take(MAX_AT_ONCE)
                    .onEach { asked[it] = nowMs }
            }
        for (id in wanted) {
            scope.launch {
                gate.withPermit {
                    try {
                        val stored = streamRepository.getNewFormat(id).lastOrNull()
                        if (stored?.audioUrl != null && stored.expiredTime > now()) return@withPermit
                        val url = streamRepository.getStream(dataStoreManager, id, isDownloading = false, isVideo = false).lastOrNull()
                        Logger.i(TAG, "Resolved ahead: $id ${if (url == null) "failed" else "ready"}")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(TAG, "Could not resolve $id ahead: ${e.message}")
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "Prewarm"
        const val PARALLEL = 2
        const val MAX_AT_ONCE = 6
        const val REPEAT_AFTER_MS = 30 * 60_000L
    }
}
