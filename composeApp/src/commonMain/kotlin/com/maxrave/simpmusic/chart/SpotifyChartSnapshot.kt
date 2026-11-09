package com.maxrave.simpmusic.chart

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** data/spotify_playlists.json on main, written daily by the spotify-playlists workflow. */
const val SPOTIFY_SNAPSHOT_URL = "https://raw.githubusercontent.com/behradmoeini/simpmusic-dark/main/data/spotify_playlists.json"

@Serializable
data class SpotifySnapshot(
    val updated: String = "",
    val playlists: List<SpotifySnapshotPlaylist> = emptyList(),
)

@Serializable
data class SpotifySnapshotPlaylist(
    val id: String = "",
    val name: String = "",
    val tracks: List<SpotifySnapshotTrack> = emptyList(),
)

@Serializable
data class SpotifySnapshotTrack(
    val uri: String? = null,
    val title: String = "",
    val artists: List<String> = emptyList(),
    val durationMs: Long? = null,
    val artwork: String? = null,
)

private val snapshotJson = Json { ignoreUnknownKeys = true }

/** Fetches the snapshot. Null when the file cannot be read, so the card can say so. */
suspend fun loadSpotifySnapshot(): SpotifySnapshot? {
    val client = HttpClient(CIO)
    return try {
        snapshotJson.decodeFromString<SpotifySnapshot>(client.get(SPOTIFY_SNAPSHOT_URL).bodyAsText())
    } catch (e: Exception) {
        null
    } finally {
        client.close()
    }
}

/** All playlists merged in order, each track once (by uri, else title + artists). */
fun SpotifySnapshot.mergedTracks(): List<SpotifySnapshotTrack> {
    val seen = HashSet<String>()
    return playlists.flatMap { it.tracks }.filter { track ->
        val key = track.uri ?: (track.title + "|" + track.artists.joinToString(","))
        seen.add(key)
    }
}
