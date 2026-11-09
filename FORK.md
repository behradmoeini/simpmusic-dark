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
| Podcasts | Home's Podcasts chip reads open sources only: fyyd.de for search and trending, each show's RSS feed for episodes, played by a separate podcast player |
| Search | Large title and a white search field |
| Library | "Your Library" title, + button for a new playlist, Playlists / Podcasts / Downloaded first |

Files touched are under `composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/` (theme, `ui/component`,
`ui/screen`, `viewModel/HomeViewModel.kt`), plus `androidApp/build.gradle.kts` for signing.

## Podcasts from open sources

YouTube Music's podcasts are gone from Home: every card YouTube marks as a podcast (an episode's
`MUSIC_VIDEO_TYPE_PODCAST_*`, an `MPSP…` show or `MPED…` episode page) is filtered out, shelves
left empty are dropped, and the chart's podcast shelf is hidden (`utils/YouTubePodcastFilter.kt`).

The Podcasts chip lists shows from [fyyd](https://fyyd.de) (open directory, keyless API: search and
the "hot" list) and reads episodes straight from the show's RSS feed, skipping video enclosures.
Followed shows are stored on the device (DataStore key `open_podcast_subscriptions`).

Playback is a second Media3 session (`podcast/OpenPodcastPlayer.android.kt`,
`OpenPodcastPlaybackService`), because the music player resolves every track as a YouTube video id
and that code lives in the upstream `core` submodule. It has its own notification and lock-screen
controls, −15 s / +30 s, remembers where each episode was left, pauses the music when an episode
starts and is paused when music starts. Android only; Desktop lists shows but cannot play them.

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
- Offline, the app opens normally unless an earlier launch already saw a newer build: that is
  remembered and still blocks until the update is installed.
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
