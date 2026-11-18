package org.simpmusic.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * SimpMusic MCP server: a chat client (Claude Desktop, Claude Code, Cursor…) asks for music and it
 * plays on this computer. MCP over stdio, newline-delimited JSON-RPC 2.0.
 *
 * Only the protocol may reach stdout, so the real stdout is kept for it and System.out is pointed
 * at stderr: core's logger, OkHttp and anything else that prints lands in the client's server log
 * instead of corrupting the stream.
 */
fun main() {
    val protocolOut = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8)
    System.setOut(System.err)
    val player = MusicPlayer()
    Runtime.getRuntime().addShutdownHook(Thread { player.shutdown() })
    runBlocking { McpServer(protocolOut, MusicTools(player)).run() }
    player.shutdown()
    // OkHttp and the extractor keep non-daemon threads; the client closing stdin means we are done.
    exitProcess(0)
}

private class McpServer(
    private val out: PrintStream,
    private val tools: MusicTools,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Any()

    suspend fun run() =
        coroutineScope {
            val reader = System.`in`.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                if (line.isBlank()) continue
                val message =
                    runCatching { json.parseToJsonElement(line).jsonObject }.getOrElse {
                        send(error(JsonNull, -32700, "Parse error"))
                        continue
                    }
                // Each request runs on its own, so a slow play does not hold up a ping.
                launch(Dispatchers.Default) { handle(message) }
            }
        }

    private suspend fun handle(message: JsonObject) {
        val method = message["method"]?.jsonPrimitive?.contentOrNull ?: return // a response; we send no requests
        val id = message["id"] ?: return // a notification (notifications/initialized and the like)
        val params = message["params"] as? JsonObject
        val result: JsonElement =
            try {
                when (method) {
                    "initialize" -> initialize(params)
                    "ping" -> JsonObject(emptyMap())
                    "tools/list" -> buildJsonObject { put("tools", tools.definitions()) }
                    "tools/call" -> tools.call(params)
                    else -> return send(error(id, -32601, "Method not found: $method"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return send(error(id, -32603, e.message ?: e::class.simpleName.orEmpty()))
            }
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            },
        )
    }

    private fun initialize(params: JsonObject?): JsonElement {
        val requested = params?.get("protocolVersion")?.jsonPrimitive?.contentOrNull
        val version = if (requested in SUPPORTED_VERSIONS) requested!! else SUPPORTED_VERSIONS.last()
        return buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) })
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", "simpmusic")
                    put("version", VERSION)
                },
            )
            put("instructions", INSTRUCTIONS)
        }
    }

    private fun error(
        id: JsonElement,
        code: Int,
        message: String,
    ) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }

    private fun send(message: JsonObject) {
        val text = json.encodeToString(JsonObject.serializer(), message)
        synchronized(writeLock) {
            out.print(text)
            out.print('\n')
            out.flush()
        }
    }

    private companion object {
        const val VERSION = "1.0.0"
        val SUPPORTED_VERSIONS = listOf("2024-11-05", "2025-03-26", "2025-06-18")
        const val INSTRUCTIONS =
            "Plays music from YouTube Music on the user's computer through mpv. " +
                "Use `play` with a free-text query (\"Shadmehr Aghili - Taghdir\") to start a song " +
                "and a radio of similar songs after it; use `search_music` first when the user " +
                "wants to choose. `now_playing` tells what is on."
    }
}
