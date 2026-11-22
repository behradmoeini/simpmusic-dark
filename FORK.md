# SimpMusic Dark (personal fork)

A private fork of [SimpMusic](https://github.com/maxrave-dev/SimpMusic) v2.5.0 (GPL-3.0) with a
dark streaming-app look and a Podcasts view on Home. The YouTube Music code is upstream's, unchanged.

## What this fork changes

| Area | Change |
| --- | --- |
| Theme | Near-black layers, white titles, grey secondary text, saturated accent (default mint `#2EE6A6`; Settings > custom theme color changes it) |
| Bottom bar | Full-width Home / Search / Your Library row that fades into black (liquid glass setting still brings back the capsule bar) |
| Mini player | Card tinted from the artwork, square cover, plain heart and play icons, thin progress line |
| Chips | Filled grey pills, accent fill when selected |
| Home | No YouTube Music podcast cards or podcast chart; a "Same vibe, new artists" mix under the first shelf |
| Podcasts | Home's Podcasts chip reads open sources only: fyyd.de for search and trending, each show's RSS feed for episodes, played by the music player |
| Search | Large title and a white search field |
| Library | "Your Library" title, + button for a new playlist, Playlists / Podcasts / Downloaded first |

Files touched are under `composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/` (theme, `ui/component`,
`ui/screen`, `viewModel/HomeViewModel.kt`), plus `androidApp/build.gradle.kts` for signing.

## Android TV

The same APK installs on Android TV: the manifest declares a `LEANBACK_LAUNCHER` entry and a home
screen tile (`androidApp/src/main/res/drawable-xhdpi/tv_banner.png`), and marks touchscreen as not
required. The wide layout (navigation rail) is what a TV shows. A 32-bit TV such as the Google TV
Streamer needs the `universal` APK; the forced update already picks it there.

## Podcasts from open sources

YouTube Music's podcasts are gone from Home: every card YouTube marks as a podcast (an episode's
`MUSIC_VIDEO_TYPE_PODCAST_*`, an `MPSP…` show or `MPED…` episode page) is filtered out, shelves
left mostly empty (half or more removed) are dropped, and the chart's podcast shelf is hidden
(`utils/YouTubePodcastFilter.kt`). Two catches go by shape, since YouTube does not label them:
episode rows sent as multi-row list items, and any video (not a song) of 20 minutes or more, which
is how video podcasts and interview clips arrive. The second also hides long DJ sets and concerts
from Home.

The Podcasts chip lists shows from [fyyd](https://fyyd.de) (open directory, keyless API: search and
the "hot" list) and reads episodes straight from the show's RSS feed, skipping video enclosures.
Followed shows are stored on the device (DataStore key `open_podcast_subscriptions`).

Episodes play through the music player itself (`podcast/OpenPodcastPlayer.kt`): the track id is
`DirectAudio.idFor(audioUrl)` (`"podcast:"` + base64url of the file URL, defined in core), which core
resolves straight to that URL and for which it skips everything YouTube-only (radio, SponsorBlock,
formats, watch-time pings, scrobbling, listening analytics; the app also skips lyrics and canvas).
So an episode gets the normal mini player, Now Playing, notification and lock-screen controls, on
Android and Desktop. The app remembers where each episode was left (DataStore key
`open_podcast_positions`) and starts it there.

Not covered: YouTube podcast results in Search and YouTube podcasts already saved in Library still
open YouTube pages, and a YouTube radio can still slip in an episode (the queue code is in `core`).

## "Same vibe, new artists"

A daily mix on Home (`recommendation/DiscoverMix.kt` for the algorithm, `DiscoverMixLoader.kt` for
the I/O), built from this device's listening history, so it needs **local tracking** on:

1. Seeds: about five artists sampled from the last 30 days' top 15, weighted by the square root of
   plays (not just the top few), plus one "older favourite" not played in the last 30 days.
2. Candidates: each seed's related artists on YouTube Music ("Fans might also like").
3. Novelty: anyone in the play history, followed, or behind a liked song is dropped.
4. Score: closer to the top of a seed's list counts more, and an artist named by several seeds
   (between two things you like) counts most.
5. Diversity: greedy pick of 12 artists with a penalty each time a seed has already supplied one.
6. Two of each artist's top six songs, not always the biggest hit, skipping songs you have played;
   interleaved so no artist plays twice in a row. 24 tracks at most.

The mix is fixed for the day and cached; the refresh button builds the next variant.

## Home shelves from the play history and Deezer

Home has five shelves modelled on Spotify's, built from the local play history and Deezer's
public API (no key; `recommendation/DeezerCatalog.kt`):

- **Like what you play** (`SimilarTracksLoader`): unplayed songs by artists Deezer relates to the
  listener's own. Seeds are sampled by plays, an artist named by several seeds ranks higher,
  artists already played are discounted, two songs per artist.
- **Your top mixes**: one card per top artist; a tap plays that artist's Deezer station.
- **More like <artist>**: the top song of each artist related to one the listener plays.
- **New releases for you**: releases of the last 120 days by the listener's top artists.
- **Albums featuring songs you like**: the albums behind the most played songs (local only).

The last four come from `HomeShelvesLoader`. Both loaders build once a day and cache in
DataStore (`similar_mix_cache`, `home_shelves_cache`). Deezer only supplies names and covers:
a card is matched on YouTube Music when it is tapped (`YouTubeMatcher`), choosing among the
first five results the one whose title and artist agree, else the first result.

## Library charts: countries and Persian

Your library shows the SimpMusic chart playlists (one "Trending Weekly" per country, from the chart
server). Two changes on top:

- **Settings → Content → Library charts** picks which countries show. Empty means all. The choice is
  stored in DataStore key `library_chart_countries` and filtered client-side
  (`utils/LibraryCharts.kt`).
- **Trending Weekly - Persian** is built on the phone: `PlaylistRepositoryImpl.persianTrendingChart()`
  runs YouTube Music's playlist search for "Persian trending songs" and uses the top result. It still
  shows when the chart server is down. It is not verified against live results from the build
  environment, which can only reach GitHub.

## Forced updates

Every time the app comes to the foreground it asks GitHub for the `latest` release
(`api.github.com/repos/behradmoeini/simpmusic-dark/releases/tags/latest`). CI bakes the commit it
built from into the APK (`BuildConfig.FORK_UPDATE_COMMIT`, from the `FORK_UPDATE_COMMIT` env var,
set only for builds of `main`). When the release targets another commit, the whole app is replaced
by an "Update required" screen: Update downloads the arm64-v8a APK (universal on other devices),
asks once for "install unknown apps", and hands it to the system installer. The same signing key
makes it install over the old app.

- **The repo must be public.** A phone has no GitHub login; a private repo answers 404 and the
  check silently does nothing.
- Local builds (`build-apk.ps1`) and branch builds carry no commit and are never forced.
- Offline (GitHub unreachable), the app opens normally. A newer build seen earlier is remembered,
  and the update is asked for again on the next launch that reaches GitHub.
- While CI is publishing, the release is briefly deleted; a check in that window does nothing.
- Code: `composeApp/src/androidMain/.../update/ForkUpdater.kt` (check, download, install) and
  `expect/ForkUpdate.android.kt` (the screen), hooked into `App.kt` right after the unofficial-build
  check. Upstream's own update check is no longer started from `MainActivity`.

## Building

- **CI**: every push to `main` runs `.github/workflows/build-apk.yml` and replaces the `latest`
  pre-release with fresh APKs (`arm64-v8a` for most phones, `universal` for anything). It can also be
  started by hand from the Actions tab.
  Permanent download links (always the newest build):
  `https://github.com/behradmoeini/simpmusic-dark/releases/download/latest/simpmusic-dark-arm64-v8a.apk`
  and `.../simpmusic-dark-universal.apk`.
- **Local (Windows)**: `.\build-apk.ps1`. Needs JDK 21 at `C:\Users\bmoeini\AndroidTools\jdk-21`
  and the Android SDK; output lands in `androidApp/build/outputs/apk/debug/`.

The build is the FOSS variant (`-PisFullBuild=false`): no Sentry, no Google services.

## Signing

Local and CI builds sign with the same key, so a new APK installs over the old one and keeps the
library. The key lives outside the repo at `C:\Users\bmoeini\AndroidTools\keys\` (`simpmusic-dark.jks`
plus `simpmusic-dark.env`) and in the repo secrets `APK_KEYSTORE_BASE64`, `APK_KEYSTORE_PASSWORD`,
`APK_KEY_ALIAS`, `APK_KEY_PASSWORD`. Back up the two local files: losing the key means the next APK
cannot update the installed app without uninstalling it first.

## Pulling upstream updates

```bash
git fetch upstream --tags
git merge v2.6.0   # or whichever upstream tag
git submodule update --init
```

Upstream's own workflows were removed on purpose (one of them builds on macOS runners); check that a
merge does not bring them back.

## MCP server: music from a chat client

`mcpServer/` is a standalone program that lets Claude (or any MCP client) play YouTube Music on the
user's computer, with no SimpMusic app installed. It reuses core's `kotlinYtmusicScraper` for
search, radio (`YouTube.next`) and stream extraction (`YouTube.player`, the same tiers as the app),
picks Opus 251, then AAC 140, and plays the URL in an `mpv` process driven over its JSON IPC
(two connections: commands and events). The protocol is MCP over stdio, written by hand (JSON-RPC
2.0 with kotlinx.serialization: initialize, ping, tools/list, tools/call). Real stdout is kept for
the protocol and System.out is pointed at stderr, so core's logger cannot corrupt it.

It has to run locally: googlevideo URLs are tied to the IP that resolved them. Users need Java 21+
and mpv. CI builds `:mcpServer:distZip` and attaches `simpmusic-mcp.zip` to the `latest` release.
Setup and tools: `mcpServer/README.md`.

