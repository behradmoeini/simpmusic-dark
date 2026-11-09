package com.maxrave.simpmusic.diagnostics

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A separate audio log for playback bugs such as "playing, but silent". It records what the system
 * reports for this app's audio streams, the stream volume and mute state, and whether the app
 * believes it is playing. Each day goes to logs/audio-yyyyMMdd.log, and files older than seven days
 * are deleted. Every line also goes to the app log, so the existing export carries it too.
 */
object AudioDiagnostics {
    private const val KEEP_DAYS = 7L
    private const val TAG = "Audio"
    private val lock = Any()
    private var folder: File? = null
    private var lastPlayback = ""

    fun start(context: Context, scope: CoroutineScope, isPlaying: Flow<Boolean>) {
        if (folder != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        folder = dir
        prune(dir)
        write("start pid=${android.os.Process.myPid()} sdk=${android.os.Build.VERSION.SDK_INT}")

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        logStream(audio)

        audio.registerAudioPlaybackCallback(
            object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    val summary =
                        configs.orEmpty().joinToString(" | ") { c: AudioPlaybackConfiguration ->
                            "state=${c.playerState} usage=${c.audioAttributes.usage} volume=${c.volume}"
                        }
                    if (summary != lastPlayback) {
                        lastPlayback = summary
                        write("system playback [${configs?.size ?: 0}] $summary")
                        logStream(audio)
                    }
                }
            },
            Handler(Looper.getMainLooper()),
        )

        scope.launch {
            isPlaying.distinctUntilChanged().collect { playing ->
                write("app isPlaying=$playing")
                logStream(audio)
            }
        }
    }

    private fun logStream(audio: AudioManager) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        write("stream music=${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/$max muted=${audio.isStreamMute(AudioManager.STREAM_MUSIC)}")
    }

    private fun write(line: String) {
        Logger.i(TAG, line)
        val dir = folder ?: return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        synchronized(lock) {
            runCatching { File(dir, "audio-$day.log").appendText("$stamp $line\n") }
        }
    }

    private fun prune(dir: File) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24 * 60 * 60 * 1000
        dir.listFiles { f -> f.name.startsWith("audio-") && f.name.endsWith(".log") }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }
}
