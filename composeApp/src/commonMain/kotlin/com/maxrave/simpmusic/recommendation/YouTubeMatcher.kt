package com.maxrave.simpmusic.recommendation

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.utils.toTrack
import kotlinx.coroutines.flow.firstOrNull

/** Finds on YouTube Music what another catalogue only names. The choice itself is [SimilarMix.bestMatch]. */
class YouTubeMatcher(
    private val searchRepository: SearchRepository,
) {
    /** The song for (artist, title), or null when there is none or it is in [exclude]. */
    suspend fun song(
        artist: String,
        title: String,
        exclude: Set<String> = emptySet(),
    ): Track? {
        val results =
            searchRepository
                .getSearchDataSong("$title $artist")
                .firstOrNull { it.data != null }
                ?.data
                .orEmpty()
                .filter { it.videoId.isNotBlank() }
                .take(POOL)
        val index = SimilarMix.bestMatch(artist, title, results.map { it.title.orEmpty() to it.artists.orEmpty().joinToString(" ") { a -> a.name } })
        return results.getOrNull(index)?.takeIf { it.videoId !in exclude }?.toTrack()
    }

    /** Browse id of the album (or single) for (artist, title). */
    suspend fun album(
        artist: String,
        title: String,
    ): String? {
        val results =
            searchRepository
                .getSearchDataAlbum("$title $artist")
                .firstOrNull { it.data != null }
                ?.data
                .orEmpty()
                .filter { it.browseId.isNotBlank() }
                .take(POOL)
        val index = SimilarMix.bestMatch(artist, title, results.map { it.title to it.artists.joinToString(" ") { a -> a.name } })
        return results.getOrNull(index)?.browseId
    }

    private companion object {
        const val POOL = 5
    }
}
