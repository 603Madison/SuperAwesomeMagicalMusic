//! The person's devices in the player bar, as the web player's: a button listing them (web
//! players, phones, and speakers the server plays on, with what's playing on each) to move
//! playback there or here, and, while another device plays, the bar controlling it instead.

use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::time::{Duration, Instant};

use adw::prelude::*;
use gtk::glib;

use crate::connect::{Device, hub};
use crate::ui::ctx;
use crate::ui::widgets::{artwork, duration};


fn kind_icon(kind: &str) -> &'static str {
    match kind {
        "phone" => "samm-smartphone-symbolic",
        "speaker" => "samm-speaker-symbolic",
        _ => "samm-laptop-symbolic",
    }
}

/// "Midnight Signal · Velvet Satellites · playing", for a device with something on.
fn playing_on(device: &Device) -> String {
    let Some(state) = &device.state else { return String::new() };
    let Some(track) = &state.track else { return String::new() };
    let mut parts = vec![track.title.clone()];
    if !track.artist.is_empty() {
        parts.push(track.artist.clone());
    }
    parts.push(if state.playing { "playing".into() } else { "paused".into() });
    parts.join(" · ")
}

/// The devices button: choosing one moves playback there (this computer, to play it here).
pub fn devices_button() -> gtk::MenuButton {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("track-list");
    let heading = gtk::Label::builder()
        .label("Play on a device")
        .xalign(0.0)
        .margin_start(10)
        .margin_top(6)
        .margin_bottom(4)
        .build();
    heading.add_css_class("heading");
    let column = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(4)
        .width_request(340)
        .build();
    column.append(&heading);
    column.append(&list);
    let popover = gtk::Popover::builder().child(&column).build();
    let button = gtk::MenuButton::builder()
        .icon_name("samm-monitor-speaker-symbolic")
        .tooltip_text("Play on a device")
        .popover(&popover)
        .valign(gtk::Align::Center)
        .build();
    button.add_css_class("flat");
    button.add_css_class("circular");
    // Each row's device (None for a note).
    let targets: Rc<RefCell<Vec<Option<String>>>> = Rc::default();
    let (t, p) = (targets.clone(), popover.clone());
    list.connect_row_activated(move |_, row| {
        if let (Some(Some(id)), Some(hub)) = (t.borrow().get(row.index() as usize), hub()) {
            hub.transfer(id);
        }
        p.popdown();
    });
    let fill = {
        let list = list.clone();
        move || {
            targets.replace(fill_devices(&list));
        }
    };
    let f = fill.clone();
    popover.connect_show(move |_| f());
    if let Some(hub) = hub() {
        let weak = button.downgrade();
        let popover = popover.downgrade();
        hub.subscribe(move || {
            let (Some(button), Some(popover)) = (weak.upgrade(), popover.upgrade()) else {
                return;
            };
            let elsewhere =
                crate::connect::hub().is_some_and(|h| h.others().iter().any(|d| d.active && d.state.as_ref().is_some_and(|s| s.playing)));
            if elsewhere {
                button.add_css_class("on");
                button.set_tooltip_text(Some("Playing on another device"));
            } else {
                button.remove_css_class("on");
                button.set_tooltip_text(Some("Play on a device"));
            }
            if popover.is_visible() {
                fill();
            }
        });
    }
    button
}

fn device_row(icon: &str, name: &str, detail: &str, on: bool) -> (gtk::ListBoxRow, gtk::Box) {
    let line = gtk::Box::builder().spacing(12).build();
    let image = gtk::Image::from_icon_name(icon);
    image.set_pixel_size(20);
    if on {
        image.add_css_class("accent");
    }
    line.append(&image);
    let text = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .valign(gtk::Align::Center)
        .hexpand(true)
        .build();
    let title = gtk::Label::builder()
        .label(name)
        .xalign(0.0)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .build();
    title.add_css_class("track-title");
    text.append(&title);
    if !detail.is_empty() {
        let sub = gtk::Label::builder()
            .label(detail)
            .xalign(0.0)
            .ellipsize(gtk::pango::EllipsizeMode::End)
            .max_width_chars(40)
            .build();
        sub.add_css_class("track-sub");
        text.append(&sub);
    }
    line.append(&text);
    let row = gtk::ListBoxRow::builder().child(&line).activatable(true).build();
    if on {
        row.add_css_class("current");
    }
    (row, line)
}

/// Lists the devices; returns each row's device id.
fn fill_devices(list: &gtk::ListBox) -> Vec<Option<String>> {
    while let Some(child) = list.first_child() {
        list.remove(&child);
    }
    let Some(hub) = hub() else { return vec![] };
    let mut targets = vec![];
    let me = hub.me();
    let here = ctx().player.state();
    let here_note = match here.shown() {
        Some((title, _, _)) if here.playing => format!("{title} · playing"),
        _ => "Plays here".into(),
    };
    let (row, _) = device_row("samm-laptop-symbolic", "This computer", &here_note, here.playing);
    row.set_tooltip_text(Some("Play here"));
    list.append(&row);
    targets.push(Some(me));
    let others = hub.others();
    for device in &others {
        let mut detail: Vec<String> = vec![];
        if let Some(d) = device.detail.as_ref().filter(|d| !d.is_empty()) {
            detail.push(d.clone());
        }
        let playing = playing_on(device);
        if !playing.is_empty() {
            detail.push(playing);
        }
        let on = device.active && device.state.as_ref().is_some_and(|s| s.playing);
        let (row, line) = device_row(kind_icon(&device.kind), &device.name, &detail.join(" · "), on);
        row.set_tooltip_text(Some(&format!("Play on {}", device.name)));
        // Something on it: play or pause it from here.
        if let Some(state) = device.state.as_ref().filter(|s| s.track.is_some()) {
            let toggle = gtk::Button::builder()
                .icon_name(if state.playing {
                    "samm-pause-symbolic"
                } else {
                    "samm-play-symbolic"
                })
                .tooltip_text(if state.playing { "Pause" } else { "Play" })
                .valign(gtk::Align::Center)
                .build();
            toggle.add_css_class("flat");
            toggle.add_css_class("circular");
            let (id, playing) = (device.id.clone(), state.playing);
            toggle.connect_clicked(move |_| {
                if let Some(hub) = crate::connect::hub() {
                    hub.command(&id, if playing { "pause" } else { "play" }, None, None);
                }
            });
            line.append(&toggle);
        }
        list.append(&row);
        targets.push(Some(device.id.clone()));
    }
    if others.is_empty() {
        let note = gtk::Label::builder()
            .label(if hub.online() {
                "Open the app or another browser to see it here"
            } else {
                "Connecting…"
            })
            .wrap(true)
            .xalign(0.0)
            .margin_start(10)
            .margin_end(10)
            .margin_top(8)
            .margin_bottom(8)
            .build();
        note.add_css_class("dim-label");
        let row = gtk::ListBoxRow::builder().child(&note).activatable(false).build();
        list.append(&row);
        targets.push(None);
    }
    targets
}

fn icon_button(icon: &str, tooltip: &str) -> gtk::Button {
    let button = gtk::Button::builder()
        .icon_name(icon)
        .tooltip_text(tooltip)
        .valign(gtk::Align::Center)
        .build();
    button.add_css_class("flat");
    button.add_css_class("circular");
    button
}

/// Controls for the song another device is playing, as Spotify shows "Playing on…": in place of
/// the player bar while it plays there and not here. Returns the bar and a check for whether it
/// should show.
pub fn remote_bar() -> gtk::Widget {
    let bar = gtk::Box::builder().spacing(16).build();
    bar.add_css_class("player-bar");
    bar.add_css_class("remote");

    let now = gtk::Box::builder().spacing(12).hexpand(true).valign(gtk::Align::Center).build();
    let art = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    let text = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .valign(gtk::Align::Center)
        .build();
    let make = |class: &str| {
        let l = gtk::Label::builder()
            .xalign(0.0)
            .ellipsize(gtk::pango::EllipsizeMode::End)
            .max_width_chars(30)
            .build();
        l.add_css_class(class);
        l
    };
    let title = make("now-title");
    let artist = make("now-artist");
    let on_line = gtk::Box::builder().spacing(6).build();
    let on_icon = gtk::Image::from_icon_name("samm-laptop-symbolic");
    on_icon.set_pixel_size(13);
    let on_label = make("now-detail");
    on_line.append(&on_icon);
    on_line.append(&on_label);
    on_line.add_css_class("accent");
    text.append(&title);
    text.append(&artist);
    text.append(&on_line);
    now.append(&art);
    now.append(&text);

    let center = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(2)
        .hexpand(true)
        .valign(gtk::Align::Center)
        .build();
    let buttons = gtk::Box::builder().spacing(8).halign(gtk::Align::Center).build();
    let previous = icon_button("samm-skip-back-symbolic", "Previous");
    let play = gtk::Button::builder()
        .icon_name("samm-play-symbolic")
        .valign(gtk::Align::Center)
        .build();
    play.add_css_class("suggested-action");
    play.add_css_class("play-button");
    let next = icon_button("samm-skip-forward-symbolic", "Next");
    buttons.append(&previous);
    buttons.append(&play);
    buttons.append(&next);
    let progress_row = gtk::Box::builder().spacing(10).build();
    let elapsed = gtk::Label::builder().label("0:00").width_chars(5).xalign(1.0).build();
    elapsed.add_css_class("time");
    elapsed.add_css_class("numeric");
    let scale = gtk::Scale::with_range(gtk::Orientation::Horizontal, 0.0, 1.0, 1.0);
    scale.set_hexpand(true);
    scale.set_draw_value(false);
    let total = gtk::Label::builder().label("0:00").width_chars(5).xalign(0.0).build();
    total.add_css_class("time");
    total.add_css_class("numeric");
    progress_row.append(&elapsed);
    progress_row.append(&scale);
    progress_row.append(&total);
    let live = gtk::Label::builder().label("LIVE").halign(gtk::Align::Center).build();
    live.add_css_class("live-pill");
    let progress = adw::Clamp::builder()
        .maximum_size(620)
        .tightening_threshold(620)
        .child(&progress_row)
        .build();
    center.append(&buttons);
    center.append(&progress);
    center.append(&live);

    let end = gtk::Box::builder()
        .spacing(4)
        .halign(gtk::Align::End)
        .valign(gtk::Align::Center)
        .build();
    let here = crate::ui::widgets::button(None, "Play here", "tonal");
    here.set_valign(gtk::Align::Center);
    here.connect_clicked(|_| {
        if let Some(hub) = hub() {
            hub.transfer(&hub.me());
        }
    });
    let mute = icon_button("samm-volume-2-symbolic", "Volume");
    let volume = gtk::Scale::with_range(gtk::Orientation::Horizontal, 0.0, 1.0, 0.01);
    volume.set_width_request(110);
    volume.set_draw_value(false);
    volume.set_valign(gtk::Align::Center);
    end.append(&here);
    end.append(&devices_button());
    end.append(&mute);
    end.append(&volume);
    let end_side = gtk::Box::builder().hexpand(true).build();
    end_side.append(&end);
    end.set_hexpand(true);

    bar.append(&now);
    bar.append(&center);
    bar.append(&end_side);
    let sides = gtk::SizeGroup::new(gtk::SizeGroupMode::Horizontal);
    sides.add_widget(&now);
    sides.add_widget(&end_side);

    // The device shown, and what it said last.
    let shown: Rc<RefCell<Option<Device>>> = Rc::default();
    let send = {
        let shown = shown.clone();
        move |action: &str, position_ms: Option<i64>, volume: Option<f64>| {
            if let (Some(hub), Some(device)) = (hub(), shown.borrow().as_ref()) {
                hub.command(&device.id, action, position_ms, volume);
            }
        }
    };
    let s = send.clone();
    previous.connect_clicked(move |_| s("previous", None, None));
    let s = send.clone();
    next.connect_clicked(move |_| s("next", None, None));
    let (s, sh) = (send.clone(), shown.clone());
    play.connect_clicked(move |_| {
        let playing = sh.borrow().as_ref().and_then(|d| d.state.as_ref()).is_some_and(|st| st.playing);
        s(if playing { "pause" } else { "play" }, None, None);
    });
    // Dragging the position or the volume: sent as it moves (a few times a second), and what's
    // shown stays where it was put until the device says it's there.
    let held_seek: Rc<Cell<Option<Instant>>> = Rc::default();
    let (s, held) = (send.clone(), held_seek.clone());
    scale.connect_change_value(move |_, _, value| {
        held.set(Some(Instant::now()));
        s("seek", Some((value * 1000.0) as i64), None);
        glib::Propagation::Proceed
    });
    let held_volume: Rc<Cell<Option<Instant>>> = Rc::default();
    let last_sent = Rc::new(Cell::new(None::<Instant>));
    let (s, held) = (send.clone(), held_volume.clone());
    volume.connect_change_value(move |_, _, value| {
        held.set(Some(Instant::now()));
        if last_sent.get().is_none_or(|t| t.elapsed() > Duration::from_millis(150)) {
            last_sent.set(Some(Instant::now()));
            s("volume", None, Some(value.clamp(0.0, 1.0)));
        }
        glib::Propagation::Proceed
    });
    // The last of a drag (between the sends) arrives too.
    let (s, v) = (send.clone(), volume.clone());
    let release = gtk::GestureClick::new();
    release.connect_released(move |_, _, _, _| s("volume", None, Some(v.value().clamp(0.0, 1.0))));
    volume.add_controller(release);
    let (s, sh) = (send, shown.clone());
    mute.connect_clicked(move |_| {
        let level = sh
            .borrow()
            .as_ref()
            .and_then(|d| d.state.as_ref())
            .map(|st| st.volume)
            .unwrap_or(0.8);
        s("volume", None, Some(if level > 0.0 { 0.0 } else { 0.8 }));
    });

    let shown_art = Rc::new(RefCell::new(None::<Option<String>>));
    let draw = move || {
        let Some(hub) = hub() else { return };
        let Some(device) = hub.remote() else { return };
        let Some(state) = device.state.clone() else { return };
        let Some(track) = state.track.clone() else { return };
        title.set_text(&track.title);
        artist.set_text(&track.artist);
        on_label.set_text(&format!("Playing on {}", device.name));
        on_icon.set_icon_name(Some(kind_icon(&device.kind)));
        if shown_art.borrow().as_ref() != Some(&track.artwork_url) {
            while let Some(child) = art.first_child() {
                art.remove(&child);
            }
            art.append(&artwork(track.artwork_url.as_deref(), 56, false));
            shown_art.replace(Some(track.artwork_url.clone()));
        }
        play.set_icon_name(if state.playing {
            "samm-pause-symbolic"
        } else {
            "samm-play-symbolic"
        });
        play.set_tooltip_text(Some(if state.playing { "Pause" } else { "Play" }));
        previous.set_sensitive(!state.live);
        next.set_sensitive(!state.live);
        progress.set_visible(!state.live);
        live.set_visible(state.live);
        if !state.live && held_seek.get().is_none_or(|t| t.elapsed() > Duration::from_secs(2)) {
            let position = hub.position_of(&device);
            scale.set_range(0.0, (state.duration_ms as f64 / 1000.0).max(1.0));
            scale.set_value(position as f64 / 1000.0);
            elapsed.set_text(&duration(position));
            total.set_text(&duration(state.duration_ms));
        }
        if held_volume.get().is_none_or(|t| t.elapsed() > Duration::from_secs(2)) {
            volume.set_value(state.volume);
        }
        mute.set_icon_name(match state.volume {
            v if v <= 0.0 => "samm-volume-x-symbolic",
            v if v < 0.5 => "samm-volume-1-symbolic",
            _ => "samm-volume-2-symbolic",
        });
        shown.replace(Some(device));
    };
    let draw = Rc::new(draw);
    if let Some(hub) = hub() {
        let d = draw.clone();
        hub.subscribe(move || d());
    }
    // The position moves along between the device's reports.
    let weak = bar.downgrade();
    glib::timeout_add_local(Duration::from_secs(1), move || {
        let Some(bar) = weak.upgrade() else {
            return glib::ControlFlow::Break;
        };
        if bar.is_mapped() {
            draw();
        }
        glib::ControlFlow::Continue
    });
    bar.upcast()
}

/// Whether the player bar shows another device (one playing there, while this one doesn't).
pub fn showing_remote() -> bool {
    hub().is_some_and(|h| h.remote().is_some())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::connect::PlayState;

    #[test]
    fn a_device_says_what_it_s_playing() {
        let mut device = Device {
            id: "p".into(),
            name: "Pixel".into(),
            kind: "phone".into(),
            ..Default::default()
        };
        assert_eq!(playing_on(&device), "");
        device.state = Some(PlayState {
            track: Some(crate::api::Track {
                title: "Midnight Signal".into(),
                artist: "Velvet Satellites".into(),
                ..Default::default()
            }),
            playing: true,
            ..Default::default()
        });
        assert_eq!(playing_on(&device), "Midnight Signal · Velvet Satellites · playing");
        assert_eq!(kind_icon("speaker"), "samm-speaker-symbolic");
        assert_eq!(kind_icon("web"), "samm-laptop-symbolic");
    }
}
