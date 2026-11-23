package com.maxrave.simpmusic.recommendation.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TasteEngineTest {
    @Test
    fun aPlayIsAYesANoOrAShare() {
        assertEquals(1.0, Taste.signal(200, 200), 1e-9)
        assertEquals(0.5, Taste.signal(100, 200), 1e-9)
        assertEquals(-0.6, Taste.signal(20, 200), 1e-9)
        // A second or two is a mis-tap, not an opinion.
        assertEquals(-0.15, Taste.signal(2, 200), 1e-9)
    }

    @Test
    fun finishedSongsRaiseAnArtistAndSkipsLowerOne() {
        val songs =
            mapOf(
                "s1" to SongFacts("s1", "One", listOf("Alpha")),
                "s2" to SongFacts("s2", "Two", listOf("Beta")),
            )
        val plays =
            List(3) { Play("s1", 1.0, 200, 200) } + List(3) { Play("s2", 1.0, 10, 200) }
        val taste = Taste.build(plays, songs)
        assertTrue(taste.artist.getValue("alpha") > 0)
        assertTrue(taste.artist.getValue("beta") < 0)
        assertEquals(listOf("alpha"), taste.liked().map { it.first })
        assertTrue("beta" in taste.disliked())
    }

    @Test
    fun aLikeCountsWithoutAnyPlay() {
        val taste = Taste.build(emptyList(), mapOf("s1" to SongFacts("s1", "One", listOf("Alpha"), liked = true)))
        assertTrue(taste.artist.getValue("alpha") > 0)
    }

    @Test
    fun twoSourcesAgreeingMakeAStrongerLinkThanEither() {
        val deezer = ArtistGraph.weight(GraphEdge("x", deezer = 0.5f))
        val listenBrainz = ArtistGraph.weight(GraphEdge("x", listenBrainz = 0.5f))
        val both = ArtistGraph.weight(GraphEdge("x", deezer = 0.5f, listenBrainz = 0.5f))
        assertTrue(both > deezer && both > listenBrainz)
        assertTrue(both < 1.0)
    }

    @Test
    fun theMapKeepsOneSourceWhenTheOtherIsSetLater() {
        val graph = ArtistGraph()
        graph.upsert(GraphNode("a", "A"))
        graph.setLinks("a", deezer = listOf("b" to 1f), listenBrainz = null, today = 10)
        graph.setLinks("a", deezer = null, listenBrainz = listOf("b" to 1f, "c" to 0.5f), today = 11)
        val links = graph.adjacency().getValue("a").toMap()
        assertTrue(links.getValue("b") > links.getValue("c"))
        assertTrue(graph.isExpanded("a", today = 12))
        assertTrue(graph.hasListenBrainz("a", today = 12))
    }

    @Test
    fun twoSpellingsOfAnArtistAreOnePlaceOnTheMap() {
        val graph = ArtistGraph()
        graph.upsert(GraphNode("fav", "Fav"))
        graph.upsert(GraphNode("other", "Other"))
        // The history writes "other"; a catalogue lists the same artist as "otherlatin".
        graph.alias("otherlatin", "other")
        graph.setLinks("fav", deezer = listOf("otherlatin" to 1f), listenBrainz = null, today = 1)
        assertEquals(listOf("other"), graph.adjacency().getValue("fav").map { it.first })
    }

    @Test
    fun theWalkFadesWithDistanceAndKeepsItsMass() {
        val adjacency = mapOf("a" to listOf("b" to 1.0), "b" to listOf("c" to 1.0))
        val rank = Walk.personalizedPageRank(adjacency, mapOf("a" to 1.0))
        assertTrue(rank.getValue("a") > rank.getValue("b"))
        assertTrue(rank.getValue("b") > rank.getValue("c"))
        assertTrue(rank.getValue("c") > 0)
        assertEquals(1.0, rank.values.sum(), 1e-6)
    }

    @Test
    fun anArtistReachedFromTwoFavouritesBeatsOneReachedFromOne() {
        val adjacency =
            mapOf(
                "fav1" to listOf("shared" to 1.0, "only1" to 1.0),
                "fav2" to listOf("shared" to 1.0, "only2" to 1.0),
            )
        val scores = Walk.score(adjacency, mapOf("fav1" to 1.0, "fav2" to 1.0))
        assertTrue(scores.getValue("shared") > scores.getValue("only1"))
        assertTrue(scores.getValue("shared") > scores.getValue("only2"))
    }

    @Test
    fun whatIsNearSkippedArtistsScoresLower() {
        val adjacency =
            mapOf(
                "fav" to listOf("x" to 1.0, "y" to 1.0),
                "skipped" to listOf("y" to 1.0),
            )
        val scores = Walk.score(adjacency, mapOf("fav" to 1.0), mapOf("skipped" to 1.0))
        assertTrue(scores.getValue("x") > scores.getValue("y"))
    }

    @Test
    fun artistsFallIntoGroupsByTheirLinks() {
        val liked = listOf("a1" to 1.0, "a2" to 0.9, "a3" to 0.8, "b1" to 0.5, "b2" to 0.4)
        val adjacency =
            mapOf(
                "a1" to listOf("a2" to 1.0, "a3" to 1.0),
                "a2" to listOf("a1" to 1.0, "a3" to 1.0),
                "a3" to listOf("a1" to 1.0, "a2" to 1.0),
                "b1" to listOf("b2" to 1.0),
                "b2" to listOf("b1" to 1.0),
            )
        val groups = Clusters.groups(liked, adjacency)
        assertEquals(2, groups.size)
        assertEquals(setOf("a1", "a2", "a3"), groups[0].artists.toSet())
        assertEquals(setOf("b1", "b2"), groups[1].artists.toSet())
    }

    @Test
    fun aWeakBridgeDoesNotMergeTwoScenes() {
        val adjacency =
            mapOf(
                "a1" to listOf("a2" to 1.0, "a3" to 1.0, "b1" to 0.1),
                "a2" to listOf("a1" to 1.0, "a3" to 1.0),
                "a3" to listOf("a1" to 1.0, "a2" to 1.0),
                "b1" to listOf("b2" to 1.0, "b3" to 1.0),
                "b2" to listOf("b1" to 1.0, "b3" to 1.0),
                "b3" to listOf("b1" to 1.0, "b2" to 1.0),
            )
        val scenes = Scenes.of(adjacency)
        assertEquals(scenes["a1"], scenes["a2"])
        assertEquals(scenes["a1"], scenes["a3"])
        assertEquals(scenes["b1"], scenes["b3"])
        assertTrue(scenes["a1"] != scenes["b1"])
        assertEquals(scenes["a1"], Scenes.home(scenes, mapOf("a2" to 1.0, "b2" to 0.3)))
    }

    @Test
    fun aSmallGroupGetsMoreThanItsStrictShare() {
        val slots = Calibration.allocate(10, listOf(9.0, 1.0))
        assertEquals(10, slots.sum())
        assertTrue(slots[1] >= 2)
    }

    @Test
    fun recommendationsAreScoredByWhatHappenedToThem() {
        val served =
            listOf(
                Served("v1", "a", day = 10, trackKey = "k1"),
                Served("v2", "b", day = 10, trackKey = "k2"),
                Served("v3", "c", day = 10, trackKey = "k3"),
            )
        val plays =
            mapOf(
                "v1" to listOf(Play("v1", 5.0, 200, 200)),
                "v2" to listOf(Play("v2", 5.0, 10, 200)),
            )
        val records = Feedback.records(served, plays, today = 20)
        assertEquals(1, records.getValue("a").played)
        assertEquals(1, records.getValue("b").skipped)
        assertEquals(1, records.getValue("c").ignored)
        // Offered and never played: held back. Played ones are in the history and excluded there.
        assertEquals(setOf("k3"), Feedback.cooling(served, plays.keys, today = 20))
    }

    @Test
    fun anArtistsChanceStaysAroundOne() {
        val random = Random(7)
        repeat(50) {
            val chance = Feedback.chance(ArtistRecord(played = 2, skipped = 3, ignored = 4), random)
            assertTrue(chance in 0.5..1.5)
        }
    }

    @Test
    fun aSkippedArtistUsuallyDrawsLowerThanAPlayedOne() {
        val random = Random(11)
        val liked = (1..200).map { Feedback.chance(ArtistRecord(played = 8), random) }.average()
        val disliked = (1..200).map { Feedback.chance(ArtistRecord(skipped = 8), random) }.average()
        assertTrue(liked > disliked + 0.3)
    }

    @Test
    fun theSameArtistIsKeptApart() {
        val out = Sequencer.spread(listOf("a1", "a2", "b1", "a3", "c1"), gap = 1) { it.take(1) }
        assertEquals(5, out.size)
        assertTrue(out.zipWithNext().none { (x, y) -> x.take(1) == y.take(1) })
    }

    @Test
    fun knownSongsAreWovenBetweenNewOnes() {
        assertEquals(listOf("n1", "n2", "k1", "n3", "n4", "k2"), Sequencer.weave(listOf("n1", "n2", "n3", "n4"), listOf("k1", "k2", "k3")))
    }

    @Test
    fun pickingArtistsSpreadsAcrossCircles() {
        val candidates = listOf(ScoredArtist("a1", 1.0), ScoredArtist("a2", 0.95), ScoredArtist("b1", 0.6))
        val adjacency = mapOf("a1" to listOf("a2" to 1.0), "a2" to listOf("a1" to 1.0))
        // a2 is nearly as good as a1 but in the same circle: b1 comes first.
        assertEquals(listOf("a1", "b1"), Selection.diverse(candidates, adjacency, 2).map { it.key })
    }
}
