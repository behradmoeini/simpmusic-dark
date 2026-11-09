package com.maxrave.simpmusic.spotify

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Developer test: the steps of scripts/spotify-probe.py, run on the device. Reads the token the way
 * the Spotify web player does, then a public user's playlists and the first playlist's songs. It
 * returns a text report so every step's result is visible, including the first failure.
 */
object SpotifyPlaylistProbe {
    private const val USER = "31xtmpfhpw4qo2fvz36tjq3oktzm"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private const val SECRETS_URL =
        "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json"
    private const val SPOTIFY_ORIGIN = "https://open.spotify.com"

    suspend fun run(): String {
        val out = StringBuilder()
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            // 1. Secret list.
            val secrets = Json.parseToJsonElement(client.get(SECRETS_URL) { header("User-Agent", UA) }.text()).jsonObject
            val version = secrets.keys.maxBy { it.toInt() }
            val raw = secrets.getValue(version).jsonArray.map { it.jsonPrimitive.int }
            out.appendLine("[1] secret list v$version (${raw.size} bytes)")

            // 2. Server time.
            val time = client.get("$SPOTIFY_ORIGIN/api/server-time") { spotifyHeaders() }
            out.appendLine("[2] server time HTTP ${time.status.value}")
            val serverTime = Json.parseToJsonElement(time.text()).jsonObject.getValue("serverTime").jsonPrimitive.long

            // 3. Web-player token.
            val key = raw.mapIndexed { i, b -> b xor ((i % 33) + 9) }.joinToString("").encodeToByteArray()
            val otp = totp(key, serverTime)
            val tokenResponse =
                client.get("$SPOTIFY_ORIGIN/api/token") {
                    spotifyHeaders()
                    parameter("reason", "init")
                    parameter("productType", "web-player")
                    parameter("totp", otp)
                    parameter("totpServer", otp)
                    parameter("totpVer", version)
                }
            out.appendLine("[3] token HTTP ${tokenResponse.status.value}")
            val token =
                Json.parseToJsonElement(tokenResponse.text()).jsonObject["accessToken"]?.jsonPrimitive?.content
                    ?: return out.appendLine("No token: stopped.").toString()

            // 4. The user's playlists.
            val playlists =
                client.get("https://api.spotify.com/v1/users/$USER/playlists?limit=50") {
                    header("Authorization", "Bearer $token")
                    header("User-Agent", UA)
                }
            out.appendLine("[4] playlists HTTP ${playlists.status.value} ${playlists.text().take(160)}")
            if (playlists.status.value != 200) return out.toString()
            val items =
                Json.parseToJsonElement(playlists.text()).jsonObject["items"] as? JsonArray ?: JsonArray(emptyList())
            out.appendLine("    ${items.size} playlists")
            items.take(10).forEach { p ->
                val o = p as JsonObject
                out.appendLine("    - ${o["name"]?.jsonPrimitive?.content} (${o["id"]?.jsonPrimitive?.content})")
            }
            val first = items.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
                ?: return out.appendLine("No playlists: stopped.").toString()

            // 5. Songs of the first playlist, on both endpoint names.
            for (path in listOf("items", "tracks")) {
                val tracks = client.get("https://api.spotify.com/v1/playlists/$first/$path?limit=5") {
                    header("Authorization", "Bearer $token")
                    header("User-Agent", UA)
                }
                out.appendLine("[5] $path HTTP ${tracks.status.value}")
                if (tracks.status.value == 200) {
                    val entries = Json.parseToJsonElement(tracks.text()).jsonObject["items"] as? JsonArray ?: JsonArray(emptyList())
                    entries.forEach { e ->
                        val track = (e as JsonObject)["track"]?.jsonObject ?: (e as JsonObject)["item"]?.jsonObject ?: JsonObject(emptyMap())
                        val artists = (track["artists"] as? JsonArray)?.joinToString(", ") {
                            it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                        }.orEmpty()
                        out.appendLine("    - ${track["name"]?.jsonPrimitive?.content} — $artists")
                    }
                    break
                }
            }
        } catch (e: Exception) {
            out.appendLine("Error: ${e.message}")
        } finally {
            client.close()
        }
        return out.toString()
    }

    private suspend fun HttpResponse.text(): String = bodyAsText()

    private fun io.ktor.client.request.HttpRequestBuilder.spotifyHeaders() {
        header("User-Agent", UA)
        header("Origin", SPOTIFY_ORIGIN)
        header("Referer", "$SPOTIFY_ORIGIN/")
    }

    /** RFC 6238 TOTP with a 30-second step and six digits, as the web player computes it. */
    private fun totp(key: ByteArray, serverTimeSeconds: Long): String {
        val counter = (serverTimeSeconds / 30).let { c -> ByteArray(8) { i -> (c shr (56 - 8 * i)).toByte() } }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val digest = mac.doFinal(counter)
        val offset = digest.last().toInt() and 0x0F
        val code =
            ((digest[offset].toInt() and 0x7F) shl 24) or
                ((digest[offset + 1].toInt() and 0xFF) shl 16) or
                ((digest[offset + 2].toInt() and 0xFF) shl 8) or
                (digest[offset + 3].toInt() and 0xFF)
        return (code % 1_000_000).toString().padStart(6, '0')
    }
}
