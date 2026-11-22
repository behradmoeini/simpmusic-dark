package com.maxrave.simpmusic.recommendation

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.AnalyticsRepository
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

/** "<Artist> Mix": the artist's Deezer station, resolved to songs only when it is played. */
@Serializable
data class MixCard(
    val artist: DeezerArtist,
    /** A few artists the mix also plays, for the line under the card. */
    val alsoPlays: List<String> = emptyList(),
)

@Serializable
data class MoreLikeShelf(
    val artist: DeezerArtist,
    val tracks: List<DeezerTrack>,
)

/** An album the listener keeps playing songs from. [browseId] opens it. */
@Serializable
data class LikedAlbum(
    val browseId: String,
    val title: String,
    val artist: String,
    val thumbnail: String? = null,
)

@Serializable
data class HomeShelves(
    val day: Long = 0,
    val mixes: List<MixCard> = emptyList(),
    val moreLike: MoreLikeShelf? = null,
    val newReleases: List<DeezerAlbum> = emptyList(),
    val albums: List<LikedAlbum> = emptyList(),
)

/**
 * The Home shelves Spotify builds from the listener's history, rebuilt here from the local play
 * history and Deezer's catalogue: "Your top mixes", "More like <artist>", "New releases for you"
 * and "Albums featuring songs you like". Built once a day and cached; nothing here searches
 * YouTube, cards are matched when they are tapped.
 */
class HomeShelvesLoader(
    private val analyticsRepository: AnalyticsRepository,
    private val songRepository: SongRepository,
    private val dataStoreManager: DataStoreManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): HomeShelves {
        val today = LocalDate.now().toEpochDay()
        val cached =
            dataStoreManager
                .getString(CACHE_KEY)
                .first()
                ?.let { runCatching { json.decodeFromString(HomeShelves.serializer(), it) }.getOrNull() }
        if (cached != null && cached.day == today) return cached
        val built = build(today)
        // A day with no network must not pin an empty Home until tomorrow.
        if (built.mixes.isNotEmpty()) dataStoreManager.putString(CACHE_KEY, json.encodeToString(HomeShelves.serializer(), built))
        return built
    }

    private suspend fun build(today: Long): HomeShelves {
        val allTime = analyticsRepository.queryTopPlayedSongsLastXDays(3650).first()
        if (allTime.isEmpty()) return HomeShelves(day = today)
        val recent = analyticsRepository.queryTopPlayedSongsLastXDays(30).first()
        val base = recent.ifEmpty { allTime }
        val ids = (base.take(HISTORY_ROWS).map { it.videoId } + allTime.take(HISTORY_ROWS).map { it.videoId }).distinct()
        val songs = songRepository.getSongsByListVideoId(ids).first().associateBy { it.videoId }

        val albums =
            HomeShelvesLogic.likedAlbums(
                allTime.mapNotNull { row ->
                    val song = songs[row.videoId] ?: return@mapNotNull null
                    val id = song.albumId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    LikedAlbum(id, song.albumName.orEmpty(), song.artistName?.firstOrNull().orEmpty(), song.thumbnails) to row.playCount
                },
                MAX_ALBUMS,
            )

        val topArtists =
            SimilarMix
                .artistPlays(base.mapNotNull { row -> songs[row.videoId]?.artistName?.firstOrNull()?.let { it to row.playCount } })
                .take(TOP_ARTISTS)
        val limit = Semaphore(PARALLEL)
        val artists =
            coroutineScope {
                topArtists.map { a -> async { limit.withPermit { guarded("artist ${a.channelId}") { DeezerCatalog.artist(a.channelId) } } } }.awaitAll()
            }.filterNotNull().distinctBy { it.id }
        if (artists.isEmpty()) return HomeShelves(day = today, albums = albums)

        val random = Random(today)
        val moreLikeArtist = artists.take(MORE_LIKE_POOL).random(random)
        return coroutineScope {
            val mixes =
                artists.take(MAX_MIXES).map { artist ->
                    async {
                        limit.withPermit {
                            val related = guarded("related ${artist.name}") { DeezerCatalog.relatedArtists(artist.id, 3) }.orEmpty()
                            MixCard(artist, related.map { it.name })
                        }
                    }
                }
            val releases =
                artists.map { artist ->
                    async { limit.withPermit { guarded("albums ${artist.name}") { DeezerCatalog.albums(artist.id, artist.name) }.orEmpty() } }
                }
            val moreLike =
                async {
                    val related = limit.withPermit { guarded("related ${moreLikeArtist.name}") { DeezerCatalog.relatedArtists(moreLikeArtist.id, MORE_LIKE_CARDS) } }.orEmpty()
                    related
                        .map { r -> async { limit.withPermit { guarded("top ${r.name}") { DeezerCatalog.topTracks(r.id, 1) }?.firstOrNull() } } }
                        .awaitAll()
                        .filterNotNull()
                }
            val shelves =
                HomeShelves(
                    day = today,
                    mixes = mixes.awaitAll(),
                    moreLike = moreLike.await().takeIf { it.size >= 3 }?.let { MoreLikeShelf(moreLikeArtist, it) },
                    newReleases = HomeShelvesLogic.newReleases(releases.awaitAll().flatten(), LocalDate.ofEpochDay(today), MAX_RELEASES),
                    albums = albums,
                )
            Logger.i(TAG, "Shelves: mixes ${shelves.mixes.size}, more like ${shelves.moreLike?.tracks?.size ?: 0}, releases ${shelves.newReleases.size}, albums ${albums.size}")
            shelves
        }
    }

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

    private companion object {
        const val TAG = "HomeShelves"
        const val CACHE_KEY = "home_shelves_cache"
        const val HISTORY_ROWS = 400
        const val TOP_ARTISTS = 8
        const val MAX_MIXES = 6
        const val MORE_LIKE_POOL = 5
        const val MORE_LIKE_CARDS = 10
        const val MAX_RELEASES = 12
        const val MAX_ALBUMS = 12
        const val PARALLEL = 4
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}

/** The pure parts of [HomeShelvesLoader]. */
object HomeShelvesLogic {
    /** Releases older than this are not "new". */
    const val NEW_RELEASE_DAYS = 120L

    /** Albums by total plays of their songs, most played first. YouTube Music album ids start with MPRE. */
    fun likedAlbums(
        plays: List<Pair<LikedAlbum, Int>>,
        max: Int,
    ): List<LikedAlbum> =
        plays
            .filter { it.first.browseId.startsWith("MPRE") && it.first.title.isNotBlank() }
            .groupBy { it.first.browseId }
            .map { (_, rows) -> rows.first().first to rows.sumOf { it.second } }
            .sortedByDescending { it.second }
            .take(max)
            .map { it.first }

    /** Releases of the last [NEW_RELEASE_DAYS] days, newest first, one card per (artist, title). */
    fun newReleases(
        albums: List<DeezerAlbum>,
        today: LocalDate,
        max: Int,
    ): List<DeezerAlbum> =
        albums
            .mapNotNull { album ->
                val date = runCatching { LocalDate.parse(album.releaseDate) }.getOrNull() ?: return@mapNotNull null
                if (date.isAfter(today) || date.isBefore(today.minusDays(NEW_RELEASE_DAYS))) null else album to date
            }.sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { SimilarMix.trackKey(it.artist, it.title) }
            .take(max)
}
