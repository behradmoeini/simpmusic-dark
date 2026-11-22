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
}
