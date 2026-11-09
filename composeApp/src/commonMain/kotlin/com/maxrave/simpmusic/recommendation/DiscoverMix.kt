package com.maxrave.simpmusic.recommendation

import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * "Same vibe, new artists": a daily mix that stays close to what the listener plays but leaves
 * the artists they already know.
 *
 * YouTube Music's own Home ranks by "more of what you played", so it keeps circling the same
 * artists. This mix borrows the usual fixes for that filter bubble from recommender research:
 *
 * - Seeds are SAMPLED from recent top artists, weighted by the square root of plays, instead of
 *   always taking the top few: the long tail of the listener's taste gets a turn.
 * - One seed is an "older favourite": loved before, quiet lately. It brings back a side of the
 *   taste that recent plays have crowded out.
 * - Candidates are each seed's related artists ("Fans might also like"). That keeps the vibe.
 *   Anyone the listener already plays, or follows, is dropped. That is the novelty.
 * - A candidate named by several seeds scores higher: it sits between two things the listener
 *   likes, which is the strongest "same vibe" signal available.
 * - Selection is greedy with a penalty per seed already used (a simple MMR re-rank), so one
 *   cluster of the taste cannot fill the whole mix.
 * - Tracks are drawn from each new artist's top songs, but not always the first one, and the mix
 *   is interleaved so the same artist never plays twice in a row.
 *
 * Everything here is pure, so it can be tested; DiscoverMixLoader does the I/O.
 */

data class ArtistPlays(
    val channelId: String,
    val plays: Int,
)

data class Seed(
    val channelId: String,
    val weight: Double,
)

/** A related artist as listed on [seed]'s page, at [position] (0 = first). */
data class RelatedHit(
    val seed: String,
    val channelId: String,
    val name: String,
    val position: Int,
)

data class Candidate(
    val channelId: String,
    val name: String,
    val score: Double,
    /** The seed that contributed most: used to spread the mix across seeds. */
    val primarySeed: String,
)

object DiscoverMix {
    /** Recent top artists considered for sampling. */
    const val RECENT_POOL = 15

    /** All-time artists considered for the "older favourite" seed. */
    const val OLDER_POOL = 40

    /** Score multiplier applied each time a seed has already supplied an artist. */
    const val SEED_REUSE_PENALTY = 0.6

    /** How quickly a related artist's weight falls with its position on the seed's page. */
    const val POSITION_DECAY = 0.25

    fun pickSeeds(
        recent: List<ArtistPlays>,
        allTime: List<ArtistPlays>,
        random: Random,
        count: Int = 5,
    ): List<Seed> {
        val recentPool = recent.filter { it.channelId.isNotBlank() && it.plays > 0 }.take(RECENT_POOL)
        val base = recentPool.ifEmpty { allTime.filter { it.channelId.isNotBlank() && it.plays > 0 }.take(RECENT_POOL) }
        if (base.isEmpty()) return emptyList()

        val seeds = weightedSample(base, (count - 1).coerceAtLeast(1), random) { sqrt(it.plays.toDouble()) }.toMutableList()
        val taken = seeds.map { it.channelId }.toMutableSet()
        // Anyone played in the recent window is not an "older" favourite, top 15 or not.
        val recentIds = recent.map { it.channelId }.toSet()
        val older =
            allTime
                .asSequence()
                .filter { it.channelId.isNotBlank() && it.channelId !in recentIds && it.channelId !in taken }
                .take(OLDER_POOL)
                .toList()
        weightedSample(older, 1, random) { sqrt(it.plays.toDouble()) }.firstOrNull()?.let { seeds += it }
        // Top it up from the base pool when there is no older favourite.
        if (seeds.size < count) {
            val rest = base.filter { it.channelId !in seeds.map { s -> s.channelId } }
            seeds += weightedSample(rest, count - seeds.size, random) { sqrt(it.plays.toDouble()) }
        }
        val total = seeds.sumOf { sqrt(it.plays.toDouble()) }.takeIf { it > 0 } ?: 1.0
        return seeds.map { Seed(it.channelId, sqrt(it.plays.toDouble()) / total) }
    }

    fun rankCandidates(
        hits: List<RelatedHit>,
        seeds: List<Seed>,
        known: Set<String>,
    ): List<Candidate> {
        val seedWeight = seeds.associate { it.channelId to it.weight }
        val seedIds = seedWeight.keys
        return hits
            .filter { it.channelId.isNotBlank() && it.channelId !in known && it.channelId !in seedIds }
            .groupBy { it.channelId }
            .map { (id, list) ->
                val perSeed =
                    list
                        .groupBy { it.seed }
                        .mapValues { (seed, h) -> (seedWeight[seed] ?: 0.0) * h.maxOf { 1.0 / (1.0 + POSITION_DECAY * it.position) } }
                Candidate(
                    channelId = id,
                    name = list.first().name,
                    score = perSeed.values.sum(),
                    primarySeed = perSeed.maxBy { it.value }.key,
                )
            }.sortedByDescending { it.score }
    }

    /** Greedy re-rank: each pick lowers the value of further picks from the same seed. */
    fun selectDiverse(
        candidates: List<Candidate>,
        max: Int,
    ): List<Candidate> {
        val pool = candidates.toMutableList()
        val usedPerSeed = HashMap<String, Int>()
        val picked = ArrayList<Candidate>()
        while (picked.size < max && pool.isNotEmpty()) {
            val best = pool.maxBy { it.score * SEED_REUSE_PENALTY.pow(usedPerSeed[it.primarySeed] ?: 0) }
            pool.remove(best)
            picked += best
            usedPerSeed[best.primarySeed] = (usedPerSeed[best.primarySeed] ?: 0) + 1
        }
        return picked
    }

    /**
     * Picks [count] of an artist's top songs, favouring the first ones without always taking the
     * single biggest hit: song i is drawn with weight 1 / (i + 1).
     */
    fun <T> pickSongs(
        topSongs: List<T>,
        count: Int,
        random: Random,
    ): List<T> {
        val pool = topSongs.take(6)
        val indexed = pool.indices.toList()
        return weightedSample(indexed, count, random) { 1.0 / (it + 1) }.sorted().map { pool[it] }
    }

    /** Round-robin across artists, so the same artist never plays twice in a row. */
    fun <T> interleave(groups: List<List<T>>): List<T> {
        val out = ArrayList<T>()
        var round = 0
        while (true) {
            var added = false
            for (g in groups) {
                if (round < g.size) {
                    out += g[round]
                    added = true
                }
            }
            if (!added) return out
            round++
        }
    }

    /** Weighted sampling without replacement (Efraimidis–Spirakis keys). */
    fun <T> weightedSample(
        items: List<T>,
        count: Int,
        random: Random,
        weight: (T) -> Double,
    ): List<T> =
        items
            .map { item ->
                val w = weight(item).coerceAtLeast(1e-9)
                item to random.nextDouble().coerceAtLeast(1e-12).pow(1.0 / w)
            }.sortedByDescending { it.second }
            .take(count.coerceAtLeast(0))
            .map { it.first }
}
