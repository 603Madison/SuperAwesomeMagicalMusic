//! Listen: what's playing around you. The microphone records for up to ten seconds; every few
//! seconds what's been heard so far goes to the server, which looks for it in your library (and,
//! last, AudD if you've added it), stopping as soon as it's sure.

use std::cell::RefCell;
use std::rc::Rc;
use std::time::{Duration, Instant};

use adw::prelude::*;
use gtk::glib;

use crate::api::{Heard, ListenResult};
use crate::mic::Mic;
use crate::rt::bg;
use crate::ui::ctx;
use crate::ui::widgets::{artwork, button, heading, nav_page, page_box, page_title, scrolled};

/// When each try is sent (seconds of recording); the last one may also ask AudD.
const TRIES: [u64; 3] = [4, 7, 10];

pub fn listen_page() -> adw::NavigationPage {
    let content = page_box();
    content.append(&page_title("Listen", "What's playing around you? Hold your computer near the music."));

    let hero = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(14)
        .halign(gtk::Align::Center)
        .margin_top(12)
        .build();
    let icon = gtk::Image::from_icon_name("audio-input-microphone-symbolic");
    icon.set_pixel_size(56);
    let big = gtk::Button::builder().child(&icon).halign(gtk::Align::Center).tooltip_text("Listen").build();
    big.add_css_class("listen-button");
    let state = gtk::Label::new(Some("Click to listen"));
    state.add_css_class("listen-state");
    let result = gtk::Box::builder().orientation(gtk::Orientation::Vertical).halign(gtk::Align::Center).build();
    let status = gtk::Label::builder().wrap(true).justify(gtk::Justification::Center).build();
    status.add_css_class("dim-label");
    hero.append(&big);
    hero.append(&state);
    hero.append(&result);
    hero.append(&status);
    content.append(&hero);
    let recent = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).margin_top(18).build();
    content.append(&recent);
    let (page, _) = nav_page("Listen", &scrolled(&content));

    let Some(api) = ctx().api() else { return page };
    {
        let status = status.clone();
        let api = api.clone();
        glib::spawn_future_local(async move {
            if let Ok(s) = bg(async move { api.listen_status().await }).await {
                let library = if !s.libraries {
                    "No library to learn yet: add your Jellyfin on the server.".to_string()
                } else if !s.enabled {
                    "Learning your library is turned off on the server.".to_string()
                } else if s.total > 0 && s.learned < s.total {
                    format!("Learning your library: {} of {} songs so far", s.learned, s.total)
                } else {
                    format!("Knows all {} songs in your library", s.learned)
                };
                let audd = if s.audd { "AudD for everything else" } else { "Add AudD on the server to recognise any song" };
                status.set_text(&format!("{library} · {audd}"));
            }
        });
    }
    type Show = Rc<RefCell<Option<Rc<dyn Fn()>>>>;
    let show_recent: Show = Rc::new(RefCell::new(None));
    {
        let recent = recent.clone();
        let api = api.clone();
        let again = show_recent.clone();
        show_recent.replace(Some(Rc::new(move || {
            let recent = recent.clone();
            let api = api.clone();
            let again = again.clone();
            glib::spawn_future_local(async move {
                let Ok(items) = bg({ let api = api.clone(); async move { api.listen_heard().await } }).await else { return };
                while let Some(child) = recent.first_child() {
                    recent.remove(&child);
                }
                if !items.is_empty() {
                    recent.append(&heading("Recently heard"));
                    for item in items {
                        let (api, again) = (api.clone(), again.clone());
                        let forget = move |id: String| {
                            let (api, again) = (api.clone(), again.clone());
                            glib::spawn_future_local(async move {
                                if bg(async move { api.forget_heard(&id).await }).await.is_ok()
                                    && let Some(show) = again.borrow().clone()
                                {
                                    show();
                                }
                            });
                        };
                        recent.append(&heard_row(&item, forget));
                    }
                }
            });
        })));
    }
    let refresh_recent = {
        let show_recent = show_recent.clone();
        move || {
            if let Some(show) = show_recent.borrow().clone() {
                show();
            }
        }
    };
    refresh_recent();

    // Listening: a session at a time; clicking again stops it.
    let session: Rc<RefCell<Option<Rc<Mic>>>> = Rc::new(RefCell::new(None));
    big.connect_clicked(move |big| {
        if let Some(mic) = session.borrow_mut().take() {
            mic.stop();
            big.remove_css_class("is-listening");
            state.set_text("Click to listen");
            return;
        }
        while let Some(child) = result.first_child() {
            result.remove(&child);
        }
        let mic = match Mic::start() {
            Ok(mic) => Rc::new(mic),
            Err(e) => {
                state.set_text("Couldn't open the microphone");
                ctx().toast(&e.to_string());
                return;
            }
        };
        session.replace(Some(mic.clone()));
        big.add_css_class("is-listening");
        let (big, state, result, session, api, refresh_recent) = (big.clone(), state.clone(), result.clone(), session.clone(), api.clone(), refresh_recent.clone());
        glib::spawn_future_local(async move {
            let began = Instant::now();
            let mut candidate: Option<String> = None;
            let still = |session: &Rc<RefCell<Option<Rc<Mic>>>>, mic: &Rc<Mic>| session.borrow().as_ref().is_some_and(|m| Rc::ptr_eq(m, mic));
            for (i, at) in TRIES.iter().enumerate() {
                // The seconds counting up, until this try is due.
                while began.elapsed() < Duration::from_secs(*at) {
                    if !still(&session, &mic) {
                        return;
                    }
                    state.set_text(&format!("Listening… {}s", began.elapsed().as_secs()));
                    glib::timeout_future(Duration::from_millis(250)).await;
                }
                if !still(&session, &mic) {
                    return;
                }
                let last = i == TRIES.len() - 1;
                if last {
                    mic.stop();
                }
                let recorded = Instant::now();
                let wav = mic.wav();
                let asking = api.clone();
                let hint = candidate.clone();
                let answer = bg(async move { asking.listen(wav, last, hint.as_deref()).await }).await;
                if !still(&session, &mic) {
                    return;
                }
                let answer = match answer {
                    Ok(answer) => answer,
                    Err(_) if !last => continue,
                    Err(e) => ListenResult { message: Some(e.to_string()), ..Default::default() },
                };
                if answer.found || last {
                    mic.stop();
                    session.replace(None);
                    big.remove_css_class("is-listening");
                    state.set_text(if answer.found { "Got it" } else { "Click to try again" });
                    result.append(&if answer.found { found(&answer, recorded) } else { missed(&answer) });
                    if answer.found {
                        refresh_recent();
                    }
                    return;
                }
                candidate = answer.candidate.or(candidate);
            }
        });
    });
    page
}

/// What was recognised: its cover and name, and ways to play it (from where it is in the room, too).
fn found(answer: &ListenResult, recorded: Instant) -> gtk::Widget {
    let card = gtk::Box::builder().spacing(20).margin_top(6).build();
    card.add_css_class("listen-found");
    card.append(&artwork(answer.artwork_url.as_deref().or(answer.song.as_ref().and_then(|s| s.artwork_url.as_deref())), 160, false));
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).valign(gtk::Align::Center).build();
    let title = gtk::Label::builder().label(answer.title.as_deref().unwrap_or_default()).xalign(0.0).wrap(true).build();
    title.add_css_class("listen-title");
    text.append(&title);
    let by = [answer.artist.as_deref(), answer.album.as_deref()].into_iter().flatten().collect::<Vec<_>>().join(" · ");
    let artist = gtk::Label::builder().label(&by).xalign(0.0).wrap(true).build();
    artist.add_css_class("dim-label");
    text.append(&artist);
    let via = gtk::Label::builder()
        .label(if answer.via.as_deref() == Some("library") { "From your library" } else { "Recognised by AudD" })
        .xalign(0.0)
        .build();
    via.add_css_class("caption");
    via.add_css_class("dim-label");
    text.append(&via);
    let actions = gtk::Box::builder().spacing(8).margin_top(8).build();
    match &answer.song {
        Some(song) => {
            let play = button(Some("media-playback-start-symbolic"), "Play", "suggested");
            let s = song.clone();
            play.connect_clicked(move |_| ctx().player.play_tracks(vec![s.clone()], 0));
            actions.append(&play);
            if let Some(at) = answer.position_ms {
                let from_here = button(Some("media-skip-forward-symbolic"), "Play from here", "tonal");
                let s = song.clone();
                from_here.connect_clicked(move |_| {
                    // Where the song is now: where it was when recording stopped, and the time since.
                    ctx().player.play_from(s.clone(), at as f64 / 1000.0 + recorded.elapsed().as_secs_f64());
                });
                actions.append(&from_here);
            }
        }
        None => {
            let none = gtk::Label::new(Some("It isn't on any of your sources."));
            none.add_css_class("dim-label");
            actions.append(&none);
        }
    }
    text.append(&actions);
    card.append(&text);
    card.upcast()
}

fn missed(answer: &ListenResult) -> gtk::Widget {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).build();
    let title = gtk::Label::new(Some("Couldn't tell what that was"));
    title.add_css_class("heading");
    column.append(&title);
    if let Some(message) = &answer.message {
        let line = gtk::Label::builder().label(message).wrap(true).justify(gtk::Justification::Center).max_width_chars(60).build();
        line.add_css_class("dim-label");
        column.append(&line);
    }
    column.upcast()
}

fn heard_row(item: &Heard, forget: impl Fn(String) + 'static) -> gtk::Widget {
    let row = gtk::Box::builder().spacing(12).build();
    row.add_css_class("listen-heard");
    row.append(&artwork(item.artwork_url.as_deref().or(item.song.as_ref().and_then(|s| s.artwork_url.as_deref())), 48, false));
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).hexpand(true).valign(gtk::Align::Center).build();
    let title = gtk::Label::builder().label(&item.title).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).build();
    title.add_css_class("heading");
    text.append(&title);
    let artist = gtk::Label::builder().label(format!("{} · {}", item.artist, ago(item.at))).xalign(0.0).ellipsize(gtk::pango::EllipsizeMode::End).build();
    artist.add_css_class("dim-label");
    text.append(&artist);
    row.append(&text);
    if let Some(song) = item.song.clone() {
        let play = gtk::Button::builder().icon_name("media-playback-start-symbolic").tooltip_text("Play").valign(gtk::Align::Center).build();
        play.add_css_class("flat");
        play.add_css_class("circular");
        play.connect_clicked(move |_| ctx().player.play_tracks(vec![song.clone()], 0));
        row.append(&play);
    }
    let remove = gtk::Button::builder().icon_name("window-close-symbolic").tooltip_text("Remove").valign(gtk::Align::Center).build();
    remove.add_css_class("flat");
    remove.add_css_class("circular");
    let id = item.id.clone();
    remove.connect_clicked(move |_| forget(id.clone()));
    row.append(&remove);
    row.upcast()
}

/// "Just now", "12 min ago", "3 h ago", or days.
fn ago(at: i64) -> String {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(at);
    let minutes = (now - at) / 60_000;
    match minutes {
        m if m < 1 => "Just now".into(),
        m if m < 60 => format!("{m} min ago"),
        m if m < 24 * 60 => format!("{} h ago", m / 60),
        m if m < 48 * 60 => "Yesterday".into(),
        m => format!("{} days ago", m / (24 * 60)),
    }
}
