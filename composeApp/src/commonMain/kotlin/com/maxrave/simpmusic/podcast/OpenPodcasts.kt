package com.maxrave.simpmusic.podcast

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/*
 * Podcasts that do not come from YouTube Music.
 *
 * Discovery is fyyd (https://fyyd.de): an open podcast directory with a free, keyless API, the one
 * AntennaPod also searches without a key. Episodes come from each show's own RSS feed, the open
 * standard every podcast publishes, so the audio is the publisher's file and not a YouTube video.
 *
 * Plain JDK networking and XML: composeApp only targets Android and the JVM.
 */

@Serializable
data class OpenPodcastShow(
    /** The RSS feed: the show's identity everywhere in this package. */
    val feedUrl: String,
    val title: String,
    val author: String = "",
    val imageUrl: String? = null,
    val description: String? = null,
)

data class OpenPodcastEpisode(
    /** guid when the feed has one, else the audio URL. Key for saved positions. */
    val id: String,
    val title: String,
    val audioUrl: String,
    val pubDateMillis: Long?,
    val durationSeconds: Long?,
    val description: String?,
    val imageUrl: String?,
)

data class OpenPodcastFeed(
    val show: OpenPodcastShow,
    val episodes: List<OpenPodcastEpisode>,
)

object OpenPodcastDirectory {
    private const val TAG = "OpenPodcast"
    private const val FYYD = "https://api.fyyd.de/0.2"
    private const val USER_AGENT = "SimpMusicDark/1.0 (podcasts)"
    private const val MAX_EPISODES = 300
    private val json = Json { ignoreUnknownKeys = true }

    /** Shows whose title matches [query]. */
    suspend fun search(query: String): List<OpenPodcastShow> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return fyydShows("$FYYD/search/podcast?title=$q&count=30")
    }

    /** fyyd's "hot" list: shows getting the most attention right now. */
    suspend fun trending(language: String? = null): List<OpenPodcastShow> {
        val lang = language?.takeIf { it.isNotBlank() }?.let { "&language=$it" }.orEmpty()
        return fyydShows("$FYYD/feature/podcast/hot?count=40$lang")
    }

    private suspend fun fyydShows(url: String): List<OpenPodcastShow> {
        val body = String(get(url), Charsets.UTF_8)
        val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val feed = o.str("xmlURL")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            OpenPodcastShow(
                feedUrl = feed,
                title = o.str("title") ?: return@mapNotNull null,
                author = o.str("author").orEmpty(),
                imageUrl = o.str("imgURL") ?: o.str("layoutImageURL") ?: o.str("thumbImageURL"),
                description = o.str("description")?.stripHtml(),
            )
        }.distinctBy { it.feedUrl }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Downloads and parses a show's RSS feed. [known] fills gaps the feed leaves. */
    suspend fun feed(
        feedUrl: String,
        known: OpenPodcastShow? = null,
    ): OpenPodcastFeed = parseFeed(feedUrl, get(feedUrl), known)

    private suspend fun get(url: String): ByteArray =
        withContext(Dispatchers.IO) {
            var current = url
            // HttpURLConnection does not follow http <-> https redirects, which feeds do often.
            repeat(5) {
                val conn = URI(current).toURL().openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                try {
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location") ?: error("Redirect without Location")
                        current = URI(current).resolve(location.replace(" ", "%20")).toString()
                        return@repeat
                    }
                    if (code != HttpURLConnection.HTTP_OK) error("HTTP $code for $current")
                    return@withContext conn.inputStream.use { it.readBytes() }
                } finally {
                    conn.disconnect()
                }
            }
            error("Too many redirects for $url")
        }

    internal fun parseFeed(
        feedUrl: String,
        bytes: ByteArray,
        known: OpenPodcastShow?,
    ): OpenPodcastFeed {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
        // No DTDs or external entities in a document fetched from the internet.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val channel =
            doc.getElementsByTagName("channel").item(0) as? Element
                ?: error("Not an RSS feed")

        val showImage =
            channel.child(ITUNES, "image")?.getAttribute("href")?.takeIf { it.isNotBlank() }
                ?: channel.child(null, "image")?.child(null, "url")?.text()
        val show =
            OpenPodcastShow(
                feedUrl = feedUrl,
                title = channel.child(null, "title")?.text() ?: known?.title ?: feedUrl,
                author = channel.child(ITUNES, "author")?.text() ?: known?.author.orEmpty(),
                imageUrl = showImage ?: known?.imageUrl,
                description =
                    (channel.child(null, "description")?.text() ?: channel.child(ITUNES, "summary")?.text())
                        ?.stripHtml() ?: known?.description,
            )

        val episodes =
            channel
                .children(null, "item")
                .mapNotNull { item ->
                    val enclosure = item.child(null, "enclosure") ?: return@mapNotNull null
                    val audio = enclosure.getAttribute("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                    val type = enclosure.getAttribute("type")
                    // Video enclosures are exactly what this is meant to keep out.
                    if (type.startsWith("video/")) return@mapNotNull null
                    OpenPodcastEpisode(
                        id = item.child(null, "guid")?.text() ?: audio,
                        title = item.child(null, "title")?.text() ?: item.child(ITUNES, "title")?.text() ?: "",
                        audioUrl = audio,
                        pubDateMillis = item.child(null, "pubDate")?.text()?.let(::parseRfc822),
                        durationSeconds = item.child(ITUNES, "duration")?.text()?.let(::parseDuration),
                        description =
                            (item.child(ITUNES, "summary")?.text() ?: item.child(null, "description")?.text())
                                ?.stripHtml(),
                        imageUrl = item.child(ITUNES, "image")?.getAttribute("href")?.takeIf { it.isNotBlank() } ?: show.imageUrl,
                    )
                }.sortedByDescending { it.pubDateMillis ?: 0L }
                .take(MAX_EPISODES)
        Logger.i(TAG, "Feed ${show.title}: ${episodes.size} episodes")
        return OpenPodcastFeed(show, episodes)
    }

    private const val ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"

    /** Direct children only, so an item's <title> is never taken for the channel's. */
    private fun Element.children(
        ns: String?,
        local: String,
    ): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType != Node.ELEMENT_NODE) continue
            val name = n.localName ?: n.nodeName.substringAfter(':')
            if (name != local) continue
            val matches = if (ns == null) n.namespaceURI.isNullOrEmpty() else n.namespaceURI == ns || n.nodeName.startsWith("itunes:")
            if (matches) out += n as Element
        }
        return out
    }

    private fun Element.child(
        ns: String?,
        local: String,
    ): Element? = children(ns, local).firstOrNull()

    private fun Element.text(): String? = textContent?.trim()?.takeIf { it.isNotEmpty() }

    private val RFC822 =
        listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm Z",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )

    internal fun parseRfc822(value: String): Long? {
        for (pattern in RFC822) {
            val parsed = runCatching { SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }.parse(value.trim()) }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return null
    }

    /** itunes:duration is seconds, MM:SS or HH:MM:SS. */
    internal fun parseDuration(value: String): Long? {
        val parts = value.trim().split(':').map { it.trim().substringBefore('.').toLongOrNull() ?: return null }
        return when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }?.takeIf { it > 0 }
    }
}

internal fun String.stripHtml(): String =
    replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

/** The shows the listener follows, kept on this device only. */
class OpenPodcastSubscriptions(
    private val dataStoreManager: DataStoreManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val shows: Flow<List<OpenPodcastShow>> =
        dataStoreManager.getString(KEY).map { decode(it) }

    suspend fun isSubscribed(feedUrl: String): Boolean = current().any { it.feedUrl == feedUrl }

    suspend fun toggle(show: OpenPodcastShow): Boolean {
        val list = current()
        val subscribed = list.any { it.feedUrl == show.feedUrl }
        val next = if (subscribed) list.filterNot { it.feedUrl == show.feedUrl } else listOf(show) + list
        dataStoreManager.putString(KEY, json.encodeToString(LIST, next))
        return !subscribed
    }

    private suspend fun current(): List<OpenPodcastShow> = decode(dataStoreManager.getString(KEY).first())

    private fun decode(raw: String?): List<OpenPodcastShow> =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(LIST, it) }.getOrNull() }
            ?: emptyList()

    private companion object {
        const val KEY = "open_podcast_subscriptions"
        val LIST = ListSerializer(OpenPodcastShow.serializer())
    }
}
