package com.maxrave.simpmusic.recommendation

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
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

    private fun rows(o: JsonObject): List<JsonObject> = (o["data"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.toArtist(): DeezerArtist? {
        val id = str("id")?.toLongOrNull() ?: return null
        return DeezerArtist(id, str("name") ?: return null, str("picture_big") ?: str("picture_medium"))
    }

    private fun JsonObject.toTrack(): DeezerTrack? {
        val title = str("title") ?: return null
        val artist = (this["artist"] as? JsonObject)?.str("name") ?: return null
        val album = this["album"] as? JsonObject
        return DeezerTrack(artist, title, album?.str("cover_big") ?: album?.str("cover_medium"))
    }

    /** The artist Deezer lists under [name]: the exact name match if there is one, else the first hit. */
    suspend fun artist(name: String): DeezerArtist? {
        val hits = rows(getJson("/search/artist", "q" to name, "limit" to "5"))
        val wanted = SimilarMix.key(name)
        return (hits.firstOrNull { SimilarMix.key(it.str("name").orEmpty()) == wanted } ?: hits.firstOrNull())?.toArtist()
    }

    suspend fun artistId(name: String): Long? = artist(name)?.id

    /** Related artists of an artist, Deezer's order. */
    suspend fun relatedArtists(
        id: Long,
        limit: Int = 4,
    ): List<DeezerArtist> = rows(getJson("/artist/$id/related", "limit" to limit.toString())).mapNotNull { it.toArtist() }

    /** An artist's top tracks. */
    suspend fun topTracks(
        id: Long,
        limit: Int = 3,
    ): List<DeezerTrack> = rows(getJson("/artist/$id/top", "limit" to limit.toString())).mapNotNull { it.toTrack() }

    /** The artist's station: songs by the artist and by artists close to them. */
    suspend fun radio(
        id: Long,
        limit: Int = 30,
    ): List<DeezerTrack> = rows(getJson("/artist/$id/radio", "limit" to limit.toString())).mapNotNull { it.toTrack() }

    /** The artist's albums, singles and EPs. The rows do not name the artist, so [artistName] is passed in. */
    suspend fun albums(
        id: Long,
        artistName: String,
        limit: Int = 50,
    ): List<DeezerAlbum> =
        rows(getJson("/artist/$id/albums", "limit" to limit.toString())).mapNotNull { o ->
            DeezerAlbum(
                title = o.str("title") ?: return@mapNotNull null,
                artist = artistName,
                cover = o.str("cover_big") ?: o.str("cover_medium"),
                releaseDate = o.str("release_date").orEmpty(),
                recordType = o.str("record_type").orEmpty(),
            )
        }
}

@Serializable
data class DeezerArtist(
    val id: Long,
    val name: String,
    val picture: String? = null,
)

@Serializable
data class DeezerTrack(
    val artist: String,
    val title: String,
    val cover: String? = null,
)

@Serializable
data class DeezerAlbum(
    val title: String,
    val artist: String,
    val cover: String? = null,
    val releaseDate: String = "",
    val recordType: String = "",
)
