package com.maxrave.simpmusic.podcast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OpenPodcastFeedTest {
    private val rss =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>Show &amp; Tell</title>
            <itunes:author>Some Host</itunes:author>
            <itunes:image href="https://example.com/show.jpg"/>
            <description><![CDATA[<p>About the <b>show</b></p>]]></description>
            <item>
              <title>Older</title>
              <guid>ep-1</guid>
              <pubDate>Mon, 02 Mar 2026 08:00:00 +0000</pubDate>
              <itunes:duration>45:30</itunes:duration>
              <enclosure url="https://example.com/1.mp3" type="audio/mpeg" length="1"/>
            </item>
            <item>
              <title>Newer</title>
              <guid>ep-2</guid>
              <pubDate>Mon, 09 Mar 2026 08:00:00 GMT</pubDate>
              <itunes:duration>3725</itunes:duration>
              <itunes:image href="https://example.com/ep2.jpg"/>
              <enclosure url="https://example.com/2.mp3" type="audio/mpeg" length="1"/>
            </item>
            <item>
              <title>Video only</title>
              <enclosure url="https://example.com/3.mp4" type="video/mp4" length="1"/>
            </item>
            <item><title>No audio</title></item>
          </channel>
        </rss>
        """.trimIndent()

    @Test
    fun parsesShowAndAudioEpisodesNewestFirst() {
        val feed = OpenPodcastDirectory.parseFeed("https://example.com/feed", rss.toByteArray(), null)
        assertEquals("Show & Tell", feed.show.title)
        assertEquals("Some Host", feed.show.author)
        assertEquals("https://example.com/show.jpg", feed.show.imageUrl)
        assertEquals("About the show", feed.show.description)
        assertEquals(listOf("Newer", "Older"), feed.episodes.map { it.title })
        val newer = feed.episodes.first()
        assertEquals("ep-2", newer.id)
        assertEquals(3725L, newer.durationSeconds)
        assertEquals("https://example.com/ep2.jpg", newer.imageUrl)
        assertEquals(45L * 60 + 30, feed.episodes[1].durationSeconds)
        assertEquals("https://example.com/show.jpg", feed.episodes[1].imageUrl)
        assertNotNull(newer.pubDateMillis)
    }

    @Test
    fun durations() {
        assertEquals(90L, OpenPodcastDirectory.parseDuration("90"))
        assertEquals(90L, OpenPodcastDirectory.parseDuration("1:30"))
        assertEquals(3661L, OpenPodcastDirectory.parseDuration("01:01:01"))
        assertEquals(null, OpenPodcastDirectory.parseDuration("soon"))
    }
}
