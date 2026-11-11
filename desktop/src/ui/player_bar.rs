//! The player along the bottom: what's playing, play/pause/skip, where it is in the song, the
//! queue and the volume.

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;

use crate::player::State;
use crate::ui::ctx;
use crate::ui::widgets::{artwork, duration, plain_row};

pub fn player_bar() -> gtk::Widget {
    let player = ctx().player.clone();
    let bar = gtk::CenterBox::new();
    bar.add_css_class("player-bar");

    // What's playing.
    let now = gtk::Box::builder().spacing(12).build();
    let art_holder = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    art_holder.append(&artwork(None, 56, false));
    let text = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .valign(gtk::Align::Center)
        .build();
    let title = gtk::Label::builder()
        .xalign(0.0)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .max_width_chars(28)
        .label("Nothing playing")
        .build();
    title.add_css_class("now-title");
    let artist = gtk::Label::builder()
        .xalign(0.0)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .max_width_chars(28)
        .build();
    artist.add_css_class("now-artist");
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
    now.append(&art_holder);
    now.append(&text);
    bar.set_start_widget(Some(&now));

    // The controls and where it is.
    let center = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(2)
        .width_request(220)
        .build();
    let buttons = gtk::Box::builder().spacing(10).halign(gtk::Align::Center).build();
    let previous = gtk::Button::builder()
        .icon_name("media-skip-backward-symbolic")
        .tooltip_text("Previous (Ctrl+←)")
        .valign(gtk::Align::Center)
        .build();
    previous.add_css_class("flat");
    previous.add_css_class("circular");
    let play = gtk::Button::builder()
        .icon_name("media-playback-start-symbolic")
        .tooltip_text("Play (Ctrl+P)")
        .build();
    play.add_css_class("suggested-action");
    play.add_css_class("play-button");
    let next = gtk::Button::builder()
        .icon_name("media-skip-forward-symbolic")
        .tooltip_text("Next (Ctrl+→)")
        .valign(gtk::Align::Center)
        .build();
    next.add_css_class("flat");
    next.add_css_class("circular");
    let spinner = gtk::Spinner::builder().visible(false).build();
    buttons.append(&previous);
    buttons.append(&play);
    buttons.append(&next);
    buttons.append(&spinner);
    let progress_row = gtk::Box::builder().spacing(8).build();
    let elapsed = gtk::Label::builder().label("0:00").width_chars(5).xalign(1.0).build();
    elapsed.add_css_class("numeric");
    elapsed.add_css_class("time");
    let scale = gtk::Scale::with_range(gtk::Orientation::Horizontal, 0.0, 1.0, 1.0);
    scale.set_hexpand(true);
    scale.set_draw_value(false);
    let total = gtk::Label::builder().label("0:00").width_chars(5).xalign(0.0).build();
    total.add_css_class("numeric");
    total.add_css_class("time");
    progress_row.append(&elapsed);
    progress_row.append(&scale);
    progress_row.append(&total);
    center.append(&buttons);
    center.append(&progress_row);
    bar.set_center_widget(Some(&center));

    // The queue and the volume.
    let end = gtk::Box::builder().spacing(6).valign(gtk::Align::Center).build();
    let queue_list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    queue_list.add_css_class("boxed-list");
    let queue_scroll = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .min_content_height(320)
        .max_content_height(480)
        .min_content_width(360)
        .propagate_natural_height(true)
        .child(&queue_list)
        .build();
    let queue_popover = gtk::Popover::builder().child(&queue_scroll).build();
    let queue_button = gtk::MenuButton::builder()
        .icon_name("view-list-symbolic")
        .tooltip_text("Queue")
        .popover(&queue_popover)
        .build();
    queue_button.add_css_class("flat");
    let volume_icon = gtk::Image::from_icon_name("audio-volume-high-symbolic");
    let volume = gtk::Scale::with_range(gtk::Orientation::Horizontal, 0.0, 1.0, 0.01);
    volume.set_width_request(90);
    volume.set_draw_value(false);
    volume.set_value(player.state().volume);
    end.append(&queue_button);
    end.append(&volume_icon);
    end.append(&volume);
    bar.set_end_widget(Some(&end));

    // What it does.
    let p = player.clone();
    previous.connect_clicked(move |_| p.previous());
    let p = player.clone();
    play.connect_clicked(move |_| p.toggle());
    let p = player.clone();
    next.connect_clicked(move |_| p.next());
    let p = player.clone();
    scale.connect_change_value(move |_, _, value| {
        p.seek(value);
        gtk::glib::Propagation::Proceed
    });
    let p = player.clone();
    volume.connect_change_value(move |_, _, value| {
        p.set_volume(value.clamp(0.0, 1.0));
        gtk::glib::Propagation::Proceed
    });
    let p = player.clone();
    let list = queue_list.clone();
    queue_popover.connect_show(move |popover| fill_queue(&list, &p.state(), popover));

    // Following the player.
    let shown_art = Rc::new(RefCell::new(None::<Option<String>>));
    let shown_volume = Cell::new(player.state().volume);
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
        let is_live = state.live();
        live.set_visible(is_live);
        play.set_icon_name(if state.playing {
            "media-playback-pause-symbolic"
        } else {
            "media-playback-start-symbolic"
        });
        play.set_tooltip_text(Some(if state.playing { "Pause (Ctrl+P)" } else { "Play (Ctrl+P)" }));
        play.set_sensitive(state.index.is_some());
        previous.set_sensitive(state.index.is_some() && !is_live);
        next.set_sensitive(state.has_next());
        spinner.set_visible(state.loading);
        spinner.set_spinning(state.loading);
        progress_row.set_sensitive(!is_live && state.duration > 0.0);
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
        volume_icon.set_icon_name(Some(match state.volume {
            v if v <= 0.0 => "audio-volume-muted-symbolic",
            v if v < 0.34 => "audio-volume-low-symbolic",
            v if v < 0.67 => "audio-volume-medium-symbolic",
            _ => "audio-volume-high-symbolic",
        }));
    });
    bar.upcast()
}

/// The queue, from what's playing on; click one to play it.
fn fill_queue(list: &gtk::ListBox, state: &State, popover: &gtk::Popover) {
    while let Some(child) = list.first_child() {
        list.remove(&child);
    }
    let start = state.index.unwrap_or(0);
    if state.queue.is_empty() {
        let row = adw::ActionRow::builder().title("The queue is empty").build();
        list.append(&row);
        return;
    }
    for (i, track) in state.queue.iter().enumerate().skip(start).take(100) {
        let row = plain_row(track.title.as_str(), track.artist.as_str());
        row.set_activatable(true);
        row.set_title_lines(1);
        row.add_prefix(&artwork(track.artwork_url.as_deref(), 36, false));
        if Some(i) == state.index {
            let playing = gtk::Image::from_icon_name("media-playback-start-symbolic");
            playing.add_css_class("accent");
            row.add_suffix(&playing);
        }
        let popover = popover.clone();
        row.connect_activated(move |_| {
            ctx().player.jump_to(i);
            popover.popdown();
        });
        list.append(&row);
    }
}
