# Music

An Android music player for self-hosters. It plays from your **Jellyfin** library first and fills
the gaps from **Qobuz** or **YouTube Music**. Songs Jellyfin doesn't have get their artist added
to **Lidarr**. Bitrates are set separately for Wi-Fi, cellular and **satellite** (T-Satellite and
other direct-to-cell services). Every song can be **downloaded** for offline listening, and
playlists are kept in your **Nextcloud**.

Kotlin, Jetpack Compose (Material 3), Media3/ExoPlayer, Room, WorkManager. Android 8.0+ (API 26).

## Features

### 1. Sources: Jellyfin first, then Qobuz / YouTube Music, with Lidarr

- **Search** queries every configured source in parallel. Jellyfin results appear first under
  "In your library". Qobuz and YouTube Music results appear under "Stream from…", with any song
  already in your library left out.
- **Playback always prefers Jellyfin.** Before a Qobuz or YouTube track plays, it is matched
  against your library (title + artist + duration, ignoring "Remastered", "feat." and similar
  tags), and the Jellyfin copy is used when one exists. A playlist built from YouTube therefore
  switches to your own files once they are in the library.
- **When Jellyfin doesn't have a song**, it streams from the track's own service and then from the
  other services, in the order you choose in Settings. If a service fails (not signed in, track
  removed, blocked), the next one is tried.
- **Lidarr:** when you play or download a song that isn't in Jellyfin, its artist is looked up in
  your Lidarr library. If the artist is missing, they are
  found on MusicBrainz through Lidarr and added. New artists are monitored and their albums are
  searched for by default. You can pick the root folder and the quality and metadata profiles, or
  use the server defaults. Artists are matched by exact name only, so the wrong artist is never
  added.

### 2. Bitrates per network, including satellite

Settings has a slider for each of **Wi-Fi**, **Cellular** and **Satellite**: *Original quality*,
or a cap from 320 kbps down to 64 kbps (the standard MP3 steps). A separate slider sets the
download quality.

| Source | How the cap is applied |
|---|---|
| Jellyfin | Exact: files at or under the cap play untouched; larger ones are transcoded on the server to the cap (MP3, or AAC, which sounds better at low bitrates). |
| Qobuz | Fixed tiers: MP3 320 or FLAC (CD / hi-res). Original requests the best FLAC. Any cap uses MP3 320. Qobuz can't go lower, so for caps under 320 another service that fits is preferred, and Qobuz is used only when nothing else has the song. |
| YouTube Music | Fixed tiers, roughly 48/50/70/128/160 kbps (AAC/Opus). The cap rounds down to the closest tier. |

**Satellite** follows Android's
[constrained satellite network](https://developer.android.com/develop/connectivity/satellite/constrained-networks)
guidance:

- The manifest declares `android.telephony.PROPERTY_SATELLITE_DATA_OPTIMIZED`, so Android lets the
  app use satellite data (e.g. T-Mobile T-Satellite) when no other network is available.
- The app's network request removes `NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED`. A network counts as
  satellite when it has `TRANSPORT_SATELLITE` or lacks that capability.
- On satellite the app streams at the satellite bitrate (64 kbps by default) and skips album art
  (this can be turned on). Downloads wait for another network unless you allow them. The player
  uses large buffers, so data arrives in bursts with long idle gaps, which suits satellite links.

When the network changes, upcoming songs switch to the new bitrate. The current song keeps its
stream so seeking stays consistent.

### 3. Offline listening, from every source

"Download" works on songs from Jellyfin, Qobuz and YouTube Music, on whole playlists, and from the
player. Downloads run in the background (WorkManager) at the download quality. Large files are
fetched in 4 MB chunks, and a dropped connection retries only the current chunk. Downloaded copies
always play first. When offline, search looks through your downloads.

### 4. Playlists synced to Nextcloud

You can create, rename, reorder, shuffle, download and delete playlists. Connect Nextcloud with an
**app password** (Nextcloud › Settings › Security) and each playlist is stored as a JSON file in a
folder you choose (default `Music/Playlists`). Sync runs after every change, every few hours, and
on demand. It works both ways across devices: renames and deletions carry over, and conflicts go
to the most recent edit. Each track stores its source and source id, so a playlist made on one
phone plays on another (and switches to Jellyfin copies where they exist).

```json
{
  "format": "music-app-playlist",
  "version": 1,
  "id": "4b7d0f0e-…",
  "name": "Road Trip",
  "createdAt": 1761700000000,
  "updatedAt": 1761780000000,
  "tracks": [
    { "source": "JELLYFIN", "sourceId": "a1b2c3…", "title": "Get Lucky", "artist": "Daft Punk",
      "album": "Random Access Memories", "durationMs": 369000 },
    { "source": "YOUTUBE_MUSIC", "sourceId": "5NV6Rdv1a3I", "title": "Get Lucky", "artist": "Daft Punk" }
  ]
}
```

## Setting up

Open **Settings** in the app:

- **Jellyfin**: server URL, username and password. The password is used once to get an access
  token and is not stored.
- **Qobuz**: Qobuz's API is only open to registered applications, so you need an **app ID and app
  secret** issued to you. Then sign in with your e-mail/username and password, or paste a user
  auth token. Full-length streaming needs a paid subscription.
- **YouTube Music**: on by default, no account needed.
- **Lidarr**: server URL and API key (Lidarr › Settings › General).
- **Nextcloud**: server URL, username, app password and folder.

Plain-HTTP server URLs are allowed, because home servers often run without TLS on the LAN.

## Building

Needs JDK 17+ and the Android SDK (platform 36).

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests + Robolectric UI smoke test
./gradlew lintDebug
```

## Project layout

```
app/src/main/java/com/music/player/
├── sources/        Jellyfin, Qobuz, YouTube Music clients; TrackMatcher; SourceSelector (source rules)
├── quality/        StreamQuality and the round-down tier selection
├── net/            NetworkMonitor (Wi-Fi / cellular / satellite), OkHttp helpers
├── lidarr/         Lidarr API client and the auto-add-artist service
├── playback/       Media3 PlaybackService, StreamResolver (download → Jellyfin → fallbacks), PlayerController
├── downloads/      DownloadRepository, DownloadWorker, chunked downloader
├── nextcloud/      WebDAV client and two-way playlist sync
├── playlists/      Playlist repository
├── data/           Room database
├── settings/       DataStore-backed settings
└── ui/             Compose screens: search, playlists, downloads, settings, now playing
```

The queue holds `music://track?d=…` URIs. The real stream URL is chosen only when ExoPlayer opens
a song (`ResolvingDataSource`), so each song uses the network and settings in effect at that
moment.

## Notes and limitations

- **YouTube Music** uses [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor). When
  YouTube changes its site, update the `newpipeExtractor` version in `gradle/libs.versions.toml`.
  NewPipeExtractor is **GPL-3.0**, so distributing builds that include it puts the app under
  GPL-3.0 terms.
- Credentials are stored in app-private storage and are excluded from backups and device
  transfers. Use a Nextcloud app password and a Jellyfin account dedicated to the app if that
  matters to you.
- Seeking within a song Jellyfin is transcoding is estimated from the constant bitrate. A seek far
  ahead may wait while the server catches up.
- Satellite data needs a carrier plan that supports it and Android 15 or later. On older Android
  versions the app treats every network as Wi-Fi or cellular.
