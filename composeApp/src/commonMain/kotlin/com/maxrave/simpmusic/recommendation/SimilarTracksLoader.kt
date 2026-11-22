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
 * "Like what you play": Deezer's related artists of the artists the listener played recently, their
 * top tracks, minus everything already in the listener's history, each matched to a YouTube Music song.
 */
class SimilarTracksLoader(
    private val analyticsRepository: AnalyticsRepository,
    private val songRepository: SongRepository,
    private val searchRepository: SearchRepository,
) {
    suspend fun load(variant: Int = 0): List<Track> {
        val played = analyticsRepository.queryTopPlayedSongsLastXDays(3650).first().map { it.videoId }
        if (played.isEmpty()) return emptyList()
        val playedIds = played.toHashSet()

        // Titles the listener already knows, so a candidate they have heard is never offered again.
        val playedKeys = HashSet<String>()
        for (id in played.take(HISTORY_KEYS)) {
            val song = songRepository.getSongById(id).first() ?: continue
            playedKeys += trackKey(song.artistName?.firstOrNull().orEmpty(), song.title)
        }

        val recent = analyticsRepository.queryTopPlayedSongsLastXDays(30).first().map { it.videoId }
        val random = Random(variant.toLong() + played.size)
        val seedArtists =
            (recent.ifEmpty { played })
                .shuffled(random)
                .take(SEEDS)
                .mapNotNull { id -> songRepository.getSongById(id).first()?.artistName?.firstOrNull() }
                .distinct()

        val candidates = ArrayList<Pair<String, String>>()
        for (artist in seedArtists) {
            try {
                val id = DeezerCatalog.artistId(artist) ?: continue
                for ((relatedId, _) in DeezerCatalog.relatedArtists(id)) {
                    candidates += DeezerCatalog.topTracks(relatedId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Deezer failed for $artist: ${e.message}")
            }
        }

        val out = ArrayList<Track>()
        val used = HashSet<String>()
        for ((candArtist, candTitle) in candidates.shuffled(random)) {
            if (out.size >= MAX_TRACKS) break
            if (trackKey(candArtist, candTitle) in playedKeys) continue
            val match = matchOnYouTube(candArtist, candTitle, playedIds, used) ?: continue
            used += match.videoId
            out += match
        }
        Logger.i(TAG, "Similar: ${out.size} new tracks from ${seedArtists.size} artists, ${candidates.size} candidates")
        return out
    }

    /** The first YouTube Music song for the pair that the listener has not played yet. */
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

    private fun trackKey(
        artist: String,
        title: String,
    ): String = (artist + "|" + title).lowercase().filter { it.isLetterOrDigit() || it == '|' }

    private companion object {
        const val TAG = "SimilarTracks"
        const val SEEDS = 6
        const val HISTORY_KEYS = 300
        const val MAX_TRACKS = 30
    }
}
