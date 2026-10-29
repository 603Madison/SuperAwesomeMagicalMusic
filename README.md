# Music

An Android music player for self-hosters. It plays from your **Jellyfin** library first and fills
the gaps from **Qobuz** or **YouTube Music**. It also plays **SiriusXM** live radio, and songs you
hear there can be saved to a playlist. Songs Jellyfin doesn't have get their artist added to
**Lidarr**, queued until your Lidarr server is reachable. Bitrates are set separately for Wi-Fi,
cellular and **satellite** (T-Satellite and other direct-to-cell services). Every song can be
**downloaded** for offline listening. Playlists belong to your **authentik** account and are kept
in your **Nextcloud**. Works in **Android Auto**.

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
- **Lidarr:** when you play, download or save a song that isn't in Jellyfin, its artist is looked
  up in your Lidarr library. If the artist is missing, they are found on MusicBrainz through Lidarr
  and added. New artists are monitored and their albums are searched for by default. You can pick
  the root folder and the quality and metadata profiles, or use the server defaults. Artists are
  matched by exact name only, so the wrong artist is never added.
- **Lidarr queue:** Lidarr usually only answers on your home network, so requests are saved and
  sent when the server can be reached: right away if it can, otherwise when the network changes
  (e.g. you get home), every 30 minutes, or when you tap "Send now". Settings › Lidarr lists what's
  waiting.

### SiriusXM

- Sign in with your SiriusXM account and the **Radio** tab lists your channels. Live channels
  play like anything else, including over satellite: the stream's bitrate rounds down to your
  cap for the current network.
- While a channel plays, the app shows the song you're actually hearing (the live edge runs ahead
  of the audio). It appears in the player, the notification and Android Auto.
- **Save** adds that song to a playlist using the usual rules: the Jellyfin copy if your library
  has it. Otherwise the artist goes to Lidarr and the song is saved as a SiriusXM entry, which
  plays from Qobuz or YouTube Music until Lidarr has brought the album into Jellyfin.
- SiriusXM has no public API. The app uses the private API of SiriusXM's web player, following
  the open-source [sxm-player](https://github.com/yob15662/sxm-player) /
  [omarchy-siriusxm](https://github.com/onesastr/omarchy-siriusxm) clients. SiriusXM can change or
  block it at any time.

### Android Auto

The app appears as a media source in Android Auto. You can browse **Playlists**, **Downloads**
and **SiriusXM** channels by category, search, and ask the Assistant to "play … on Music": a
SiriusXM channel name plays that channel, anything else searches Jellyfin first. The
**Save song** (heart) button adds the SiriusXM song you're hearing to a "Heard on SiriusXM"
playlist (or, for other music, the current song to "Saved songs").

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

### 4. Playlists synced to Nextcloud, per authentik user

When you sign in with **authentik**, playlists belong to your authentik user id (the OIDC `sub`):
only your playlists are shown, and they sync to their own Nextcloud subfolder named after that id.
Playlists made before signing in become yours the first time you sign in.

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
- **SiriusXM**: your SiriusXM e-mail and password. The password is kept, so the app can sign in
  again when SiriusXM's session expires.
- **Lidarr**: server URL and API key (Lidarr › Settings › General).
- **Nextcloud**: server URL, username, app password and folder.
- **authentik**: in authentik, create an **OAuth2/OpenID provider** with client type **Public**
  and redirect URI `com.music.player:/oauth2redirect`, then an application that uses it. In the
  app, enter the issuer URL (`https://<authentik>/application/o/<application slug>/`) and the
  client ID, then tap **Sign in with authentik**. authentik must be reachable over HTTPS.

Plain-HTTP server URLs are allowed, because home servers often run without TLS on the LAN.

## Installing

Download `Music-vX.Y.Z.apk` from the repository's **Releases** page and open it on your phone
(allow installing from your browser or file manager when Android asks). Every release is signed
with the same key, so new versions install over old ones and keep your settings and downloads.

## Building

Needs JDK 17+ and the Android SDK (platform 36).

Pushing a `v*` tag runs `.github/workflows/release.yml`, which tests, builds and publishes the
signed APK as a GitHub release. It needs two repository secrets: `RELEASE_KEYSTORE_BASE64` (the
release keystore, base64-encoded) and `RELEASE_KEYSTORE_PASSWORD`.

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests + Robolectric UI smoke test
./gradlew lintDebug
```

## Project layout

```
app/src/main/java/com/music/player/
├── sources/        Jellyfin, Qobuz, YouTube Music, SiriusXM clients; TrackMatcher; SourceSelector (source rules)
├── auth/           authentik sign-in (OpenID Connect via AppAuth)
├── quality/        StreamQuality and the round-down tier selection
├── net/            NetworkMonitor (Wi-Fi / cellular / satellite), OkHttp helpers
├── lidarr/         Lidarr API client, auto-add-artist service and its request queue
├── playback/       Media3 PlaybackService + Android Auto library, StreamResolver (download → Jellyfin → fallbacks)
├── downloads/      DownloadRepository, DownloadWorker, chunked downloader
├── nextcloud/      WebDAV client and two-way playlist sync
├── playlists/      Playlist repository
├── data/           Room database
├── settings/       DataStore-backed settings
└── ui/             Compose screens: search, playlists, radio, downloads, settings, now playing
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
