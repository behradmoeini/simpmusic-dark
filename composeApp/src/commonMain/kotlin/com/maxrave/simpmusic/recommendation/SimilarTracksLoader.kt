package com.maxrave.simpmusic.recommendation

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.AnalyticsRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.random.Random

@Serializable
private data class SimilarMixCache(
    val day: Long,
    val variant: Int,
    val tracks: List<Track>,
)

/**
 * "Like what you play": songs the listener has not played, by artists Deezer relates to the ones
 * they play, each matched to a YouTube Music song.
 *
 * - Seeds are sampled from the listener's artists, weighted by plays, with one older favourite
 *   ([DiscoverMix.pickSeeds]), so the shelf follows the whole taste and not only the top artist.
 * - A related artist named by several seeds ranks higher; artists the listener already plays stay
 *   in at a discount (unlike "Same vibe, new artists", which drops them), and one seed cannot fill
 *   the shelf ([DiscoverMix.selectDiverse]).
 * - At most [SONGS_PER_ARTIST] songs per artist, interleaved, never one already in the history.
 * - Fixed for the day and cached; the refresh button moves to the next variant.
 */
class SimilarTracksLoader(
    private val analyticsRepository: AnalyticsRepository,
    private val songRepository: SongRepository,
    private val searchRepository: SearchRepository,
    private val dataStoreManager: DataStoreManager,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val matcher = YouTubeMatcher(searchRepository)

    suspend fun load(newVariant: Boolean = false): List<Track> {
        val today = LocalDate.now().toEpochDay()
        val cached = readCache()
        if (!newVariant && cached != null && cached.day == today && cached.tracks.isNotEmpty()) return cached.tracks
        val variant = if (cached?.day == today) cached.variant + (if (newVariant) 1 else 0) else 0
        val tracks = build(Random(today * 1_000 + variant + 7))
        if (tracks.isNotEmpty()) writeCache(SimilarMixCache(today, variant, tracks))
        return tracks
    }

    private suspend fun build(random: Random): List<Track> {
        val allTime = analyticsRepository.queryTopPlayedSongsLastXDays(3650).first()
        if (allTime.isEmpty()) return emptyList()
        val recent = analyticsRepository.queryTopPlayedSongsLastXDays(30).first()
        val playedIds = allTime.map { it.videoId }.toHashSet()

        // One query for the rows behind the history: artist names for seeds, titles to exclude.
        val wanted = (recent.take(HISTORY_ROWS).map { it.videoId } + allTime.take(HISTORY_ROWS).map { it.videoId }).distinct()
        val songs = songRepository.getSongsByListVideoId(wanted).first().associateBy { it.videoId }
        val playedKeys = HashSet<String>()
        val knownArtistKeys = HashSet<String>()
        for (song in songs.values) {
            val artist = song.artistName?.firstOrNull().orEmpty()
            playedKeys += SimilarMix.trackKey(artist, song.title)
            song.artistName?.forEach { knownArtistKeys += SimilarMix.key(it) }
        }

        fun plays(rows: List<com.maxrave.domain.data.entities.analytics.query.TopPlayedTracks>) =
            SimilarMix.artistPlays(rows.mapNotNull { row -> songs[row.videoId]?.artistName?.firstOrNull()?.let { it to row.playCount } })
        val seeds = DiscoverMix.pickSeeds(plays(recent), plays(allTime), random, count = SEEDS)
        if (seeds.isEmpty()) return emptyList()

        val limit = Semaphore(PARALLEL)
        val hits =
            coroutineScope {
                seeds
                    .map { seed ->
                        async {
                            limit.withPermit {
                                guarded("related of ${seed.channelId}") {
                                    val id = DeezerCatalog.artistId(seed.channelId) ?: return@guarded emptyList()
                                    DeezerCatalog.relatedArtists(id, RELATED_PER_SEED).mapIndexed { index, related ->
                                        RelatedHit(seed = seed.channelId, channelId = related.id.toString(), name = related.name, position = index)
                                    }
                                }.orEmpty()
                            }
                        }
                    }.awaitAll()
                    .flatten()
            }
        val ranked =
            SimilarMix.discountKnown(
                DiscoverMix.rankCandidates(hits, seeds, emptySet()),
                knownArtistKeys,
                seeds.map { SimilarMix.key(it.channelId) }.toSet(),
            )
        val chosen = DiscoverMix.selectDiverse(ranked, MAX_ARTISTS)

        val perArtist =
            coroutineScope {
                chosen
                    .map { candidate ->
                        async {
                            limit.withPermit {
                                val top =
                                    guarded("top of ${candidate.name}") {
                                        DeezerCatalog.topTracks(candidate.channelId.toLong(), TOP_PER_ARTIST)
                                    }.orEmpty()
                                        .distinctBy { SimilarMix.trackKey(it.artist, it.title) }
                                        .filter { SimilarMix.trackKey(it.artist, it.title) !in playedKeys }
                                DiscoverMix.pickSongs(top, SONGS_PER_ARTIST, random)
                            }
                        }
                    }.awaitAll()
            }
        val candidates = DiscoverMix.interleave(perArtist.filter { it.isNotEmpty() }).distinctBy { SimilarMix.trackKey(it.artist, it.title) }

        val matched =
            coroutineScope {
                candidates
                    .map { c -> async { limit.withPermit { guarded("match ${c.title}") { matcher.song(c.artist, c.title, playedIds) } } } }
                    .awaitAll()
            }
        val out =
            matched
                .filterNotNull()
                .distinctBy { it.videoId }
                .distinctBy { SimilarMix.trackKey(it.artists?.firstOrNull()?.name.orEmpty(), it.title) }
                .take(MAX_TRACKS)
        Logger.i(TAG, "Similar: seeds ${seeds.size}, related ${hits.size}, artists ${chosen.size}, candidates ${candidates.size}, tracks ${out.size}")
        return out
    }

    /** One slow or failing request costs its own result, not the shelf. */
    private suspend fun <T> guarded(
        what: String,
        block: suspend () -> T,
    ): T? =
        try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Failed: $what: ${e.message}")
            null
        }

    private suspend fun readCache(): SimilarMixCache? =
        dataStoreManager
            .getString(CACHE_KEY)
            .first()
            ?.let { runCatching { json.decodeFromString(SimilarMixCache.serializer(), it) }.getOrNull() }

    private suspend fun writeCache(cache: SimilarMixCache) {
        dataStoreManager.putString(CACHE_KEY, json.encodeToString(SimilarMixCache.serializer(), cache))
    }

    private companion object {
        const val TAG = "SimilarTracks"
        const val CACHE_KEY = "similar_mix_cache"
        const val SEEDS = 6
        const val HISTORY_ROWS = 400
        const val RELATED_PER_SEED = 10
        const val MAX_ARTISTS = 16
        const val TOP_PER_ARTIST = 8
        const val SONGS_PER_ARTIST = 2
        const val MAX_TRACKS = 24
        const val PARALLEL = 4
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
