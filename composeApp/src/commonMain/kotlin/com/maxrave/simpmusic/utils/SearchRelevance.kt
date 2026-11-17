package com.maxrave.simpmusic.utils

import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.videos.VideosResult
import com.maxrave.domain.data.type.SearchResultType

/**
 * How closely a result's title answers the query: 4 exact, 3 starts with it, 2 contains it,
 * 1 has every word of it, 0 otherwise. Persian and Arabic letter variants and zero-width joiners are
 * ignored, so "رختکن بازنده ها" matches "رختکن بازنده‌ها".
 */
fun searchRelevance(query: String, title: String): Int {
    val q = normalizeForSearch(query)
    val t = normalizeForSearch(title)
    if (q.isEmpty() || t.isEmpty()) return 0
    return when {
        t == q -> 4
        t.startsWith(q) -> 3
        t.contains(q) -> 2
        q.split(' ').all { it.isNotEmpty() && t.contains(it) } -> 1
        else -> 0
    }
}

/** The title a result is ranked by: the artist name for an artist, the title for everything else. */
fun SearchResultType.relevanceTitle(): String =
    when (this) {
        is SongsResult -> title.orEmpty()
        is VideosResult -> title
        is AlbumsResult -> title
        is ArtistsResult -> artist
        is PlaylistsResult -> title
        else -> ""
    }

private fun normalizeForSearch(text: String): String =
    text
        .lowercase()
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .replace("‌", "")
        .replace("‏", "")
        .replace("‎", "")
        .replace(Regex("\\s+"), " ")
        .trim()
