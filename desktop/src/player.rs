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

use crate::api::{Api, Listen, LiveKind, PersonalNow, Track, enc};
use crate::rt::bg;

/// How far behind one of the person's own stations the player may fall before it tunes in again.
const MAX_BEHIND_MS: i64 = 25_000;

/// What's on a live channel or station.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct OnAir {
    pub title: String,
    pub artist: String,
    pub artwork_url: Option<String>,
    /// The song, to keep in history (SiriusXM says which; stations only give a title).
    pub song: Option<Track>,
    pub album: Option<String>,
    /// When the song started (on the live clock) and how long it is, when the channel says.
    pub start_ms: i64,
    pub duration_ms: i64,
    /// When it was first heard here.
    pub heard_at: i64,
}

impl OnAir {
    fn of(now: crate::api::NowView) -> OnAir {
        OnAir {
            title: now.title,
            artist: now.artist,
            artwork_url: now.artwork_url,
            song: now.song,
            album: now.album,
            start_ms: now.start_ms,
            duration_ms: now.duration_ms,
            heard_at: 0,
        }
    }
}

/// The copy the server picked, as it said.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Copy {
    pub kbps: Option<i32>,
    pub within_cap: bool,
    pub gain_db: Option<f64>,
    pub transcoded: bool,
    pub extension: String,
}

/// What GStreamer says of what's playing: from the stream's tags and the decoded audio's caps.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Heard {
    pub codec: Option<String>,
    pub container: Option<String>,
    /// Bits a second.
    pub bitrate: Option<u32>,
    pub rate: Option<i32>,
    pub channels: Option<i32>,
    /// The decoded samples' format ("S16LE", "F32LE").
    pub format: Option<String>,
}

/// Playing the queue again at its end, or the one song again.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Repeat {
    #[default]
    Off,
    All,
    One,
}

impl Repeat {
    /// The next in Off → All → One → Off, as the web player's button goes.
    pub fn next(self) -> Repeat {
        match self {
            Repeat::Off => Repeat::All,
            Repeat::All => Repeat::One,
            Repeat::One => Repeat::Off,
        }
    }
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
    /// On one of the person's own stations: what's next, its thumbs and what the DJ says.
    pub personal: Option<PersonalNow>,
    /// How it's playing ("Jellyfin FLAC", "YouTube Music → Opus 160 kbps").
    pub description: String,
    pub volume: f64,
    pub repeat: Repeat,
    /// A sleep timer is set.
    pub sleeping: bool,
    /// The copy playing, as the server described it (not for live radio).
    pub copy: Option<Copy>,
    /// What GStreamer says of it.
    pub heard: Heard,
    /// On live radio: what it played before, since tuned in (newest first).
    pub live_history: Vec<OnAir>,
    /// Thumbs given to Pandora stations' songs (by song id): UP or DOWN.
    pub thumbs: std::collections::HashMap<String, String>,
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

    /// Thumbs for what's playing, where it takes them (one of the person's own stations, or a
    /// Pandora station's song): whether they can be given now, and the one given.
    pub fn thumbs_now(&self) -> Option<(bool, Option<String>)> {
        let track = self.current()?;
        if track.is_personal() {
            let info = self.personal.as_ref()?;
            return Some((info.song_key.is_some(), info.thumb.clone()));
        }
        let (_, song) = split_station(track.source_id.strip_prefix("stationtrack:")?);
        Some((true, self.thumbs.get(&song).cloned()))
    }

    /// Where the queue is going: after the current song, in order.
    pub fn upcoming(&self) -> &[Track] {
        let at = self.index.map(|i| i + 1).unwrap_or(0).min(self.queue.len());
        &self.queue[at..]
    }

    pub fn has_next(&self) -> bool {
        // One of the person's own stations moves on to its next song.
        self.current().is_some_and(Track::is_personal)
            || self.index.is_some_and(|i| i + 1 < self.queue.len())
            || (self.repeat == Repeat::All && !self.queue.is_empty())
    }

    /// What plays after the current song: the next one, or the first again when repeating all.
    fn following(&self) -> Option<usize> {
        let i = self.index?;
        if i + 1 < self.queue.len() {
            Some(i + 1)
        } else if self.repeat == Repeat::All && !self.queue.is_empty() {
            Some(0)
        } else {
            None
        }
    }
}

/// When the sleep timer pauses.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Sleep {
    Minutes(u32),
    EndOfSong,
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
    /// When this player last tuned in again to catch up with one of the person's own stations.
    caught_up: Cell<Option<Instant>>,
    reported: Cell<bool>,
    live_mark: Cell<f64>,
    failures: Cell<u32>,
    buffering: Cell<bool>,
    /// The server (its scheme, name and port) and the sign-in as a cookie for it, for GStreamer's
    /// HTTP sources (on its own threads). Only requests for that server get it.
    cookie: Arc<Mutex<(String, String)>>,
    _bus: RefCell<Option<gst::bus::BusWatchGuard>>,
    /// The song's stream, whether it can be jumped about in, and (when the server started it
    /// partway, as it does for those that can't) how far in it starts.
    stream: RefCell<String>,
    seekable: Cell<bool>,
    offset: Cell<f64>,
    /// Where the next song loaded starts (Listen's "Play from here").
    start_at: Cell<f64>,
    /// The sleep timer: its clock, or pausing at the end of the song.
    sleep_clock: RefCell<Option<glib::SourceId>>,
    sleep_at_end: Cell<bool>,
    /// Even volume: its arithmetic, and the `volume` element it sets (none without GStreamer's
    /// good plug-ins, when songs play unlevelled).
    leveller: RefCell<crate::leveller::Leveller>,
    level_gain: Option<gst::Element>,
    /// Where the decoded audio comes in, for its format and rate.
    audio_in: Option<gst::Pad>,
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
        let cookie = Arc::new(Mutex::new((String::new(), String::new())));
        let (level_gain, audio_in) = match levelling_filter() {
            Some((filter, gain, pad)) => {
                playbin.set_property("audio-filter", &filter);
                (Some(gain), Some(pad))
            }
            None => (None, None),
        };
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
            caught_up: Cell::new(None),
            reported: Cell::new(false),
            live_mark: Cell::new(0.0),
            failures: Cell::new(0),
            buffering: Cell::new(false),
            cookie: cookie.clone(),
            _bus: RefCell::new(None),
            stream: RefCell::new(String::new()),
            seekable: Cell::new(true),
            offset: Cell::new(0.0),
            start_at: Cell::new(0.0),
            sleep_clock: RefCell::new(None),
            sleep_at_end: Cell::new(false),
            leveller: RefCell::new(Default::default()),
            level_gain,
            audio_in,
        });

        // The sign-in, as the session cookie, on every HTTP request GStreamer makes to the server.
        let cookie_for_source = cookie;
        player.playbin.connect("source-setup", false, move |values| {
            if let Ok(source) = values[1].get::<gst::Element>() {
                let (server, cookie) = cookie_for_source.lock().map(|c| c.clone()).unwrap_or_default();
                let location = source
                    .has_property("location")
                    .then(|| source.property_value("location").get::<Option<String>>().ok().flatten())
                    .flatten();
                let for_server = location.as_deref().and_then(origin).is_some_and(|o| o == server);
                if for_server && !cookie.is_empty() && source.has_property("cookies") {
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
                .and_then(|a| Some((origin(a.base())?, format!("samm_session={}", a.token()?))))
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
    /// Tells every listener the state again (after a list on screen was rebuilt).
    pub fn nudge(&self) {
        self.notify();
    }

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

    /// Shuffles what's after the current song (as the web player's shuffle button does).
    pub fn shuffle_upcoming(&self) {
        {
            let mut state = self.state.borrow_mut();
            let at = state.index.map(|i| i + 1).unwrap_or(0).min(state.queue.len());
            state.queue[at..].shuffle(&mut rand::rng());
        }
        self.notify();
    }

    /// Pauses in some minutes, or at the end of the song; None turns the timer off.
    pub fn sleep_in(self: &Rc<Self>, when: Option<Sleep>) {
        if let Some(clock) = self.sleep_clock.borrow_mut().take() {
            clock.remove();
        }
        self.sleep_at_end.set(false);
        match when {
            Some(Sleep::Minutes(minutes)) => {
                let me = Rc::downgrade(self);
                let clock = glib::timeout_add_seconds_local_once(minutes * 60, move || {
                    if let Some(me) = me.upgrade() {
                        me.sleep_clock.replace(None);
                        me.pause();
                        me.state.borrow_mut().sleeping = false;
                        me.notify();
                    }
                });
                self.sleep_clock.replace(Some(clock));
            }
            Some(Sleep::EndOfSong) => self.sleep_at_end.set(true),
            None => {}
        }
        self.state.borrow_mut().sleeping = when.is_some();
        self.notify();
    }

    pub fn cycle_repeat(&self) {
        let next = self.state.borrow().repeat.next();
        self.set_repeat(next);
    }

    pub fn set_repeat(&self, repeat: Repeat) {
        self.state.borrow_mut().repeat = repeat;
        self.notify();
    }

    pub fn jump_to(self: &Rc<Self>, index: usize) {
        if index < self.state.borrow().queue.len() {
            self.load(index, true);
        }
    }

    /// Moves the song at [from] to [to] in the queue (what's playing goes on playing).
    pub fn move_item(&self, from: usize, to: usize) {
        {
            let mut state = self.state.borrow_mut();
            let len = state.queue.len();
            if from == to || from >= len || to >= len {
                return;
            }
            let moved = state.queue.remove(from);
            state.queue.insert(to, moved);
            if let Some(index) = state.index {
                state.index = Some(moved_index(index, from, to));
            }
        }
        self.notify();
    }

    /// Takes the song at [index] out of the queue; the one playing gives way to the next.
    pub fn remove(self: &Rc<Self>, index: usize) {
        let current = self.state.borrow().index;
        if index >= self.state.borrow().queue.len() {
            return;
        }
        self.state.borrow_mut().queue.remove(index);
        match current {
            Some(i) if i == index => {
                let (len, playing) = {
                    let state = self.state.borrow();
                    (state.queue.len(), state.playing)
                };
                if index < len {
                    self.load(index, playing);
                } else {
                    self.stop_here();
                }
            }
            Some(i) if index < i => {
                self.state.borrow_mut().index = Some(i - 1);
                self.notify();
            }
            _ => self.notify(),
        }
    }

    /// Empties what's after the current song.
    pub fn clear_upcoming(&self) {
        {
            let mut state = self.state.borrow_mut();
            let at = state.index.map(|i| i + 1).unwrap_or(0).min(state.queue.len());
            state.queue.truncate(at);
        }
        self.notify();
    }

    /// Carries on with a queue another device was playing, from the same moment.
    pub fn take_over(self: &Rc<Self>, tracks: Vec<Track>, index: usize, seconds: f64, playing: bool) {
        let tracks: Vec<Track> = tracks.into_iter().filter(|t| !t.is_missing()).collect();
        if tracks.is_empty() {
            return;
        }
        let index = index.min(tracks.len() - 1);
        let live = tracks[index].is_live();
        self.state.borrow_mut().queue = tracks;
        if !live {
            self.start_at.set(seconds.max(0.0));
        }
        self.load(index, playing);
    }

    /// The last song was taken out: nothing plays (the volume and repeat stay).
    fn stop_here(&self) {
        self.report_listen();
        self.generation.set(self.generation.get() + 1);
        let _ = self.playbin.set_state(gst::State::Null);
        {
            let mut state = self.state.borrow_mut();
            state.index = if state.queue.is_empty() { None } else { Some(state.queue.len() - 1) };
            state.playing = false;
            state.loading = false;
            state.on_air = None;
            state.personal = None;
            state.live_history.clear();
            state.copy = None;
            state.heard = Heard::default();
        }
        self.notify();
    }

    /// Where live radio is, as a time on the station's clock: what's heard, not what's newest
    /// (None when it can't say, as for internet radio).
    pub fn live_clock(&self) -> Option<i64> {
        let state = self.state.borrow();
        let track = state.current()?;
        if track.is_personal() {
            Some(now_ms())
        } else if track.is_channel() {
            Some(now_ms() - CHANNEL_DELAY_MS)
        } else {
            None
        }
    }

    // ---- Even volume ----

    /// Turns even volume on or off.
    pub fn set_levelling(&self, on: bool) {
        self.leveller.borrow_mut().set_enabled(on);
        self.apply_levelling();
    }

    /// Whether even volume is on, its gain now (dB), and whether that's the song's own.
    pub fn levelling(&self) -> (bool, f64, bool) {
        let l = self.leveller.borrow();
        (l.enabled() && self.level_gain.is_some(), l.gain_db(), l.known())
    }

    fn apply_levelling(&self) {
        if let Some(gain) = &self.level_gain {
            gain.set_property("volume", self.leveller.borrow().amplitude());
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
        // One of the person's own stations: the station skips to its next song (the stream goes on).
        let personal = self.state.borrow().current().filter(|t| t.is_personal()).map(|t| t.source_id.clone());
        if let Some(id) = personal {
            let Some(api) = self.api.borrow().clone() else { return };
            let me = self.clone();
            let heard = self.state.borrow().personal.as_ref().and_then(|p| p.song_key.clone());
            let generation = self.generation.get();
            glib::spawn_future_local(async move {
                match bg(async move { api.personal_skip(&id, heard.as_deref()).await }).await {
                    // Behind the station: tuned in again, to hear the next song now.
                    Ok(result) if result.retune && me.generation.get() == generation => {
                        let index = me.state.borrow().index;
                        if let Some(index) = index {
                            me.load(index, true);
                        }
                    }
                    Ok(_) => {}
                    Err(e) => me.tell_error(&format!("The station didn't skip: {e}")),
                }
            });
            return;
        }
        let following = self.state.borrow().following();
        if let Some(i) = following {
            self.load(i, true);
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

    /// Plays [track] from [seconds] in (a song Listen heard, from where it is in the room).
    pub fn play_from(self: &Rc<Self>, track: Track, seconds: f64) {
        self.start_at.set(seconds.max(0.0));
        self.play_tracks(vec![track], 0);
    }

    /// Jumps to [seconds] once the song has loaded far enough to (the time that takes counts too).
    fn seek_when_ready(self: &Rc<Self>, generation: u64, seconds: f64) {
        let me = Rc::downgrade(self);
        let started = Instant::now();
        glib::timeout_add_local(Duration::from_millis(200), move || {
            let Some(player) = me.upgrade() else { return glib::ControlFlow::Break };
            if player.generation.get() != generation || started.elapsed() > Duration::from_secs(15) {
                return glib::ControlFlow::Break;
            }
            let ready = {
                let state = player.state.borrow();
                !state.loading && state.duration > 0.0
            };
            if !ready {
                return glib::ControlFlow::Continue;
            }
            player.seek(seconds + started.elapsed().as_secs_f64());
            glib::ControlFlow::Break
        });
    }

    pub fn seek(&self, seconds: f64) {
        if self.state.borrow().live() {
            return;
        }
        let seconds = seconds.max(0.0);
        if self.seekable.get() {
            let target = gst::ClockTime::from_mseconds((seconds * 1000.0) as u64);
            let _ = self.playbin.seek_simple(gst::SeekFlags::FLUSH | gst::SeekFlags::KEY_UNIT, target);
        } else {
            // A conversion can't be jumped about in: the server starts it again from there.
            let playing = self.state.borrow().playing;
            let _ = self.playbin.set_state(gst::State::Null);
            self.playbin.set_property("uri", with_start(&self.stream.borrow(), seconds));
            self.offset.set(seconds);
            let _ = self.playbin.set_state(if playing { gst::State::Playing } else { gst::State::Paused });
        }
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
            let (volume, repeat) = (state.volume, state.repeat);
            *state = State {
                volume,
                repeat,
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
        let start_at = self.start_at.replace(0.0);
        self.offset.set(0.0);
        self.seekable.set(true);
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
            state.personal = None;
            state.description = String::new();
            state.copy = None;
            state.heard = Heard::default();
            state.live_history.clear();
        }
        self.leveller.borrow_mut().new_song(None);
        self.apply_levelling();
        self.notify();
        let Some(api) = self.api.borrow().clone() else { return };
        let (kbps, thorough) = self.choices.get();
        let me = self.clone();
        glib::spawn_future_local(async move {
            let uri = if let Some(live) = track.live_stream() {
                me.state.borrow_mut().description = match live.kind {
                    LiveKind::Channel => "SiriusXM".into(),
                    LiveKind::Radio => "Radio".into(),
                    LiveKind::Personal => "Made for you".into(),
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
                        me.leveller.borrow_mut().new_song(resolved.gain_db);
                        me.apply_levelling();
                        let mut state = me.state.borrow_mut();
                        state.description = resolved.description.clone();
                        state.copy = Some(Copy {
                            kbps: resolved.kbps,
                            within_cap: resolved.within_cap,
                            gain_db: resolved.gain_db,
                            transcoded: resolved.transcoded,
                            extension: resolved.extension.clone(),
                        });
                        // The copy found may have the cover the request lacked.
                        if let Some(current) = state.queue.get_mut(index)
                            && current.artwork_url.is_none()
                        {
                            current.artwork_url = resolved.track.artwork_url.clone();
                        }
                        me.seekable.set(resolved.seekable);
                        api.url(&resolved.stream_url)
                    }
                    Err(e) => return me.failed(generation, &e.to_string()),
                }
            };
            if me.generation.get() != generation {
                return;
            }
            me.stream.replace(uri.clone());
            // Starting partway (Listen's "Play from here"): from there, by the server if the
            // stream can't be jumped about in, or by a jump once it's ready.
            let mut uri = uri;
            if start_at > 0.0 && track.live_stream().is_none() {
                if me.seekable.get() {
                    me.seek_when_ready(generation, start_at);
                } else {
                    uri = with_start(&uri, start_at);
                    me.offset.set(start_at);
                    me.state.borrow_mut().position = start_at;
                }
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
        // Not on a personal station, where "next" skips a song on the air.
        let has_next = self.state.borrow().has_next() && !self.state.borrow().live();
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
                let (live, has_next, again) = {
                    let state = self.state.borrow();
                    (state.live(), state.has_next(), state.repeat == Repeat::One)
                };
                if self.sleep_at_end.replace(false) && !live {
                    // The sleep timer: stop here.
                    self.report_listen();
                    self.reported.set(true);
                    let mut state = self.state.borrow_mut();
                    state.playing = false;
                    state.sleeping = false;
                    drop(state);
                    let _ = self.playbin.set_state(gst::State::Null);
                    self.notify();
                } else if again && !live {
                    // Repeat one: the same song from the start.
                    if let Some(i) = self.state.borrow().index {
                        let me = self.clone();
                        glib::idle_add_local_once(move || me.load(i, true));
                    }
                } else if live {
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
                    self.read_caps();
                    if self.state.borrow().loading {
                        self.state.borrow_mut().loading = false;
                        self.notify();
                    }
                }
            }
            MessageView::DurationChanged(..) => self.tick(),
            MessageView::Tag(tag) => {
                let tags = tag.tags();
                let mut heard = self.state.borrow().heard.clone();
                if let Some(codec) = tags.get::<gst::tags::AudioCodec>() {
                    heard.codec = Some(codec.get().to_string());
                }
                if let Some(container) = tags.get::<gst::tags::ContainerFormat>() {
                    heard.container = Some(container.get().to_string());
                }
                let bitrate = tags
                    .get::<gst::tags::Bitrate>()
                    .map(|b| b.get())
                    .or_else(|| tags.get::<gst::tags::NominalBitrate>().map(|b| b.get()))
                    .filter(|b| *b > 0);
                if bitrate.is_some() {
                    heard.bitrate = bitrate;
                }
                if heard != self.state.borrow().heard {
                    self.state.borrow_mut().heard = heard;
                    self.notify();
                }
            }
            MessageView::Element(element) => {
                // Even volume: the level of the music, four times a second.
                let Some(s) = element.structure().filter(|s| s.name() == "level") else { return };
                let Ok(rms) = s.get::<glib::ValueArray>("rms") else { return };
                let loudest = rms.iter().filter_map(|v| v.get::<f64>().ok()).fold(f64::NEG_INFINITY, f64::max);
                if loudest.is_finite() && self.state.borrow().playing {
                    self.leveller.borrow_mut().heard(loudest, LEVEL_INTERVAL_S);
                    self.apply_levelling();
                }
            }
            _ => {}
        }
    }

    /// The decoded audio's sample rate, channels and format, from where it comes in.
    fn read_caps(&self) {
        let Some(caps) = self.audio_in.as_ref().and_then(|p| p.current_caps()) else { return };
        let Some(s) = caps.structure(0) else { return };
        let mut heard = self.state.borrow().heard.clone();
        heard.rate = s.get::<i32>("rate").ok();
        heard.channels = s.get::<i32>("channels").ok();
        heard.format = s.get::<String>("format").ok();
        if heard != self.state.borrow().heard {
            self.state.borrow_mut().heard = heard;
            self.notify();
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
                state.position = p + self.offset.get();
            }
            // (Started partway by the server, the stream knows only what's left: the song's own length stands.)
            if let Some(d) = duration.filter(|d| *d > 0.0 && !state.live() && self.offset.get() == 0.0) {
                state.duration = d;
            }
        }
        self.notify();
    }

    // ---- Live radio ----

    /// Thumbs the song on one of the person's own stations: "UP" (more like it), "DOWN" (never
    /// again, and it moves on) or, given again, takes it back. A Pandora station's song too
    /// (down skips it, as Pandora does).
    pub fn thumb(self: &Rc<Self>, direction: &str) {
        let station_song = self.state.borrow().current().filter(|t| t.is_station_track()).cloned();
        if let Some(track) = station_song {
            return self.station_thumb(track, direction);
        }
        let (id, key, next) = {
            let state = self.state.borrow();
            let Some(track) = state.current().filter(|t| t.is_personal()) else { return };
            let Some(info) = &state.personal else { return };
            let Some(key) = info.song_key.clone() else { return };
            let next = if info.thumb.as_deref() == Some(direction) { "NONE" } else { direction };
            (track.source_id.clone(), key, next.to_string())
        };
        if let Some(info) = self.state.borrow_mut().personal.as_mut() {
            info.thumb = (next != "NONE").then(|| next.clone());
        }
        self.notify();
        let Some(api) = self.api.borrow().clone() else { return };
        let me = self.clone();
        glib::spawn_future_local(async move {
            if let Err(e) = bg(async move { api.personal_thumb(&id, &key, &next).await }).await {
                me.tell_error(&format!("The station didn't take the thumb: {e}"));
            }
        });
    }

    fn station_thumb(self: &Rc<Self>, track: Track, direction: &str) {
        let Some(via) = track.via.clone() else { return };
        let Some((station, song)) = track.source_id.strip_prefix("stationtrack:").map(split_station) else {
            return;
        };
        let next = {
            let mut state = self.state.borrow_mut();
            let next = if state.thumbs.get(&song).map(String::as_str) == Some(direction) { "NONE" } else { direction };
            if next == "NONE" {
                state.thumbs.remove(&song);
            } else {
                state.thumbs.insert(song.clone(), next.to_string());
            }
            next.to_string()
        };
        self.notify();
        let elapsed = (self.state.borrow().position * 1000.0).round() as i64;
        let Some(api) = self.api.borrow().clone() else { return };
        let me = self.clone();
        let down = next == "DOWN";
        glib::spawn_future_local(async move {
            match bg(async move { api.station_thumb(&via, &station, &song, &next, elapsed).await }).await {
                Ok(()) if down => me.next(),
                Ok(()) => {}
                Err(e) => me.tell_error(&format!("Pandora didn't take the thumb: {e}")),
            }
        });
    }

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
                    LiveKind::Personal => {
                        let id = track.source_id.clone();
                        // Where this player is in its stream: the server says what that is.
                        let position = player.playbin.query_position::<gst::ClockTime>().map(|t| t.mseconds() as i64);
                        let info = bg(async move { asking.personal_now(&id, position).await }).await.ok();
                        if player.generation.get() != generation {
                            return;
                        }
                        // Far behind the station (each stall while loading leaves it further behind): tuned in again.
                        let behind = info.as_ref().and_then(|i| i.behind_ms).unwrap_or(0);
                        let playing = player.state.borrow().playing;
                        if playing && behind > MAX_BEHIND_MS && player.caught_up.get().is_none_or(|at| at.elapsed() > Duration::from_secs(60)) {
                            player.caught_up.set(Some(Instant::now()));
                            let index = player.state.borrow().index;
                            if let Some(index) = index {
                                player.load(index, true);
                            }
                            return;
                        }
                        let on_air = info.as_ref().and_then(|i| i.now.clone()).map(OnAir::of);
                        if let Some(info) = info {
                            let changed = player.state.borrow().personal.as_ref().map(|p| (p.dj.clone(), p.next.len(), p.recent.len(), p.thumb.clone()))
                                != Some((info.dj.clone(), info.next.len(), info.recent.len(), info.thumb.clone()));
                            player.state.borrow_mut().personal = Some(info);
                            if changed {
                                player.notify();
                            }
                        }
                        on_air
                    }
                    LiveKind::Channel => {
                        let Some((kind, id)) = track.channel_parts() else { return };
                        let via = via.clone();
                        // What's heard is behind the newest of the stream (about three segments).
                        let at = now_ms() - CHANNEL_DELAY_MS;
                        bg(async move { asking.channel_now(&via, &kind, &id, at).await })
                            .await
                            .ok()
                            .flatten()
                            .map(OnAir::of)
                    }
                    LiveKind::Radio => {
                        let via = via.clone();
                        let id = track.source_id.trim_start_matches("hls:").to_string();
                        bg(async move { asking.radio_now(&via, &id).await })
                            .await
                            .ok()
                            .flatten()
                            .map(|(title, now)| match (now, title.split_once(" - ")) {
                                (Some(now), _) => OnAir::of(now),
                                (None, Some((artist, song))) => OnAir {
                                    title: song.trim().into(),
                                    artist: artist.trim().into(),
                                    ..Default::default()
                                },
                                (None, None) => OnAir {
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
                    let mut state = player.state.borrow_mut();
                    // What was on goes into what was played (once).
                    if let Some(before) = state.on_air.take() {
                        state.live_history.retain(|h| h.title != before.title || h.artist != before.artist);
                        state.live_history.insert(0, before);
                        state.live_history.truncate(50);
                    }
                    state.on_air = on_air.map(|o| OnAir { heard_at: now_ms(), ..o });
                    drop(state);
                    player.notify();
                } else if let Some(on_air) = on_air {
                    let mut state = player.state.borrow_mut();
                    let heard_at = state.on_air.as_ref().map(|o| o.heard_at).unwrap_or_else(now_ms);
                    state.on_air = Some(OnAir { heard_at, ..on_air });
                }
                drop(player);
                let every = match kind {
                    LiveKind::Channel => 15,
                    LiveKind::Personal => 3,
                    LiveKind::Radio => 20,
                };
                glib::timeout_future(Duration::from_secs(every)).await;
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

/// A Pandora station song's "station:song" ids.
fn split_station(ids: &str) -> (String, String) {
    match ids.split_once(':') {
        Some((station, song)) => (station.to_string(), song.to_string()),
        None => (String::new(), ids.to_string()),
    }
}

/// Where the song at [index] is after the one at [from] moved to [to].
fn moved_index(index: usize, from: usize, to: usize) -> usize {
    if from == index {
        to
    } else if from < index && to >= index {
        index - 1
    } else if from > index && to <= index {
        index + 1
    } else {
        index
    }
}

/// How often GStreamer's `level` measures, for even volume.
const LEVEL_INTERVAL_S: f64 = 0.25;

/// How far behind the newest of a SiriusXM channel's stream what's heard is (about three segments).
const CHANNEL_DELAY_MS: i64 = 30_000;

/// Even volume's part of the pipeline: the music's level is measured, a gain set, and a limiter
/// stops boosts clipping. Returns the filter, its gain, and where the decoded audio comes in
/// (None without GStreamer's good plug-ins: songs then play unlevelled).
fn levelling_filter() -> Option<(gst::Element, gst::Element, gst::Pad)> {
    let bin = gst::parse::bin_from_description(
        &format!(
            "audioconvert name=levelin ! level name=level interval={} post-messages=true ! volume name=levelgain ! audioconvert ! rglimiter ! audioconvert",
            (LEVEL_INTERVAL_S * 1e9) as u64
        ),
        true,
    )
    .map_err(|e| eprintln!("Even volume isn't available (GStreamer's good plug-ins): {e}"))
    .ok()?;
    let gain = bin.by_name("levelgain")?;
    let pad = bin.by_name("levelin")?.static_pad("sink")?;
    Some((bin.upcast(), gain, pad))
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn streams_that_cant_be_jumped_about_in_start_again_from_there() {
        assert_eq!(with_start("http://s/api/stream?via=a&id=b", 92.04), "http://s/api/stream?via=a&id=b&start=92.0");
        assert_eq!(with_start("http://s/x.flac", 5.0), "http://s/x.flac?start=5.0");
    }

    #[test]
    fn the_sign_in_goes_only_to_the_server() {
        let server = origin("https://Music.example.com").unwrap();
        assert_eq!(server, "https://music.example.com:443");
        assert_eq!(origin("https://music.example.com:443/api/stream?x=1").as_deref(), Some(server.as_str()));
        // Plain HTTP, another port or another name: not the server.
        assert_ne!(origin("http://music.example.com/api/stream").unwrap(), server);
        assert_ne!(origin("https://music.example.com:8443/").unwrap(), server);
        assert_ne!(origin("https://music.example.com.evil.example/").unwrap(), server);
        assert_eq!(origin("not a url"), None);
    }

    fn song(id: &str) -> Track {
        Track {
            source: "JELLYFIN".into(),
            source_id: id.into(),
            title: id.into(),
            ..Default::default()
        }
    }

    #[test]
    fn moving_songs_keeps_the_one_playing_as_the_web_player_does() {
        // Playing c (2) in a b c d e.
        assert_eq!(moved_index(2, 2, 0), 0);
        assert_eq!(moved_index(2, 0, 3), 1);
        assert_eq!(moved_index(2, 4, 1), 3);
        assert_eq!(moved_index(2, 3, 4), 2);
        let mut state = State {
            queue: vec![song("a"), song("b"), song("c")],
            index: Some(0),
            ..Default::default()
        };
        assert_eq!(state.upcoming().len(), 2);
        state.index = Some(2);
        assert!(state.upcoming().is_empty());
    }

    #[test]
    fn repeat_goes_round_the_queue_and_cycles_like_the_web_player() {
        let mut state = State {
            queue: vec![song("a"), song("b")],
            index: Some(0),
            ..Default::default()
        };
        assert_eq!(state.following(), Some(1));
        state.index = Some(1);
        assert_eq!(state.following(), None);
        assert!(!state.has_next());
        state.repeat = Repeat::All;
        assert_eq!(state.following(), Some(0));
        assert!(state.has_next());
        assert_eq!(Repeat::Off.next(), Repeat::All);
        assert_eq!(Repeat::All.next(), Repeat::One);
        assert_eq!(Repeat::One.next(), Repeat::Off);
    }
}

/// A web address's scheme, name and port ("https://music.example.com:443"): which server it's on.
fn origin(address: &str) -> Option<String> {
    let url = url::Url::parse(address).ok()?;
    Some(format!("{}://{}:{}", url.scheme(), url.host_str()?.to_ascii_lowercase(), url.port_or_known_default()?))
}

/// [uri] started [seconds] in, by the server (for streams that can't be jumped about in).
fn with_start(uri: &str, seconds: f64) -> String {
    let joiner = if uri.contains('?') { '&' } else { '?' };
    format!("{uri}{joiner}start={seconds:.1}")
}
