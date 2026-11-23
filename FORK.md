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
| Home | The All page is built by the fork's own recommender, not by YouTube (see "The own recommender"); no YouTube Music podcast cards or podcast chart |
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

What is different on a TV (`isTelevision()`, the `leanback` system feature):

- **Sign-in is a QR code.** The YouTube sign-in screen and Settings → Devices show the login sync
  dialog the Desktop shows (`ui/component/LoginSyncHostDialog.kt`): the phone app scans it and sends
  its sign-ins over the local network. For that the host side of login sync also runs on Android
  (core: `LoginSyncServer` in loginSync's `commonMain`, `LoginSyncHostRepositoryImpl` in
  `data/src/jvmAndroidMain`, compiled by both the Android and JVM source sets).
- **Focus is a white outline** (`ui/theme/TvFocus.kt`), provided as `LocalIndication`, plus a strong
  focus tint for Material components that bring their own ripple. The phone's 4% tint is invisible
  from a sofa.
- **One bar of controls** across the top, in place of the side rail
  (`AppNavigationRail(topBar = true)`): the name, the tabs (Home, Podcasts, Search, Library, then
  Mix and Analytics) and Settings. Home's own row of avatar, chips and Create is not drawn on a
  TV: two rows of controls above the page made it unclear where the remote would go. Podcasts,
  a chip on a phone, is a tab there (`TvHomeMode`); the Mood chip has no TV counterpart.
- **A shelf is a title and a row of cards.** The recommender shelves' Play and refresh buttons
  are not drawn on a TV: Down goes shelf to shelf, Left and Right along the cards, OK plays from
  that card, holding OK opens "don't recommend this".
- **A focused card stops above the mini player** (`TvBringIntoViewSpec`'s `coveredEndPx`), so its
  title can be read.
- **No blur and no Home glow.** A system trace on the Google TV Streamer showed the app's own work
  under 3 ms a frame while the graphics chip took ~45 ms to paint Home: the glow is five see-through
  layers the width of the screen. Without it Home paints in ~4 ms. Blur is off for the whole tree
  (`LocalHazeBlurStyle`), so bars draw the opaque fallback their style names.
- **Home has a hero** (`ui/component/TvHero.kt`): under the chips, the card the remote is on is
  drawn large (cover, title, the line under it), as Spotify's TV app does. Home provides
  `LocalTvHero`; a card reports itself with `Modifier.tvHero(...)` (or `rememberCardHover`'s `hero`)
  when it takes focus, and a card that does not leaves the hero on the previous one. The hero is
  part of the header, so Home's page keeps the focused shelf the header's measured height down
  (its own `LocalBringIntoViewSpec`), while rows keep the app-wide 150dp. It has no artwork
  backdrop, for the reason the glow is gone.
- **Down from a floating header reaches the page** (`ui/component/TvKeys.kt`: `tvPage` on the page,
  `tvDownTo` on the header; Home, Search, Library). The pages scroll under their header, so they
  start level with it, and arrow-key focus search only takes a group for Down when its top edge is
  below the focused item's. A screen with a floating header and no `tvDownTo` traps the remote in
  the header.
- **The focused item sits 150dp in from its list's leading edge** (`TvBringIntoViewSpec`), clear of
  the header, with the previous card peeking in on a row.
- **One focus target per card.** Cards had `focusable(true)` above their `clickable`; the remote
  landed on the outer one, where OK does nothing. Marquee text lines use `focusableUnlessTv()`.
- **Pull-to-refresh hides itself under key input** (`ui/component/PullRefreshKeys.kt`): moving focus
  up at the top of a list counts as a pull that keys never release.

The dev build is **not debuggable** (`isDebuggable = false` in `androidApp/build.gradle.kts`).
Android runs a debuggable app in its interpreter with nothing compiled, which made every frame take
about 150 ms on the Streamer. `BuildConfig.DEBUG` follows that flag, so the code reads
`BuildConfig.DEV_BUILD` instead. To attach a debugger, flip the flag locally.

## Icon and banner

"Listen" in white Helvetica Bold on a navy gradient (`#041023` bottom-left to `#084367` top-right)
over a cyan bar (`#3DBEEC`): the look of the owner's other TV apps (Watch, شبکه نمایش خانگی in
`alphadl-tv`). The launcher icons, the adaptive icon (`drawable/ic_launcher_bg.xml` +
`mipmap-*/ic_launcher_foreground.png`) and the TV tile (`drawable-xhdpi/tv_banner.png`) are
generated; the TV top bar draws the same mark in Compose.

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

## The own recommender

YouTube's recommendations are not used: not its Home, not its song radio, not its "up next".
`recommendation/engine/` builds all three on the device. YouTube is still where a song is played
from and where a song another catalogue names is found (`YouTubeMatcher`), and the Mood chip still
opens YouTube's mood pages.

How it decides (`TasteEngine.kt`, pure and tested by `TasteEngineTest`):

1. **Taste.** Every play is a signal, not a count: heard to the end is a yes (1), skipped in the
   first third is a no (-0.6), in between counts its share, and all of it fades with age. Two
   profiles are kept, a short one (half-life 14 days) and a long one (180 days), blended 55/45, so
   a week of one album does not erase years of taste. A like never fades.
2. **The artist map.** "Listeners of A also play B" links from two independent sources: Deezer's
   related artists and ListenBrainz's similarity, which is computed from what people play in one
   sitting (it knows Persian artists; lists are shorter there). A link both name is stronger than
   either alone (noisy-OR). The map is kept in `files/recommender/artist-map.json` and grows a few
   artists per build; links are refetched after 45 days.
3. **The walk.** Candidates are not "the neighbours of the top artists". A personalized PageRank
   walk starts from the whole taste (or from a radio's song), follows links several steps out and
   keeps returning home, so an artist reached along many paths from many favourites scores high
   even when no single favourite names it. The same walk from the artists the listener skips is
   subtracted, and big stars everyone links to are damped.
4. **Taste groups.** The listener's artists are grouped by the map (label propagation). Each
   group gets a share of every shelf, weighted so a small group is heard, and a mix card of its own.
5. **Learning.** What was offered is logged (`offered.json`). A recommendation played through
   counts for its artist, a skipped one against, and each artist's chance is drawn from that record
   (Thompson sampling). A song offered and not played rests for 14 days.
6. **Order.** The same artist is kept apart; new songs are woven with songs from the history by
   artists in the same neighbourhood.
7. **Scenes.** Label propagation over the whole map groups artists whose listeners overlap, which
   in practice follows language and country more than genre (Persian pop and Persian rap sit
   together, apart from Western pop). A radio or a mix rates artists outside the scene it starts
   in at 0.15, so a Persian song's radio stays Persian without any catalogue stating a language.
8. **Hidden.** A long press on a recommended song (holding OK on a TV remote) offers "Don't
   recommend this song" and "Don't recommend <artist>" (`ui/screen/home/RecommendationActions.kt`,
   stored in `hidden.json`). A hidden artist is never picked, alone or as a guest, and counts
   against the artists near them the way a skipped artist does. The same menu lists what is hidden
   and brings it back.

A device with little history of its own (under 150 plays: a TV just signed in) also reads the
signed-in account's listening history and liked songs from YouTube Music. That is what the
listener played, not what YouTube suggests.

Where it shows:

- **Home, All page**: "Your top mixes" (one per taste group; a mix opens with up to three songs
  by the artist on its card, and a group whose artists the catalogue does not know gets no card),
  "Like what you play", "Same vibe, new artists" (artists never played), then the history shelves
  below. The All page does not request YouTube's Home at all, and YouTube's chart block (video
  charts, top artists, trending) is not on it. A listener with no history gets Deezer's chart until there is
  something to learn from. A pull to refresh deals the next set.
- **Radio**: what follows a song, "Start radio", and the continuation of any queue come from
  `RecommendationEngine.radio` (core asks it through `OwnRadio`; with no answer it falls back to
  YouTube's radio so a queue never just stops). It starts mostly from the song's artist plus the
  part of the listener's taste that is close to that artist, and later batches drift to where the
  radio has got to. Artists skipped in the last hours of the radio are held back.
- **After a playlist or album ends**: the endless queue setting is on unless switched off, so the
  queue carries on with the own radio of its last song.

Limits: the map knows artists, not individual songs' sound (no tempo or mood matching); a
recommended song is one of the artist's top songs on Deezer, found on YouTube by name, so a wrong
match is possible; the first build on a new install takes up to a minute because MusicBrainz, which
turns a name into the id ListenBrainz needs, allows one request a second.

## Home's history shelves

Under the recommender's shelves, three more built from the local play history and Deezer's public
API (no key; `recommendation/DeezerCatalog.kt`, `HomeShelvesLoader`), once a day, cached in
DataStore (`home_shelves_cache`):

- **More like <artist>**: the top song of each artist related to one the listener plays.
- **New releases for you**: releases of the last 120 days by the listener's top artists.
- **Albums featuring songs you like**: the albums behind the most played songs (local only).

Deezer only supplies names and covers: a card is matched on YouTube Music when it is tapped
(`YouTubeMatcher`), choosing among the first five results the one whose title and artist agree,
else the first result.

## Starting a song sooner

- The stream URL is no longer held back by a Tidal lookup. Upstream fetched a song's tempo and key
  from Tidal (for DJ-style crossfades) before handing the URL to the player; it now happens after.
- `recommendation/StreamPrewarmer.kt` resolves streams ahead: the first four songs of each
  recommender shelf when it loads, and on a TV the song the remote rests on for half a second. A
  resolved stream is stored for its lifetime (about six hours) and the player uses it.

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

