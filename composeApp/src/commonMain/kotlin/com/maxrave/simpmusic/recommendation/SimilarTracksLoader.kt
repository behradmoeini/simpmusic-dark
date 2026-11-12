package com.maxrave.simpmusic.recommendation

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.repository.AnalyticsRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.random.Random

/**
 * "Like what you play": Last.fm's similar tracks for a few songs the listener played recently,
 * minus every song already in their history, each matched to a YouTube Music song to play.
 */
class SimilarTracksLoader(
    private val analyticsRepository: AnalyticsRepository,
    private val songRepository: SongRepository,
    private val searchRepository: SearchRepository,
) {
    suspend fun load(variant: Int = 0): List<Track> {
        if (!LastFmSimilar.isConfigured) return emptyList()
        val played = analyticsRepository.queryTopPlayedSongsLastXDays(3650).first().map { it.videoId }
        if (played.isEmpty()) return emptyList()
        val playedSet = played.toHashSet()
        val recent = analyticsRepository.queryTopPlayedSongsLastXDays(30).first().map { it.videoId }
        val random = Random(variant.toLong() + played.size)
        val seeds = recent.ifEmpty { played }.shuffled(random).take(SEEDS)

        val out = ArrayList<Track>()
        val used = HashSet<String>()
        for (seedId in seeds) {
            if (out.size >= MAX_TRACKS) break
            val seed = songRepository.getSongById(seedId).first() ?: continue
            val artist = seed.artistName?.firstOrNull() ?: continue
            val similar =
                try {
                    LastFmSimilar.similarTracks(artist, seed.title)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Last.fm failed for $seedId: ${e.message}")
                    continue
                }
            for ((simArtist, simTitle) in similar.take(PER_SEED)) {
                if (out.size >= MAX_TRACKS) break
                val match = matchOnYouTube(simArtist, simTitle, playedSet, used) ?: continue
                used += match.videoId
                out += match
            }
        }
        Logger.i(TAG, "Similar: ${out.size} new tracks from ${seeds.size} seeds")
        return out
    }

    /** The first YouTube Music song for the Last.fm pair that the listener has not played yet. */
    private suspend fun matchOnYouTube(
        artist: String,
        title: String,
        played: Set<String>,
        used: Set<String>,
    ): Track? {
        val results = searchRepository.getSearchDataSong("$title $artist").first().data ?: return null
        return results
            .firstOrNull { it.videoId.isNotBlank() && it.videoId !in played && it.videoId !in used }
            ?.toTrack()
    }

    private companion object {
        const val TAG = "SimilarTracks"
        const val SEEDS = 6
        const val PER_SEED = 8
        const val MAX_TRACKS = 30
    }
}
