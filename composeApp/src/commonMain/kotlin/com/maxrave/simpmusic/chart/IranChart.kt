package com.maxrave.simpmusic.chart

/**
 * Iran in Home's chart picker. YouTube publishes no chart for Iran, so this one is built from the
 * daily Spotify snapshot (data/spotify_playlists.json) the fork already keeps for its Persian cards.
 */
const val IRAN_CHART_KEY = "IR"
const val IRAN_CHART_NAME = "Iran"

data class IranChartArtist(
    val name: String,
    val artwork: String?,
    val songCount: Int,
)

data class IranChart(
    val updated: String,
    /** The weekly top-songs playlist, in Spotify's order. */
    val topSongs: List<SpotifySnapshotTrack>,
    val playlists: List<SpotifySnapshotPlaylist>,
    /** Artists by how many charted songs they have, then by their best position. */
    val artists: List<IranChartArtist>,
)

fun SpotifySnapshot.toIranChart(): IranChart? {
    val withTracks = playlists.filter { it.tracks.isNotEmpty() }
    if (withTracks.isEmpty()) return null
    val top =
        withTracks.firstOrNull { it.name.contains("Top 50", ignoreCase = true) || it.name.contains("Chart", ignoreCase = true) }
            ?: withTracks.first()

    data class Tally(
        var count: Int,
        val firstPosition: Int,
        val artwork: String?,
    )
    val tallies = LinkedHashMap<String, Tally>()
    mergedTracks().forEachIndexed { position, track ->
        track.artists.forEach { name ->
            val tally = tallies.getOrPut(name) { Tally(0, position, track.artwork) }
            tally.count++
        }
    }
    val artists =
        tallies.entries
            .sortedWith(compareByDescending<Map.Entry<String, Tally>> { it.value.count }.thenBy { it.value.firstPosition })
            .take(30)
            .map { IranChartArtist(it.key, it.value.artwork, it.value.count) }

    return IranChart(
        updated = updated.take(10),
        topSongs = top.tracks,
        playlists = withTracks,
        artists = artists,
    )
}
