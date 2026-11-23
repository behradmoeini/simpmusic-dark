package com.maxrave.simpmusic.recommendation

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** An artist ListenBrainz names as similar, with its co-listening score. */
data class SimilarArtist(
    val mbid: String,
    val name: String,
    val score: Double,
)

/**
 * ListenBrainz's artist similarity, the recommender's second map source. No key is needed.
 *
 * It is collaborative filtering on real listening: two artists are similar when the same people
 * play them in the same sitting. That is a different kind of evidence from Deezer's editorial and
 * catalogue-based list, which is why the two are worth combining. Checked 2026-11-22: it knows
 * Persian artists too (Mohsen Chavoshi: Siavash Ghomayshi, Ebi, Mohsen Yeganeh, Reza Sadeghi…),
 * with shorter lists than for Western ones.
 *
 * ListenBrainz identifies artists by MusicBrainz id, so a name is looked up on MusicBrainz first.
 * MusicBrainz allows one request a second; [mbid] waits its turn.
 */
object ListenBrainzCatalog {
    private const val MUSICBRAINZ = "https://musicbrainz.org/ws/2/artist/"
    private const val SIMILAR = "https://labs.api.listenbrainz.org/similar-artists/json"

    /** The data set ListenBrainz's own radio uses: sessions of 300 s over 7500 days of listens. */
    private const val ALGORITHM = "session_based_days_7500_session_300_contribution_5_threshold_10_limit_100_filter_True_skip_30"
    private const val USER_AGENT = "SimpMusicDark/1.0 (https://github.com/behradmoeini/simpmusic-dark)"
    private const val MUSICBRAINZ_GAP_MS = 1_100L

    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { HttpClient(CIO) }
    private val musicBrainzTurn = Mutex()
    private var lastMusicBrainzCall = 0L

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    /** MusicBrainz id of the artist called [name], or null when no artist of that name is listed. */
    suspend fun mbid(name: String): String? =
        musicBrainzTurn.withLock {
            val wait = MUSICBRAINZ_GAP_MS - (System.currentTimeMillis() - lastMusicBrainzCall)
            if (wait > 0) delay(wait)
            try {
                val body =
                    client
                        .get(MUSICBRAINZ) {
                            header("User-Agent", USER_AGENT)
                            parameter("query", "artist:\"${name.replace("\"", "")}\"")
                            parameter("fmt", "json")
                            parameter("limit", "3")
                        }.bodyAsText()
                val artists = ((json.parseToJsonElement(body) as? JsonObject)?.get("artists") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val wanted = SimilarMix.key(name)
                // Only an artist written the same way: a near miss here would graft a stranger's
                // neighbourhood onto the listener's map.
                artists.firstOrNull { SimilarMix.key(it.str("name").orEmpty()) == wanted }?.str("id")
            } finally {
                lastMusicBrainzCall = System.currentTimeMillis()
            }
        }

    /** Artists played in the same sittings as [mbid], strongest first. */
    suspend fun similar(mbid: String): List<SimilarArtist> {
        val body =
            client
                .get(SIMILAR) {
                    header("User-Agent", USER_AGENT)
                    parameter("artist_mbids", mbid)
                    parameter("algorithm", ALGORITHM)
                }.bodyAsText()
        return (json.parseToJsonElement(body) as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { row ->
                SimilarArtist(
                    mbid = row.str("artist_mbid") ?: return@mapNotNull null,
                    name = row.str("name") ?: return@mapNotNull null,
                    score = row.str("score")?.toDoubleOrNull() ?: return@mapNotNull null,
                )
            }.filter { it.mbid != mbid }
            .sortedByDescending { it.score }
    }
}
