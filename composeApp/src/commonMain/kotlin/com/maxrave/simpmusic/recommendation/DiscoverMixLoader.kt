package com.maxrave.simpmusic.recommendation

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.artist.ArtistBrowse
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.AnalyticsRepository
import com.maxrave.domain.repository.ArtistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.MusicVideoType
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.random.Random

@Serializable
private data class DiscoverMixCache(
    val day: Long,
    val variant: Int,
    val tracks: List<Track>,
)

/**
 * Builds [DiscoverMix] from the listener's own history (local playback events, liked songs,
 * followed artists) and YouTube Music's artist pages (related artists, top songs).
 *
 * The mix is fixed for the day, so Home does not reshuffle on every visit; "New mix" moves to the
 * next variant. It is cached so opening the app does not repeat ~17 artist-page requests.
 */
class DiscoverMixLoader(
    private val analyticsRepository: AnalyticsRepository,
    private val artistRepository: ArtistRepository,
    private val songRepository: SongRepository,
    private val dataStoreManager: DataStoreManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(newVariant: Boolean = false): List<Track> {
        val today = LocalDate.now().toEpochDay()
        val cached = readCache()
        if (!newVariant && cached != null && cached.day == today && cached.tracks.isNotEmpty()) return cached.tracks
        val variant = if (cached?.day == today) cached.variant + (if (newVariant) 1 else 0) else 0
        val tracks = build(Random(today * 1_000 + variant))
        if (tracks.isNotEmpty()) writeCache(DiscoverMixCache(today, variant, tracks))
        return tracks
    }

    private suspend fun build(random: Random): List<Track> {
        val recent = analyticsRepository.queryTopArtistsLastXDays(30).first().map { ArtistPlays(it.channelId, it.playCount) }
        val allTime = analyticsRepository.queryTopArtistsLastXDays(3650).first().map { ArtistPlays(it.channelId, it.playCount) }
        val seeds = DiscoverMix.pickSeeds(recent, allTime, random)
        if (seeds.isEmpty()) {
            Logger.i(TAG, "No listening history yet, no mix")
            return emptyList()
        }

        val liked = runCatching { songRepository.getLikedSongs().first() }.getOrDefault(emptyList())
        val knownArtists =
            buildSet {
                allTime.forEach { add(it.channelId) }
                runCatching { artistRepository.getFollowedArtists().first() }.getOrDefault(emptyList()).forEach { add(it.channelId) }
                liked.forEach { song -> song.artistId?.forEach { add(it) } }
            }
        val knownSongs =
            buildSet {
                analyticsRepository.queryTopPlayedSongsLastXDays(3650).first().forEach { add(it.videoId) }
                liked.forEach { add(it.videoId) }
            }

        val limit = Semaphore(4)
        val seedPages =
            coroutineScope {
                seeds.map { seed -> async { limit.withPermit { seed to artistPage(seed.channelId) } } }.awaitAll()
            }
        val hits =
            seedPages.flatMap { (seed, page) ->
                page?.related?.results.orEmpty().mapIndexed { index, r ->
                    RelatedHit(seed = seed.channelId, channelId = r.browseId, name = r.title, position = index)
                }
            }
        val chosen = DiscoverMix.selectDiverse(DiscoverMix.rankCandidates(hits, seeds, knownArtists), MAX_ARTISTS)
        Logger.i(TAG, "Seeds ${seeds.size}, related ${hits.size}, new artists ${chosen.size}")

        val perArtist =
            coroutineScope {
                chosen
                    .map { candidate ->
                        async {
                            limit.withPermit {
                                val songs =
                                    artistPage(candidate.channelId)
                                        ?.songs
                                        ?.results
                                        .orEmpty()
                                        .filter {
                                            it.videoId !in knownSongs &&
                                                it.isAvailable &&
                                                !it.isLive &&
                                                !MusicVideoType.isPodcast(it.videoType)
                                        }
                                DiscoverMix.pickSongs(songs, SONGS_PER_ARTIST, random).map { it.toTrack() }
                            }
                        }
                    }.awaitAll()
            }
        return DiscoverMix.interleave(perArtist.filter { it.isNotEmpty() }).distinctBy { it.videoId }.take(MAX_TRACKS)
    }

    private suspend fun artistPage(channelId: String): ArtistBrowse? =
        try {
            withTimeoutOrNull(20_000) {
                (artistRepository.getArtistData(channelId).firstOrNull() as? Resource.Success)?.data
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Artist page $channelId failed: ${e.message}")
            null
        }

    private suspend fun readCache(): DiscoverMixCache? =
        dataStoreManager
            .getString(CACHE_KEY)
            .first()
            ?.let { runCatching { json.decodeFromString(DiscoverMixCache.serializer(), it) }.getOrNull() }

    private suspend fun writeCache(cache: DiscoverMixCache) {
        dataStoreManager.putString(CACHE_KEY, json.encodeToString(DiscoverMixCache.serializer(), cache))
    }

    private companion object {
        const val TAG = "DiscoverMix"
        const val CACHE_KEY = "discover_mix_cache"
        const val MAX_ARTISTS = 12
        const val SONGS_PER_ARTIST = 2
        const val MAX_TRACKS = 24
    }
}
