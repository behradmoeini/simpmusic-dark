package com.maxrave.simpmusic.recommendation.engine

import com.maxrave.simpmusic.recommendation.SimilarMix
import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * The fork's own recommender. Nothing in it asks YouTube what to play.
 *
 * It is the usual shape of a production recommender, sized for one listener on one device:
 *
 * 1. TASTE (this file, [Taste]). Every play is a signal, not a count: a song heard to the end is a
 *    yes, one skipped in its first third is a no, and both fade with age. Two profiles are kept, a
 *    short one (what the listener is into this fortnight) and a long one (what they have always
 *    liked), so a week of one album does not erase years of taste.
 * 2. THE MAP ([ArtistGraph]). Artists and "listeners of A also play B" links from two independent
 *    sources: Deezer's related artists and ListenBrainz's similarity, which is computed from what
 *    people play in one sitting. A link both sources agree on is stronger than either alone.
 * 3. THE WALK ([Walk.personalizedPageRank]). Candidates are not "the neighbours of the top four
 *    artists". A random walk starts from the whole taste (or from the song a radio is for),
 *    follows links several steps out, and keeps returning home. An artist reached along many paths
 *    from many things the listener likes scores high even when no single favourite names it.
 *    The same walk started from what the listener SKIPS is subtracted.
 * 4. TASTE GROUPS ([Clusters]). The listener's own artists fall into groups on the map (say Persian
 *    pop, rap, film scores). Each group gets its share of every shelf ([Calibration]), so a big
 *    group cannot crowd a small one out.
 * 5. LEARNING ([Feedback]). What was recommended is remembered. A recommendation played through
 *    counts for its artist, one skipped counts against, and each artist's chance is drawn from that
 *    record (Thompson sampling), so the engine tries things, and stops insisting. A song offered
 *    and ignored rests for two weeks instead of being offered again.
 * 6. ORDER ([Sequencer]). The same artist is kept apart, and a radio starts near its song and
 *    drifts outwards.
 *
 * Everything in this file is pure and tested; RecommendationEngine does the I/O.
 */

/** One play: how long ago, and how much of the song was heard. */
data class Play(
    val videoId: String,
    val ageDays: Double,
    val listenedSec: Long,
    val durationSec: Long,
)

/** What is known about a song in the history. */
data class SongFacts(
    val videoId: String,
    val title: String,
    val artists: List<String>,
    val liked: Boolean = false,
)

data class TrackTaste(
    val videoId: String,
    val score: Double,
    val plays: Int,
    val skips: Int,
    val lastAgeDays: Double,
)

class TasteProfile(
    /** Affinity per artist key, in -1..1: the blend of the short and the long profile. */
    val artist: Map<String, Double>,
    val shortTerm: Map<String, Double>,
    val longTerm: Map<String, Double>,
    /** Artist key to the name as it is written in the history. */
    val names: Map<String, String>,
    val tracks: Map<String, TrackTaste>,
    /** [SimilarMix.trackKey] of every song in the history. */
    val playedKeys: Set<String>,
) {
    val isEmpty: Boolean get() = artist.none { it.value > 0 }

    /** Artists the listener likes, best first. */
    fun liked(): List<Pair<String, Double>> = artist.filter { it.value > 0 }.toList().sortedByDescending { it.second }

    /** Artists the listener mostly skips. */
    fun disliked(): Map<String, Double> = artist.filter { it.value < 0 }.mapValues { -it.value }
}

object Taste {
    const val SHORT_HALF_LIFE_DAYS = 14.0
    const val LONG_HALF_LIFE_DAYS = 180.0
    const val SHORT_SHARE = 0.55
    const val LIKE_BONUS = 2.0

    /**
     * What one play says: 1 for a song heard (nearly) to the end, the heard share in between, and
     * a negative for a skip. A play of a few seconds is a mis-tap or a queue passing by and says
     * little either way.
     */
    fun signal(
        listenedSec: Long,
        durationSec: Long,
    ): Double {
        if (durationSec <= 0) return if (listenedSec >= 60) 1.0 else if (listenedSec < 15) -0.3 else 0.3
        val share = (listenedSec.toDouble() / durationSec).coerceIn(0.0, 1.0)
        return when {
            listenedSec < 5 -> -0.15
            share < 0.3 -> -0.6
            share >= 0.8 -> 1.0
            else -> share
        }
    }

    fun decay(
        ageDays: Double,
        halfLifeDays: Double,
    ): Double = 0.5.pow(ageDays.coerceAtLeast(0.0) / halfLifeDays)

    fun build(
        plays: List<Play>,
        songs: Map<String, SongFacts>,
    ): TasteProfile {
        val short = HashMap<String, Double>()
        val long = HashMap<String, Double>()
        val names = HashMap<String, String>()
        val trackScore = HashMap<String, Double>()
        val trackPlays = HashMap<String, Int>()
        val trackSkips = HashMap<String, Int>()
        val trackLast = HashMap<String, Double>()
        val playedKeys = HashSet<String>()

        fun credit(
            song: SongFacts,
            shortValue: Double,
            longValue: Double,
        ) {
            song.artists.forEachIndexed { index, name ->
                val key = SimilarMix.key(name)
                if (key.isEmpty()) return@forEachIndexed
                names.getOrPut(key) { name.trim() }
                // The first credited artist is whose song it is; a guest gets half.
                val share = if (index == 0) 1.0 else 0.5
                short[key] = (short[key] ?: 0.0) + shortValue * share
                long[key] = (long[key] ?: 0.0) + longValue * share
            }
        }

        for (play in plays) {
            val song = songs[play.videoId] ?: continue
            val s = signal(play.listenedSec, play.durationSec)
            credit(song, s * decay(play.ageDays, SHORT_HALF_LIFE_DAYS), s * decay(play.ageDays, LONG_HALF_LIFE_DAYS))
            trackScore[play.videoId] = (trackScore[play.videoId] ?: 0.0) + s * decay(play.ageDays, LONG_HALF_LIFE_DAYS)
            trackPlays[play.videoId] = (trackPlays[play.videoId] ?: 0) + 1
            if (s < 0) trackSkips[play.videoId] = (trackSkips[play.videoId] ?: 0) + 1
            trackLast[play.videoId] = minOf(trackLast[play.videoId] ?: Double.MAX_VALUE, play.ageDays)
        }
        for (song in songs.values) {
            playedKeys += SimilarMix.trackKey(song.artists.firstOrNull().orEmpty(), song.title)
            if (song.liked) {
                // A like does not fade: it is the one thing the listener said out loud.
                credit(song, 0.0, LIKE_BONUS)
                trackScore[song.videoId] = (trackScore[song.videoId] ?: 0.0) + LIKE_BONUS
            }
        }

        val shortNorm = normalise(short)
        val longNorm = normalise(long)
        val blended = HashMap<String, Double>()
        for (key in shortNorm.keys + longNorm.keys) {
            blended[key] = SHORT_SHARE * (shortNorm[key] ?: 0.0) + (1 - SHORT_SHARE) * (longNorm[key] ?: 0.0)
        }
        val tracks =
            trackScore.mapValues { (id, score) ->
                TrackTaste(id, score, trackPlays[id] ?: 0, trackSkips[id] ?: 0, trackLast[id] ?: Double.MAX_VALUE)
            }
        return TasteProfile(blended, shortNorm, longNorm, names, tracks, playedKeys)
    }

    /** Scales so the strongest positive value is 1; negatives keep their size relative to it. */
    private fun normalise(values: Map<String, Double>): Map<String, Double> {
        val top = values.values.maxOrNull()?.takeIf { it > 0 } ?: return values.mapValues { it.value.coerceIn(-1.0, 0.0) }
        return values.mapValues { (it.value / top).coerceIn(-1.0, 1.0) }
    }
}

/* ---------------------------------------------------------------- the map */

@Serializable
data class GraphNode(
    val key: String,
    val name: String,
    val deezerId: Long? = null,
    val mbid: String? = null,
    val picture: String? = null,
    /** Epoch day its Deezer links were last fetched, or -1 when they never were. */
    val expandedDay: Long = -1,
    /** The same for ListenBrainz, which is looked up separately because it is slower. */
    val listenBrainzDay: Long = -1,
)

@Serializable
data class GraphEdge(
    val to: String,
    /** Strength of the link in each source, 0..1; 0 when the source does not name it. */
    val deezer: Float = 0f,
    val listenBrainz: Float = 0f,
)

@Serializable
data class ArtistGraphData(
    val nodes: Map<String, GraphNode> = emptyMap(),
    val edges: Map<String, List<GraphEdge>> = emptyMap(),
)

/** The artist map, kept on the device and grown a few artists at a time. */
class ArtistGraph(
    data: ArtistGraphData = ArtistGraphData(),
) {
    private val nodes = HashMap(data.nodes)
    private val edges = HashMap(data.edges)

    val size: Int get() = nodes.size

    fun node(key: String): GraphNode? = nodes[key]

    fun isExpanded(
        key: String,
        today: Long,
        maxAgeDays: Long = EDGE_TTL_DAYS,
    ): Boolean = nodes[key]?.expandedDay?.let { it >= 0 && today - it <= maxAgeDays } == true

    /** Adds the node, or fills in what an earlier sighting of it lacked. */
    fun upsert(node: GraphNode): GraphNode {
        val old = nodes[node.key]
        val merged =
            if (old == null) {
                node
            } else {
                old.copy(
                    deezerId = old.deezerId ?: node.deezerId,
                    mbid = old.mbid ?: node.mbid,
                    picture = old.picture ?: node.picture,
                    expandedDay = maxOf(old.expandedDay, node.expandedDay),
                    listenBrainzDay = maxOf(old.listenBrainzDay, node.listenBrainzDay),
                )
            }
        nodes[node.key] = merged
        return merged
    }

    fun hasListenBrainz(
        key: String,
        today: Long,
    ): Boolean = nodes[key]?.listenBrainzDay?.let { it >= 0 && today - it <= EDGE_TTL_DAYS } == true

    /**
     * Sets [from]'s links from one or both sources (key to strength) and marks each given source
     * fetched on [today]. A source passed as null keeps the links it gave last time.
     */
    fun setLinks(
        from: String,
        deezer: List<Pair<String, Float>>?,
        listenBrainz: List<Pair<String, Float>>?,
        today: Long,
    ) {
        val node = nodes[from] ?: return
        val old = edges[from].orEmpty()
        val d = deezer ?: old.filter { it.deezer > 0f }.map { it.to to it.deezer }
        val l = listenBrainz ?: old.filter { it.listenBrainz > 0f }.map { it.to to it.listenBrainz }
        edges[from] = merge(d, l).filter { it.to != from }.sortedByDescending { weight(it) }.take(MAX_EDGES)
        nodes[from] =
            node.copy(
                expandedDay = if (deezer != null) today else node.expandedDay,
                listenBrainzDay = if (listenBrainz != null) today else node.listenBrainzDay,
            )
    }

    /**
     * The walkable map: every fetched link, and its reverse at half strength where the other end
     * has not been fetched, so a walk can pass through an artist nobody has looked up yet.
     */
    fun adjacency(): Map<String, List<Pair<String, Double>>> {
        val out = HashMap<String, MutableList<Pair<String, Double>>>()
        for ((from, links) in edges) {
            for (link in links) {
                val w = weight(link)
                if (w <= 0) continue
                out.getOrPut(from) { ArrayList() } += link.to to w
                if (edges[link.to].isNullOrEmpty()) out.getOrPut(link.to) { ArrayList() } += from to w * REVERSE_SHARE
            }
        }
        return out
    }

    fun snapshot(): ArtistGraphData = ArtistGraphData(HashMap(nodes), HashMap(edges))

    /** Drops the artists furthest from anything fetched when the map outgrows [maxNodes]. */
    fun prune(maxNodes: Int = MAX_NODES) {
        if (nodes.size <= maxNodes) return
        val keep = HashSet<String>(edges.keys)
        val referenced = HashMap<String, Double>()
        for (links in edges.values) for (link in links) referenced[link.to] = maxOf(referenced[link.to] ?: 0.0, weight(link))
        referenced.entries.sortedByDescending { it.value }.take((maxNodes - keep.size).coerceAtLeast(0)).forEach { keep += it.key }
        nodes.keys.retainAll(keep)
        for ((from, links) in edges.toMap()) edges[from] = links.filter { it.to in keep }
    }

    companion object {
        const val MAX_EDGES = 40
        const val MAX_NODES = 4000
        const val EDGE_TTL_DAYS = 45L
        const val REVERSE_SHARE = 0.5
        private const val DEEZER_TRUST = 0.8
        private const val LISTENBRAINZ_TRUST = 0.9

        /** Deezer lists related artists best first, with no number: position stands in for strength. */
        fun deezerStrength(position: Int): Float = (1.0 / (1.0 + 0.12 * position)).toFloat()

        /** ListenBrainz gives a co-listening count; its square root against the list's best. */
        fun listenBrainzStrength(
            score: Double,
            best: Double,
        ): Float = if (best <= 0) 0f else sqrt((score / best).coerceIn(0.0, 1.0)).toFloat()

        /** Either source can vouch for a link; both together are stronger than one (noisy-OR). */
        fun weight(edge: GraphEdge): Double = 1.0 - (1.0 - DEEZER_TRUST * edge.deezer) * (1.0 - LISTENBRAINZ_TRUST * edge.listenBrainz)

        /** One list of links from the two sources' lists, matched by artist key. */
        fun merge(
            deezer: List<Pair<String, Float>>,
            listenBrainz: List<Pair<String, Float>>,
        ): List<GraphEdge> {
            val out = LinkedHashMap<String, GraphEdge>()
            for ((key, strength) in deezer) out[key] = GraphEdge(key, deezer = maxOf(strength, out[key]?.deezer ?: 0f))
            for ((key, strength) in listenBrainz) {
                val old = out[key]
                out[key] = (old ?: GraphEdge(key)).copy(listenBrainz = maxOf(strength, old?.listenBrainz ?: 0f))
            }
            return out.values.toList()
        }
    }
}

/* --------------------------------------------------------------- the walk */

object Walk {
    /**
     * Personalized PageRank by power iteration: where a walker ends up who starts at [restart],
     * follows links in proportion to their strength, and with chance 1 - [follow] goes back to the
     * start. An artist with no links out sends the walker home.
     */
    fun personalizedPageRank(
        adjacency: Map<String, List<Pair<String, Double>>>,
        restart: Map<String, Double>,
        follow: Double = 0.7,
        iterations: Int = 24,
    ): Map<String, Double> {
        val total = restart.values.filter { it > 0 }.sum()
        if (total <= 0) return emptyMap()
        val home = restart.filter { it.value > 0 }.mapValues { it.value / total }
        val outSum = adjacency.mapValues { (_, links) -> links.sumOf { it.second } }
        var rank: Map<String, Double> = home
        repeat(iterations) {
            val next = HashMap<String, Double>()
            var homeward = 1 - follow
            for ((node, mass) in rank) {
                val links = adjacency[node]
                val sum = outSum[node] ?: 0.0
                if (links.isNullOrEmpty() || sum <= 0) {
                    homeward += follow * mass
                    continue
                }
                for ((to, w) in links) next[to] = (next[to] ?: 0.0) + follow * mass * w / sum
            }
            // homeward is the share that returns to the start: 1 - follow of everything (the ranks
            // sum to 1), plus what the dead ends sent back.
            for ((node, share) in home) next[node] = (next[node] ?: 0.0) + homeward * share
            rank = next
        }
        return rank
    }

    /**
     * Takes the edge off the stars. An artist everybody links to collects rank from everywhere
     * without being close to anyone's taste; dividing by a root of what flows in keeps the artists
     * that are near, not merely big.
     */
    fun dampHubs(
        rank: Map<String, Double>,
        adjacency: Map<String, List<Pair<String, Double>>>,
        exponent: Double = 0.35,
    ): Map<String, Double> {
        val inflow = HashMap<String, Double>()
        for (links in adjacency.values) for ((to, w) in links) inflow[to] = (inflow[to] ?: 0.0) + w
        return rank.mapValues { (node, value) -> value / (1.0 + (inflow[node] ?: 0.0)).pow(exponent) }
    }

    /** The walk from what is liked, minus [dislikeShare] of the walk from what is skipped. */
    fun score(
        adjacency: Map<String, List<Pair<String, Double>>>,
        liked: Map<String, Double>,
        disliked: Map<String, Double> = emptyMap(),
        follow: Double = 0.7,
        dislikeShare: Double = 0.6,
    ): Map<String, Double> {
        val up = dampHubs(personalizedPageRank(adjacency, liked, follow), adjacency)
        if (disliked.isEmpty()) return up
        val down = dampHubs(personalizedPageRank(adjacency, disliked, follow), adjacency)
        return up.mapValues { (node, value) -> value - dislikeShare * (down[node] ?: 0.0) }
    }
}

/* ------------------------------------------------------------ taste groups */

data class TasteGroup(
    /** Its artists, the one the listener likes most first. */
    val artists: List<String>,
    val weight: Double,
)

object Clusters {
    const val MAX_ARTISTS = 60

    /**
     * Groups the listener's artists by the map: label propagation, where each artist takes the
     * label its neighbours carry most strongly, repeated until nobody changes. Artists are visited
     * best-liked first, so the result does not depend on chance.
     */
    fun groups(
        liked: List<Pair<String, Double>>,
        adjacency: Map<String, List<Pair<String, Double>>>,
        rounds: Int = 12,
    ): List<TasteGroup> {
        val artists = liked.filter { it.second > 0 }.sortedByDescending { it.second }.take(MAX_ARTISTS)
        if (artists.isEmpty()) return emptyList()
        val affinity = artists.toMap()
        val links = HashMap<String, HashMap<String, Double>>()
        for ((artist, _) in artists) {
            for ((to, w) in adjacency[artist].orEmpty()) {
                if (to !in affinity || to == artist) continue
                val forward = links.getOrPut(artist) { HashMap() }
                forward[to] = maxOf(forward[to] ?: 0.0, w)
                val back = links.getOrPut(to) { HashMap() }
                back[artist] = maxOf(back[artist] ?: 0.0, w)
            }
        }
        val label = HashMap<String, String>()
        artists.forEach { label[it.first] = it.first }
        repeat(rounds) {
            var changed = false
            for ((artist, _) in artists) {
                val votes = HashMap<String, Double>()
                for ((to, w) in links[artist].orEmpty()) {
                    val l = label.getValue(to)
                    votes[l] = (votes[l] ?: 0.0) + w
                }
                if (votes.isEmpty()) continue
                val best = votes.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenByDescending { affinity[it.key] ?: 0.0 }).first().key
                if (best != label[artist]) {
                    label[artist] = best
                    changed = true
                }
            }
            if (!changed) return@repeat
        }
        return artists
            .groupBy { label.getValue(it.first) }
            .values
            .map { members -> TasteGroup(members.map { it.first }, members.sumOf { it.second }) }
            .sortedByDescending { it.weight }
    }
}

object Calibration {
    /**
     * Splits [slots] between groups. Proportional to weight raised to [exponent]: below 1 a small
     * group gets more than its strict share, so it is heard at all. Largest remainders fill the
     * last slots.
     */
    fun allocate(
        slots: Int,
        weights: List<Double>,
        exponent: Double = 0.7,
    ): List<Int> {
        if (weights.isEmpty() || slots <= 0) return weights.map { 0 }
        val powered = weights.map { it.coerceAtLeast(0.0).pow(exponent) }
        val total = powered.sum()
        if (total <= 0) return weights.map { 0 }
        val exact = powered.map { it / total * slots }
        val out = exact.map { it.toInt() }.toMutableList()
        var left = slots - out.sum()
        val order = exact.indices.sortedByDescending { exact[it] - exact[it].toInt() }
        var i = 0
        while (left > 0 && order.isNotEmpty()) {
            out[order[i % order.size]]++
            left--
            i++
        }
        return out
    }
}

/* ---------------------------------------------------------------- learning */

/** A song the engine put in front of the listener. */
@Serializable
data class Served(
    val videoId: String,
    val artistKey: String,
    val day: Long,
    /** [SimilarMix.trackKey] of the song, to hold it back before it is matched on YouTube again. */
    val trackKey: String = "",
)

data class ArtistRecord(
    val played: Int = 0,
    val skipped: Int = 0,
    val ignored: Int = 0,
)

object Feedback {
    const val COOL_DAYS = 14L
    const val MAX_LOG = 600

    /** How long a recommendation waits for a play before it counts as ignored. */
    const val IGNORED_AFTER_DAYS = 3L

    /** Per artist: recommendations played through, skipped, and left untouched. */
    fun records(
        served: List<Served>,
        plays: Map<String, List<Play>>,
        today: Long,
    ): Map<String, ArtistRecord> {
        val out = HashMap<String, ArtistRecord>()
        for (item in served.distinctBy { it.videoId }) {
            val old = out[item.artistKey] ?: ArtistRecord()
            val after = plays[item.videoId].orEmpty().filter { it.ageDays <= (today - item.day) + 1.0 }
            out[item.artistKey] =
                when {
                    after.any { Taste.signal(it.listenedSec, it.durationSec) >= 0.5 } -> old.copy(played = old.played + 1)
                    after.isNotEmpty() -> old.copy(skipped = old.skipped + 1)
                    today - item.day >= IGNORED_AFTER_DAYS -> old.copy(ignored = old.ignored + 1)
                    else -> old
                }
        }
        return out
    }

    /**
     * A factor around 1 for an artist's chances, drawn from their record (Thompson sampling with a
     * Beta distribution). An artist with no record draws anywhere in a wide range, which is what
     * makes the engine try new things; one skipped five times draws low nearly always.
     */
    fun chance(
        record: ArtistRecord?,
        random: Random,
    ): Double {
        val r = record ?: ArtistRecord()
        val a = 1.5 + r.played
        val b = 1.5 + r.skipped + 0.2 * r.ignored
        return 0.5 + beta(a, b, random)
    }

    /** Track keys offered in the last [COOL_DAYS] days and not played since: not offered again yet. */
    fun cooling(
        served: List<Served>,
        playedIds: Set<String>,
        today: Long,
    ): Set<String> = served.filter { today - it.day <= COOL_DAYS && it.videoId !in playedIds }.map { it.trackKey }.toSet()

    fun append(
        log: List<Served>,
        added: List<Served>,
    ): List<Served> = (log + added).takeLast(MAX_LOG)

    private fun beta(
        a: Double,
        b: Double,
        random: Random,
    ): Double {
        val x = gamma(a, random)
        val y = gamma(b, random)
        return if (x + y <= 0) 0.5 else x / (x + y)
    }

    /** Marsaglia and Tsang's method; [shape] is at least 1 here. */
    private fun gamma(
        shape: Double,
        random: Random,
    ): Double {
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double
            var v: Double
            do {
                x = gaussian(random)
                v = 1.0 + c * x
            } while (v <= 0)
            v = v * v * v
            val u = random.nextDouble().coerceAtLeast(1e-12)
            if (ln(u) < 0.5 * x * x + d - d * v + d * ln(v)) return d * v
        }
    }

    private fun gaussian(random: Random): Double {
        // Box–Muller.
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * kotlin.math.PI * u2)
    }
}

/* ------------------------------------------------------------- selection */

data class ScoredArtist(
    val key: String,
    val score: Double,
)

object Selection {
    /**
     * The [count] best artists, each pick lowering artists linked to it: after one artist of a
     * tight circle is in, the next of that circle has to be clearly better than something from
     * elsewhere (maximal marginal relevance, with the map's link strength as the similarity).
     */
    fun diverse(
        candidates: List<ScoredArtist>,
        adjacency: Map<String, List<Pair<String, Double>>>,
        count: Int,
        sameness: Double = 0.45,
    ): List<ScoredArtist> {
        val pool = candidates.filter { it.score > 0 }.sortedByDescending { it.score }.take(count * 6).toMutableList()
        val picked = ArrayList<ScoredArtist>()
        val linkTo = HashMap<String, Double>()
        while (picked.size < count && pool.isNotEmpty()) {
            val best = pool.maxBy { it.score * (1.0 - sameness * (linkTo[it.key] ?: 0.0)) }
            pool.remove(best)
            picked += best
            for ((to, w) in adjacency[best.key].orEmpty()) linkTo[to] = maxOf(linkTo[to] ?: 0.0, w.coerceAtMost(1.0))
        }
        return picked
    }

    /** Candidates for discovery: scored artists minus those the listener already plays. */
    fun unknownOnly(
        scores: Map<String, Double>,
        known: Set<String>,
    ): List<ScoredArtist> = scores.filter { it.key !in known && it.value > 0 }.map { ScoredArtist(it.key, it.value) }
}

object Sequencer {
    /**
     * Orders [items] keeping their given order as far as it can while holding the same artist at
     * least [gap] places apart. When nothing else is left the rule gives way rather than drop songs.
     */
    fun <T> spread(
        items: List<T>,
        gap: Int = 3,
        artist: (T) -> String,
    ): List<T> {
        val pool = items.toMutableList()
        val out = ArrayList<T>()
        while (pool.isNotEmpty()) {
            val recent = out.takeLast(gap).map(artist).toSet()
            val index = pool.indexOfFirst { artist(it) !in recent }.takeIf { it >= 0 } ?: 0
            out += pool.removeAt(index)
        }
        return out
    }

    /**
     * Weaves songs the listener knows into new ones: one known song after every [newPerKnown] new
     * ones, so a radio is neither all strangers nor a replay of the history.
     */
    fun <T> weave(
        fresh: List<T>,
        known: List<T>,
        newPerKnown: Int = 2,
    ): List<T> {
        val out = ArrayList<T>()
        var k = 0
        fresh.forEachIndexed { index, item ->
            out += item
            if ((index + 1) % newPerKnown == 0 && k < known.size) out += known[k++]
        }
        while (k < known.size && fresh.isEmpty()) out += known[k++]
        return out
    }
}
