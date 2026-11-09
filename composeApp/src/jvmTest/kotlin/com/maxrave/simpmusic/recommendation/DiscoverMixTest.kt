package com.maxrave.simpmusic.recommendation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoverMixTest {
    private val recent = (1..20).map { ArtistPlays("R$it", 100 - it * 4) }
    private val older = listOf(ArtistPlays("O1", 80), ArtistPlays("O2", 60)) + recent

    @Test
    fun seedsIncludeAnOlderFavouriteAndAreDistinct() {
        val seeds = DiscoverMix.pickSeeds(recent, older, Random(1), count = 5)
        assertEquals(5, seeds.size)
        assertEquals(seeds.size, seeds.map { it.channelId }.toSet().size)
        assertTrue(seeds.any { it.channelId.startsWith("O") }, "one seed should be an older favourite")
        assertEquals(1.0, seeds.sumOf { it.weight }, 1e-9)
    }

    @Test
    fun seedsVaryWithTheDayButNotWithinIt() {
        val a = DiscoverMix.pickSeeds(recent, older, Random(42))
        val b = DiscoverMix.pickSeeds(recent, older, Random(42))
        assertEquals(a, b)
        val days = (0 until 10).map { DiscoverMix.pickSeeds(recent, older, Random(it.toLong())).map { s -> s.channelId }.toSet() }
        assertTrue(days.toSet().size > 1, "different days should not all pick the same seeds")
    }

    @Test
    fun noHistoryMeansNoSeeds() {
        assertTrue(DiscoverMix.pickSeeds(emptyList(), emptyList(), Random(0)).isEmpty())
    }

    @Test
    fun knownArtistsAndSeedsAreNeverCandidates() {
        val seeds = listOf(Seed("S1", 0.5), Seed("S2", 0.5))
        val hits =
            listOf(
                RelatedHit("S1", "S2", "seed two", 0),
                RelatedHit("S1", "K", "known", 1),
                RelatedHit("S1", "N", "new", 2),
            )
        val ranked = DiscoverMix.rankCandidates(hits, seeds, known = setOf("K"))
        assertEquals(listOf("N"), ranked.map { it.channelId })
    }

    @Test
    fun anArtistBetweenTwoSeedsOutranksOneNearOnlyOne() {
        val seeds = listOf(Seed("S1", 0.5), Seed("S2", 0.5))
        val hits =
            listOf(
                RelatedHit("S1", "A", "only S1, first", 0),
                RelatedHit("S1", "B", "both", 3),
                RelatedHit("S2", "B", "both", 3),
            )
        val ranked = DiscoverMix.rankCandidates(hits, seeds, emptySet())
        assertEquals("B", ranked.first().channelId)
    }

    @Test
    fun selectionSpreadsAcrossSeeds() {
        val fromS1 = (1..6).map { Candidate("A$it", "a", 1.0 - it * 0.01, "S1") }
        val fromS2 = (1..6).map { Candidate("B$it", "b", 0.5 - it * 0.01, "S2") }
        val picked = DiscoverMix.selectDiverse(fromS1 + fromS2, max = 6)
        assertTrue(picked.count { it.primarySeed == "S2" } >= 2, "the weaker seed still gets a share: $picked")
    }

    @Test
    fun interleaveNeverRepeatsAnArtistBackToBack() {
        val mixed = DiscoverMix.interleave(listOf(listOf("a1", "a2"), listOf("b1", "b2"), listOf("c1")))
        assertEquals(listOf("a1", "b1", "c1", "a2", "b2"), mixed)
        mixed.zipWithNext().forEach { (x, y) -> assertFalse(x.first() == y.first()) }
    }

    @Test
    fun pickSongsKeepsChartOrderAndDoesNotRepeat() {
        val picked = DiscoverMix.pickSongs(listOf(1, 2, 3, 4, 5, 6, 7), 2, Random(3))
        assertEquals(2, picked.size)
        assertEquals(picked.sorted(), picked)
        assertTrue(picked.all { it <= 6 })
    }
}
