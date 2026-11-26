//! This app as one of the person's devices, like Spotify Connect (as the web player's
//! web/src/player/connect.ts and the phone app's ConnectClient): it keeps a websocket open to the
//! server's devices hub (`/api/connect/ws`), says what it's playing, hears what the person's other
//! devices (web players, phones, speakers the server plays on) are doing, and does what they ask.
//! Playback can move between them.

use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::time::{Duration, Instant};

use futures_util::{SinkExt, StreamExt};
use gtk::glib;
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;

use crate::api::{Api, Track, enc};
use crate::player::{Player, Repeat, State};

/// What a device is playing, as it last said (the server moves the position along).
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PlayState {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub track: Option<Track>,
    /// The whole queue: sent when playback moves to another device, not in the device list.
    pub queue: Vec<Track>,
    pub index: i64,
    pub position_ms: i64,
    pub duration_ms: i64,
    pub playing: bool,
    /// 0 to 1.
    pub volume: f64,
    pub live: bool,
    /// "off", "all" or "one".
    pub repeat: String,
}

/// One of the person's devices: a web player, a phone, a speaker the server plays on.
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
#[serde(default)]
pub struct Device {
    pub id: String,
    pub name: String,
    /// "web", "phone" or "speaker".
    pub kind: String,
    pub state: Option<PlayState>,
    /// Playing, or the one that played last: what the others show and control.
    pub active: bool,
    /// What it is, for speakers the server plays on ("Sonos", "Music Assistant · Chromecast").
    pub detail: Option<String>,
}

/// Something another device asks this one to do ("take" carries the queue it hands over).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Command {
    pub action: String,
    pub position_ms: Option<i64>,
    pub volume: Option<f64>,
    pub state: Option<PlayState>,
}

#[derive(Debug, Deserialize)]
struct Inbound {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    devices: Option<Vec<Device>>,
    #[serde(default)]
    you: Option<String>,
    #[serde(default)]
    command: Option<Command>,
}

/// From the connection to the main thread.
enum Event {
    Online,
    Offline,
    Text(String),
}

/// The devices as the server last listed them, and this app's place among them.
pub struct Hub {
    player: Rc<Player>,
    devices: RefCell<Vec<Device>>,
    me: RefCell<String>,
    online: Cell<bool>,
    /// When the list arrived, for moving positions along.
    received_at: Cell<Instant>,
    out: RefCell<Option<mpsc::UnboundedSender<String>>>,
    listeners: RefCell<Vec<Rc<dyn Fn()>>>,
    last_sent: RefCell<String>,
    last_sent_at: Cell<Option<Instant>>,
    last_position: Cell<f64>,
    /// The queue as last reported (its songs' keys), to notice changes.
    last_queue: RefCell<Vec<String>>,
    /// What else was last reported (the song, playing or not, the volume…), to notice changes.
    last_gist: RefCell<String>,
    /// Bumped on every start and stop: an older connection's events are dropped.
    generation: Cell<u64>,
}

thread_local! {
    static HUB: RefCell<Option<Rc<Hub>>> = const { RefCell::new(None) };
}

/// The hub (made with the player, once).
pub fn hub() -> Option<Rc<Hub>> {
    HUB.with(|h| h.borrow().clone())
}

/// Makes the hub for [player]: it reports the player's changes while connected.
pub fn install(player: Rc<Player>, id: String) {
    let hub = Rc::new(Hub {
        player: player.clone(),
        devices: RefCell::new(vec![]),
        me: RefCell::new(id),
        online: Cell::new(false),
        received_at: Cell::new(Instant::now()),
        out: RefCell::new(None),
        listeners: RefCell::new(vec![]),
        last_sent: RefCell::new(String::new()),
        last_sent_at: Cell::new(None),
        last_position: Cell::new(0.0),
        last_queue: RefCell::new(vec![]),
        last_gist: RefCell::new(String::new()),
        generation: Cell::new(0),
    });
    let weak = Rc::downgrade(&hub);
    player.subscribe(move |state| {
        if let Some(hub) = weak.upgrade() {
            hub.maybe_report(state, false);
        }
    });
    HUB.with(|h| h.replace(Some(hub)));
}

/// A new id for this app among the person's devices.
pub fn new_device_id() -> String {
    use rand::RngExt;
    let mut rng = rand::rng();
    (0..12).map(|_| format!("{:02x}", rng.random::<u8>())).collect()
}

impl Hub {
    pub fn me(&self) -> String {
        self.me.borrow().clone()
    }

    pub fn online(&self) -> bool {
        self.online.get()
    }

    /// The person's other devices.
    pub fn others(&self) -> Vec<Device> {
        let me = self.me();
        self.devices.borrow().iter().filter(|d| d.id != me).cloned().collect()
    }

    /// Told when the devices (or whether it's connected) change.
    pub fn subscribe(&self, listener: impl Fn() + 'static) {
        self.listeners.borrow_mut().push(Rc::new(listener));
    }

    fn changed(&self) {
        let listeners: Vec<_> = self.listeners.borrow().clone();
        for listener in listeners {
            listener();
        }
    }

    /// The device the others show and control, if it isn't this one (and this one isn't playing).
    pub fn remote(&self) -> Option<Device> {
        let me = self.me();
        let playing_here = self.player.state().playing;
        remote_of(&self.devices.borrow(), &me, playing_here)
    }

    /// Where [device]'s song is now, in milliseconds.
    pub fn position_of(&self, device: &Device) -> i64 {
        position_of(device, self.received_at.get().elapsed())
    }

    /// Moves playback to [to] (this app's id, to take it here).
    pub fn transfer(&self, to: &str) {
        self.send(serde_json::json!({ "type": "transfer", "to": to }));
    }

    /// Asks [target] to play, pause, go to the next or previous song, seek or change its volume.
    pub fn command(&self, target: &str, action: &str, position_ms: Option<i64>, volume: Option<f64>) {
        let mut command = serde_json::json!({ "action": action });
        if let Some(p) = position_ms {
            command["positionMs"] = p.into();
        }
        if let Some(v) = volume {
            command["volume"] = v.into();
        }
        self.send(serde_json::json!({ "type": "command", "target": target, "command": command }));
    }

    fn send(&self, message: serde_json::Value) {
        if let Some(out) = self.out.borrow().as_ref() {
            let _ = out.send(message.to_string());
        }
    }

    /// Connects (again) as [name], signed in with [api].
    pub fn start(self: &Rc<Self>, api: &Api, name: &str) {
        self.stop();
        let Some(token) = api.token().map(str::to_string) else { return };
        let Some(url) = socket_url(api.base(), &self.me(), name) else {
            return;
        };
        let generation = self.generation.get();
        let (out_tx, out_rx) = mpsc::unbounded_channel::<String>();
        let (event_tx, mut event_rx) = mpsc::unbounded_channel::<Event>();
        self.out.replace(Some(out_tx));
        crate::rt::spawn(run(url, token, out_rx, event_tx));
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            while let Some(event) = event_rx.recv().await {
                let Some(hub) = weak.upgrade() else { return };
                if hub.generation.get() != generation {
                    return;
                }
                hub.on_event(event);
            }
        });
    }

    /// Disconnects (signed out).
    pub fn stop(&self) {
        self.generation.set(self.generation.get() + 1);
        self.out.replace(None);
        self.devices.borrow_mut().clear();
        self.online.set(false);
        self.last_sent.borrow_mut().clear();
        self.changed();
    }

    fn on_event(&self, event: Event) {
        match event {
            Event::Online => {
                self.online.set(true);
                self.last_sent.borrow_mut().clear();
                self.maybe_report(&self.player.state(), true);
                self.changed();
            }
            Event::Offline => {
                self.online.set(false);
                self.changed();
            }
            Event::Text(text) => {
                let Ok(message) = serde_json::from_str::<Inbound>(&text) else {
                    return;
                };
                match message.kind.as_str() {
                    "devices" => {
                        if let Some(you) = message.you {
                            self.me.replace(you);
                        }
                        self.devices.replace(message.devices.unwrap_or_default());
                        self.received_at.set(Instant::now());
                        self.changed();
                    }
                    "command" => {
                        if let Some(command) = message.command {
                            self.obey(command);
                        }
                    }
                    _ => {}
                }
            }
        }
    }

    /// Does what another device asked.
    fn obey(&self, command: Command) {
        let player = &self.player;
        match command.action.as_str() {
            "play" => player.play(),
            "pause" => player.pause(),
            "next" => player.next(),
            "previous" => player.previous(),
            "seek" => {
                if let Some(ms) = command.position_ms {
                    player.seek(ms as f64 / 1000.0);
                }
            }
            "volume" => {
                if let Some(v) = command.volume {
                    player.set_volume(v);
                }
            }
            "take" => {
                if let Some(state) = command.state.filter(|s| !s.queue.is_empty()) {
                    let index = state.index.max(0) as usize;
                    player.take_over(state.queue, index, state.position_ms as f64 / 1000.0, state.playing);
                }
            }
            _ => {}
        }
    }

    /// Says what's playing here when what the others see changed: another song, playing or not,
    /// a jump, the volume; and now and then while playing, to stay in step.
    fn maybe_report(&self, state: &State, force: bool) {
        if !self.online.get() {
            return;
        }
        let queue: Vec<String> = state.queue.iter().map(crate::ui::widgets::track_key).collect();
        let since = self.last_sent_at.get().map(|t| t.elapsed().as_secs_f64());
        let expected = self.last_position.get() + if state.playing { since.unwrap_or(0.0) } else { 0.0 };
        let jumped = (state.position - expected).abs() > 3.0;
        let stale = state.playing && since.is_none_or(|s| s > 15.0);
        // Moving along by itself isn't news (the report is made only when there is some).
        let gist = format!(
            "{:?}|{}|{:.2}|{:?}|{}|{}",
            state.index,
            state.playing,
            state.volume,
            state.repeat,
            state.duration.round(),
            state.live()
        );
        let same = *self.last_queue.borrow() == queue && *self.last_gist.borrow() == gist;
        if !force && same && !jumped && !stale {
            return;
        }
        let text = serde_json::json!({ "type": "state", "state": play_state(state) }).to_string();
        if *self.last_sent.borrow() == text {
            return;
        }
        self.last_queue.replace(queue);
        self.last_gist.replace(gist);
        self.last_sent.replace(text.clone());
        self.last_sent_at.set(Some(Instant::now()));
        self.last_position.set(state.position);
        if let Some(out) = self.out.borrow().as_ref() {
            let _ = out.send(text);
        }
    }
}

/// What this app reports of [state].
pub fn play_state(state: &State) -> PlayState {
    PlayState {
        track: state.current().cloned(),
        queue: state.queue.clone(),
        index: state.index.map(|i| i as i64).unwrap_or(-1),
        position_ms: (state.position * 1000.0).round() as i64,
        duration_ms: (state.duration * 1000.0).round() as i64,
        playing: state.playing,
        volume: (state.volume * 100.0).round() / 100.0,
        live: state.live(),
        repeat: match state.repeat {
            Repeat::Off => "off",
            Repeat::All => "all",
            Repeat::One => "one",
        }
        .into(),
    }
}

/// The device another device should show and control, if it isn't [me].
pub fn remote_of(devices: &[Device], me: &str, playing_here: bool) -> Option<Device> {
    let active = devices.iter().find(|d| d.active)?;
    if active.id == me || playing_here || active.state.as_ref().and_then(|s| s.track.as_ref()).is_none() {
        return None;
    }
    Some(active.clone())
}

/// Where [device]'s song is, [since] the list arrived.
pub fn position_of(device: &Device, since: Duration) -> i64 {
    let Some(state) = &device.state else { return 0 };
    let moved = state.position_ms
        + if state.playing && !state.live {
            since.as_millis() as i64
        } else {
            0
        };
    if state.duration_ms > 0 {
        moved.min(state.duration_ms)
    } else {
        moved
    }
}

/// `wss://music.example.com/api/connect/ws?id=…&name=…&kind=web`.
fn socket_url(base: &str, id: &str, name: &str) -> Option<String> {
    let ws = match base.strip_prefix("https://") {
        Some(rest) => format!("wss://{rest}"),
        None => format!("ws://{}", base.strip_prefix("http://")?),
    };
    Some(format!("{ws}/api/connect/ws?id={}&name={}&kind=web", enc(id), enc(name)))
}

/// The connection, on the network runtime: connects, passes messages both ways, and tries again
/// (waiting longer each time, up to 30 seconds) until the app stops wanting it.
async fn run(url: String, token: String, mut out: mpsc::UnboundedReceiver<String>, events: mpsc::UnboundedSender<Event>) {
    let mut retries = 0u32;
    loop {
        let Ok(mut request) = url.as_str().into_client_request() else {
            return;
        };
        if let Ok(value) = format!("Bearer {token}").parse() {
            request.headers_mut().insert("Authorization", value);
        }
        if let Ok(value) = concat!("SuperAwesomeMagicalMusic-Desktop/", env!("CARGO_PKG_VERSION")).parse() {
            request.headers_mut().insert("User-Agent", value);
        }
        match tokio_tungstenite::connect_async(request).await {
            Ok((socket, _)) => {
                retries = 0;
                if events.send(Event::Online).is_err() {
                    return;
                }
                let (mut write, mut read) = socket.split();
                let mut ping = tokio::time::interval(Duration::from_secs(25));
                ping.tick().await;
                let stopped = loop {
                    tokio::select! {
                        message = read.next() => match message {
                            Some(Ok(Message::Text(text))) => {
                                if events.send(Event::Text(text.to_string())).is_err() {
                                    break true;
                                }
                            }
                            Some(Ok(Message::Close(_))) | Some(Err(_)) | None => break false,
                            Some(Ok(_)) => {}
                        },
                        text = out.recv() => match text {
                            Some(text) => {
                                if write.send(Message::Text(text.into())).await.is_err() {
                                    break false;
                                }
                            }
                            None => break true,
                        },
                        _ = ping.tick() => {
                            if write.send(Message::Ping(Vec::new().into())).await.is_err() {
                                break false;
                            }
                        }
                    }
                };
                if stopped {
                    let _ = write.send(Message::Close(None)).await;
                    return;
                }
                if events.send(Event::Offline).is_err() {
                    return;
                }
            }
            Err(e) => {
                eprintln!("Couldn't join your devices: {e}");
                if events.is_closed() {
                    return;
                }
            }
        }
        let wait = Duration::from_millis((1000u64 << retries.min(5)).min(30_000));
        retries += 1;
        // Waiting, but gone at once if the app stops wanting it.
        tokio::select! {
            _ = tokio::time::sleep(wait) => {}
            _ = events.closed() => return,
        }
        // What was asked to be sent while away is out of date: the app reports again on connecting.
        while out.try_recv().is_ok() {}
        if out.is_closed() {
            return;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn device(id: &str, active: bool, playing: bool) -> Device {
        Device {
            id: id.into(),
            name: id.into(),
            kind: "web".into(),
            active,
            state: Some(PlayState {
                track: Some(Track {
                    source: "JELLYFIN".into(),
                    source_id: "s".into(),
                    title: "Song".into(),
                    ..Default::default()
                }),
                position_ms: 10_000,
                duration_ms: 60_000,
                playing,
                ..Default::default()
            }),
            detail: None,
        }
    }

    #[test]
    fn the_server_s_messages_are_read_as_the_web_player_reads_them() {
        let text = r#"{"type":"devices","you":"abc12345","devices":[{"id":"p1","name":"Pixel","kind":"phone","active":true,
            "state":{"track":{"source":"JELLYFIN","sourceId":"1","title":"T","artist":"A"},"index":0,"positionMs":5000,"durationMs":9000,"playing":true,"volume":0.5,"live":false,"repeat":"off"}},
            {"id":"s1","name":"Kitchen","kind":"speaker","detail":"Sonos"}]}"#;
        let message: Inbound = serde_json::from_str(text).unwrap();
        assert_eq!(message.kind, "devices");
        assert_eq!(message.you.as_deref(), Some("abc12345"));
        let devices = message.devices.unwrap();
        assert_eq!(devices[0].state.as_ref().unwrap().track.as_ref().unwrap().title, "T");
        assert_eq!(devices[1].detail.as_deref(), Some("Sonos"));
        let take: Inbound = serde_json::from_str(
            r#"{"type":"command","command":{"action":"take","from":"p1","state":{"queue":[{"source":"JELLYFIN","sourceId":"1","title":"T","artist":"A"}],"index":0,"positionMs":61000,"playing":true}}}"#,
        )
        .unwrap();
        let command = take.command.unwrap();
        assert_eq!(command.action, "take");
        assert_eq!(command.state.unwrap().position_ms, 61_000);
    }

    #[test]
    fn what_this_app_says_is_what_the_hub_reads() {
        let state = State {
            queue: vec![Track {
                source: "JELLYFIN".into(),
                source_id: "1".into(),
                title: "T".into(),
                ..Default::default()
            }],
            index: Some(0),
            position: 12.3456,
            duration: 200.0,
            playing: true,
            volume: 0.8,
            ..Default::default()
        };
        let value = serde_json::to_value(play_state(&state)).unwrap();
        assert_eq!(value["positionMs"], 12_346);
        assert_eq!(value["durationMs"], 200_000);
        assert_eq!(value["index"], 0);
        assert_eq!(value["repeat"], "off");
        assert_eq!(value["track"]["sourceId"], "1");
        assert_eq!(value["queue"][0]["title"], "T");
    }

    #[test]
    fn another_device_playing_is_shown_here_unless_this_one_plays() {
        let devices = vec![device("me", false, false), device("phone", true, true)];
        assert_eq!(remote_of(&devices, "me", false).unwrap().id, "phone");
        assert!(remote_of(&devices, "me", true).is_none());
        assert!(remote_of(&devices, "phone", false).is_none());
        // Positions move along while it plays, up to the song's end.
        assert_eq!(position_of(&devices[1], Duration::from_secs(2)), 12_000);
        assert_eq!(position_of(&devices[1], Duration::from_secs(600)), 60_000);
        assert_eq!(position_of(&devices[0], Duration::from_secs(2)), 10_000);
    }

    #[test]
    fn the_socket_is_on_the_server() {
        assert_eq!(
            socket_url("https://music.example.com", "abcdef12", "Linux desktop (box)").unwrap(),
            "wss://music.example.com/api/connect/ws?id=abcdef12&name=Linux%20desktop%20%28box%29&kind=web"
        );
        assert!(
            socket_url("http://127.0.0.1:8099", "abcdef12", "x")
                .unwrap()
                .starts_with("ws://127.0.0.1:8099/api/connect/ws?")
        );
    }
}
