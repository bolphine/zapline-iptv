# Zapline IPTV

Note: This is AI generated but it's already much better than anything I could find on the market, completely open source, no premium features 

A fast, good-looking IPTV player for Android phones. It's built for big playlists: tens of thousands of channels,
with sane language/country filtering, duplicate merging and a proper TV guide.

> **Zapline is a player only.** It contains no channels, playlists, streams or provider details.
> You bring your own M3U playlist or Xtream Codes login from a provider you are entitled to use.

## Features

**Finding things**
- M3U links, local M3U files and Xtream Codes logins, including multiple saved playlists
- Language and country detection from names, groups (`UK| General`), `tvg-*` tags and flags, with your own overrides and rules
- Duplicate versions of a channel (`HD`, `FHD`, backups) merged into one row, best working version first
- Categories, groups, favourites with your own numbered line-up, and search across channels, the guide, films and series

**TV guide**
- XMLTV guide with now/next on every row and a scrolling grid
- Catch-up (Xtream archive and M3U `catchup` modes), "Start over" on live programmes, reminders with notifications

**Channel health**
- A background check reports which channels in your filters work, plus each version's resolution, codec, audio (5.1/stereo) and bitrate
- It's paced for one-connection accounts: it checks before using a connection, never runs while you watch, and only calls a channel dead after two failed checks

**Player**
- Media3/ExoPlayer: HLS and MPEG-TS, closed captions, audio and quality tracks, speed, sleep timer
- Surf mode (swipe between channels), multiview (two streams), picture-in-picture, portrait mode, rotation lock
- Vertical view with an ambient colour backdrop for films and series
- Signs in to the provider again when a stream is refused, and waits for a free connection when the account is busy
- Chromecast: live MPEG-TS is relayed through the phone as HLS so the TV can play it

**Films, series and sport**
- Film and series libraries, continue watching, and an "up next" countdown between episodes
- Football hub: today's games, which of your channels are showing them, and live stats, line-ups, key moments,
  league tables and live text

## Building

Requirements: JDK 17 and the Android SDK (API 35).

```bash
./gradlew testDebugUnitTest      # unit + Robolectric UI tests
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the debug key unless a release key is configured, either through environment
variables or a git-ignored `keystore.properties` in the project root:

```properties
ZAPLINE_KEYSTORE=/absolute/path/to/release.jks
ZAPLINE_KEYSTORE_PASSWORD=...
ZAPLINE_KEY_ALIAS=...
ZAPLINE_KEY_PASSWORD=...
```

### CI and releases

- `.github/workflows/ci.yml` runs the tests on every push and pull request.
- `.github/workflows/release.yml` publishes a release whenever `main` carries a `versionName` that has no
  release yet. To ship, bump `versionCode`/`versionName` in `app/build.gradle.kts` and push. It also runs for
  `v*` tags and from the Actions tab ("Run workflow").
  It needs these repository secrets: `ZAPLINE_KEYSTORE_BASE64` (the keystore, base64-encoded),
  `ZAPLINE_KEYSTORE_PASSWORD`, `ZAPLINE_KEY_ALIAS` and `ZAPLINE_KEY_PASSWORD`.

## Third-party data

- Football scores and stats come from ESPN's public site API. It's undocumented and may change or stop working
  at any time. Zapline only calls it for today's fixtures and for the single game you're looking at.
- The test fixtures in `app/src/test/resources` are saved responses from that API, kept for parser tests.

## Privacy

There are no accounts, analytics or tracking. Playlists, logins and settings stay on the device unless you export a backup.
Backups include your playlist logins, so keep them private.

## Licence

[GPL-3.0-or-later](LICENSE). You may use, modify and share Zapline, but anything you distribute that's based on it
must also be released under the GPL with its source code.
