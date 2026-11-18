package org.simpmusic.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel

/** mpv could not be found or started; the message says what to install. */
class MpvUnavailableException(
    message: String,
) : Exception(message)

/**
 * One mpv process, audio only, driven over its JSON IPC (`--input-ipc-server`).
 *
 * Two connections to the same IPC server: one carries commands and their replies, used by one
 * caller at a time; the other only reads events. A Windows named pipe opened without overlapped
 * I/O serialises a read and a write on the same handle, so a reader blocked on events would stall
 * every command if they shared one; two connections avoid that on every platform.
 */
class Mpv(
    private val onEndOfFile: (reason: String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private var process: Process? = null
    private var commands: IpcConnection? = null
    private var requestId = 0L

    @Synchronized
    fun command(vararg args: Any?): JsonElement? {
        ensureRunning()
        val id = ++requestId
        val payload =
            buildJsonObject {
                put("command", JsonArray(args.map { it.toJsonElement() }))
                put("request_id", id)
            }
        val connection = commands ?: throw MpvUnavailableException("mpv is not connected")
        try {
            connection.writeLine(json.encodeToString(JsonObject.serializer(), payload))
            while (true) {
                val line = connection.readLine() ?: throw IllegalStateException("mpv closed the connection")
                val reply = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                if (reply["request_id"]?.jsonPrimitive?.longOrNull != id) continue
                val status = reply["error"]?.jsonPrimitive?.contentOrNull
                if (status != "success") throw IllegalStateException("mpv: $status")
                return reply["data"]
            }
        } catch (e: java.io.IOException) {
            // mpv died: drop it, so the next command starts a fresh one.
            stopProcess()
            throw IllegalStateException("mpv stopped: ${e.message}", e)
        }
    }

    fun property(name: String): JsonElement? = runCatching { command("get_property", name) }.getOrNull()

    fun setProperty(
        name: String,
        value: Any,
    ) {
        command("set_property", name, value)
    }

    val isRunning: Boolean get() = process?.isAlive == true

    @Synchronized
    fun shutdown() {
        runCatching { commands?.writeLine("""{"command":["quit"]}""") }
        stopProcess()
    }

    private fun ensureRunning() {
        if (process?.isAlive == true && commands != null) return
        stopProcess()
        val executable = findMpv()
        val ipcPath =
            if (isWindows) {
                "\\\\.\\pipe\\simpmusic-mcp-${ProcessHandle.current().pid()}"
            } else {
                File(System.getProperty("java.io.tmpdir"), "simpmusic-mcp-${ProcessHandle.current().pid()}.sock")
                    .also { it.delete() }
                    .absolutePath
            }
        val started =
            try {
                ProcessBuilder(
                    executable,
                    "--idle=yes",
                    "--no-video",
                    "--no-terminal",
                    "--force-window=no",
                    "--audio-display=no",
                    "--ytdl=no",
                    "--cache=yes",
                    "--input-ipc-server=$ipcPath",
                ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            } catch (e: java.io.IOException) {
                throw MpvUnavailableException("Could not start mpv ($executable): ${e.message}. $INSTALL_HINT")
            }
        process = started
        val deadline = System.currentTimeMillis() + 10_000
        var events: IpcConnection? = null
        while (System.currentTimeMillis() < deadline && started.isAlive) {
            try {
                commands = commands ?: IpcConnection.open(ipcPath, isWindows)
                events = IpcConnection.open(ipcPath, isWindows)
                break
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        val eventConnection = events
        if (commands == null || eventConnection == null) {
            stopProcess()
            throw MpvUnavailableException("mpv started but its control socket did not open. $INSTALL_HINT")
        }
        // The command connection reads only replies; events on it would just be skipped.
        runCatching {
            commands?.writeLine("""{"command":["disable_event","all"]}""")
            commands?.readLine()
        }
        Thread({ readEvents(eventConnection) }, "mpv-events").apply { isDaemon = true }.start()
    }

    private fun readEvents(connection: IpcConnection) {
        try {
            while (true) {
                val line = connection.readLine() ?: break
                val event = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                if (event["event"]?.jsonPrimitive?.contentOrNull == "end-file") {
                    val reason = event["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    // "stop" is mpv answering our own loadfile/stop, not the track finishing.
                    if (reason == "eof" || reason == "error") onEndOfFile(reason)
                }
            }
        } catch (_: Exception) {
        } finally {
            connection.close()
        }
    }

    private fun stopProcess() {
        commands?.close()
        commands = null
        process?.let {
            it.destroy()
            if (!it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) it.destroyForcibly()
        }
        process = null
    }

    private fun findMpv(): String {
        System.getenv("SIMPMUSIC_MPV")?.takeIf { it.isNotBlank() }?.let { return it }
        val names = if (isWindows) listOf("mpv.exe", "mpv.com") else listOf("mpv")
        val dirs =
            System.getenv("PATH").orEmpty().split(File.pathSeparator) +
                listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/snap/bin") +
                listOfNotNull(
                    System.getenv("ProgramFiles")?.let { "$it\\mpv" },
                    System.getenv("LOCALAPPDATA")?.let { "$it\\Microsoft\\WinGet\\Links" },
                    System.getenv("USERPROFILE")?.let { "$it\\scoop\\shims" },
                )
        for (dir in dirs) {
            for (name in names) {
                val file = File(dir, name)
                if (file.isFile && file.canExecute()) return file.absolutePath
            }
        }
        throw MpvUnavailableException("mpv was not found. $INSTALL_HINT")
    }

    private fun Any?.toJsonElement(): JsonElement =
        when (this) {
            null -> JsonNull
            is JsonElement -> this
            is Boolean -> JsonPrimitive(this)
            is Number -> JsonPrimitive(this)
            else -> JsonPrimitive(toString())
        }

    companion object {
        const val INSTALL_HINT =
            "Install mpv (macOS: `brew install mpv`; Windows: `winget install mpv` or `scoop install mpv`; " +
                "Linux: `sudo apt install mpv` or your package manager), or set SIMPMUSIC_MPV to the mpv executable."
    }
}

/** One line-based connection to mpv's IPC server: a Unix socket, or a named pipe on Windows. */
private class IpcConnection(
    input: InputStream,
    private val output: OutputStream,
    private val closer: () -> Unit,
) {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

    fun readLine(): String? = reader.readLine()

    fun writeLine(line: String) {
        output.write((line + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    fun close() {
        runCatching { closer() }
    }

    companion object {
        fun open(
            path: String,
            windows: Boolean,
        ): IpcConnection =
            if (windows) {
                val pipe = RandomAccessFile(path, "rw")
                IpcConnection(
                    input =
                        object : InputStream() {
                            override fun read(): Int = pipe.read()

                            override fun read(
                                b: ByteArray,
                                off: Int,
                                len: Int,
                            ): Int = pipe.read(b, off, len)
                        },
                    output =
                        object : OutputStream() {
                            override fun write(b: Int) = pipe.write(b)

                            override fun write(
                                b: ByteArray,
                                off: Int,
                                len: Int,
                            ) = pipe.write(b, off, len)
                        },
                    closer = { pipe.close() },
                )
            } else {
                val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                channel.connect(UnixDomainSocketAddress.of(path))
                IpcConnection(Channels.newInputStream(channel), Channels.newOutputStream(channel)) { channel.close() }
            }
    }
}

internal fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

internal fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

internal fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.intOrNull
