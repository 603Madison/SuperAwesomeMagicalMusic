//! The player along the bottom, as the web player's: what's playing (and how), shuffle, previous,
//! play/pause, next and repeat over where it is in the song, then the station's star, the queue,
//! the sleep timer and the volume.

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

use crate::player::{Repeat, Sleep, State};
use crate::rt::bg;
use crate::ui::ctx;
use crate::ui::widgets::{artwork, duration, track_key};

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

/// The bar, and what it hides in a narrow window (as the web player's narrow layout does).
pub fn player_bar() -> (gtk::Widget, Vec<gtk::Widget>) {
    let player = ctx().player.clone();
    let bar = gtk::Box::builder().spacing(16).build();
    bar.add_css_class("player-bar");

    // What's playing, and how.
    let now = gtk::Box::builder().spacing(12).hexpand(true).valign(gtk::Align::Center).build();
    let art_holder = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    art_holder.append(&artwork(None, 56, false));
    let text = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .valign(gtk::Align::Center)
        .build();
    let label = |class: &str| {
        let l = gtk::Label::builder()
            .xalign(0.0)
            .ellipsize(gtk::pango::EllipsizeMode::End)
            .max_width_chars(30)
            .build();
        l.add_css_class(class);
        l
    };
    let title = label("now-title");
    title.set_text("Nothing playing");
    let artist = label("now-artist");
    let detail = label("now-detail");
    let line = gtk::Box::builder().spacing(8).build();
    line.append(&title);
    let live = gtk::Label::builder()
        .label("LIVE")
        .valign(gtk::Align::Center)
        .visible(false)
        .build();
    live.add_css_class("live-pill");
    line.append(&live);
    text.append(&line);
    text.append(&artist);
    text.append(&detail);
    now.append(&art_holder);
    now.append(&text);
    // Click the song for Now playing, as on the web.
    let open = gtk::GestureClick::new();
    open.connect_released(|_, _, _, _| ctx().open_now_playing());
    now.add_controller(open);
    now.set_cursor_from_name(Some("pointer"));
    now.set_tooltip_text(Some("Open what's playing"));

    // The controls, over where it is.
    let center = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(2)
        .hexpand(true)
        .valign(gtk::Align::Center)
        .build();
    let buttons = gtk::Box::builder().spacing(8).halign(gtk::Align::Center).build();
    let shuffle = icon_button("media-playlist-shuffle-symbolic", "Shuffle what's next");
    let previous = icon_button("media-skip-backward-symbolic", "Previous (Ctrl+←)");
    let play_icon = gtk::Image::from_icon_name("media-playback-start-symbolic");
    play_icon.set_pixel_size(22);
    let spinner = gtk::Spinner::builder().spinning(true).build();
    let play_face = gtk::Stack::new();
    play_face.add_named(&play_icon, Some("icon"));
    play_face.add_named(&spinner, Some("loading"));
    let play = gtk::Button::builder()
        .child(&play_face)
        .tooltip_text("Play (Ctrl+P)")
        .valign(gtk::Align::Center)
        .build();
    play.add_css_class("suggested-action");
    play.add_css_class("play-button");
    let next = icon_button("media-skip-forward-symbolic", "Next (Ctrl+→)");
    let repeat = icon_button("media-playlist-repeat-symbolic", "Repeat: off");
    // On one of the person's own stations or a Pandora station: thumbs for the song.
    let thumb_down = icon_button("samm-thumb-down-symbolic", "Thumbs down (never play it again)");
    let thumb_up = icon_button("samm-thumb-up-symbolic", "Thumbs up (more like this)");
    for (button, direction) in [(&thumb_down, "DOWN"), (&thumb_up, "UP")] {
        button.set_visible(false);
        let player = player.clone();
        button.connect_clicked(move |_| player.thumb(direction));
    }
    for b in [&shuffle, &previous] {
        buttons.append(b);
    }
    buttons.append(&play);
    for b in [&next, &repeat] {
        buttons.append(b);
    }
    let progress_row = gtk::Box::builder().spacing(10).build();
    let elapsed = gtk::Label::builder().label("0:00").width_chars(5).xalign(1.0).build();
    elapsed.add_css_class("numeric");
    elapsed.add_css_class("time");
    let scale = gtk::Scale::with_range(gtk::Orientation::Horizontal, 0.0, 1.0, 1.0);
    scale.set_hexpand(true);
    scale.set_draw_value(false);
    scale.add_css_class("seek");
    let total = gtk::Label::builder().label("0:00").width_chars(5).xalign(0.0).build();
    total.add_css_class("numeric");
    total.add_css_class("time");
    progress_row.append(&elapsed);
    progress_row.append(&scale);
    progress_row.append(&total);
    // As wide as the web player's (620 pixels at most).
    let progress = adw::Clamp::builder()
        .maximum_size(620)
        .tightening_threshold(620)
        .child(&progress_row)
        .build();
    center.append(&buttons);
    center.append(&progress);

    // The station's star, the queue, the sleep timer and the volume.
    let end = gtk::Box::builder()
        .spacing(2)
        .hexpand(true)
        .halign(gtk::Align::End)
        .valign(gtk::Align::Center)
        .build();
    let star = gtk::ToggleButton::builder()
        .icon_name("non-starred-symbolic")
        .tooltip_text("Favourite")
        .valign(gtk::Align::Center)
        .visible(false)
        .build();
    star.add_css_class("flat");
    star.add_css_class("circular");
    let queue_list = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    let queue_scroll = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .min_content_height(320)
        .max_content_height(560)
        .min_content_width(420)
        .propagate_natural_height(true)
        .child(&queue_list)
        .build();
    let queue_popover = gtk::Popover::builder().child(&queue_scroll).build();
    let queue_button = gtk::MenuButton::builder()
        .icon_name("view-list-symbolic")
        .tooltip_text("Queue")
        .popover(&queue_popover)
        .valign(gtk::Align::Center)
        .build();
    queue_button.add_css_class("flat");
    queue_button.add_css_class("circular");
    let sleep_button = gtk::MenuButton::builder()
        .icon_name("weather-clear-night-symbolic")
        .tooltip_text("Sleep timer")
        .menu_model(&sleep_menu(false))
        .valign(gtk::Align::Center)
        .build();
    sleep_button.add_css_class("flat");
    sleep_button.add_css_class("circular");
    sleep_button.insert_action_group("sleep", Some(&sleep_actions()));
    let lyrics = icon_button("audio-input-microphone-symbolic", "Lyrics");
    lyrics.connect_clicked(|_| crate::ui::now_playing::show_lyrics());
    let devices = crate::ui::devices::devices_button();
    let mute = icon_button("audio-volume-high-symbolic", "Mute");
    let volume = gtk::Scale::with_range(gtk::Orientation::Horizontal, 0.0, 1.0, 0.01);
    volume.set_width_request(110);
    volume.set_draw_value(false);
    volume.set_valign(gtk::Align::Center);
    volume.set_value(player.state().volume);
    for w in [
        thumb_down.upcast_ref::<gtk::Widget>(),
        thumb_up.upcast_ref(),
        star.upcast_ref(),
        lyrics.upcast_ref(),
        queue_button.upcast_ref(),
        devices.upcast_ref(),
        sleep_button.upcast_ref(),
        mute.upcast_ref(),
        volume.upcast_ref(),
    ] {
        end.append(w);
    }

    bar.append(&now);
    bar.append(&center);
    // The right side, as wide as the left one, with its buttons at its end.
    let end_side = gtk::Box::builder().hexpand(true).build();
    end_side.append(&end);
    bar.append(&end_side);
    // The two sides the same width, so the controls sit in the middle.
    let sides = gtk::SizeGroup::new(gtk::SizeGroupMode::Horizontal);
    sides.add_widget(&now);
    sides.add_widget(&end_side);

    // What it does.
    let p = player.clone();
    shuffle.connect_clicked(move |_| {
        p.shuffle_upcoming();
        ctx().toast("Shuffled what's next");
    });
    let p = player.clone();
    previous.connect_clicked(move |_| p.previous());
    let p = player.clone();
    play.connect_clicked(move |_| p.toggle());
    let p = player.clone();
    next.connect_clicked(move |_| p.next());
    let p = player.clone();
    repeat.connect_clicked(move |_| p.cycle_repeat());
    let p = player.clone();
    scale.connect_change_value(move |_, _, value| {
        p.seek(value);
        glib::Propagation::Proceed
    });
    let p = player.clone();
    volume.connect_change_value(move |_, _, value| {
        p.set_volume(value.clamp(0.0, 1.0));
        glib::Propagation::Proceed
    });
    let before_mute = Rc::new(Cell::new(0.8));
    let (p, before) = (player.clone(), before_mute.clone());
    mute.connect_clicked(move |_| {
        let now = p.state().volume;
        if now > 0.0 {
            before.set(now);
            p.set_volume(0.0);
        } else {
            p.set_volume(before.get().max(0.05));
        }
    });
    let p = player.clone();
    let list = queue_list.clone();
    let queue_shown = Rc::new(RefCell::new(String::new()));
    let shown_key = queue_shown.clone();
    queue_popover.connect_show(move |popover| {
        shown_key.replace(queue_key(&p.state()));
        fill_queue(&list, &p.state(), popover);
    });
    let p = player.clone();
    let quiet_star = Rc::new(Cell::new(false));
    let q = quiet_star.clone();
    star.connect_toggled(move |b| {
        b.set_icon_name(if b.is_active() {
            "starred-symbolic"
        } else {
            "non-starred-symbolic"
        });
        if q.get() {
            return;
        }
        let Some(track) = p.state().current().cloned().filter(|t| t.is_live()) else {
            return;
        };
        let Some(api) = ctx().api() else { return };
        let on = b.is_active();
        glib::spawn_future_local(async move {
            match bg(async move { api.set_live_favorite(&track, on).await }).await {
                Ok(_) => crate::ui::window::refresh_sidebar(),
                Err(e) => ctx().failed(&e),
            }
        });
    });
    let sleep_for_state = sleep_button.clone();
    // Hidden in a narrow window, as the web player's narrow layout does.
    let wide_only: Vec<gtk::Widget> = vec![
        shuffle.clone().upcast(),
        repeat.clone().upcast(),
        sleep_button.upcast(),
        volume.clone().upcast(),
    ];

    // Following the player.
    let shown_art = Rc::new(RefCell::new(None::<Option<String>>));
    let shown_volume = Cell::new(player.state().volume);
    let shown_sleep = Cell::new(false);
    player.subscribe(move |state: &State| {
        match state.shown() {
            Some((t, a, art)) => {
                title.set_text(&t);
                artist.set_text(&a);
                if shown_art.borrow().as_ref() != Some(&art) {
                    while let Some(child) = art_holder.first_child() {
                        art_holder.remove(&child);
                    }
                    art_holder.append(&artwork(art.as_deref(), 56, false));
                    shown_art.replace(Some(art));
                }
            }
            None => {
                title.set_text("Nothing playing");
                artist.set_text("");
            }
        }
        // On one of the person's own stations, what the DJ said over this song (as the web shows it).
        let personal = state.current().is_some_and(crate::api::Track::is_personal);
        let dj = state.personal.as_ref().and_then(|p| p.dj.as_ref()).filter(|_| personal).map(|d| d.text.clone());
        match &dj {
            Some(said) => {
                detail.set_text(&format!("DJ: {said}"));
                detail.set_tooltip_text(Some(said));
                detail.add_css_class("dj");
            }
            None => {
                detail.set_text(&state.description);
                detail.set_tooltip_text(None);
                detail.remove_css_class("dj");
            }
        }
        detail.set_visible(!detail.text().is_empty());
        let thumb = state.thumbs_now();
        let station_song = state.current().is_some_and(crate::api::Track::is_station_track);
        thumb_down.set_tooltip_text(Some(if station_song { "Thumbs down (skips it)" } else { "Thumbs down (never play it again)" }));
        thumb_up.set_tooltip_text(Some(if station_song { "Thumbs up" } else { "Thumbs up (more like this)" }));
        for (button, direction) in [(&thumb_down, "DOWN"), (&thumb_up, "UP")] {
            button.set_visible(thumb.is_some());
            button.set_sensitive(thumb.as_ref().is_some_and(|(known, _)| *known));
            if thumb.as_ref().and_then(|(_, t)| t.as_deref()) == Some(direction) {
                button.add_css_class("on");
            } else {
                button.remove_css_class("on");
            }
        }
        shuffle.set_visible(!personal);
        repeat.set_visible(!personal);
        let is_live = state.live();
        live.set_visible(is_live);
        lyrics.set_sensitive(state.index.is_some() && !is_live);
        // The queue, as it is now, while it's open.
        if queue_popover.is_visible() && *queue_shown.borrow() != queue_key(state) {
            queue_shown.replace(queue_key(state));
            fill_queue(&queue_list, state, &queue_popover);
        }
        play_icon.set_icon_name(Some(if state.playing {
            "media-playback-pause-symbolic"
        } else {
            "media-playback-start-symbolic"
        }));
        play_face.set_visible_child_name(if state.loading && state.playing { "loading" } else { "icon" });
        play.set_tooltip_text(Some(if state.playing { "Pause (Ctrl+P)" } else { "Play (Ctrl+P)" }));
        play.set_sensitive(state.index.is_some());
        previous.set_sensitive(state.index.is_some() && !is_live);
        next.set_sensitive(state.has_next());
        next.set_tooltip_text(Some(if personal { "Skip this song" } else { "Next (Ctrl+→)" }));
        shuffle.set_sensitive(state.index.is_some_and(|i| i + 2 < state.queue.len()));
        let (icon, said) = match state.repeat {
            Repeat::Off => ("media-playlist-repeat-symbolic", "Repeat: off"),
            Repeat::All => ("media-playlist-repeat-symbolic", "Repeat: all"),
            Repeat::One => ("media-playlist-repeat-song-symbolic", "Repeat: this song"),
        };
        repeat.set_icon_name(icon);
        repeat.set_tooltip_text(Some(said));
        if state.repeat == Repeat::Off {
            repeat.remove_css_class("on");
        } else {
            repeat.add_css_class("on");
        }
        progress.set_sensitive(!is_live && state.duration > 0.0);
        if is_live {
            scale.set_range(0.0, 1.0);
            scale.set_value(0.0);
        } else {
            scale.set_range(0.0, state.duration.max(1.0));
            scale.set_value(state.position);
        }
        elapsed.set_text(&duration((state.position * 1000.0) as i64));
        total.set_text(&if is_live {
            "Live".to_string()
        } else {
            duration((state.duration * 1000.0) as i64)
        });
        if (shown_volume.get() - state.volume).abs() > 0.001 {
            shown_volume.set(state.volume);
            volume.set_value(state.volume);
        }
        mute.set_icon_name(match state.volume {
            v if v <= 0.0 => "audio-volume-muted-symbolic",
            v if v < 0.34 => "audio-volume-low-symbolic",
            v if v < 0.67 => "audio-volume-medium-symbolic",
            _ => "audio-volume-high-symbolic",
        });
        mute.set_tooltip_text(Some(if state.volume <= 0.0 { "Unmute" } else { "Mute" }));
        // A live channel or station: its star.
        let station = state.current().filter(|t| t.is_live());
        star.set_visible(station.is_some());
        if let Some(station) = station {
            let on = crate::ui::window::is_favourite(&track_key(station));
            if star.is_active() != on {
                quiet_star.set(true);
                star.set_active(on);
                quiet_star.set(false);
            }
        }
        if shown_sleep.replace(state.sleeping) != state.sleeping {
            sleep_for_state.set_menu_model(Some(&sleep_menu(state.sleeping)));
            if state.sleeping {
                sleep_for_state.add_css_class("on");
                sleep_for_state.set_tooltip_text(Some("Sleep timer (on)"));
            } else {
                sleep_for_state.remove_css_class("on");
                sleep_for_state.set_tooltip_text(Some("Sleep timer"));
            }
        }
    });
    // Another of the person's devices playing (and not this one): the bar controls it instead.
    let bars = gtk::Stack::builder()
        .transition_type(gtk::StackTransitionType::Crossfade)
        .vhomogeneous(false)
        .build();
    bars.add_named(&bar, Some("here"));
    bars.add_named(&crate::ui::devices::remote_bar(), Some("remote"));
    let switch = {
        let bars = bars.downgrade();
        move || {
            if let Some(bars) = bars.upgrade() {
                bars.set_visible_child_name(if crate::ui::devices::showing_remote() { "remote" } else { "here" });
            }
        }
    };
    if let Some(hub) = crate::connect::hub() {
        let s = switch.clone();
        hub.subscribe(move || s());
    }
    let playing = Cell::new(false);
    player.subscribe(move |state| {
        if playing.replace(state.playing) != state.playing {
            switch();
        }
    });
    (bars.upcast(), wide_only)
}

/// The sleep timer's choices, as the web player's.
fn sleep_menu(on: bool) -> gio::Menu {
    let menu = gio::Menu::new();
    let times = gio::Menu::new();
    for minutes in [15u32, 30, 45, 60, 90] {
        let item = gio::MenuItem::new(Some(&format!("In {minutes} minutes")), None);
        item.set_action_and_target_value(Some("sleep.in"), Some(&minutes.to_variant()));
        times.append_item(&item);
    }
    times.append(Some("At the end of this song"), Some("sleep.end-of-song"));
    menu.append_section(None, &times);
    if on {
        menu.append(Some("Turn off the sleep timer"), Some("sleep.off"));
    }
    menu
}

fn sleep_actions() -> gio::SimpleActionGroup {
    let group = gio::SimpleActionGroup::new();
    let minutes = gio::SimpleAction::new("in", Some(glib::VariantTy::UINT32));
    minutes.connect_activate(|_, value| {
        if let Some(m) = value.and_then(|v| v.get::<u32>()) {
            ctx().player.sleep_in(Some(Sleep::Minutes(m)));
            ctx().toast(&format!("Pausing in {m} minutes"));
        }
    });
    group.add_action(&minutes);
    let end = gio::SimpleAction::new("end-of-song", None);
    end.connect_activate(|_, _| {
        ctx().player.sleep_in(Some(Sleep::EndOfSong));
        ctx().toast("Pausing at the end of this song");
    });
    group.add_action(&end);
    let off = gio::SimpleAction::new("off", None);
    off.connect_activate(|_, _| {
        ctx().player.sleep_in(None);
        ctx().toast("Sleep timer off");
    });
    group.add_action(&off);
    group
}

/// What the queue is (its songs and where it is), to notice changes.
fn queue_key(state: &State) -> String {
    let keys: Vec<String> = state.queue.iter().map(track_key).collect();
    format!("{:?}|{}", state.index, keys.join(","))
}

/// The queue, as on the Now playing page: click one to play it; move or take out the rest.
fn fill_queue(holder: &gtk::Box, state: &State, popover: &gtk::Popover) {
    while let Some(child) = holder.first_child() {
        holder.remove(&child);
    }
    let popover = popover.downgrade();
    let view = crate::ui::now_playing::queue_view(
        state,
        Some(Rc::new(move || {
            if let Some(p) = popover.upgrade() {
                p.popdown();
            }
        })),
    );
    view.set_margin_top(6);
    view.set_margin_bottom(6);
    holder.append(&view);
}
