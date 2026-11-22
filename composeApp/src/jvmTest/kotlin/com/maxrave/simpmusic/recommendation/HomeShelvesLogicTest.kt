package com.maxrave.simpmusic.recommendation

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeShelvesLogicTest {
    @Test
    fun likedAlbumsSumPlaysAndSkipRowsThatAreNotAlbums() {
        val a = LikedAlbum("MPREb_a", "A", "x")
        val b = LikedAlbum("MPREb_b", "B", "y")
        val out =
            HomeShelvesLogic.likedAlbums(
                listOf(a to 3, b to 5, a to 4, LikedAlbum("UC123", "Channel", "z") to 99, LikedAlbum("MPREb_c", "", "z") to 99),
                max = 5,
            )
        assertEquals(listOf(a, b), out)
    }

    @Test
    fun newReleasesKeepRecentOnesNewestFirstWithoutDuplicates() {
        val today = LocalDate.of(2026, 11, 22)
        val out =
            HomeShelvesLogic.newReleases(
                listOf(
                    DeezerAlbum("Old", "x", releaseDate = "2020-01-01"),
                    DeezerAlbum("Second", "x", releaseDate = "2026-10-01"),
                    DeezerAlbum("First", "y", releaseDate = "2026-11-20"),
                    DeezerAlbum("First (Deluxe)", "y", releaseDate = "2026-11-10"),
                    DeezerAlbum("Future", "x", releaseDate = "2027-01-01"),
                    DeezerAlbum("No date", "x", releaseDate = "0000-00-00"),
                ),
                today,
                max = 10,
            )
        assertEquals(listOf("First", "Second"), out.map { it.title })
    }
}
