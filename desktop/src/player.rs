//! Playback: GStreamer's playbin3 (HLS, FLAC, Opus, AAC, MP3), a queue, and what's on for live
//! channels. Media requests carry the sign-in as the session cookie, which GStreamer's HLS
//! downloader passes on to every playlist and segment request (custom headers it drops).
//!
//! Listens are kept for history and Wrapped as the web player does: a song once half of it (or four
//! minutes, at least 30 seconds) was heard; live radio as each stretch between song changes.

use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use gst::prelude::*;
use gtk::glib;
use rand::seq::SliceRandom;

use crate::api::{Api, Listen, LiveKind, Track, enc};
use crate::rt::bg;

/// What's on a live channel or station.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct OnAir {
    pub title: String,
    pub artist: String,
    pub artwork_url: Option<String>,
    /// The song, to keep in history (SiriusXM says which; stations only give a title).
    pub song: Option<Track>,
}

#[derive(Debug, Clone, Default)]
pub struct State {
    pub queue: Vec<Track>,
    pub index: Option<usize>,
    /// Playing, or about to (it may be loading).
    pub playing: bool,
    pub loading: bool,
    pub position: f64,
    pub duration: f64,
    pub on_air: Option<OnAir>,
    /// How it's playing ("Jellyfin FLAC", "YouTube Music → Opus 160 kbps").
    pub description: String,
    pub volume: f64,
}

impl State {
    pub fn current(&self) -> Option<&Track> {
        self.index.and_then(|i| self.queue.get(i))
    }

    pub fn live(&self) -> bool {
        self.current().is_some_and(Track::is_live)
    }

    /// Title, artist and cover of what's heard: on a live channel, the song that's on.
    pub fn shown(&self) -> Option<(String, String, Option<String>)> {
        let track = self.current()?;
        Some(match &self.on_air {
            Some(on) => (
                on.title.clone(),
                if on.artist.is_empty() {
                    track.title.clone()
                } else {
                    on.artist.clone()
                },
                on.artwork_url.clone().or_else(|| track.artwork_url.clone()),
            ),
            None => (track.title.clone(), track.artist.clone(), track.artwork_url.clone()),
        })
    }

    pub fn has_next(&self) -> bool {
        self.index.is_some_and(|i| i + 1 < self.queue.len())
    }
}

/// Told of every change in the player.
type Listener = Rc<dyn Fn(&State)>;
/// Told when something can't play.
type ErrorListener = Rc<dyn Fn(&str)>;

pub struct Player {
    playbin: gst::Element,
    state: RefCell<State>,
    api: RefCell<Option<Api>>,
    /// The bitrate cap and whether to compare every service (Settings).
    choices: Cell<(u32, bool)>,
    listeners: RefCell<Vec<Listener>>,
    errors: RefCell<Vec<ErrorListener>>,
    /// Bumped on every load: work for an older load drops its result.
    generation: Cell<u64>,
    listened_ms: Cell<f64>,
    last_tick: Cell<Option<Instant>>,
    reported: Cell<bool>,
    live_mark: Cell<f64>,
    failures: Cell<u32>,
    buffering: Cell<bool>,
    /// The sign-in as a cookie, for GStreamer's HTTP sources (on its own threads).
    cookie: Arc<Mutex<String>>,
    _bus: RefCell<Option<gst::bus::BusWatchGuard>>,
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

impl Player {
    pub fn new() -> Rc<Player> {
        let playbin = gst::ElementFactory::make("playbin3")
            .build()
            .expect("GStreamer's playbin3 (gstreamer1.0-plugins-base)");
        // Audio only.
        let flags = playbin.property_value("flags");
        if let Some(flags_class) = glib::FlagsClass::with_type(flags.type_())
            && let Some(builder) = flags_class.builder_with_value(flags)
            && let Some(value) = builder.unset_by_nick("video").unset_by_nick("text").build()
        {
            playbin.set_property_from_value("flags", &value);
        }
        // For trying it where there's no sound card: SAMM_AUDIO_SINK="fakesink sync=true".
        if let Ok(description) = std::env::var("SAMM_AUDIO_SINK")
            && let Ok(sink) = gst::parse::bin_from_description(&description, true)
        {
            playbin.set_property("audio-sink", &sink);
        }
        let cookie = Arc::new(Mutex::new(String::new()));
        let player = Rc::new(Player {
            playbin,
            state: RefCell::new(State {
                volume: 0.8,
                ..Default::default()
            }),
            api: RefCell::new(None),
            choices: Cell::new((0, true)),
            listeners: RefCell::new(vec![]),
            errors: RefCell::new(vec![]),
            generation: Cell::new(0),
            listened_ms: Cell::new(0.0),
            last_tick: Cell::new(None),
            reported: Cell::new(false),
            live_mark: Cell::new(0.0),
            failures: Cell::new(0),
            buffering: Cell::new(false),
            cookie: cookie.clone(),
            _bus: RefCell::new(None),
        });

        // The sign-in, as the session cookie, on every HTTP request GStreamer makes to the server.
        let cookie_for_source = cookie;
        player.playbin.connect("source-setup", false, move |values| {
            if let Ok(source) = values[1].get::<gst::Element>() {
                let cookie = cookie_for_source.lock().map(|c| c.clone()).unwrap_or_default();
                if !cookie.is_empty() && source.has_property("cookies") {
                    source.set_property("cookies", [cookie.as_str()].to_value());
                }
                if source.has_property("user-agent") {
                    source.set_property(
                        "user-agent",
                        concat!("SuperAwesomeMagicalMusic-Desktop/", env!("CARGO_PKG_VERSION")),
                    );
                }
            }
            None
        });
        let weak = Rc::downgrade(&player);
        let bus = player.playbin.bus().expect("playbin's bus");
        let guard = bus
            .add_watch_local(move |_, message| {
                if let Some(player) = weak.upgrade() {
                    player.on_message(message);
                }
                glib::ControlFlow::Continue
            })
            .expect("a bus watch");
        player._bus.replace(Some(guard));

        let weak = Rc::downgrade(&player);
        glib::timeout_add_local(Duration::from_millis(250), move || match weak.upgrade() {
            Some(player) => {
                player.tick();
                glib::ControlFlow::Continue
            }
            None => glib::ControlFlow::Break,
        });
        player.apply_volume();
        player
    }

    // ---- Wiring ----

    pub fn set_api(&self, api: Option<Api>) {
        if let Ok(mut cookie) = self.cookie.lock() {
            *cookie = api
                .as_ref()
                .and_then(|a| a.token())
                .map(|t| format!("samm_session={t}"))
                .unwrap_or_default();
        }
        if api.is_none() {
            self.stop();
        }
        self.api.replace(api);
    }

    pub fn set_choices(&self, kbps: u32, thorough: bool) {
        self.choices.set((kbps, thorough));
    }

    /// Told of every change (and four times a second while playing, for the position).
    pub fn subscribe(&self, listener: impl Fn(&State) + 'static) {
        self.listeners.borrow_mut().push(Rc::new(listener));
    }

    /// Told when something can't play.
    pub fn on_error(&self, listener: impl Fn(&str) + 'static) {
        self.errors.borrow_mut().push(Rc::new(listener));
    }

    pub fn state(&self) -> State {
        self.state.borrow().clone()
    }

    fn notify(&self) {
        let state = self.state.borrow().clone();
        let listeners: Vec<_> = self.listeners.borrow().clone();
        for listener in listeners {
            listener(&state);
        }
    }

    fn tell_error(&self, message: &str) {
        let listeners: Vec<_> = self.errors.borrow().clone();
        for listener in listeners {
            listener(message);
        }
    }

    // ---- What the interface calls ----

    pub fn play_tracks(self: &Rc<Self>, tracks: Vec<Track>, start: usize) {
        let playable: Vec<Track> = tracks.into_iter().filter(|t| !t.is_missing()).collect();
        if playable.is_empty() {
            return;
        }
        let start = start.min(playable.len() - 1);
        self.state.borrow_mut().queue = playable;
        self.load(start, true);
    }

    pub fn play_shuffled(self: &Rc<Self>, mut tracks: Vec<Track>) {
        tracks.shuffle(&mut rand::rng());
        self.play_tracks(tracks, 0);
    }

    /// After the current song.
    pub fn play_next(self: &Rc<Self>, tracks: Vec<Track>) {
        let empty = self.state.borrow().index.is_none();
        if empty {
            return self.play_tracks(tracks, 0);
        }
        {
            let mut state = self.state.borrow_mut();
            let at = state.index.map(|i| i + 1).unwrap_or(0);
            let tail = state.queue.split_off(at);
            state.queue.extend(tracks.into_iter().filter(|t| !t.is_missing()));
            state.queue.extend(tail);
        }
        self.notify();
    }

    pub fn add_to_queue(self: &Rc<Self>, tracks: Vec<Track>) {
        let empty = self.state.borrow().index.is_none();
        if empty {
            return self.play_tracks(tracks, 0);
        }
        self.state.borrow_mut().queue.extend(tracks.into_iter().filter(|t| !t.is_missing()));
        self.notify();
    }

    pub fn jump_to(self: &Rc<Self>, index: usize) {
        if index < self.state.borrow().queue.len() {
            self.load(index, true);
        }
    }

    pub fn toggle(self: &Rc<Self>) {
        if self.state.borrow().playing { self.pause() } else { self.play() }
    }

    pub fn play(self: &Rc<Self>) {
        let (index, live) = {
            let state = self.state.borrow();
            (state.index, state.live())
        };
        let Some(index) = index else { return };
        // A live stream picks up where it is now, not where it was paused.
        if live || self.playbin.current_state() == gst::State::Null {
            return self.load(index, true);
        }
        self.state.borrow_mut().playing = true;
        let _ = self.playbin.set_state(gst::State::Playing);
        self.notify();
    }

    pub fn pause(&self) {
        self.state.borrow_mut().playing = false;
        let live = self.state.borrow().live();
        let _ = self.playbin.set_state(if live { gst::State::Null } else { gst::State::Paused });
        self.last_tick.set(None);
        self.notify();
    }

    pub fn next(self: &Rc<Self>) {
        let (index, len) = {
            let state = self.state.borrow();
            (state.index, state.queue.len())
        };
        if let Some(i) = index.filter(|i| i + 1 < len) {
            self.load(i + 1, true);
        }
    }

    pub fn previous(self: &Rc<Self>) {
        let (index, position) = {
            let state = self.state.borrow();
            (state.index, state.position)
        };
        match index {
            Some(i) if position <= 3.0 && i > 0 => self.load(i - 1, true),
            Some(_) => self.seek(0.0),
            None => {}
        }
    }

    pub fn seek(&self, seconds: f64) {
        if self.state.borrow().live() {
            return;
        }
        let target = gst::ClockTime::from_mseconds((seconds.max(0.0) * 1000.0) as u64);
        let _ = self.playbin.seek_simple(gst::SeekFlags::FLUSH | gst::SeekFlags::KEY_UNIT, target);
        self.state.borrow_mut().position = seconds;
        self.notify();
    }

    pub fn set_volume(&self, volume: f64) {
        self.state.borrow_mut().volume = volume.clamp(0.0, 1.0);
        self.apply_volume();
        self.notify();
    }

    fn apply_volume(&self) {
        // Cubic, as people hear it.
        let volume = self.state.borrow().volume;
        self.playbin.set_property("volume", volume.powi(3));
    }

    pub fn stop(&self) {
        self.report_listen();
        self.generation.set(self.generation.get() + 1);
        let _ = self.playbin.set_state(gst::State::Null);
        {
            let mut state = self.state.borrow_mut();
            let volume = state.volume;
            *state = State {
                volume,
                ..Default::default()
            };
        }
        self.notify();
    }

    // ---- Loading ----

    fn load(self: &Rc<Self>, index: usize, autoplay: bool) {
        self.report_listen();
        let generation = self.generation.get() + 1;
        self.generation.set(generation);
        self.listened_ms.set(0.0);
        self.live_mark.set(0.0);
        self.reported.set(false);
        self.last_tick.set(None);
        let _ = self.playbin.set_state(gst::State::Null);
        let Some(track) = self.state.borrow().queue.get(index).cloned() else {
            return;
        };
        {
            let mut state = self.state.borrow_mut();
            state.index = Some(index);
            state.playing = autoplay;
            state.loading = true;
            state.position = 0.0;
            state.duration = track.duration_ms.unwrap_or(0) as f64 / 1000.0;
            state.on_air = None;
            state.description = String::new();
        }
        self.notify();
        let Some(api) = self.api.borrow().clone() else { return };
        let (kbps, thorough) = self.choices.get();
        let me = self.clone();
        glib::spawn_future_local(async move {
            let uri = if let Some(live) = track.live_stream() {
                me.state.borrow_mut().description = if live.kind == LiveKind::Channel {
                    "SiriusXM".into()
                } else {
                    "Radio".into()
                };
                let played = track.clone();
                let api_for_played = api.clone();
                bg(async move { api_for_played.live_played(&played).await }).await.ok();
                api.url(&live.path)
            } else if let Some(path) = station_track_path(&track) {
                me.state.borrow_mut().description = "Pandora station · SiriusXM".into();
                api.url(&path)
            } else {
                let wanted = track.clone();
                let resolver = api.clone();
                match bg(async move { resolver.resolve(&wanted, kbps, thorough).await }).await {
                    Ok(resolved) => {
                        if me.generation.get() != generation {
                            return;
                        }
                        let mut state = me.state.borrow_mut();
                        state.description = resolved.description.clone();
                        // The copy found may have the cover the request lacked.
                        if let Some(current) = state.queue.get_mut(index)
                            && current.artwork_url.is_none()
                        {
                            current.artwork_url = resolved.track.artwork_url.clone();
                        }
                        api.url(&resolved.stream_url)
                    }
                    Err(e) => return me.failed(generation, &e.to_string()),
                }
            };
            if me.generation.get() != generation {
                return;
            }
            me.playbin.set_property("uri", &uri);
            let _ = me
                .playbin
                .set_state(if autoplay { gst::State::Playing } else { gst::State::Paused });
            me.notify();
            if let Some(live) = track.live_stream() {
                me.watch_on_air(generation, track, live.kind);
            }
        });
    }

    /// Couldn't play this one: say so, and go on to the next (as the other apps do), unless
    /// several in a row failed.
    fn failed(self: &Rc<Self>, generation: u64, message: &str) {
        if self.generation.get() != generation {
            return;
        }
        let title = self.state.borrow().current().map(|t| t.title.clone()).unwrap_or_default();
        self.tell_error(&format!("Couldn't play “{title}”: {message}"));
        self.failures.set(self.failures.get() + 1);
        self.state.borrow_mut().loading = false;
        let has_next = self.state.borrow().has_next();
        if has_next && self.failures.get() < 4 {
            self.next();
        } else {
            self.state.borrow_mut().playing = false;
            let _ = self.playbin.set_state(gst::State::Null);
            self.notify();
        }
    }

    fn on_message(self: &Rc<Self>, message: &gst::Message) {
        use gst::MessageView;
        match message.view() {
            MessageView::Eos(..) => {
                let (live, has_next) = {
                    let state = self.state.borrow();
                    (state.live(), state.has_next())
                };
                if live {
                    // A live stream that ended: pick it up again.
                    if let Some(i) = self.state.borrow().index {
                        let me = self.clone();
                        glib::timeout_add_local_once(Duration::from_secs(2), move || me.load(i, true));
                    }
                } else if has_next {
                    self.next();
                } else {
                    self.report_listen();
                    self.reported.set(true);
                    self.state.borrow_mut().playing = false;
                    let _ = self.playbin.set_state(gst::State::Null);
                    self.notify();
                }
            }
            MessageView::Error(error) => {
                let text = error.error().to_string();
                eprintln!("Playback error: {text} ({:?})", error.debug());
                self.failed(self.generation.get(), &text);
            }
            MessageView::Buffering(buffering) => {
                // Waits for enough to play on smoothly (not for live streams, which keep going).
                let percent = buffering.percent();
                if self.state.borrow().live() {
                    return;
                }
                let wants = self.state.borrow().playing;
                if percent < 100 && !self.buffering.get() {
                    self.buffering.set(true);
                    if wants {
                        let _ = self.playbin.set_state(gst::State::Paused);
                    }
                } else if percent >= 100 && self.buffering.get() {
                    self.buffering.set(false);
                    if wants {
                        let _ = self.playbin.set_state(gst::State::Playing);
                    }
                }
            }
            MessageView::StateChanged(changed) => {
                if changed.src().map(|s| s == &self.playbin).unwrap_or(false) && changed.current() == gst::State::Playing {
                    self.failures.set(0);
                    if self.state.borrow().loading {
                        self.state.borrow_mut().loading = false;
                        self.notify();
                    }
                }
            }
            MessageView::DurationChanged(..) => self.tick(),
            _ => {}
        }
    }

    /// Four times a second: the position, and how long it's been heard.
    fn tick(&self) {
        let playing = self.state.borrow().playing && self.playbin.current_state() == gst::State::Playing && !self.buffering.get();
        if !playing {
            self.last_tick.set(None);
            return;
        }
        let now = Instant::now();
        if let Some(last) = self.last_tick.get() {
            self.listened_ms
                .set(self.listened_ms.get() + now.duration_since(last).as_secs_f64().min(2.0) * 1000.0);
        }
        self.last_tick.set(Some(now));
        let position = self.playbin.query_position::<gst::ClockTime>().map(|t| t.seconds_f64());
        let duration = self.playbin.query_duration::<gst::ClockTime>().map(|t| t.seconds_f64());
        {
            let mut state = self.state.borrow_mut();
            if let Some(p) = position {
                state.position = p;
            }
            if let Some(d) = duration.filter(|d| *d > 0.0 && !state.live()) {
                state.duration = d;
            }
        }
        self.notify();
    }

    // ---- Live radio ----

    /// What's on, every so often while the channel or station plays.
    fn watch_on_air(self: &Rc<Self>, generation: u64, track: Track, kind: LiveKind) {
        let Some(api) = self.api.borrow().clone() else { return };
        let Some(via) = track.via.clone() else { return };
        let me = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            loop {
                let Some(player) = me.upgrade() else { return };
                if player.generation.get() != generation {
                    return;
                }
                let asking = api.clone();
                let on_air = match kind {
                    LiveKind::Channel => {
                        let Some((kind, id)) = track.channel_parts() else { return };
                        let via = via.clone();
                        // What's heard is behind the newest of the stream (about three segments).
                        let at = now_ms() - 30_000;
                        bg(async move { asking.channel_now(&via, &kind, &id, at).await })
                            .await
                            .ok()
                            .flatten()
                            .map(|now| OnAir {
                                title: now.title,
                                artist: now.artist,
                                artwork_url: now.artwork_url,
                                song: now.song,
                            })
                    }
                    LiveKind::Radio => {
                        let via = via.clone();
                        let id = track.source_id.trim_start_matches("hls:").to_string();
                        bg(async move { asking.radio_now(&via, &id).await })
                            .await
                            .ok()
                            .flatten()
                            .map(|title| match title.split_once(" - ") {
                                Some((artist, song)) => OnAir {
                                    title: song.trim().into(),
                                    artist: artist.trim().into(),
                                    ..Default::default()
                                },
                                None => OnAir {
                                    title: title.trim().into(),
                                    ..Default::default()
                                },
                            })
                    }
                };
                if player.generation.get() != generation {
                    return;
                }
                let changed = {
                    let state = player.state.borrow();
                    let before = state.on_air.as_ref().map(|o| (&o.title, &o.artist));
                    before != on_air.as_ref().map(|o| (&o.title, &o.artist))
                };
                if changed {
                    player.end_live_stretch();
                    player.state.borrow_mut().on_air = on_air;
                    player.notify();
                } else if on_air.is_some() {
                    player.state.borrow_mut().on_air = on_air;
                }
                drop(player);
                glib::timeout_future(Duration::from_secs(if kind == LiveKind::Channel { 15 } else { 20 })).await;
            }
        });
    }

    // ---- History ----

    /// The song that was playing, if enough of it was heard (or the live stretch).
    fn report_listen(&self) {
        let Some(track) = self.state.borrow().current().cloned() else {
            return;
        };
        if track.is_live() {
            return self.end_live_stretch();
        }
        if self.reported.get() {
            return;
        }
        let listened = self.listened_ms.get();
        let length = track.duration_ms.unwrap_or(0) as f64;
        let needed = 30_000f64.max(if length > 0.0 { (length / 2.0).min(240_000.0) } else { 30_000.0 });
        if listened < needed {
            return;
        }
        self.reported.set(true);
        self.send_listen(Listen {
            track: track.as_song(),
            played_at: now_ms() - listened as i64,
            listened_ms: listened as i64,
            client: "desktop".into(),
            station: None,
        });
    }

    /// Live radio: what was heard since what's on last changed, at least 30 seconds of it, as a
    /// listen of that song, or of the channel or station when the song isn't known.
    fn end_live_stretch(&self) {
        let state = self.state.borrow();
        let Some(channel) = state.current().filter(|t| t.is_live()).cloned() else {
            return;
        };
        let ms = self.listened_ms.get() - self.live_mark.get();
        self.live_mark.set(self.listened_ms.get());
        if ms < 30_000.0 {
            return;
        }
        let track = state
            .on_air
            .as_ref()
            .and_then(|o| o.song.as_ref())
            .map(Track::as_song)
            .unwrap_or_else(|| channel.clone());
        drop(state);
        self.send_listen(Listen {
            track,
            played_at: now_ms() - ms as i64,
            listened_ms: ms as i64,
            client: "desktop".into(),
            station: Some(channel.title),
        });
    }

    fn send_listen(&self, listen: Listen) {
        let Some(api) = self.api.borrow().clone() else { return };
        glib::spawn_future_local(async move {
            if let Err(e) = bg(async move { api.add_listens(vec![listen]).await }).await {
                eprintln!("Couldn't keep a listen: {e}");
            }
        });
    }

    /// When the app closes: the last listen.
    pub fn shutdown(&self) {
        self.report_listen();
        let _ = self.playbin.set_state(gst::State::Null);
    }
}

/// A Pandora station's song plays from the station on the server.
fn station_track_path(track: &Track) -> Option<String> {
    if !track.is_station_track() {
        return None;
    }
    let via = track.via.as_deref()?;
    let rest = track.source_id.strip_prefix("stationtrack:")?;
    let track_id = rest.split_once(':').map(|(_, t)| t).unwrap_or(rest);
    Some(format!("/api/live/sxm/{}/station-track/{}", enc(via), enc(track_id)))
}
