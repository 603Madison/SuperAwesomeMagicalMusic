# SuperAwesomeMagicalMusic for Linux

The desktop app for your SuperAwesomeMagicalMusic server: native, written in Rust with GTK 4 and
libadwaita (GNOME's own toolkit), playing through GStreamer, the system's media framework. No web
view, no Chromium: a 5 MB program that starts at once.

- **Sign in** with your server's address, then your password or your server's single sign-on (it
  opens your browser, which hands the sign-in back to the app).
- **Search** artists, albums and songs from your library and every service on your server, with
  artist and album pages.
- **Your playlists** and **history**, from every device.
- **Radio**: your favourite channels and stations, every SiriusXM channel (with what's on), and
  local and internet radio. Star them to keep them, here and everywhere.
- **The player** along the bottom, with the queue; **media keys and the desktop's media
  controls** (GNOME's, KDE's, the lock screen) through MPRIS, with the song and its cover.
- What you play counts toward your **history and Wrapped**, live radio included.
- **Preferences**: the quality cap, and comparing every service for the best copy.
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

How it fits together: `src/api.rs` (the server's API, as the web player and phone app use it),
`src/player.rs` (GStreamer's playbin3, the queue, what's on, listens), `src/mpris.rs` (media
controls), `src/ui/` (the window, sign-in, pages and the player bar). Media requests carry the
sign-in as the session cookie, which GStreamer's HLS downloader passes on to every segment.
