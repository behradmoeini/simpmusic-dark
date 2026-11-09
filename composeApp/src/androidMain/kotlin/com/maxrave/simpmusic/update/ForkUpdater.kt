package com.maxrave.simpmusic.update

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Forced updates for this fork. CI replaces the `latest` pre-release on every push to main and
 * targets it at the pushed commit; an APK built from any other commit must install that one before
 * the app can be used again.
 *
 * The repo must be public: a phone has no GitHub login, and a private repo answers 404.
 */
object ForkUpdater {
    private const val TAG = "ForkUpdate"
    private const val RELEASE_API = "https://api.github.com/repos/behradmoeini/simpmusic-dark/releases/tags/latest"
    private const val PREFS = "fork_update"
    private val FULL_SHA = Regex("^[0-9a-f]{40}$")

    /** Set by MainActivity from BuildConfig. Empty means a local or branch build: never forced. */
    @Volatile
    var currentCommit: String = ""

    data class Release(
        val commit: String,
        val apkUrl: String,
        val apkSize: Long,
    )

    /**
     * The update this device still owes, remembered across launches so opening the app offline
     * after an update was seen does not skip it. Null when nothing is pending.
     */
    fun pending(context: Context): Release? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val commit = prefs.getString("commit", null) ?: return null
        val url = prefs.getString("url", null) ?: return null
        if (commit == currentCommit) {
            clearPending(context)
            return null
        }
        return Release(commit, url, prefs.getLong("size", -1L))
    }

    private fun savePending(
        context: Context,
        release: Release,
    ) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
        putString("commit", release.commit)
        putString("url", release.apkUrl)
        putLong("size", release.apkSize)
    }

    private fun clearPending(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }

    /**
     * Asks GitHub for the `latest` release. Returns the release when it was built from another
     * commit than this APK, null when this APK is current or GitHub cannot be reached right now.
     *
     * An unreachable GitHub does not lock the app: the pending update stays saved, so the next
     * launch that reaches GitHub asks for it again. Blocking here left offline users with no way
     * to play their downloads (issue #1).
     */
    suspend fun check(context: Context): Release? {
        val latest = fetchLatest() ?: return null
        return if (latest.commit == currentCommit) {
            clearPending(context)
            null
        } else {
            savePending(context, latest)
            latest
        }
    }

    private suspend fun fetchLatest(): Release? =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL(RELEASE_API).openConnection() as HttpURLConnection)
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "simpmusic-dark")
                try {
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                        Logger.w(TAG, "Release check answered ${conn.responseCode}")
                        return@withContext null
                    }
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    // CI creates the release with --target <full sha>. Anything else (a branch name)
                    // would never equal a commit and would force an update on every launch.
                    val commit = json.optString("target_commitish")
                    if (!FULL_SHA.matches(commit)) {
                        Logger.w(TAG, "Release target is not a commit: $commit")
                        return@withContext null
                    }
                    val assets = json.getJSONArray("assets")
                    val apks =
                        (0 until assets.length())
                            .map { assets.getJSONObject(it) }
                            .filter { it.optString("name").endsWith(".apk") }
                    val wantArm64 = "arm64-v8a" in Build.SUPPORTED_ABIS
                    val asset =
                        (if (wantArm64) apks.firstOrNull { "arm64-v8a" in it.optString("name") } else null)
                            ?: apks.firstOrNull { "universal" in it.optString("name") }
                            ?: return@withContext null
                    Release(
                        commit = commit,
                        apkUrl = asset.getString("browser_download_url"),
                        apkSize = asset.optLong("size", -1L),
                    ).also { Logger.i(TAG, "Latest build ${commit.take(7)}, this build ${currentCommit.take(7)}") }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Release check failed: ${e.message}")
                null
            }
        }

    /** Whether Android lets this app open the package installer (Android 8+ asks per app). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Downloads the APK into the cache, reusing a finished download of the same commit. */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Float) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "fork-update").apply { mkdirs() }
            val target = File(dir, "${release.commit}.apk")
            if (target.exists() && (release.apkSize <= 0 || target.length() == release.apkSize)) {
                onProgress(1f)
                return@withContext target
            }
            dir.listFiles()?.forEach { it.delete() }
            val part = File(dir, "${release.commit}.apk.part")
            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection)
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "simpmusic-dark")
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                var done = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (total > 0 && done != total) error("Incomplete download: $done of $total bytes")
                if (!part.renameTo(target)) error("Could not move the download into place")
                target
            } finally {
                conn.disconnect()
                part.delete()
            }
        }

    /** Hands the APK to the system installer, which replaces this app in place (same signing key). */
    fun install(
        context: Context,
        apk: File,
    ) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
