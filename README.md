# SuperAwesomeMagicalMusic

<p align="center">
  <a href="https://fdroid.link/#https://603madison.github.io/SuperAwesomeMagicalMusic/repo?fingerprint=ED2C0523BC9120F63952EAE882FA4C0FBD3153967130C0A4ADF1AF98D07B5283"><img alt="Add to F-Droid" src="https://img.shields.io/badge/Add_to-F--Droid-1976D2?style=for-the-badge&amp;logo=fdroid&amp;logoColor=white" height="40"></a>
</p>

Your own music service, for self-hosters. A server you run at home brings together your **Jellyfin**
or **Subsonic** library (Navidrome, Gonic, Airsonic…), **YouTube Music**, **SiriusXM** (live
channels, Xtra and Pandora stations), internet and **local FM/HD radio**, and plays them all in one
place: a web player, apps for Android phones, flip phones and the Linux desktop, **Android Auto**,
and your **Sonos**, **MusicCast** and **Music Assistant** speakers. Your library comes first;
anything it doesn't have plays from a service, and **Lidarr** adds its artist so it soon will.

It also makes **radio stations for you** from what you play (with a DJ), recognises songs
playing around you (**Listen**, and **Now Playing** in the background), sums up your year
(**Wrapped**), keeps playlists and history per person, and is careful with data: bitrates are set
separately for Wi-Fi, mobile data and **satellite**, and anything can be downloaded for offline.

## What's here

| | What it is | Get it |
| --- | --- | --- |
| **The server** (`server/`, web player in `web/`) | Your own streaming service in Docker or Podman: accounts (local or single sign-on such as Authentik), sources shared by the admins or added by each person, playlists and history per person, personal radio stations, Listen, Wrapped, a web player, and speakers as places to play. See [server/README.md](server/README.md). | `ghcr.io/603madison/superawesomemagicalmusic` |
| **SuperAwesomeMagicalMusic** (`connect/`) | The Android app for the server: your library, radio and playlists; downloads; Android Auto; your devices working together; MusicCast and Sonos speakers at home; Listen and Now Playing; Wrapped. | `SuperAwesomeMagicalMusic-vX.Y.Z.apk`, or F-Droid |
| **SuperAwesomeMagicalMusic Mini** (`mini/`) | The server's app for Android flip phones, made for the keypad, signed in with a short code instead of a password. | `SuperAwesomeMagicalMusic-Mini-vX.Y.Z.apk`, or F-Droid |
| **SuperAwesomeMagicalMusic for Linux** (`desktop/`) | A native desktop app for the server (GTK 4, libadwaita, GStreamer) for Ubuntu 24.04+ and Fedora 40+. See [desktop/README.md](desktop/README.md). | `…-Desktop-vX.Y.Z-amd64.deb` / `-x86_64.rpm` |
| **SuperAwesomeMagicalMusic Standalone** (`app/`) | The original Android app that works on its own, with no server: it talks to Jellyfin, YouTube Music, SiriusXM, Lidarr and Nextcloud itself. (Called Lite before 1.30.1.) | `SuperAwesomeMagicalMusic-Standalone-vX.Y.Z.apk`, or F-Droid |
| **The tuner** (`tuner/`) | Local FM and HD Radio for the server, like an HDHomeRun for the radio: RTL-SDR dongles on a Raspberry Pi, in a container. See [tuner/README.md](tuner/README.md). | `ghcr.io/603madison/superawesomemagicalmusic-tuner` |
| `core/`, `nowplaying/` | The music services as plain Kotlin, shared by the apps and the server; Now Playing as an Android library another app can host. | |

The apps install side by side. Kotlin throughout (Ktor on the server; Jetpack Compose, Media3 and
Room on Android, Android 8.0+), React for the web player, Rust for the desktop app.

## Getting started

1. **Run the server:** `docker run -p 8080:8080 -v samm-data:/data ghcr.io/603madison/superawesomemagicalmusic`
   (or Podman, or Compose; behind a reverse proxy for HTTPS). [server/README.md](server/README.md)
   has the details, single sign-on included.
2. **Add your sources** as an admin, in the web player's **Admin › Shared sources › Add a shared
   source**: your library (**Jellyfin** or **Subsonic**), music services and radio (YouTube Music,
   SiriusXM, Radio, the FM tuner), places to play (**Sonos**, **Music Assistant**) and helpers
   (Lidarr, AudD). Admins can also let people add their own (**Admin › Settings**), which then
   appear under each person's **Settings › Sources**.
3. **Get the apps** (see [Installing](#installing)) and sign in with the server's address.

## In action

The web player's Now Playing page: the cover's colours fill the window, and the lyrics follow the
song (the line being sung is big and bright; click any line to jump there).

![Now Playing in the web player, with the cover beside lyrics that follow the song](docs/screenshots/web-now-playing-lyrics.jpg)

| The Song view (cover and details, no scrolling) | One search box for artists, albums and songs |
| --- | --- |
| ![Now Playing's Song view](docs/screenshots/web-now-playing.jpg) | ![Search with a top result, songs and albums](docs/screenshots/web-search.jpg) |
| **An artist's page** | **The web player on a phone** |
| ![An artist's page with their popular songs](docs/screenshots/web-artist.jpg) | <img src="docs/screenshots/web-phone.jpg" alt="Now Playing on a phone, with its own controls" width="300"> |

The SuperAwesomeMagicalMusic app (for the server), in the same look as the other apps (Lexend,
rounded cards over a soft gradient, light and dark): an artist's page, controlling the web player
from the phone (devices work together, like Spotify Connect, and MusicCast and Sonos speakers join them),
favourite stations at the top of Radio, and a playlist in the dark theme.

<p>
  <img src="docs/screenshots/app-artist.png" alt="The app's artist page" width="200">
  <img src="docs/screenshots/app-devices.png" alt="The app controlling the web player, with the devices to play on" width="200">
  <img src="docs/screenshots/app-radio.png" alt="Favourite stations at the top of the app's Radio tab" width="200">
  <img src="docs/screenshots/app-playlist-dark.png" alt="A playlist in the dark theme" width="200">
</p>

The screenshots use a made-up demo library (Velvet Satellites aren't a real band, lol), so the covers
are generated.

## Features

### Sources: your library first

- **Your library: Jellyfin or Subsonic.** Jellyfin, or any server that speaks the Subsonic API
  (Navidrome, Gonic, Airsonic(-Advanced), Ampache, LMS, Nextcloud Music, Funkwhale…), added by an
  admin like any source. Subsonic servers sign in with a username and password (only a salted
  token is sent, or the password itself for servers that can't check tokens, such as LDAP
  accounts) or an API key (OpenSubsonic); their playlists can be imported (**Playlists › Import ›
  From your library**). Libraries are searched first, played from first, learned by Listen and
  counted in Wrapped.
- **Search** works like a streaming app's: every configured source is asked at once and the
  answers become one set of results, with the likeliest match on top (an artist, an album or a
  song), then songs, artists and albums. The same song, artist or album found on several sources
  is shown once (your library's copy when it has one), and which service it comes from is left
  out. Artists open on their best-known songs, albums and singles; albums on their songs in
  order, taken from your library when it has the whole album. A song's ⋮ menu goes to its artist
  or album.
- **Playback always prefers your library.** Before a YouTube track plays, it is matched
  against your library (title + artist + duration, ignoring "Remastered", "feat." and similar
  tags), and your library's copy is used when there is one. A playlist built from YouTube therefore
  switches to your own files once they are in the library.
- **When your library doesn't have a song**, it streams from YouTube Music, at the best quality that
  fits your bitrate cap.
- **Better networks, better copies:** when you move to a better network (e.g. satellite to Wi-Fi),
  the song playing switches to a better copy where it left off, and songs you replay later are
  picked again for the network you're on.
- **Lidarr:** when you play, download or save a song that isn't in your library, its artist is looked
  up in your Lidarr library. If the artist is missing, they are found on MusicBrainz through Lidarr
  and added. New artists are monitored and their albums are searched for by default. You can pick
  the root folder and the quality and metadata profiles, or use the server defaults. Artists are
  matched by exact name only, so the wrong artist is never added.
- **Lidarr queue** (in the Standalone app, which talks to Lidarr itself): Lidarr usually only answers on your home network, so requests are saved and
  sent when the server can be reached: right away if it can, otherwise when the network changes
  (e.g. you get home), every 30 minutes, or when you tap "Send now". Settings › Lidarr lists what's
  waiting.

### Devices and speakers

- **Your devices work together**, like Spotify Connect: every signed-in web player, phone and
  desktop app sees the others, shows what they're playing, controls them and moves the music
  between them, carrying on from the same moment.
- **Sonos**, two ways:
  - **On the server:** an admin adds **Sonos** (Admin › Shared sources). The server finds the
    speakers on the network, or you give it one speaker's address. They join everyone's devices, and
    the server plays the queue on them.
  - **From the phone app:** at home, the app finds Sonos speakers on your Wi-Fi, the way it finds
    MusicCast ones, or you add one by address in **Settings › Speakers**. The phone plays your queue
    through them.
  - Either way, rooms grouped together play as one ("Living Room + Kitchen", with the group's
    volume), the Sonos app shows the song, artist and cover, and radio and your own stations play
    too. A Sonos the server already plays on isn't shown twice.
- **Yamaha MusicCast** speakers join from the phone app at home, the same way (**Settings ›
  Speakers**).
- **Music Assistant** (Home Assistant's music server): add it on the server with its address and a
  token, and its players (Sonos, Chromecast, AirPlay, Squeezebox, DLNA, Home Assistant's media
  players) become places to play.
- Speakers fetch each song from the server at an address made for that song alone: signed,
  short-lived, and opening nothing else, so they never need your sign-in. SiriusXM's live channels
  don't play on speakers yet.

### SiriusXM

- Sign in with your SiriusXM account and the **Radio** tab lists your channels. Live channels
  play like anything else, including over satellite: the stream's bitrate rounds down to your
  cap for the current network.
- While a channel plays, the app shows the song you're actually hearing (the live edge runs ahead
  of the audio). It appears in the player, the notification and Android Auto.
- **Xtra channels** arrive one track at a time. The app stitches them into one continuous stream:
  shortly before a track ends it tunes the channel again, which hands out the next track, and
  appends it, so playback carries on. It also reports the track being heard to SiriusXM, as
  SiriusXM's own players do.
- **Bookmark** channels with the star; bookmarked channels are listed first in the app and in
  Android Auto.
- **Save** adds that song to a playlist using the usual rules: the Jellyfin copy if your library
  has it. Otherwise the artist goes to Lidarr and the song is saved as a SiriusXM entry, which
  plays from YouTube Music until Lidarr has brought the album into Jellyfin.
- SiriusXM has no public API. The app uses the private API of SiriusXM's web player, following
  the open-source [sxm-player](https://github.com/yob15662/sxm-player) /
  [omarchy-siriusxm](https://github.com/onesastr/omarchy-siriusxm) clients. SiriusXM can change or
  block it at any time. If a channel misbehaves, Settings › SiriusXM › **Copy diagnostics**
  copies what SiriusXM sent, for troubleshooting.

### Pandora artist stations (on SiriusXM)

SiriusXM includes Pandora artist stations: music by and like one artist. Tap **Artist station** on
a song playing on SiriusXM to start one for that artist, or **New station** on the Radio tab to
search for an artist. Stations are saved on the Radio tab and in Android Auto (SiriusXM › Pandora
artist stations). Each track is its own item in the queue, with more fetched as you near the end.
**Thumbs up / down** (in the player and Android Auto) tell the station what you like, as in
SiriusXM's app; thumbs down also skips the song. SiriusXM sends station tracks as single
64 kbps AAC files (HE-AAC, the decoder usually finds), and as far as its web player shows, there's
no higher-quality version to ask for.
SiriusXM's search request isn't documented anywhere public, so artist search is a best guess;
starting a station from a song you're hearing doesn't depend on it.

### Local radio

The Radio tab's **Radio stations** section plays local and internet radio from
[Radio Browser](https://www.radio-browser.info/), a free, open directory. **Find stations near
me** asks once for your approximate (city-level) location, which is sent only to Radio Browser's
search and not stored; stations within 80 km are listed, most listened to first. You can also
search by name, and star favourites. Favourites and the stations found near you are listed in
Android Auto under **Radio**. Next and previous move between stations in the list you played from.
With **the tuner** (`tuner/`), your local FM and HD Radio stations join them, with what's on from
the broadcast (and HD Radio's logos and album art); a station that's also online plays from the
tuner while it comes in clearly and from the internet while it's static.

### Made for you: your own radio stations

The Radio tab starts with three stations the server makes for each person from what they play,
keep in playlists and give a thumbs up (or down):

- **[Your name]'s Radio**: your favourites now, the ones you loved a while back, songs from your
  playlists, and some you haven't heard yet from artists you love.
- **[Your name]'s Rewind**: the songs you couldn't stop playing, months ago, back on the air.
- **[Your name]'s Discovery**: new to you, from the artists you play most.

They're stations, not playlists: each is one broadcast from the server, on the air while you
listen (and off, fetching nothing, 20 seconds after you stop), and you tune in partway through a
song. Each song fades out before the next starts, at an even loudness, with a programmer's clock behind them (no artist twice in a row, no song again
for hours), and a DJ who knows your listening: back-announcing, saying what's coming up,
introducing a song you've had on repeat or haven't played since spring, and giving the time on
the hour. The web player and the phone app speak its lines over the start of songs, dipping the
music under its voice (Settings › **The DJ talks** turns that off); the desktop app shows them.
Skip moves the station on, a thumbs up plays a song more, a thumbs down never again. They play in
Android Auto too, at the top of **Radio**.

### Listen: what's playing around you

Like Shazam: tap **Listen** (the web player's sidebar or its search box, the phone app's Search
tab, or the desktop app's sidebar), hold your device near the music, and a few seconds later it
says what song it is, with **Play**, and **Play from here** to carry on from the moment it's at
in the room. Everything it recognises is kept under **Recently heard**.

- **Your library first, on your server.** The server learns every song in your Jellyfin
  and Subsonic libraries in the background (their audio fingerprints, the technique Shazam uses), so it can
  recognise them from a few seconds through a phone's microphone, noise and all, without anything
  leaving the server. It takes a few seconds a song, and about 15 MB of space for every 100 songs
  (in `listen.db`, which can always be made again). Admins can turn this off in **Admin ›
  Settings**.
- **Anything else, with AudD (optional).** Add [AudD](https://audd.io/) in **Settings ›
  Sources**, like any other source, with your own API token (300 recognitions free, then paid per
  recognition). It's only asked about what your library didn't recognise, and Listen then finds
  the song on your sources to play.

### Now Playing: recognised by itself

Like Pixel phones' Now Playing: turn it on in the phone app (**Settings › Listen**, or on the
Listen screen) and the phone recognises music playing around you without being asked. The song
shows in a quiet notification, on the lock screen too, and joins **Recently heard** everywhere.

- **How it works.** Every minute or so (30 seconds to 5 minutes, your choice) the phone listens
  for three seconds. If that sounds like music, it listens for seven more.
- **What leaves the phone.** It works out the fingerprints on the phone and sends only those to
  your server, which looks for them in your library. The sound never leaves the phone, and
  fingerprints can't be turned back into it.
- **When it rests.**
  - After recognising a song, it waits until the song should be over.
  - It listens less and less often while it hears music it doesn't know.
  - It doesn't listen while the phone plays music itself, during calls, in battery saver, or when
    the battery is low.
- **After a restart.** Android only lets an app use the microphone in the background once you've
  opened it or tapped its notification. After the phone restarts, a notification offers to carry
  on.
- **It recognises songs in your library only.** AudD needs the sound itself, so it's left to
  **Listen**.
- **Reusable.** It's a separate Android library (`nowplaying/`), so another app (or a phone's own
  system) can host it. The host only says how fingerprints are recognised (`NowPlayingHost`).

### Android Auto

The app appears as a media source in Android Auto (and to the system, Android Automotive, the
Assistant and Wear OS; other apps can only play, pause and skip). You can browse **Playlists**, **Downloads**
and **SiriusXM** channels by category, search, and ask the Assistant to "play … on SuperAwesomeMagicalMusic": a
SiriusXM channel name plays that channel, an artist plays their best-known songs, an album plays
it, and anything else plays the songs found (Jellyfin first). The
**Bookmark** (+) button keeps the song you're hearing (on SiriusXM, a Pandora station, or
anything else) for later and turns into a tick; press it again to take the bookmark back. The next
time you open the app on your phone, it asks which playlist each bookmarked song goes in, so you
never have to pick one while driving. On Pandora station songs, **thumbs up / down** buttons appear
as well.

### Listening

- **Even volume.** Songs from Jellyfin, YouTube Music and SiriusXM play at about the same
  loudness. Jellyfin's own loudness value (10.9+) is used where a song has one, which keeps its
  dynamics; anything else is levelled gently while it plays, and boosts never clip. Settings ›
  Playback › Even out volume turns it off; a song's Info shows the gain in use.
- **SiriusXM channels start at the top of the song** that's on, as SiriusXM's app does, rather than
  partway through. On a live channel, **Restart song**, **15 s** back and **Live** (once you're
  behind) are in the player and Android Auto.
- **Lyrics** (the lyrics button in the player), synced and following along where available: from
  Jellyfin when the song is in your library and has them, otherwise from LRCLIB (lrclib.net), a
  free lyrics database. Only the song's title, artist, album and length are sent, and only when
  you open lyrics. On SiriusXM they're the lyrics of the song on the channel.
- **Sleep timer** (the moon button): stop after 15–90 minutes, fading out, or at the end of the song.
- **Dead-zone cache** (Settings › Dead-zone cache): songs you play are kept, and the next few are
  fetched ahead while the connection is good (Wi-Fi, or cellular if allowed; never satellite or
  with Data Saver on), so a dead zone doesn't stop the music. A song cached whole plays with no
  connection at all. 512 MB–4 GB, least recently played first out; downloads are separate.
- **Listening history** (Playlists › Listening history) of everything played and heard, SiriusXM
  channels and Pandora stations included, with one-tap save; the latest show under **Recently
  played** on the Search tab.
- **Scrobbling** to ListenBrainz (or a server with its API, such as Maloja) and Last.fm (with your
  own API key), SiriusXM and Pandora songs optional. Listens made offline are sent later.
- **Artist station from any song** (the song's menu), through SiriusXM's Pandora stations.
- **Save Pandora thumbs-ups** to a "Liked on Pandora" playlist (Settings › Playback).
- **Resume in the car:** the last queue is remembered, so Android Auto's "start music
  automatically", a car's play button or the system's media controls pick up where you left off.
  Settings › Playback › Resume in the car also starts it whenever Android Auto connects.
- **Get ready for a trip** (Downloads › Trip): download your most played songs, what you've played
  lately and any playlists you pick, in one go.
- **Lidarr:** once Lidarr has downloaded music by an artist it added for you, you get a note.
- **Import playlists** (the link button on the Playlists tab) from a link to a playlist or album
  on Spotify, Apple Music, Tidal, Deezer, ListenBrainz or YouTube, or from a typed or pasted list
  ("Artist - Title" lines, or a CSV export from Exportify, TuneMyMusic or Soundiiz). YouTube songs
  come as they are; the others are found in your Jellyfin library first, then on YouTube Music.
  Spotify, Apple Music and Tidal only share the first 100, 100 and 50 or so songs of a long
  playlist on their public pages. Qobuz doesn't share playlists in a way apps can read, so export
  a Qobuz playlist to CSV with TuneMyMusic or Soundiiz and paste that.
- **Equalizer** (Settings › Playback): your phone's presets and a bass boost; equalizer apps
  such as Wavelet can also work on the app's sound.
- **Home-screen widget:** what's playing, with previous, play/pause and next. Play picks up
  where you left off even when the app isn't running.

### Bitrates per network, including satellite

Settings has a slider for each of **Wi-Fi**, **Cellular** and **Satellite**: *Original quality*,
or a cap from 320 kbps down to 64 kbps (the standard MP3 steps). A separate slider sets the
download quality.

| Source | How the cap is applied |
|---|---|
| Jellyfin | Exact: files at or under the cap play untouched; larger ones are transcoded on the server to the cap (MP3, or AAC, which sounds better at low bitrates). |
| Subsonic (server) | Files at or under the cap play untouched; larger ones are transcoded to MP3 at the cap by the Subsonic server (with its own transcoding settings). |
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

**Data Saver:** a separate slider sets the most to stream while Android's Data Saver is on. The
app then streams at that bitrate or the network's own, whichever is lower, even if it's been
allowed unrestricted data in Android's Data Saver settings. Like Data Saver itself, this applies
on metered connections (cellular, satellite, metered hotspots), not ordinary Wi-Fi. When Data
Saver comes on, the song playing switches down too.

When the network changes, upcoming songs switch to the new bitrate. The current song keeps its
stream so seeking stays consistent.

**Buffering:** Settings › Buffering sets how far ahead the player loads, from 30 seconds to 30
minutes (5 minutes by default). Once a song has fully loaded, the next songs in the queue load too,
so songs change seamlessly even when a satellite or cellular link drops out. Loading pauses when
the buffer is full and resumes once a quarter of it has played. The buffer uses up to 64 MB of
memory, which a lossless song reaches first. The progress bars show how far ahead has loaded.

**Song info:** "Info" in a song's menu, or ⓘ in the player, shows the service the song was saved
from and, while it plays, the copy that's playing: service, the codec the decoder actually gets
(e.g. HE-AAC · mp4a.40.5), sample rate, channels, the bitrate (declared by the stream, and measured
from the audio downloaded, which is how SiriusXM and radio streams get one), the cap that applied
and how much is buffered. Downloads show where the file came from, its bitrate and
size.

### Offline listening

"Download" works on songs from Jellyfin and YouTube Music, on whole playlists, and from the
player. Downloads run in the background (WorkManager) at the download quality. Large files are
fetched in 4 MB chunks, and a dropped connection retries only the current chunk. Downloaded copies
always play first. When offline, search looks through your downloads.

**Keep downloaded** (in a playlist's menu) keeps the whole playlist offline: songs you add later,
on this phone or through Nextcloud, are downloaded too. About once a week each song is checked
against every service, and the file is replaced when a clearly better copy turns up (at least 15%
more bitrate, lossless over lossy, or hi-res over CD quality). The old copy keeps playing until
the new one is saved. Songs removed from the playlist are deleted again, unless you downloaded
them yourself.

**Refresh downloads** (⟳ on a playlist) runs those checks now: it looks for songs from other
services that Jellyfin has since gained (e.g. once Lidarr brought in the album) and re-checks
every downloaded song. A download from YouTube Music is replaced by the Jellyfin copy
unless it is clearly better. Songs you stream already play from Jellyfin once it has them.

## The lite web player

For old and underpowered devices (old tablets and phones, e-readers' browsers, smart TVs, a
Raspberry Pi's browser), the server also has a much lighter web player at **`/lite`** (for
example `https://music.example.org/lite`). It isn't linked from the main web player.

LITE_DETAILS

## The Standalone app

The Standalone app (`app/`) works without a server: it talks to Jellyfin, YouTube Music,
SiriusXM, Radio Browser and Lidarr itself, and keeps playlists in your **Nextcloud**. Most of the
features above started here (search, SiriusXM and Pandora stations, local radio, Android Auto,
the listening features, bitrates and offline downloads); the server's own features (personal
stations, Listen, Wrapped, Subsonic, speakers) need the server.

### Setting up
Open **Settings** in the Standalone app:

- **Jellyfin**: server URL, username and password. The password is used once to get an access
  token and is not stored.
- **YouTube Music**: on by default, no account needed.
- **Subsonic servers** (Navidrome and the like) are added on the server (Settings › Sources), not
  in Standalone.
- **SiriusXM**: your SiriusXM e-mail and password. The password is kept, so the app can sign in
  again when SiriusXM's session expires.
- **Lidarr**: server URL and API key (Lidarr › Settings › General).
- **Nextcloud**: the server URL and folder, then **Sign in with browser**. Finish signing in on
  Nextcloud's page (SSO such as authentik works) and come back to the app.

Plain-HTTP server URLs are allowed, because home servers often run without TLS on the LAN.


### Playlists synced to Nextcloud


You can create, rename, reorder, shuffle, download and delete playlists. Playlists belong to the
signed-in Nextcloud account: only that account's playlists are shown, and playlists made before
signing in become the account's. Sign in with **Sign in with browser**: Nextcloud's own login
page opens, so any login Nextcloud supports works, including SSO through authentik, and Nextcloud
gives the app its own app password. Each playlist is stored as a JSON file in a folder of your
Nextcloud you choose (default `Music/Playlists`). Sync runs after every change, every few hours, and
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

## Installing

**With F-Droid** (gets updates automatically): add the app's own F-Droid repository. On your
phone, tap **Add to F-Droid** at the top of this page (it opens F-Droid with the repository
filled in), or open https://603madison.github.io/SuperAwesomeMagicalMusic/ and tap **Add the
repository**, or in F-Droid go to Settings › Repositories › **+** and enter
`https://603madison.github.io/SuperAwesomeMagicalMusic/repo` with the fingerprint
`ED2C0523BC9120F63952EAE882FA4C0FBD3153967130C0A4ADF1AF98D07B5283`. See
[fdroid/README.md](fdroid/README.md) for how the repository is built.

**From GitHub:** download the app from the repository's **Releases** page
(`SuperAwesomeMagicalMusic-vX.Y.Z.apk` for the server's app, `-Standalone-` for the one that works
on its own) and open it on your phone
(allow installing from your browser or file manager when Android asks). Every release is signed
with the same key, so new versions install over old ones and keep your settings and downloads.

**On a flip phone:** download `SuperAwesomeMagicalMusic-Mini-vX.Y.Z.apk` from **Releases** (or get
SuperAwesomeMagicalMusic Mini from the F-Droid repository), enter your server's address, and type the
code it shows at your server's `/link` page on your phone or computer. Signing in with a code also
works in the SuperAwesomeMagicalMusic app (**Sign in with a code from another device**), and the web
player's Settings › Signed in on has **Sign in a device with a code**.

**On the Linux desktop:** download the package from **Releases**, then
`sudo apt install ./SuperAwesomeMagicalMusic-Desktop-vX.Y.Z-amd64.deb` (Ubuntu 24.04 and later) or
`sudo dnf install ./SuperAwesomeMagicalMusic-Desktop-vX.Y.Z-x86_64.rpm` (Fedora 40 and later). See
[desktop/README.md](desktop/README.md).

**The server and the tuner** are container images: see [server/README.md](server/README.md) and
[tuner/README.md](tuner/README.md).

## Building
Needs JDK 17+ and the Android SDK (platform 36) for the apps; the server, core and tuner build
without it (`SAMM_SERVER_ONLY=1`). The web player needs Node 22; the desktop app Rust and GTK 4's
development packages (see [desktop/README.md](desktop/README.md)).

```sh
./gradlew testDebugUnitTest lintDebug :server:test :core:test :tuner:test   # everything's tests
./gradlew assembleDebug                                                     # the Android apps
SAMM_SERVER_ONLY=1 ./gradlew :server:installDist                            # the server
cd web && npm ci && npm run build                                           # the web player
docker build -t samm .                                                      # the server's image
```

The **Release APK** workflow (`.github/workflows/release.yml`) tests everything, builds the signed
APKs, publishes the GitHub release with every app attached, republishes the F-Droid repository,
pushes the server's and the tuner's container images and adds the desktop packages. Run it from
the Actions tab with the version (e.g. `v1.33.0`), or push a `v*` tag; notes come from
`release-notes/<version>.md`. It needs two repository secrets: `RELEASE_KEYSTORE_BASE64` (the
release keystore, base64-encoded) and `RELEASE_KEYSTORE_PASSWORD`.

## Project layout
```
server/      the server (Ktor): accounts, sources, streaming, radio, Listen, speakers, the lite web player
web/         the web player (React + TypeScript)
core/        the music services as plain Kotlin: Jellyfin, Subsonic, YouTube Music, SiriusXM, radio,
             Lidarr, lyrics, scrobbling, fingerprints, Sonos
connect/     the Android app for the server
mini/        the Android app for flip phones
nowplaying/  Now Playing, as an Android library
app/         the Standalone Android app
desktop/     the Linux desktop app (Rust, GTK 4)
tuner/       the FM and HD Radio tuner (Kotlin, rtl-sdr, redsea, nrsc5) and its container
fdroid/      how the F-Droid repository is built
```

In the Android apps, the queue holds addresses of songs, not of streams: the real stream is chosen
only when the player opens a song, so each song uses the network and settings in effect then.

## Licence

GPL-3.0-only; see [LICENSE](LICENSE).

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
- Settings › About › **Copy app log** copies what Android Auto asked the app for and how it
  answered, plus playback errors, for troubleshooting.
- Satellite data needs a carrier plan that supports it and Android 15 or later. On older Android
  versions the app treats every network as Wi-Fi or cellular.
