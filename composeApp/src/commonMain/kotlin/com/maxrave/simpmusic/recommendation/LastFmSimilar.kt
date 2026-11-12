package com.maxrave.simpmusic.recommendation

import com.maxrave.simpmusic.BuildKonfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Last.fm's track.getSimilar: tracks that listeners of a song also play. A public read-only method,
 * so only the API key is needed, never the shared secret. The key comes from the LASTFM_API_KEY
 * secret through BuildKonfig.similarApiKey; without it [similarTracks] returns nothing.
 */
object LastFmSimilar {
    private const val API = "https://ws.audioscrobbler.com/2.0/"
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { HttpClient(CIO) }

    val isConfigured: Boolean get() = BuildKonfig.similarApiKey.isNotBlank()

    /** (artist, title) pairs, most similar first. Empty when Last.fm has no data for the song. */
    suspend fun similarTracks(
        artist: String,
        title: String,
        limit: Int = 8,
    ): List<Pair<String, String>> {
        if (!isConfigured) return emptyList()
        val body =
            client
                .get(API) {
                    header("User-Agent", "SimpMusicDark/1.0 (similar tracks)")
                    parameter("method", "track.getSimilar")
                    parameter("artist", artist)
                    parameter("track", title)
                    parameter("autocorrect", 1)
                    parameter("limit", limit)
                    parameter("api_key", BuildKonfig.similarApiKey)
                    parameter("format", "json")
                }.bodyAsText()
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val tracks = (root["similartracks"] as? JsonObject)?.get("track") as? JsonArray ?: return emptyList()
        return tracks.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val artistName = ((o["artist"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content ?: return@mapNotNull null
            artistName to name
        }
    }
}
