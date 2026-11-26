# SuperAwesomeMagicalMusic for Linux

The desktop app for your SuperAwesomeMagicalMusic server: native, written in Rust with GTK 4 and
libadwaita (GNOME's own toolkit), playing through GStreamer, the system's media framework. No web
view, no Chromium: a 5 MB program that starts at once.

- **Sign in** with your server's address, then your password or your server's single sign-on (it
  opens your browser, which hands the sign-in back to the app).
- **Search** artists, albums and songs from your library and every service on your server, with
  artist and album pages, Play on the top result, and **Listen** a click away.
- **Your playlists**, from every device. Make, import (a Spotify, Apple Music, Tidal, Deezer,
  YouTube or ListenBrainz link, a list typed or pasted, a CSV file, or a playlist on your Subsonic
  server), rename, reorder, export (CSV) and delete them; **share** one with people on your server
  (or leave one shared with you), see who added each song, and look again for the songs that
  weren't found. Add songs from any song's ⋯ menu, as in the web player, or start a station from
  its artist.
- **History**, from every device, a hundred at a time, and clearing it; and your **Wrapped**: the
  year's minutes, top songs, artists, albums and stations, when you listen, where it came from and
  its highlights, any year, and the story to play through, a slide at a time.
- **Radio**: your own stations (Made for you), your favourite channels and stations, every SiriusXM
  channel (with what's on), Pandora artist stations (start one from any artist), and local and
  internet radio (what's popular in your country, near you, or by name, your FM tuner's stations
  among them). Star them to keep them, here and everywhere.
- **The player** along the bottom, as the web player's: shuffle, repeat, the queue, a sleep timer
  and a star for live stations; **media keys and the desktop's media
  controls** (GNOME's, KDE's, the lock screen) through MPRIS, with the song and its cover.
- **Now playing**, as the web player's: the cover large and the page washed in its colours, the
  song's artist and album a click away, its **lyrics** following the song (the line being sung
  big and bright; click a line to play from there), **Up next** (play from, move, remove, clear),
  and on live radio the song on the channel (with its lyrics) and what it played before. Open it
  from the song in the player bar or the sidebar; full screen from its corner.
- **Song details**: where it's from, the copy your server picked (format, bitrate, quality cap),
  what GStreamer decodes (codec, sample rate, bits) and the volume levelling applied.
- **Your devices**: the app is one of them, like the web player and the phone app. The devices
  button lists your other web players, phones and speakers (Sonos, Music Assistant) with what's
  on each: play or pause them, or move playback there or here. While another device plays, the
  player bar controls it ("Play here" brings it back).
- **Even out volume** (Preferences): your library's loudness values where it has them, else
  gentle levelling as it plays, with a limiter.
- What you play counts toward your **history and Wrapped**, live radio included.
- **The family's look**: Lexend, rounded cards over a soft glow of the theme, as the web player and
  the phone app have it, with the same **themes**: Aurora (purple and green, the default), Violet,
  Ocean and Ember, each light or dark (or as the system is), and **Bold text**.
- **Preferences**, as the web player's Settings: Appearance (theme, light or dark, bold text), the
  quality cap, comparing every service for the best copy, and whether the DJ talks on your own
  stations (spoken through speech-dispatcher, when it's installed); your **sources** (add one, or
  switch off a shared one for yourself); and your account: your name and password, where you're
  signed in (sign out any of them), **signing in a device with a code** (a flip phone, say),
  Claude's connector and what was asked of Lidarr.
- **Admin**, for admins: people (add, make admin, turn off, set a password, sign out, delete), the
  sources shared with everyone, and the server's settings, single sign-on included.
- Shortcuts: Ctrl+P play/pause, Ctrl+→ next, Ctrl+← previous, Ctrl+, preferences, Ctrl+Q quit.

## Installing

From the repository's **Releases**:

- **Ubuntu 24.04 and later** (and Linux Mint 22, Pop!_OS 24.04, Debian 13):
  `sudo apt install ./SuperAwesomeMagicalMusic-Desktop-vX.Y.Z-amd64.deb`
- **Fedora 40 and later:** `sudo dnf install ./SuperAwesomeMagicalMusic-Desktop-vX.Y.Z-x86_64.rpm`

apt and dnf bring in what it needs: GTK 4, libadwaita 1.5 or later, and GStreamer with its
plug-ins for HLS, AAC, FLAC and Opus. Older systems (Ubuntu 22.04 and what's based on it) have too
old a libadwaita. x86-64 only for now.

It's then in your applications as **SuperAwesomeMagicalMusic**; its settings (and sign-in, readable
by you only) are in `~/.config/SuperAwesomeMagicalMusic`.

## Building

With a current stable Rust and, on Ubuntu, `libgtk-4-dev libadwaita-1-dev libgstreamer1.0-dev
libssl-dev` (and GStreamer's plug-ins to play: `gstreamer1.0-plugins-good gstreamer1.0-plugins-bad
gstreamer1.0-libav`):

```sh
cargo run                  # run it
cargo test
cargo build --release
cargo deb --no-build       # target/debian/*.deb (cargo install cargo-deb)
cargo generate-rpm         # target/generate-rpm/*.rpm (cargo install cargo-generate-rpm)
```

The release workflow's `desktop` job builds both packages on Ubuntu 24.04 and adds them to each
GitHub release. The version in `Cargo.toml` is the project's version (`versionName` in
`app/build.gradle.kts`). `SAMM_AUDIO_SINK="fakesink sync=true"` plays into nothing, for trying it
without a sound card.

How it fits together: `src/api.rs` and `src/api/` (the server's API, as the web player and phone app use it),
`src/player.rs` (GStreamer's playbin3, the queue, what's on, listens), `src/leveller.rs` (even
volume, as the web player's), `src/connect.rs` (the devices hub, `/api/connect/ws`),
`src/mpris.rs` (media controls), `src/ui/` (the window, sign-in, pages, Now playing, the devices
and the player bar). Media requests carry the
sign-in as the session cookie, which GStreamer's HLS downloader passes on to every segment.
