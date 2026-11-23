package com.maxrave.simpmusic.recommendation.engine

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.recommendation.OwnRadioSource
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.recommendation.DeezerArtist
import com.maxrave.simpmusic.recommendation.DeezerCatalog
import com.maxrave.simpmusic.recommendation.DeezerTrack
import com.maxrave.simpmusic.recommendation.DiscoverMix
import com.maxrave.simpmusic.recommendation.ListenBrainzCatalog
import com.maxrave.simpmusic.recommendation.SimilarArtist
import com.maxrave.simpmusic.recommendation.SimilarMix
import com.maxrave.simpmusic.recommendation.YouTubeMatcher
import com.maxrave.simpmusic.viewModel.getFileDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/** A taste group as Home shows it: a mix card named after its leading artists. */
@Serializable
data class TasteMix(
    /** Key of the group's leading artist; [RecommendationEngine.mix] takes it. */
    val key: String,
    val name: String,
    val others: List<String> = emptyList(),
    val picture: String? = null,
    val deezerId: Long = 0,
    /** Every artist key of the group. */
    val artists: List<String> = emptyList(),
)

/** A song or an artist the listener asked never to be recommended. */
@Serializable
data class HiddenItem(
    /** [SimilarMix.trackKey] for a song, [SimilarMix.key] of the name for an artist. */
    val key: String,
    /** What to call it in the list of hidden things. */
    val label: String,
    val artist: Boolean,
    val videoId: String? = null,
)

/** The hidden songs and artists, shaped for the checks the builds make. */
private class Hidden(
    items: List<HiddenItem>,
) {
    val artists: Set<String> = items.filter { it.artist }.map { it.key }.toSet()
    val songs: Set<String> = items.filter { !it.artist }.map { it.key }.toSet()
    val ids: Set<String> = items.mapNotNull { it.videoId }.toSet()

    fun covers(track: Track): Boolean =
        track.videoId in ids ||
            SimilarMix.trackKey(track.artists?.firstOrNull()?.name.orEmpty(), track.title) in songs ||
            track.artists.orEmpty().any { SimilarMix.key(it.name) in artists }
}

@Serializable
private data class DailyLists(
    val day: Long = 0,
    val variant: Int = 0,
    val discover: List<Track> = emptyList(),
    val newArtists: List<Track> = emptyList(),
    val mixes: List<TasteMix> = emptyList(),
    /** Built with no taste to go on (the chart). Never kept: the next build may know the listener. */
    val coldStart: Boolean = false,
)

/**
 * The I/O side of the own recommender (the method is in TasteEngine.kt): reads the play history,
 * grows the artist map from Deezer and ListenBrainz, picks songs, finds them on YouTube Music, and
 * remembers what it offered. It is also the player's radio ([OwnRadioSource]).
 *
 * YouTube is used for two things only: playing a song, and finding the song a catalogue named.
 * Its recommendations, radios and Home are not asked.
 */
class RecommendationEngine(
    private val songRepository: SongRepository,
    searchRepository: SearchRepository,
    private val playlistRepository: PlaylistRepository,
    private val dataStoreManager: DataStoreManager,
) : OwnRadioSource {
    private val matcher = YouTubeMatcher(searchRepository)
    private val json = Json { ignoreUnknownKeys = true }
    private val network = Semaphore(PARALLEL)

    /** One build at a time: the map and the log are plain objects. */
    private val lock = Mutex()
    private var graph: ArtistGraph? = null
    private var served: List<Served>? = null
    private var snapshot: Snapshot? = null
    private val topTracks = HashMap<String, List<DeezerTrack>>()
    private val radios = HashMap<String, RadioSession>()

    private class Snapshot(
        val taste: TasteProfile,
        /** Every song the listener is known to have played or liked, ready to queue. */
        val songs: Map<String, Track>,
        val facts: Map<String, SongFacts>,
        val plays: Map<String, List<Play>>,
        val builtAt: Long,
    )

    @Serializable
    private data class AccountLikes(
        val day: Long = 0,
        val tracks: List<Track> = emptyList(),
    )

    private class RadioSession(
        val emittedKeys: HashSet<String> = HashSet(),
        val emittedIds: HashSet<String> = HashSet(),
        val lastArtists: ArrayList<String> = ArrayList(),
        var batches: Int = 0,
    )

    private class Picked(
        val track: Track,
        val artistKey: String,
        val fresh: Boolean,
    )

    /* ---------------------------------------------------------- hidden */

    private val hiddenLock = Any()
    private var hiddenItems: List<HiddenItem>? = null

    /** Everything the listener asked not to be recommended, newest first. */
    fun hidden(): List<HiddenItem> =
        synchronized(hiddenLock) {
            hiddenItems ?: (read(HIDDEN_FILE, ListSerializer(HiddenItem.serializer())) ?: emptyList()).also { hiddenItems = it }
        }

    /** Never recommend this song again. It is also taken out of today's lists. */
    suspend fun hideSong(track: Track) =
        hide(HiddenItem(SimilarMix.trackKey(track.artists?.firstOrNull()?.name.orEmpty(), track.title), track.title, artist = false, videoId = track.videoId))

    /** The same for a song known only by its id and names (the one playing). */
    suspend fun hideSong(
        videoId: String,
        title: String,
        artist: String,
    ) = hide(HiddenItem(SimilarMix.trackKey(artist, title), title, artist = false, videoId = videoId.takeIf { it.isNotBlank() }))

    /**
     * Never recommend this artist again, alone or as a guest. Artists close to them on the map are
     * rated down too, the way artists the listener skips are: it is the clearest "not this" there is.
     */
    suspend fun hideArtist(name: String) {
        val key = SimilarMix.key(name)
        if (key.isNotEmpty()) hide(HiddenItem(key, name.trim(), artist = true))
    }

    suspend fun showAgain(item: HiddenItem) =
        withContext(Dispatchers.IO) {
            synchronized(hiddenLock) {
                val next = hidden().filterNot { it.key == item.key && it.artist == item.artist }
                hiddenItems = next
                write(HIDDEN_FILE, ListSerializer(HiddenItem.serializer()), next)
            }
        }

    private suspend fun hide(item: HiddenItem) =
        withContext(Dispatchers.IO) {
            synchronized(hiddenLock) {
                val next = listOf(item) + hidden().filterNot { it.key == item.key && it.artist == item.artist }
                hiddenItems = next
                write(HIDDEN_FILE, ListSerializer(HiddenItem.serializer()), next)
                // Today's lists are already built: take it out of them as well.
                read(DAILY_FILE, DailyLists.serializer())?.let { daily ->
                    val now = Hidden(next)
                    write(
                        DAILY_FILE,
                        DailyLists.serializer(),
                        daily.copy(discover = daily.discover.filterNot { now.covers(it) }, newArtists = daily.newArtists.filterNot { now.covers(it) }),
                    )
                }
            }
        }

    /** Whether [track] is hidden, for a list already on screen. */
    fun isHidden(track: Track): Boolean = Hidden(hidden()).covers(track)

    /* ------------------------------------------------------------ Home */

    /** "Like what you play": songs not yet played, by artists near the listener's taste. */
    suspend fun discover(newVariant: Boolean = false): List<Track> = daily(newVariant).discover

    /** "Same vibe, new artists": the same, from artists the listener has never played. */
    suspend fun newArtists(newVariant: Boolean = false): List<Track> = daily(newVariant).newArtists

    /** One mix per taste group, biggest first. */
    suspend fun mixes(): List<TasteMix> = daily(false).mixes

    private suspend fun daily(newVariant: Boolean): DailyLists =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val today = LocalDate.now().toEpochDay()
                val cached = read(DAILY_FILE, DailyLists.serializer())
                if (!newVariant && cached != null && cached.day == today && cached.discover.isNotEmpty() && !cached.coldStart) return@withContext cached
                val variant = if (cached?.day == today) cached.variant + 1 else 0
                val built = buildDaily(today, variant)
                // A day with no network must not pin empty shelves until tomorrow.
                if (built.discover.isNotEmpty() && !built.coldStart) write(DAILY_FILE, DailyLists.serializer(), built)
                built
            }
        }

    private suspend fun buildDaily(
        today: Long,
        variant: Int,
    ): DailyLists {
        val random = Random(today * 1_000 + variant)
        val snap = snapshot(force = true)
        if (snap.taste.isEmpty) {
            // No history yet: what is played most on Deezer today, until there is something to learn from.
            val chart = guarded("chart") { DeezerCatalog.chart() }.orEmpty()
            return DailyLists(day = today, variant = variant, discover = match(chart.take(COLD_START_SONGS), emptySet()).map { it.first }, coldStart = true)
        }
        val g = graph()
        val hide = Hidden(hidden())
        val dislikes = snap.taste.disliked() + hide.artists.associateWith { 1.0 }
        val liked = snap.taste.liked().filter { it.first !in hide.artists }.take(TASTE_ARTISTS)
        liked.forEach { (key, _) -> g.upsert(GraphNode(key, snap.taste.names[key] ?: key)) }
        // The listener's own artists get both sources; what the walk reaches after that, Deezer only.
        expand(g, liked.map { it.first }, today, listenBrainz = true, budget = TASTE_ARTISTS)
        var adjacency = g.adjacency()
        var scores = Walk.score(adjacency, liked.toMap(), dislikes)
        val frontier = scores.entries.sortedByDescending { it.value }.map { it.key }.filter { !g.isExpanded(it, today) }.take(FRONTIER)
        if (expand(g, frontier, today, listenBrainz = false, budget = FRONTIER) > 0) {
            adjacency = g.adjacency()
            scores = Walk.score(adjacency, liked.toMap(), dislikes)
        }
        saveGraph(g)

        val groups = Clusters.groups(liked, adjacency).let { all -> if (all.size <= MAX_GROUPS) all else all.take(MAX_GROUPS - 1) + TasteGroup(all.drop(MAX_GROUPS - 1).flatMap { it.artists }, all.drop(MAX_GROUPS - 1).sumOf { it.weight }) }
        val known = snap.taste.artist.keys
        val scenes = Scenes.of(adjacency)
        val records = Feedback.records(served(), snap.plays, today)
        val cooling = Feedback.cooling(served(), snap.plays.keys, today)

        suspend fun shelf(newOnly: Boolean): List<Track> {
            val slots = Calibration.allocate(SHELF_ARTISTS, groups.map { it.weight })
            val chosen = ArrayList<ScoredArtist>()
            groups.forEachIndexed { index, group ->
                if (slots[index] == 0) return@forEachIndexed
                val restart = group.artists.associateWith { snap.taste.artist[it] ?: 0.0 }
                val groupScores = Walk.score(adjacency, restart, dislikes)
                val homeScene = Scenes.home(scenes, restart)
                val candidates =
                    groupScores
                        .filter { it.key !in chosen.map { c -> c.key } && it.value > 0 }
                        .mapNotNull { (key, value) ->
                            val familiar = key in known
                            if (newOnly && familiar) return@mapNotNull null
                            // An artist already played stays in, behind new ones; the listener's own
                            // favourites are what the shelf is built FROM, so they are left out.
                            if ((snap.taste.artist[key] ?: 0.0) > FAVOURITE || key in hide.artists) return@mapNotNull null
                            val sameScene = homeScene == null || scenes[key] == homeScene
                            ScoredArtist(
                                key,
                                value * (if (familiar) SimilarMix.KNOWN_ARTIST_PENALTY else 1.0) * (if (sameScene) 1.0 else OTHER_SCENE) * Feedback.chance(records[key], random),
                            )
                        }
                chosen += Selection.diverse(candidates, adjacency, slots[index])
            }
            val picked = pickFresh(g, chosen.sortedByDescending { it.score }, SONGS_PER_ARTIST, snap.taste.playedKeys + cooling + hide.songs, random, hide.artists)
            val matched = match(DiscoverMix.interleave(picked.groupBy { it.second }.values.map { it.toList() }).map { it.first }, snap.songs.keys + hide.ids)
                .filterNot { hide.covers(it.first) }
            remember(matched, today)
            return Sequencer.spread(matched, 2) { it.second }.map { it.first }.take(SHELF_SONGS)
        }

        val discover = shelf(newOnly = false)
        val newArtists = shelf(newOnly = true).filter { t -> discover.none { it.videoId == t.videoId } }
        val mixes =
            groups.take(MAX_MIXES).mapNotNull { group ->
                // The card shows one artist and the mix opens with them, so it has to be one the
                // catalogue knows and the map can walk out from. A group with no such artist would
                // make a mix of one or two songs: it gets no card.
                val lead =
                    group.artists.firstOrNull { it !in hide.artists && g.node(it)?.deezerId != null && adjacency[it].orEmpty().size >= MIN_MIX_LINKS }
                        ?: return@mapNotNull null
                val node = g.node(lead)
                TasteMix(
                    key = lead,
                    name = snap.taste.names[lead] ?: node?.name ?: lead,
                    others = group.artists.filter { it != lead }.take(3).map { snap.taste.names[it] ?: it },
                    picture = node?.picture,
                    deezerId = node?.deezerId ?: 0,
                    artists = group.artists,
                )
            }
        Logger.i(TAG, "Daily: artists ${liked.size}, map ${g.size}, groups ${groups.size}, discover ${discover.size}, new artists ${newArtists.size}")
        return DailyLists(today, variant, discover, newArtists, mixes)
    }

    /**
     * The songs of a taste group's mix. It opens with the artist on its card (up to three of their
     * songs), then new songs from around the group woven with ones already loved.
     */
    suspend fun mix(mix: TasteMix): List<Track> =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val snap = snapshot()
                val restart = mix.artists.ifEmpty { listOf(mix.key) }.associateWith { (snap.taste.artist[it] ?: 0.0).coerceAtLeast(0.05) } + (mix.key to 1.0)
                val built = build(restart, snap, RadioSession(), Random(LocalDate.now().toEpochDay() + mix.key.hashCode()), freshArtists = 10, knownSongs = 12, allowHome = true)
                val (theirs, others) = built.partition { track -> track.artists.orEmpty().any { SimilarMix.key(it.name) == mix.key } }
                val opening =
                    theirs.ifEmpty {
                        // Nothing of theirs made the cut (all played lately): their best-known songs then.
                        match(topTracks(graph(), mix.key).take(MIX_OPENING), emptySet()).map { it.first }
                    }
                (opening.take(MIX_OPENING) + others + opening.drop(MIX_OPENING)).distinctBy { it.videoId }
            }
        }

    /* ----------------------------------------------------------- radio */

    override suspend fun radio(
        seedVideoId: String,
        more: Boolean,
    ): List<Track>? =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val seed = seedSong(seedVideoId) ?: return@withContext null
                val seedArtists = seed.artistName.orEmpty().map { SimilarMix.key(it) to it }.filter { it.first.isNotEmpty() }
                if (seedArtists.isEmpty()) return@withContext null
                val snap = snapshot()
                val session = if (more) radios.getOrPut(seedVideoId) { RadioSession() } else RadioSession().also { radios[seedVideoId] = it }
                if (radios.size > MAX_RADIOS) radios.keys.filter { it != seedVideoId }.take(radios.size - MAX_RADIOS).forEach { radios.remove(it) }
                session.emittedIds += seedVideoId
                session.emittedKeys += SimilarMix.trackKey(seed.artistName?.firstOrNull().orEmpty(), seed.title)

                val g = graph()
                seedArtists.forEach { (key, name) -> g.upsert(GraphNode(key, name)) }
                val today = LocalDate.now().toEpochDay()
                // The seed's own artist gets both sources at once: its neighbourhood is the radio.
                expand(g, seedArtists.take(1).map { it.first }, today, listenBrainz = true, budget = 1)
                expand(g, seedArtists.drop(1).map { it.first }, today, listenBrainz = false, budget = 3)

                // Where the radio starts from: mostly the song's artist, a little of the listener's
                // taste where it is CLOSE to that artist (so a radio of a rap song is not pulled
                // towards the listener's pop), and, in later batches, where the radio has got to.
                val home = HashMap<String, Double>()
                seedArtists.forEachIndexed { index, (key, _) -> home[key] = if (index == 0) 1.0 else 0.4 }
                val near = Walk.personalizedPageRank(g.adjacency(), home)
                val restart = HashMap<String, Double>()
                val homeTotal = home.values.sum()
                home.forEach { (key, value) -> restart[key] = (if (more) SEED_SHARE_LATER else SEED_SHARE) * value / homeTotal }
                val taste = snap.taste.liked().mapNotNull { (key, value) -> near[key]?.let { key to value * it } }.filter { it.second > 0 }
                val tasteTotal = taste.sumOf { it.second }
                if (tasteTotal > 0) taste.forEach { (key, value) -> restart[key] = (restart[key] ?: 0.0) + TASTE_SHARE * value / tasteTotal }
                if (more && session.lastArtists.isNotEmpty()) {
                    val drift = session.lastArtists.takeLast(6)
                    drift.forEach { key -> restart[key] = (restart[key] ?: 0.0) + DRIFT_SHARE / drift.size }
                }
                build(restart, snap, session, Random(System.nanoTime()), freshArtists = RADIO_ARTISTS, knownSongs = RADIO_KNOWN, allowHome = true)
                    .takeIf { it.isNotEmpty() }
            }
        }

    private suspend fun seedSong(videoId: String): SongEntity? {
        songRepository.getSongById(videoId).first()?.let { return it }
        // A song tapped a moment ago may not be stored yet.
        kotlinx.coroutines.delay(1_500)
        return songRepository.getSongById(videoId).first()
    }

    /**
     * Songs around [restart]: new ones by the artists the walk reaches, woven with songs from the
     * history by artists in the same neighbourhood, nearest first.
     */
    private suspend fun build(
        restart: Map<String, Double>,
        snap: Snapshot,
        session: RadioSession,
        random: Random,
        freshArtists: Int,
        knownSongs: Int,
        allowHome: Boolean,
    ): List<Track> {
        val today = LocalDate.now().toEpochDay()
        val g = graph()
        restart.keys.forEach { key -> if (g.node(key) == null) g.upsert(GraphNode(key, snap.taste.names[key] ?: key)) }
        expand(g, restart.entries.sortedByDescending { it.value }.map { it.key }, today, listenBrainz = false, budget = 6)
        var adjacency = g.adjacency()
        val hide = Hidden(hidden())
        val dislikes = snap.taste.disliked() + hide.artists.associateWith { 1.0 }
        // A radio and a mix stay nearer their start than Home's shelves do.
        var scores = Walk.score(adjacency, restart, dislikes, follow = NEAR_FOLLOW)
        val frontier = scores.entries.sortedByDescending { it.value }.map { it.key }.filter { !g.isExpanded(it, today) }.take(RADIO_FRONTIER)
        if (expand(g, frontier, today, listenBrainz = false, budget = RADIO_FRONTIER) > 0) {
            adjacency = g.adjacency()
            scores = Walk.score(adjacency, restart, dislikes, follow = NEAR_FOLLOW)
        }
        saveGraph(g)

        val records = Feedback.records(served(), snap.plays, today)
        val cooling = Feedback.cooling(served(), snap.plays.keys, today)
        // Artists whose songs this radio played and the listener skipped in the last hours.
        val skippedNow =
            session.emittedIds
                .flatMap { id -> snap.plays[id].orEmpty().filter { it.ageDays < 0.25 && Taste.signal(it.listenedSec, it.durationSec) < 0 }.map { id } }
                .mapNotNull { snap.facts[it]?.artists?.firstOrNull()?.let { name -> SimilarMix.key(name) } }
                .toSet()
        val recentArtists = session.lastArtists.takeLast(8).toSet()
        // The scene the radio starts in (see Scenes): a Persian song's radio stays Persian. An
        // artist from another scene is not banned, only far behind.
        val scenes = Scenes.of(adjacency)
        val homeScene = Scenes.home(scenes, restart)

        fun sceneFactor(key: String) = if (homeScene == null || scenes[key] == homeScene) 1.0 else OTHER_SCENE
        val candidates =
            scores
                .filter { it.value > 0 && (allowHome || it.key !in restart) && it.key !in hide.artists }
                .map { (key, value) ->
                    var s = value * sceneFactor(key) * Feedback.chance(records[key], random)
                    if (key in skippedNow) s *= 0.15
                    if (key in recentArtists) s *= 0.5
                    ScoredArtist(key, s)
                }
        val chosen = Selection.diverse(candidates, adjacency, freshArtists)
        val exclude = snap.taste.playedKeys + cooling + session.emittedKeys + hide.songs
        val fresh =
            match(
                pickFresh(g, chosen, 2, exclude, random, hide.artists).let { picks -> DiscoverMix.interleave(picks.groupBy { it.second }.values.map { it.toList() }) }.map { it.first },
                snap.songs.keys + session.emittedIds + hide.ids,
            ).filterNot { hide.covers(it.first) }

        // Songs the listener already loves, by artists the walk rates: weighted by both.
        val rated = scores.entries.sortedByDescending { it.value }.take(40).associate { it.key to it.value }
        val top = rated.values.maxOrNull() ?: 0.0
        val familiar =
            if (top <= 0) {
                emptyList()
            } else {
                val pool =
                    snap.taste.tracks.values
                        .filter { it.score > 0.3 && it.lastAgeDays > 0.5 && it.skips <= it.plays / 2 && it.videoId !in session.emittedIds }
                        .mapNotNull { t ->
                            val song = snap.songs[t.videoId] ?: return@mapNotNull null
                            val key = SimilarMix.key(snap.facts[t.videoId]?.artists?.firstOrNull().orEmpty())
                            val closeness = (rated[key] ?: return@mapNotNull null) / top
                            if (key in skippedNow || hide.covers(song)) return@mapNotNull null
                            Triple(song, key, closeness * sceneFactor(key) * kotlin.math.sqrt(t.score))
                        }
                DiscoverMix.weightedSample(pool, knownSongs, random) { it.third }.sortedByDescending { it.third }
            }

        remember(fresh, today)
        val picked = Sequencer.weave(fresh.map { Picked(it.first, it.second, true) }, familiar.map { Picked(it.first, it.second, false) })
        val ordered = Sequencer.spread(picked.distinctBy { it.track.videoId }, 3) { it.artistKey }
        ordered.forEach {
            session.emittedIds += it.track.videoId
            session.emittedKeys += SimilarMix.trackKey(it.track.artists?.firstOrNull()?.name.orEmpty(), it.track.title)
            session.lastArtists += it.artistKey
        }
        session.batches++
        Logger.i(TAG, "Radio batch ${session.batches}: ${fresh.size} new + ${familiar.size} known from ${chosen.size} artists, map ${g.size}")
        // Named in the log, so a report of "this radio played something odd" can be checked.
        Logger.i(TAG, "Radio songs: " + ordered.joinToString(" | ") { (if (it.fresh) "" else "*") + it.track.title + " - " + it.track.artists?.firstOrNull()?.name.orEmpty() }.take(1500))
        return ordered.map { it.track }
    }

    /* ------------------------------------------------------- song picking */

    /** Up to [perArtist] of each artist's top songs that are not in [exclude], as (song, artist key). */
    private suspend fun pickFresh(
        g: ArtistGraph,
        artists: List<ScoredArtist>,
        perArtist: Int,
        exclude: Set<String>,
        random: Random,
        hiddenArtists: Set<String> = emptySet(),
    ): List<Pair<DeezerTrack, String>> =
        coroutineScope {
            artists
                .map { artist ->
                    async {
                        val top =
                            topTracks(g, artist.key)
                                .distinctBy { SimilarMix.trackKey(it.artist, it.title) }
                                .filter { SimilarMix.trackKey(it.artist, it.title) !in exclude && SimilarMix.key(it.artist) !in hiddenArtists }
                        DiscoverMix.pickSongs(top, perArtist, random).map { it to artist.key }
                    }
                }.awaitAll()
                .flatten()
        }

    private suspend fun topTracks(
        g: ArtistGraph,
        key: String,
    ): List<DeezerTrack> {
        synchronized(topTracks) { topTracks[key] }?.let { return it }
        val node = g.node(key) ?: return emptyList()
        val tracks =
            network.withPermit {
                guarded("top of ${node.name}") {
                    val id = node.deezerId ?: DeezerCatalog.artistId(node.name) ?: return@guarded emptyList()
                    DeezerCatalog.topTracks(id, TOP_PER_ARTIST)
                }
            } ?: return emptyList()
        synchronized(topTracks) { topTracks[key] = tracks }
        return tracks
    }

    /** Finds each song on YouTube Music; (track, key of the artist it was picked for), in order. */
    private suspend fun match(
        songs: List<DeezerTrack>,
        excludeIds: Set<String>,
    ): List<Pair<Track, String>> =
        coroutineScope {
            songs
                .map { song ->
                    async { network.withPermit { guarded("match ${song.title}") { matcher.song(song.artist, song.title, excludeIds) } }?.let { it to SimilarMix.key(song.artist) } }
                }.awaitAll()
                .filterNotNull()
                .distinctBy { it.first.videoId }
        }

    /* ------------------------------------------------------------ the map */

    /**
     * Fetches the links of up to [budget] of [keys] that lack them. Returns how many were fetched.
     * Requests run side by side; the map itself is only touched here, one result at a time.
     */
    private suspend fun expand(
        g: ArtistGraph,
        keys: List<String>,
        today: Long,
        listenBrainz: Boolean,
        budget: Int,
    ): Int {
        val wanted = keys.distinct().filter { g.node(it) != null && (!g.isExpanded(it, today) || (listenBrainz && !g.hasListenBrainz(it, today))) }.take(budget)
        if (wanted.isEmpty()) return 0
        // Deezer, side by side. A null answer is a failed request and is asked again next time; an
        // artist Deezer does not list comes back as (null, nothing) and counts as asked.
        val deezer: Map<String, Pair<DeezerArtist?, List<DeezerArtist>>?> =
            coroutineScope {
                wanted
                    .filter { !g.isExpanded(it, today) }
                    .map { key ->
                        val node = g.node(key)!!
                        async {
                            key to
                                network.withPermit {
                                    guarded<Pair<DeezerArtist?, List<DeezerArtist>>>("related of ${node.name}") {
                                        val artist = node.deezerId?.let { DeezerArtist(it, node.name, node.picture) } ?: DeezerCatalog.artist(node.name)
                                        if (artist == null) null to emptyList() else artist to DeezerCatalog.relatedAll(artist.id)
                                    }
                                }
                        }
                    }.awaitAll()
                    .toMap()
            }
        // ListenBrainz, one after another: MusicBrainz, which turns a name into the id it needs,
        // allows one request a second. Capped per build; the rest are picked up by later builds.
        val similar = HashMap<String, Pair<String?, List<SimilarArtist>>?>()
        if (listenBrainz) {
            for (key in wanted.filter { !g.hasListenBrainz(it, today) }.take(LISTENBRAINZ_PER_BUILD)) {
                val node = g.node(key) ?: continue
                similar[key] =
                    guarded<Pair<String?, List<SimilarArtist>>>("similar to ${node.name}") {
                        val mbid = node.mbid ?: ListenBrainzCatalog.mbid(node.name)
                        if (mbid == null) null to emptyList() else mbid to ListenBrainzCatalog.similar(mbid).take(ArtistGraph.MAX_EDGES)
                    }
            }
        }
        // First the spellings: an artist the history writes one way and Deezer another is one artist.
        for (key in wanted) deezer[key]?.first?.let { g.alias(SimilarMix.key(it.name), key) }
        var fetched = 0
        for (key in wanted) {
            val node = g.node(key) ?: continue
            val d = deezer[key]
            val l = similar[key]
            if (d == null && l == null) continue
            d?.first?.let { g.upsert(node.copy(deezerId = it.id, picture = it.picture)) }
            l?.first?.let { g.upsert(node.copy(mbid = it)) }
            val deezerLinks =
                d?.second?.mapIndexedNotNull { index, related ->
                    val k = SimilarMix.key(related.name).takeIf { it.isNotEmpty() }?.let { g.canonical(it) } ?: return@mapIndexedNotNull null
                    g.upsert(GraphNode(k, related.name, deezerId = related.id, picture = related.picture))
                    k to ArtistGraph.deezerStrength(index)
                }
            val best = l?.second?.maxOfOrNull { it.score } ?: 0.0
            val similarLinks =
                l?.second?.mapNotNull { other ->
                    val k = SimilarMix.key(other.name).takeIf { it.isNotEmpty() }?.let { g.canonical(it) } ?: return@mapNotNull null
                    g.upsert(GraphNode(k, other.name, mbid = other.mbid))
                    k to ArtistGraph.listenBrainzStrength(other.score, best)
                }
            g.setLinks(key, deezer = deezerLinks, listenBrainz = similarLinks, today = today)
            fetched++
        }
        return fetched
    }

    private fun graph(): ArtistGraph = graph ?: ArtistGraph(read(GRAPH_FILE, ArtistGraphData.serializer()) ?: ArtistGraphData()).also { graph = it }

    private fun saveGraph(g: ArtistGraph) {
        g.prune()
        write(GRAPH_FILE, ArtistGraphData.serializer(), g.snapshot())
    }

    /* ------------------------------------------------------- history, log */

    private suspend fun snapshot(force: Boolean = false): Snapshot {
        snapshot?.takeIf { !force && System.currentTimeMillis() - it.builtAt < SNAPSHOT_MS }?.let { return it }
        val history = songRepository.getPlayHistory(HISTORY_DAYS)
        val liked = songRepository.getLikedSongs().first()
        val ids = (history.map { it.videoId } + liked.map { it.videoId }).distinct()
        val local = HashMap<String, SongEntity>()
        // SQLite takes at most 999 values in one IN list.
        for (chunk in ids.chunked(400)) songRepository.getSongsByListVideoId(chunk).first().forEach { local[it.videoId] = it }
        liked.forEach { local[it.videoId] = it }
        val songs = HashMap<String, Track>()
        val facts = HashMap<String, SongFacts>()
        for (song in local.values) {
            songs[song.videoId] = song.toTrack()
            facts[song.videoId] = SongFacts(song.videoId, song.title, song.artistName.orEmpty(), song.liked)
        }
        // The account's liked songs are the listener's own statement of taste, and the only one a
        // device with little history of its own has (a TV signed in an hour ago). Not YouTube's
        // suggestions: the list of what this person pressed Like on.
        for (track in accountLikes()) {
            if (track.videoId in facts) continue
            songs[track.videoId] = track
            facts[track.videoId] = SongFacts(track.videoId, track.title, track.artists.orEmpty().map { it.name }, liked = true)
        }
        var plays = history.map { Play(it.videoId, it.ageDays, it.listenedSecond, it.durationSecond) }
        // The account's listening history stands in while this device has little of its own (a
        // TV signed in an hour ago knows nothing; the account knows months). Each row counts as a
        // song heard through, the list's order as its age. A device with a real history of its own
        // has these plays already and with better detail, so it is left alone there.
        if (plays.size < THIN_HISTORY) {
            val account = accountHistory()
            account.forEachIndexed { index, track ->
                if (track.videoId !in facts) {
                    songs[track.videoId] = track
                    facts[track.videoId] = SongFacts(track.videoId, track.title, track.artists.orEmpty().map { it.name })
                }
            }
            plays = plays + account.mapIndexed { index, track -> Play(track.videoId, index * ACCOUNT_HISTORY_DAYS / account.size.coerceAtLeast(1), 200, 200) }
        }
        return Snapshot(Taste.build(plays, facts), songs, facts, plays.groupBy { it.videoId }, System.currentTimeMillis()).also { snapshot = it }
    }

    /** The signed-in account's liked songs, read once a day; nothing when signed out. */
    private suspend fun accountLikes(): List<Track> =
        accountList(LIKES_FILE, "likes") { playlistRepository.getFullPlaylistData("LM", "").lastOrNull()?.data?.tracks }

    /** The signed-in account's listening history, most recent first, read once a day. */
    private suspend fun accountHistory(): List<Track> = accountList(HISTORY_FILE, "history") { songRepository.getAccountHistory() }

    private suspend fun accountList(
        file: String,
        what: String,
        fetch: suspend () -> List<Track>?,
    ): List<Track> {
        if (dataStoreManager.cookie.first().isBlank()) return emptyList()
        val today = LocalDate.now().toEpochDay()
        val cached = read(file, AccountLikes.serializer())
        if (cached != null && cached.day == today) return cached.tracks
        val fetched =
            try {
                withTimeoutOrNull(LIKES_TIMEOUT_MS) { fetch() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Account $what failed: ${e.message}")
                null
            }
        Logger.i(TAG, "Account $what: ${fetched?.size ?: "no answer"}")
        // An empty answer is not kept: the account may simply not have been read properly.
        if (fetched.isNullOrEmpty()) return cached?.tracks.orEmpty()
        write(file, AccountLikes.serializer(), AccountLikes(today, fetched))
        return fetched
    }

    private fun served(): List<Served> = served ?: (read(SERVED_FILE, ListSerializer(Served.serializer())) ?: emptyList()).also { served = it }

    private fun remember(
        offered: List<Pair<Track, String>>,
        today: Long,
    ) {
        if (offered.isEmpty()) return
        val added = offered.map { (track, artistKey) -> Served(track.videoId, artistKey, today, SimilarMix.trackKey(track.artists?.firstOrNull()?.name.orEmpty(), track.title)) }
        val log = Feedback.append(served(), added)
        served = log
        write(SERVED_FILE, ListSerializer(Served.serializer()), log)
    }

    /* -------------------------------------------------------------- files */

    private val dir: File by lazy {
        File(getFileDir().ifBlank { System.getProperty("user.home").orEmpty() + "/.simpmusic" }, "recommender").also { it.mkdirs() }
    }

    private fun <T> read(
        name: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T? = runCatching { File(dir, name).takeIf { it.exists() }?.readText()?.let { json.decodeFromString(serializer, it) } }.getOrNull()

    private fun <T> write(
        name: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
    ) {
        runCatching {
            val temp = File(dir, "$name.tmp")
            temp.writeText(json.encodeToString(serializer, value))
            if (!temp.renameTo(File(dir, name))) {
                File(dir, name).delete()
                temp.renameTo(File(dir, name))
            }
        }.onFailure { Logger.w(TAG, "Could not save $name: ${it.message}") }
    }

    /** One slow or failing request costs its own result, not the whole list. */
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
        const val TAG = "Recommender"
        const val GRAPH_FILE = "artist-map.json"
        const val SERVED_FILE = "offered.json"
        const val DAILY_FILE = "daily.json"
        const val HIDDEN_FILE = "hidden.json"
        const val LIKES_FILE = "account-likes.json"
        const val HISTORY_FILE = "account-history.json"
        const val THIN_HISTORY = 150
        const val ACCOUNT_HISTORY_DAYS = 45.0
        const val LIKES_TIMEOUT_MS = 30_000L
        const val HISTORY_DAYS = 730
        const val SNAPSHOT_MS = 120_000L
        const val TASTE_ARTISTS = 30
        const val FRONTIER = 24
        const val MAX_GROUPS = 5
        const val MAX_MIXES = 6
        const val SHELF_ARTISTS = 16
        const val SONGS_PER_ARTIST = 2
        const val SHELF_SONGS = 24
        const val COLD_START_SONGS = 20
        const val TOP_PER_ARTIST = 12
        const val FAVOURITE = 0.35
        const val RADIO_ARTISTS = 9
        const val RADIO_KNOWN = 7
        const val RADIO_FRONTIER = 8
        const val SEED_SHARE = 0.7
        const val SEED_SHARE_LATER = 0.45
        const val TASTE_SHARE = 0.3
        const val DRIFT_SHARE = 0.25
        const val MAX_RADIOS = 12
        const val LISTENBRAINZ_PER_BUILD = 10
        const val OTHER_SCENE = 0.15
        const val NEAR_FOLLOW = 0.55
        const val MIN_MIX_LINKS = 3
        const val MIX_OPENING = 3
        const val PARALLEL = 4
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
