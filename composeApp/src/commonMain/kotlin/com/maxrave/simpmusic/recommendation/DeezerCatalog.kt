package com.maxrave.simpmusic.recommendation

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deezer's public catalogue: artist search, related artists and top tracks. No key is needed, and
 * its catalogue covers Persian music well (related artists of Hoomaan, Sogand and Koorosh are all
 * Persian and sensible).
 */
object DeezerCatalog {
    private const val API = "https://api.deezer.com"
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { HttpClient(CIO) }

    private suspend fun getJson(
        path: String,
        vararg params: Pair<String, String>,
        retry: Boolean = true,
    ): JsonObject {
        val body =
            client
                .get("$API$path") {
                    header("User-Agent", "SimpMusicDark/1.0 (recommendations)")
                    params.forEach { parameter(it.first, it.second) }
                }.bodyAsText()
        val parsed = json.parseToJsonElement(body) as? JsonObject ?: JsonObject(emptyMap())
        // Deezer allows 50 requests per 5 seconds and answers over that with error code 4.
        if (retry && ((parsed["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.content == "4") {
            delay(1_500)
            return getJson(path, *params, retry = false)
        }
        return parsed
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotEmpty() }?.content

    /** Deezer's id for an artist: the exact name match if there is one, else the first hit. */
    suspend fun artistId(name: String): Long? {
        val hits = (getJson("/search/artist", "q" to name, "limit" to "5")["data"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val wanted = SimilarMix.key(name)
        val best = hits.firstOrNull { SimilarMix.key(it.str("name").orEmpty()) == wanted } ?: hits.firstOrNull()
        return best?.str("id")?.toLongOrNull()
    }

    /** Related artists of an artist: (id, name) pairs, Deezer's order. */
    suspend fun relatedArtists(
        id: Long,
        limit: Int = 4,
    ): List<Pair<Long, String>> {
        val rows = getJson("/artist/$id/related", "limit" to limit.toString())["data"] as? JsonArray ?: return emptyList()
        return rows.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val rid = o.str("id")?.toLongOrNull() ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            rid to name
        }
    }

    /** An artist's top tracks: (artist, title) pairs. */
    suspend fun topTracks(
        id: Long,
        limit: Int = 3,
    ): List<Pair<String, String>> {
        val rows = getJson("/artist/$id/top", "limit" to limit.toString())["data"] as? JsonArray ?: return emptyList()
        return rows.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val title = o.str("title") ?: return@mapNotNull null
            val artist = (o["artist"] as? JsonObject)?.str("name") ?: return@mapNotNull null
            artist to title
        }
    }
}
