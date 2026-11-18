package org.simpmusic.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The tools a chat client sees, and their dispatch onto [MusicPlayer]. */
class MusicTools(
    private val player: MusicPlayer,
) {
    private class Tool(
        val name: String,
        val description: String,
        val properties: JsonObject = JsonObject(emptyMap()),
        val required: List<String> = emptyList(),
        val run: suspend (JsonObject) -> String,
    )

    private val tools =
        listOf(
            Tool(
                name = "play",
                description =
                    "Play a song now on the user's computer, replacing the queue. Give a free-text `query` " +
                        "(title and artist work best) or an exact `video_id` from search_music. By default a " +
                        "radio of similar songs follows it.",
                properties =
                    props {
                        string("query", "What to play, e.g. \"Hayedeh - Soghati\"")
                        string("video_id", "A YouTube Music video id from search_music")
                        boolean("radio", "Queue similar songs after it (default true)")
                    },
                run = { args -> player.play(args.string("query"), args.string("video_id"), args.boolean("radio") ?: true) },
            ),
            Tool(
                name = "search_music",
                description = "Search YouTube Music. Returns ids to pass to play, add_to_queue or play_playlist.",
                properties =
                    props {
                        string("query", "Search text")
                        enum("type", "What to look for (default songs)", listOf("songs", "videos", "albums", "playlists"))
                        integer("limit", "How many results, 1-20 (default 8)")
                    },
                required = listOf("query"),
                run = { args ->
                    val hits = player.search(args.string("query").orEmpty(), args.string("type") ?: "songs", (args.int("limit") ?: 8).coerceIn(1, 20))
                    if (hits.isEmpty()) {
                        "No results."
                    } else {
                        hits.withIndex().joinToString("\n") { (i, hit) ->
                            val idLabel = if (hit.kind == "album" || hit.kind == "playlist") "playlist_id" else "video_id"
                            "${i + 1}. ${hit.title}" +
                                (if (hit.subtitle.isNotBlank()) " — ${hit.subtitle}" else "") +
                                (hit.durationSeconds?.let { " (${formatTime(it.toDouble())})" } ?: "") +
                                " [${hit.kind}, $idLabel: ${hit.id}]"
                        }
                    }
                },
            ),
            Tool(
                name = "play_playlist",
                description = "Play a whole album or playlist, replacing the queue. Takes a playlist_id from search_music.",
                properties =
                    props {
                        string("playlist_id", "Album or playlist id from search_music")
                        boolean("shuffle", "Shuffle the order (default false)")
                    },
                required = listOf("playlist_id"),
                run = { args -> player.playPlaylist(args.string("playlist_id").orEmpty(), args.boolean("shuffle") ?: false) },
            ),
            Tool(
                name = "add_to_queue",
                description = "Add a song to the queue without interrupting what is playing.",
                properties =
                    props {
                        string("query", "What to add")
                        string("video_id", "A YouTube Music video id from search_music")
                        boolean("play_next", "Put it right after the current song instead of at the end (default false)")
                    },
                run = { args -> player.addToQueue(args.string("query"), args.string("video_id"), args.boolean("play_next") ?: false) },
            ),
            Tool("pause", "Pause playback.", run = { player.setPaused(true) }),
            Tool("resume", "Resume playback.", run = { player.setPaused(false) }),
            Tool("next", "Skip to the next song.", run = { player.next() }),
            Tool("previous", "Go back to the previous song (or restart this one if it is past 5 seconds).", run = { player.previous() }),
            Tool("stop", "Stop playback and clear the queue.", run = { player.stop() }),
            Tool("now_playing", "What is playing, how far in, and what comes next.", run = { player.nowPlaying() }),
            Tool(
                name = "show_queue",
                description = "List the queue from the current song on.",
                properties = props { integer("limit", "How many songs to list (default 15)") },
                run = { args -> player.showQueue((args.int("limit") ?: 15).coerceIn(1, 100)) },
            ),
            Tool(
                name = "seek",
                description = "Jump to a position in the current song.",
                properties = props { number("seconds", "Position from the start, in seconds") },
                required = listOf("seconds"),
                run = { args -> player.seek(args.double("seconds") ?: 0.0) },
            ),
            Tool(
                name = "set_volume",
                description = "Set the player's volume.",
                properties = props { integer("percent", "0-130, 100 is normal") },
                required = listOf("percent"),
                run = { args -> player.setVolume(args.int("percent") ?: 100) },
            ),
        )

    fun definitions(): JsonArray =
        buildJsonArray {
            tools.forEach { tool ->
                add(
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        putJsonObject("inputSchema") {
                            put("type", "object")
                            put("properties", tool.properties)
                            if (tool.required.isNotEmpty()) putJsonArray("required") { tool.required.forEach { add(JsonPrimitive(it)) } }
                        }
                    },
                )
            }
        }

    suspend fun call(params: JsonObject?): JsonElement {
        val name = params?.get("name")?.jsonPrimitive?.contentOrNull
        val args = params?.get("arguments") as? JsonObject ?: JsonObject(emptyMap())
        val tool = tools.firstOrNull { it.name == name } ?: return result("Unknown tool: $name", isError = true)
        return try {
            result(tool.run(args), isError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MpvUnavailableException) {
            result(e.message.orEmpty(), isError = true)
        } catch (e: Exception) {
            System.err.println("[simpmusic-mcp] ${tool.name} failed: $e")
            result("${tool.name} failed: ${e.message ?: e::class.simpleName}", isError = true)
        }
    }

    private fun result(
        text: String,
        isError: Boolean,
    ) = buildJsonObject {
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
        put("isError", isError)
    }

    private class PropsBuilder {
        val map = LinkedHashMap<String, JsonElement>()

        private fun prop(
            name: String,
            type: String,
            description: String,
            extra: Map<String, JsonElement> = emptyMap(),
        ) {
            map[name] = JsonObject(mapOf("type" to JsonPrimitive(type), "description" to JsonPrimitive(description)) + extra)
        }

        fun string(
            name: String,
            description: String,
        ) = prop(name, "string", description)

        fun boolean(
            name: String,
            description: String,
        ) = prop(name, "boolean", description)

        fun integer(
            name: String,
            description: String,
        ) = prop(name, "integer", description)

        fun number(
            name: String,
            description: String,
        ) = prop(name, "number", description)

        fun enum(
            name: String,
            description: String,
            values: List<String>,
        ) = prop(name, "string", description, mapOf("enum" to JsonArray(values.map { JsonPrimitive(it) })))
    }

    private fun props(block: PropsBuilder.() -> Unit): JsonObject = JsonObject(PropsBuilder().apply(block).map)

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private fun JsonObject.string(key: String): String? = primitive(key)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolean(key: String): Boolean? = primitive(key)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = primitive(key)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.contentOrNull?.toIntOrNull() }

    private fun JsonObject.double(key: String): Double? = primitive(key)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
}
