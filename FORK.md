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
| Home | Podcasts chip: saved shows plus the weekly top shows (US chart, loaded without changing the Home chart region) |
| Search | Large title and a white search field |
| Library | "Your Library" title, + button for a new playlist, Playlists / Podcasts / Downloaded first |

Files touched are under `composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/` (theme, `ui/component`,
`ui/screen`, `viewModel/HomeViewModel.kt`), plus `androidApp/build.gradle.kts` for signing.

## Building

- **CI**: every push to `main` runs `.github/workflows/build-apk.yml` and replaces the `latest`
  pre-release with fresh APKs (`arm64-v8a` for most phones, `universal` for anything). It can also be
  started by hand from the Actions tab.
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
