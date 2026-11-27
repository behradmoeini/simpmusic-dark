package com.maxrave.simpmusic.recommendation

import kotlin.test.Test
import kotlin.test.assertEquals

class SimilarMixTest {
    @Test
    fun keyIgnoresCasePunctuationAndArabicLetterForms() {
        assertEquals(SimilarMix.key("Shadmehr  Aghili!"), SimilarMix.key("shadmehr aghili"))
        assertEquals(SimilarMix.key("علي"), SimilarMix.key("علی"))
    }

    @Test
    fun trackKeyDropsVersionSuffixes() {
        assertEquals(SimilarMix.trackKey("Sogand", "Tehran"), SimilarMix.trackKey("sogand", "Tehran (feat. Zakhmi) [Remix]"))
    }

    @Test
    fun artistPlaysSumsPerArtistMostPlayedFirst() {
        val plays = SimilarMix.artistPlays(listOf("A" to 2, "B" to 5, "A" to 4, "" to 9))
        assertEquals(listOf(ArtistPlays("A", 6), ArtistPlays("B", 5)), plays)
    }

    @Test
    fun knownArtistsAreDiscountedAndSeedsDropped() {
        val candidates =
            listOf(
                Candidate("1", "Known", 1.0, "s"),
                Candidate("2", "New", 0.8, "s"),
                Candidate("3", "Seed Artist", 2.0, "s"),
            )
        val out = SimilarMix.discountKnown(candidates, setOf(SimilarMix.key("Known")), setOf(SimilarMix.key("seed artist")))
        assertEquals(listOf("New", "Known"), out.map { it.name })
    }

    @Test
    fun bestMatchPrefersTitleAndArtistOverFirstResult() {
        val results =
            listOf(
                "Tehran (Cover)" to "Someone Else",
                "Tehran" to "Sogand",
                "Other" to "Sogand",
            )
        assertEquals(1, SimilarMix.bestMatch("Sogand", "Tehran", results))
    }

    @Test
    fun bestMatchFallsBackToFirstResultAcrossScripts() {
        assertEquals(0, SimilarMix.bestMatch("Sogand", "Tehran", listOf("تهران" to "سوگند", "x" to "y")))
        assertEquals(-1, SimilarMix.bestMatch("Sogand", "Tehran", emptyList()))
    }

    @Test
    fun skeletonIsTheSameInBothScripts() {
        assertEquals(SimilarMix.skeleton("To Hamishe Bekhand"), SimilarMix.skeleton("تو همیشه بخند"))
        assertEquals(SimilarMix.skeleton("Tehran"), SimilarMix.skeleton("تهران"))
    }

    @Test
    fun confidentMatchFindsThePersianTitleBehindAnotherSong() {
        val results =
            listOf(
                SimilarMix.NamedResult("Tasmim", "Mehrad Hidden", 195),
                SimilarMix.NamedResult("تو همیشه بخند", "Mehrad Hidden", 211),
            )
        assertEquals(1, SimilarMix.confidentMatch(listOf("Mehrad Hidden", "Tara Salahi"), "To Hamishe Bekhand", 210, results))
    }

    @Test
    fun confidentMatchRefusesAnotherSongByTheSameArtist() {
        val results = listOf(SimilarMix.NamedResult("Tasmim", "Mehrad Hidden", 195))
        assertEquals(-1, SimilarMix.confidentMatch(listOf("Mehrad Hidden"), "To Hamishe Bekhand", 210, results))
        assertEquals(-1, SimilarMix.confidentMatch(listOf("Mehrad Hidden"), "To Hamishe Bekhand", 210, emptyList()))
    }
}
