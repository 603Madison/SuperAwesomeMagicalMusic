//! SuperAwesomeMagicalOrchestrator, through its agent on this desktop (the session bus).
//!
//! Signing in: where someone is signed in to the agent, the app asks it for a short-lived ticket,
//! hands that to its own server, and is signed in as the same person, exactly as if they had
//! signed in by hand. Any failure (no agent, nobody signed in there, the app isn't listed, the
//! server says no) is the usual sign-in, with no error shown: this is only ever a shortcut. The
//! agent is optional: the app needs nothing installed for this (the bus is GTK's own gio), and
//! without it nothing changes.
//!
//! There's no "agree to this server" step here as on the phone: on a desktop the session bus is
//! the boundary (anything running as the person could read the app's token anyway).
//!
//! Carry on: while music plays, the agent is told what it is ([`Playing`]), so the person's other
//! devices can offer it; Preferences › "Show what I'm playing on my other devices" turns it off.

use std::cell::RefCell;
use std::time::{Duration, Instant};

use anyhow::{Result, anyhow, bail};
use gtk::glib::prelude::ToVariant;
use gtk::prelude::*;
use gtk::{gio, glib};
use serde::Deserialize;

use crate::api::{Api, Track, User, enc};
use crate::player::State;
use crate::rt::bg;

/// This app's name in the orchestrator's list of the family's apps, and in its tickets.
pub const APP: &str = "music";

const BUS_NAME: &str = "app.superawesomemagical.Orchestrator";
const PATH: &str = "/app/superawesomemagical/Orchestrator";
const INTERFACE: &str = "app.superawesomemagical.Orchestrator1";

/// The agent is started by the bus when first asked, and may ask its server: long enough for
/// that, short enough that the sign-in screen isn't left waiting on it.
const TIMEOUT_MS: i32 = 10_000;

pub const WITH_ORCHESTRATOR: &str = "Continue with SuperAwesomeMagicalOrchestrator";

/// What the agent's `Status()` says, as far as this app cares.
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Status {
    pub signed_in: bool,
    pub username: String,
    pub display_name: String,
}

impl Status {
    /// From the agent's JSON; anything else is "nobody signed in".
    pub fn parse(json: &str) -> Status {
        serde_json::from_str(json).unwrap_or_default()
    }

    /// The button's words on the sign-in screen, or none for no button.
    pub fn label(&self) -> Option<String> {
        if !self.signed_in {
            return None;
        }
        let name = if self.display_name.trim().is_empty() {
            self.username.trim()
        } else {
            self.display_name.trim()
        };
        Some(if name.is_empty() {
            WITH_ORCHESTRATOR.to_string()
        } else {
            format!("Continue as {name}")
        })
    }
}

/// Whether to ask the agent by itself when the app opens: only when nobody is signed in, the
/// person didn't sign out on purpose (they chose to be signed out: it stays that way until they
/// sign in again), and it hasn't been tried since the app started.
pub fn asks_at_start(signed_in: bool, signed_out_on_purpose: bool, tried_already: bool) -> bool {
    !signed_in && !signed_out_on_purpose && !tried_already
}

/// The server a ticket is for, as the app keeps servers: `https://music.example.com`.
pub fn server_origin(url: &str) -> Option<String> {
    let parsed = url::Url::parse(url.trim()).ok()?;
    ((parsed.scheme() == "https" || parsed.scheme() == "http") && parsed.host_str().is_some())
        .then(|| parsed.origin().ascii_serialization())
}

/// Calls the agent on the session bus. Runs on the main loop without blocking it.
async fn call(method: &str, parameters: Option<glib::Variant>, reply: Option<&glib::VariantTy>) -> Result<glib::Variant> {
    let bus = gio::bus_get_future(gio::BusType::Session).await?;
    let answer = bus
        .call_future(
            Some(BUS_NAME),
            PATH,
            INTERFACE,
            method,
            parameters.as_ref(),
            reply,
            gio::DBusCallFlags::NONE,
            TIMEOUT_MS,
        )
        .await?;
    Ok(answer)
}

/// Who is signed in to the orchestrator on this desktop (nobody, when there's no agent).
pub async fn status() -> Status {
    match call("Status", None, Some(glib::VariantTy::new("(s)").expect("a type"))).await {
        Ok(answer) => answer.get::<(String,)>().map(|(json,)| Status::parse(&json)).unwrap_or_default(),
        Err(_) => Status::default(),
    }
}

/// A ticket for this app's server: (the server's address, the ticket).
async fn ticket() -> Result<(String, String)> {
    let answer = call(
        "SignInTo",
        Some((APP,).to_variant()),
        Some(glib::VariantTy::new("(ss)").expect("a type")),
    )
    .await?;
    answer
        .get::<(String, String)>()
        .ok_or_else(|| anyhow!("The orchestrator's answer wasn't a ticket"))
}

/// A sign-in, as every other way of signing in gives it: the server, the app's token, and who.
pub struct SignedIn {
    pub server: String,
    pub token: String,
    pub user: User,
}

/// Asks the agent for a ticket and hands it to the server it names.
pub async fn sign_in(device: String) -> Result<SignedIn> {
    let (url, ticket) = ticket().await?;
    let Some(server) = server_origin(&url) else {
        bail!("The orchestrator gave no server address");
    };
    let api = Api::new(&server, None);
    let signed = bg(async move { api.orchestrator_sign_in(&ticket, &device).await }).await?;
    let token = signed.token.ok_or_else(|| anyhow!("The server sent no sign-in"))?;
    Ok(SignedIn {
        server,
        token,
        user: signed.user,
    })
}

// ---- Carry on: what's playing ----

/// What's playing, as the agent is told it.
#[derive(Debug, Clone, PartialEq)]
pub struct Now {
    pub title: String,
    pub detail: String,
    pub url: String,
}

/// What to do about what's playing.
#[derive(Debug, PartialEq)]
pub enum Step {
    Nothing,
    Tell(Now),
    /// An empty title: it stopped.
    Stop,
}

/// Activities are forgotten after fifteen minutes: said again well before.
pub const REFRESH: Duration = Duration::from_secs(10 * 60);

/// A page of the web player that opens on another device: Radio for live channels and stations
/// (and songs from one), the album where the song names one (`/album?title=…&artist=…`, as the
/// web's `albumNamed`), else the player itself.
pub fn url_of(server: &str, track: &Track) -> String {
    let base = server.trim_end_matches('/');
    let album = track.album.as_deref().unwrap_or_default().trim();
    if track.is_live() || track.is_station_track() {
        format!("{base}/radio")
    } else if !album.is_empty() && !track.artist.trim().is_empty() {
        format!("{base}/album?title={}&artist={}", enc(album), enc(track.artist.trim()))
    } else {
        format!("{base}/")
    }
}

/// What the player is playing, for the agent: none while nothing is, or while it's paused or
/// only loading (a pause says nothing: what was playing stays on offer elsewhere until the agent
/// forgets it). On a live channel that says what's on, the song with the station after its artist.
pub fn now_of(state: &State, server: &str) -> Option<Now> {
    let track = state.current()?;
    if !state.playing || state.loading {
        return None;
    }
    let (title, detail) = match state.on_air.as_ref().filter(|on| track.is_live() && !on.title.trim().is_empty()) {
        Some(on) if on.artist.trim().is_empty() || on.artist.trim() == track.title.trim() => (on.title.clone(), track.title.clone()),
        Some(on) => (on.title.clone(), format!("{} · {}", on.artist.trim(), track.title.trim())),
        None => (track.title.clone(), track.artist.clone()),
    };
    Some(Now {
        title,
        detail,
        url: url_of(server, track),
    })
}

/// The decision, apart from the bus so it can be tested: tell the agent once per song; nothing
/// while it's what was already told, until [`REFRESH`] has passed; "stopped" once when the queue
/// is empty; and with the setting off, nothing, or "stopped" if something was told before.
pub fn next(share: bool, told: Option<&(Now, Instant)>, has_track: bool, now: Option<Now>, time: Instant) -> Step {
    if !share || !has_track {
        return if told.is_some() { Step::Stop } else { Step::Nothing };
    }
    match (now, told) {
        // Paused or loading: as it was.
        (None, _) => Step::Nothing,
        (Some(now), Some((before, at))) if *before == now && time.duration_since(*at) < REFRESH => Step::Nothing,
        (Some(now), _) => Step::Tell(now),
    }
}

/// Tells the agent what's playing as the player's state changes (it says so four times a second
/// while playing, which is also what brings the ten-minute reminder round).
#[derive(Default)]
pub struct Playing {
    told: RefCell<Option<(Now, Instant)>>,
}

impl Playing {
    pub fn changed(&self, state: &State, server: Option<&str>, share: bool) {
        let now = server.and_then(|server| now_of(state, server));
        let time = Instant::now();
        let step = next(
            share,
            self.told.borrow().as_ref(),
            state.current().is_some() && server.is_some(),
            now,
            time,
        );
        match step {
            Step::Nothing => {}
            Step::Tell(now) => {
                self.told.replace(Some((now.clone(), time)));
                publish(now.title, now.detail, now.url);
            }
            Step::Stop => {
                self.told.replace(None);
                publish(String::new(), String::new(), String::new());
            }
        }
    }

    /// The app is closing: nothing is playing here any more. Sent before the window goes (the
    /// bus is already open, so this only hands the message over; no answer is waited for).
    pub fn closing(&self) {
        if self.told.replace(None).is_none() {
            return;
        }
        if let Some(bus) = gio::Application::default().and_then(|app| app.dbus_connection()) {
            let parameters = (APP, "", "", "").to_variant();
            bus.call(
                Some(BUS_NAME),
                PATH,
                INTERFACE,
                "PublishActivity",
                Some(&parameters),
                None,
                gio::DBusCallFlags::NO_AUTO_START,
                TIMEOUT_MS,
                gio::Cancellable::NONE,
                |_| {},
            );
            let _ = bus.flush_sync(gio::Cancellable::NONE);
        }
    }
}

/// `PublishActivity`, off the main thread's hands: whatever comes of it, nothing is shown.
fn publish(title: String, detail: String, url: String) {
    glib::spawn_future_local(async move {
        let _ = call(
            "PublishActivity",
            Some((APP, title.as_str(), detail.as_str(), url.as_str()).to_variant()),
            None,
        )
        .await;
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::player::OnAir;

    #[test]
    fn asked_at_start_only_when_nobody_is_signed_in_and_nobody_chose_to_sign_out() {
        assert!(asks_at_start(false, false, false));
        assert!(!asks_at_start(true, false, false));
        assert!(!asks_at_start(false, true, false));
        assert!(!asks_at_start(false, false, true));
    }

    #[test]
    fn the_buttons_words() {
        let status = Status::parse(
            r#"{"signedIn":true,"server":"https://orchestrator.example.com","username":"maddie","displayName":"Maddie",
                "connected":true,"device":{"id":"d1","name":"Maddie's laptop"}}"#,
        );
        assert_eq!(status.label().as_deref(), Some("Continue as Maddie"));
        let unnamed = Status {
            display_name: " ".into(),
            ..status.clone()
        };
        assert_eq!(unnamed.label().as_deref(), Some("Continue as maddie"));
        let nameless = Status {
            signed_in: true,
            ..Status::default()
        };
        assert_eq!(nameless.label().as_deref(), Some(WITH_ORCHESTRATOR));
        // Nobody signed in, an agent that says something else, or none at all: no button.
        assert_eq!(Status::parse(r#"{"signedIn":false,"username":"maddie"}"#).label(), None);
        assert_eq!(Status::parse("not json").label(), None);
        assert_eq!(Status::parse("{}").label(), None);
        assert_eq!(Status::default().label(), None);
    }

    #[test]
    fn a_tickets_server_becomes_the_address_the_app_keeps() {
        assert_eq!(
            server_origin("https://music.example.com/").as_deref(),
            Some("https://music.example.com")
        );
        assert_eq!(
            server_origin(" http://192.168.1.20:8090 ").as_deref(),
            Some("http://192.168.1.20:8090")
        );
        assert_eq!(
            server_origin("https://music.example.com:443/x?y").as_deref(),
            Some("https://music.example.com")
        );
        assert_eq!(server_origin(""), None);
        assert_eq!(server_origin("music.example.com"), None);
        assert_eq!(server_origin("sammusic://signed-in"), None);
        assert_eq!(server_origin("file:///etc/passwd"), None);
    }

    fn track(source: &str, id: &str, title: &str, artist: &str, album: Option<&str>) -> Track {
        serde_json::from_value(serde_json::json!({ "source": source, "sourceId": id, "title": title, "artist": artist, "album": album }))
            .expect("a track")
    }

    fn song() -> Track {
        track("JELLYFIN", "s1", "Silver Lining", "The Auroras", Some("Night & Day"))
    }

    fn playing(track: Track) -> State {
        State {
            queue: vec![track],
            index: Some(0),
            playing: true,
            ..State::default()
        }
    }

    const SERVER: &str = "https://music.example.com";

    #[test]
    fn the_address_opens_something_real_in_the_web_player() {
        assert_eq!(
            url_of(SERVER, &song()),
            "https://music.example.com/album?title=Night%20%26%20Day&artist=The%20Auroras"
        );
        assert_eq!(
            url_of("https://music.example.com/", &track("RADIO", "kexp", "KEXP", "Seattle", None)),
            "https://music.example.com/radio"
        );
        assert_eq!(
            url_of(SERVER, &track("SIRIUSXM", "channel:hits1", "Hits 1", "SiriusXM", None)),
            "https://music.example.com/radio"
        );
        assert_eq!(
            url_of(
                SERVER,
                &track("SIRIUSXM", "stationtrack:st1:t9", "A song", "Someone", Some("An album"))
            ),
            "https://music.example.com/radio"
        );
        // No album to open: the player itself.
        assert_eq!(
            url_of(SERVER, &track("YOUTUBE_MUSIC", "v1", "A song", "Someone", None)),
            "https://music.example.com/"
        );
    }

    #[test]
    fn what_is_told_is_the_song_or_what_is_on_a_station() {
        let now = now_of(&playing(song()), SERVER).expect("playing");
        assert_eq!((now.title.as_str(), now.detail.as_str()), ("Silver Lining", "The Auroras"));
        // Paused, loading or nothing queued: nothing to tell.
        assert_eq!(
            now_of(
                &State {
                    playing: false,
                    ..playing(song())
                },
                SERVER
            ),
            None
        );
        assert_eq!(
            now_of(
                &State {
                    loading: true,
                    ..playing(song())
                },
                SERVER
            ),
            None
        );
        assert_eq!(now_of(&State::default(), SERVER), None);
        // A station that says what's on: the song, with the station after its artist.
        let station = track("RADIO", "kexp", "KEXP", "Seattle", None);
        let mut state = playing(station);
        assert_eq!(now_of(&state, SERVER).unwrap().title, "KEXP");
        state.on_air = Some(OnAir {
            title: "Hyperballad".into(),
            artist: "Björk".into(),
            ..OnAir::default()
        });
        let now = now_of(&state, SERVER).unwrap();
        assert_eq!(
            (now.title.as_str(), now.detail.as_str(), now.url.as_str()),
            ("Hyperballad", "Björk · KEXP", "https://music.example.com/radio")
        );
        state.on_air = Some(OnAir {
            title: "The morning show".into(),
            ..OnAir::default()
        });
        assert_eq!(now_of(&state, SERVER).unwrap().detail, "KEXP");
    }

    #[test]
    fn told_once_per_song_again_after_ten_minutes_and_stopped_once() {
        let start = Instant::now();
        let a = now_of(&playing(song()), SERVER);
        let b = now_of(
            &playing(track("JELLYFIN", "s2", "Slow Dawn", "The Auroras", Some("Night & Day"))),
            SERVER,
        );
        // A song starts: told.
        assert_eq!(next(true, None, true, a.clone(), start), Step::Tell(a.clone().unwrap()));
        let told = (a.clone().unwrap(), start);
        // The same song, however often the player says so: nothing, until ten minutes have passed.
        assert_eq!(
            next(true, Some(&told), true, a.clone(), start + Duration::from_secs(9 * 60)),
            Step::Nothing
        );
        assert_eq!(
            next(true, Some(&told), true, a.clone(), start + REFRESH),
            Step::Tell(a.clone().unwrap())
        );
        // Another song: told at once.
        assert_eq!(
            next(true, Some(&told), true, b.clone(), start + Duration::from_secs(1)),
            Step::Tell(b.unwrap())
        );
        // Paused: as it was (not said again, not taken back).
        assert_eq!(next(true, Some(&told), true, None, start + REFRESH), Step::Nothing);
        // The queue emptied: stopped, once.
        assert_eq!(next(true, Some(&told), false, None, start), Step::Stop);
        assert_eq!(next(true, None, false, None, start), Step::Nothing);
        // The setting off: nothing, or stopped if something had been told.
        assert_eq!(next(false, None, true, a.clone(), start), Step::Nothing);
        assert_eq!(next(false, Some(&told), true, a, start), Step::Stop);
    }

    /// The real thing, against a real agent: run with `SAMM_ORCHESTRATOR_E2E=1` on a session bus
    /// where SuperAwesomeMagicalOrchestrator's agent is signed in and lists `music` (see
    /// desktop/README.md). Asks for a ticket, hands it in, reads playlists with the token, and
    /// says what's playing.
    #[test]
    fn signs_in_through_a_real_agent() {
        if std::env::var("SAMM_ORCHESTRATOR_E2E").is_err() {
            return;
        }
        let (label, signed, playlists, published) = glib::MainContext::default().block_on(async {
            let label = status().await.label();
            let signed = sign_in("Linux desktop (test)".into()).await.expect("a sign-in through the agent");
            let api = Api::new(&signed.server, Some(signed.token.clone()));
            let playlists = bg(async move { api.playlists().await }).await.expect("the token works");
            let now = now_of(&playing(song()), &signed.server).expect("playing");
            let published = call(
                "PublishActivity",
                Some((APP, now.title.as_str(), now.detail.as_str(), now.url.as_str()).to_variant()),
                None,
            )
            .await
            .map(|_| now.url);
            (label, signed, playlists, published)
        });
        println!(
            "orchestrator e2e: \"{}\"; signed in to {} as {} ({}); token of {} characters; {} playlist(s); published {:?}",
            label.unwrap_or_default(),
            signed.server,
            signed.user.username,
            signed.user.display_name,
            signed.token.len(),
            playlists.len(),
            published.map_err(|e| e.to_string()),
        );
        if let Ok(expected) = std::env::var("SAMM_ORCHESTRATOR_E2E_USER") {
            assert_eq!(signed.user.username, expected);
        }
    }
}
