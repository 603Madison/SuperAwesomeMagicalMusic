//! MPRIS: the desktop's media controls (GNOME's and KDE's, the lock screen) and the keyboard's
//! media keys play, pause and skip, and show what's playing with its cover.

use std::cell::RefCell;
use std::rc::Rc;

use gtk::glib;
use mpris_server::{Metadata, PlaybackStatus, Time, TrackId};

use crate::APP_ID;
use crate::player::{Player, State};

/// What MPRIS was last told, so only changes are sent.
#[derive(Default, PartialEq, Clone)]
struct Shown {
    title: String,
    artist: String,
    art: Option<String>,
    length_ms: i64,
    status: Option<PlaybackStatus>,
    live: bool,
    has_next: bool,
}

/// Starts the MPRIS service. [server] gives the server's address (covers come from it);
/// [raise] brings the window up.
pub fn start(player: Rc<Player>, server: impl Fn() -> Option<String> + 'static, raise: impl Fn() + 'static) {
    glib::spawn_future_local(async move {
        let mpris = match mpris_server::Player::builder("SuperAwesomeMagicalMusic")
            .identity("SuperAwesomeMagicalMusic")
            .desktop_entry(APP_ID)
            .can_play(true)
            .can_pause(true)
            .can_go_next(true)
            .can_go_previous(true)
            .can_seek(true)
            .can_raise(true)
            .can_quit(false)
            .build()
            .await
        {
            Ok(mpris) => Rc::new(mpris),
            Err(e) => {
                eprintln!("No media controls (MPRIS): {e}");
                return;
            }
        };

        let p = player.clone();
        mpris.connect_play_pause(move |_| p.toggle());
        let p = player.clone();
        mpris.connect_play(move |_| p.play());
        let p = player.clone();
        mpris.connect_pause(move |_| p.pause());
        let p = player.clone();
        mpris.connect_stop(move |_| p.pause());
        let p = player.clone();
        mpris.connect_next(move |_| p.next());
        let p = player.clone();
        mpris.connect_previous(move |_| p.previous());
        let p = player.clone();
        mpris.connect_seek(move |_, offset| {
            let position = p.state().position + offset.as_micros() as f64 / 1_000_000.0;
            p.seek(position.max(0.0));
        });
        let p = player.clone();
        mpris.connect_set_position(move |_, _, position| p.seek(position.as_micros() as f64 / 1_000_000.0));
        let p = player.clone();
        mpris.connect_set_volume(move |_, volume| p.set_volume(volume.cbrt()));
        mpris.connect_raise(move |_| raise());
        glib::spawn_future_local(mpris.run());

        let shown = Rc::new(RefCell::new(Shown::default()));
        let track_number = Rc::new(RefCell::new(0u64));
        let m = mpris.clone();
        player.subscribe(move |state: &State| {
            m.set_position(Time::from_millis((state.position * 1000.0) as i64));
            let next = shown_of(state, server());
            if *shown.borrow() == next {
                return;
            }
            let before = shown.replace(next.clone());
            let m = m.clone();
            let song_changed =
                before.title != next.title || before.artist != next.artist || before.art != next.art || before.length_ms != next.length_ms;
            if song_changed {
                *track_number.borrow_mut() += 1;
            }
            let number = *track_number.borrow();
            glib::spawn_future_local(async move {
                if song_changed {
                    let mut metadata = Metadata::builder()
                        .trackid(
                            TrackId::try_from(format!("/io/github/superawesomemagicalmusic/track/{number}")).unwrap_or(TrackId::NO_TRACK),
                        )
                        .title(next.title.clone())
                        .artist([next.artist.clone()]);
                    if next.length_ms > 0 {
                        metadata = metadata.length(Time::from_millis(next.length_ms));
                    }
                    if let Some(art) = &next.art {
                        metadata = metadata.art_url(art.clone());
                    }
                    let _ = m.set_metadata(metadata.build()).await;
                }
                if before.status != next.status {
                    let _ = m.set_playback_status(next.status.unwrap_or(PlaybackStatus::Stopped)).await;
                }
                if before.live != next.live {
                    let _ = m.set_can_seek(!next.live).await;
                }
                if before.has_next != next.has_next {
                    let _ = m.set_can_go_next(next.has_next).await;
                }
            });
        });
    });
}

fn shown_of(state: &State, server: Option<String>) -> Shown {
    let Some((title, artist, art)) = state.shown() else {
        return Shown {
            status: Some(PlaybackStatus::Stopped),
            ..Default::default()
        };
    };
    let art = art.map(|url| {
        if url.starts_with('/') {
            // The server's own (signed) cover address, at a size the controls can use.
            format!("{}{url}&px=512", server.unwrap_or_default())
        } else {
            url
        }
    });
    Shown {
        title,
        artist,
        art,
        length_ms: if state.live() { 0 } else { (state.duration * 1000.0) as i64 },
        status: Some(if state.playing {
            PlaybackStatus::Playing
        } else {
            PlaybackStatus::Paused
        }),
        live: state.live(),
        has_next: state.has_next(),
    }
}
