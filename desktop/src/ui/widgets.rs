//! Building blocks: covers loaded from the server (cached), song rows, cards and small helpers.

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::OnceLock;

use adw::prelude::*;
use gtk::{gdk, glib};

use crate::api::Track;
use crate::rt::bg;
use crate::ui::ctx;

thread_local! {
    static COVERS: RefCell<HashMap<String, gdk::Texture>> = RefCell::new(HashMap::new());
}

fn image_client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .user_agent(concat!("SuperAwesomeMagicalMusic-Desktop/", env!("CARGO_PKG_VERSION")))
            .timeout(std::time::Duration::from_secs(20))
            .build()
            .expect("the image client")
    })
}

/// The address of a cover at [px] pixels (the server's signed cover addresses take a size).
pub fn cover_url(url: &str, px: i32) -> String {
    if url.starts_with('/') {
        let base = ctx().api().map(|a| a.base().to_string()).unwrap_or_default();
        format!("{base}{url}&px={px}")
    } else {
        url.to_string()
    }
}

/// A cover, [size] pixels square (rounded, or round for artists), with a note until it loads.
/// Covers are cropped to a square, as the other apps show them.
pub fn artwork(url: Option<&str>, size: i32, round: bool) -> gtk::Widget {
    let frame = gtk::Box::builder()
        .width_request(size)
        .height_request(size)
        .halign(gtk::Align::Center)
        .valign(gtk::Align::Center)
        .build();
    // Exactly its size, whatever is inside.
    frame.set_hexpand(false);
    frame.set_vexpand(false);
    frame.set_overflow(gtk::Overflow::Hidden);
    frame.add_css_class("artwork");
    if round {
        frame.add_css_class("round");
    }
    let image = gtk::Image::builder()
        .icon_name("folder-music-symbolic")
        .pixel_size((size / 3).max(16))
        .width_request(size)
        .height_request(size)
        .build();
    image.add_css_class("dim-label");
    frame.append(&image);
    let Some(url) = url.filter(|u| !u.is_empty()) else {
        return frame.upcast();
    };
    let address = cover_url(url, (size * 2).clamp(64, 1024));
    let show = {
        let image = image.clone();
        move |texture: &gdk::Texture| {
            image.remove_css_class("dim-label");
            image.set_paintable(Some(texture));
            image.set_pixel_size(size);
        }
    };
    if let Some(texture) = COVERS.with(|c| c.borrow().get(&address).cloned()) {
        show(&texture);
        return frame.upcast();
    }
    glib::spawn_future_local(async move {
        let fetch = address.clone();
        let bytes = bg(async move {
            let response = image_client().get(&fetch).send().await.ok()?;
            if !response.status().is_success() {
                return None;
            }
            response.bytes().await.ok().map(|b| b.to_vec())
        })
        .await;
        let Some(pixbuf) = bytes.as_deref().and_then(square) else { return };
        #[allow(deprecated)]
        let texture = gdk::Texture::for_pixbuf(&pixbuf);
        COVERS.with(|c| {
            let mut covers = c.borrow_mut();
            if covers.len() > 600 {
                covers.clear();
            }
            covers.insert(address, texture.clone());
        });
        show(&texture);
    });
    frame.upcast()
}

/// The image in [bytes], cropped to its middle square.
fn square(bytes: &[u8]) -> Option<gtk::gdk_pixbuf::Pixbuf> {
    let loader = gtk::gdk_pixbuf::PixbufLoader::new();
    loader.write(bytes).ok()?;
    loader.close().ok()?;
    let pixbuf = loader.pixbuf()?;
    let (w, h) = (pixbuf.width(), pixbuf.height());
    let side = w.min(h);
    if side <= 0 {
        return None;
    }
    Some(if w == h {
        pixbuf
    } else {
        pixbuf.new_subpixbuf((w - side) / 2, (h - side) / 2, side, side)
    })
}

/// A row of plain text: titles like "Juniper & The Hum" aren't read as markup.
pub fn plain_row(title: &str, subtitle: &str) -> adw::ActionRow {
    let row = adw::ActionRow::new();
    row.set_use_markup(false);
    row.set_title(title);
    row.set_subtitle(subtitle);
    row.set_title_lines(2);
    row.set_subtitle_lines(1);
    row
}

/// 3:20, or 1:02:03.
pub fn duration(ms: i64) -> String {
    let total = (ms.max(0) / 1000) as u64;
    let (h, m, s) = (total / 3600, (total / 60) % 60, total % 60);
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m}:{s:02}")
    }
}

/// A section's heading, as the web player's.
pub fn heading(text: &str) -> gtk::Label {
    let label = gtk::Label::builder().label(text).xalign(0.0).build();
    label.add_css_class("section-title");
    label
}

/// A page's large title, with a line under it, as the web player and the phone app head pages.
pub fn page_title(title: &str, subtitle: &str) -> gtk::Box {
    let header = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(4)
        .margin_bottom(8)
        .build();
    let name = gtk::Label::builder().label(title).xalign(0.0).build();
    name.add_css_class("page-title");
    header.append(&name);
    if !subtitle.is_empty() {
        let line = gtk::Label::builder().label(subtitle).xalign(0.0).wrap(true).build();
        line.add_css_class("page-subtitle");
        header.append(&line);
    }
    header
}

/// A page's content: scrolls, and keeps to a readable width.
pub fn scrolled(child: &impl IsA<gtk::Widget>) -> gtk::ScrolledWindow {
    let clamp = adw::Clamp::builder()
        .maximum_size(1180)
        .tightening_threshold(1000)
        .child(child)
        .build();
    gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .vexpand(true)
        .child(&clamp)
        .build()
}

pub fn page_box() -> gtk::Box {
    gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(6)
        .margin_top(4)
        .margin_bottom(32)
        .margin_start(28)
        .margin_end(28)
        .build()
}

/// A page over the theme's glow, with a flat bar (the back button, when it was opened from
/// another page); its title is in the page, large.
pub fn nav_page(title: &str, content: &impl IsA<gtk::Widget>) -> (adw::NavigationPage, adw::HeaderBar) {
    let header = adw::HeaderBar::builder().show_title(false).build();
    let toolbar = adw::ToolbarView::new();
    toolbar.add_css_class("page-bg");
    toolbar.set_top_bar_style(adw::ToolbarStyle::Flat);
    toolbar.add_top_bar(&header);
    toolbar.set_content(Some(content));
    (adw::NavigationPage::builder().title(title).child(&toolbar).build(), header)
}

pub fn loading() -> gtk::Widget {
    let spinner = gtk::Spinner::builder()
        .spinning(true)
        .width_request(32)
        .height_request(32)
        .halign(gtk::Align::Center)
        .valign(gtk::Align::Center)
        .vexpand(true)
        .build();
    spinner.upcast()
}

/// An empty page, as the family's apps show one: a round icon, a title and a line.
pub fn status(icon: &str, title: &str, description: &str) -> gtk::Box {
    let empty = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(10)
        .halign(gtk::Align::Center)
        .valign(gtk::Align::Center)
        .vexpand(true)
        .margin_top(48)
        .margin_bottom(48)
        .build();
    let circle = gtk::Box::builder().halign(gtk::Align::Center).build();
    circle.add_css_class("empty-icon");
    circle.append(
        &gtk::Image::builder()
            .icon_name(icon)
            .pixel_size(32)
            .hexpand(true)
            .vexpand(true)
            .build(),
    );
    empty.append(&circle);
    let name = gtk::Label::builder()
        .label(title)
        .wrap(true)
        .justify(gtk::Justification::Center)
        .build();
    name.add_css_class("empty-title");
    empty.append(&name);
    if !description.is_empty() {
        let line = gtk::Label::builder()
            .label(description)
            .wrap(true)
            .max_width_chars(52)
            .justify(gtk::Justification::Center)
            .build();
        line.add_css_class("empty-text");
        empty.append(&line);
    }
    empty
}

/// Play and Shuffle, for a list of songs.
pub fn play_buttons(tracks: Rc<Vec<Track>>) -> gtk::Box {
    let row = gtk::Box::builder().spacing(8).margin_top(6).build();
    let play = gtk::Button::builder()
        .child(
            &adw::ButtonContent::builder()
                .icon_name("media-playback-start-symbolic")
                .label("Play")
                .build(),
        )
        .build();
    play.add_css_class("suggested-action");
    play.add_css_class("pill");
    let empty = tracks.is_empty();
    let all = tracks.clone();
    play.connect_clicked(move |_| ctx().player.play_tracks(all.to_vec(), 0));
    let shuffle = gtk::Button::builder()
        .child(
            &adw::ButtonContent::builder()
                .icon_name("media-playlist-shuffle-symbolic")
                .label("Shuffle")
                .build(),
        )
        .build();
    shuffle.add_css_class("pill");
    shuffle.connect_clicked(move |_| ctx().player.play_shuffled(tracks.to_vec()));
    play.set_sensitive(!empty);
    shuffle.set_sensitive(!empty);
    row.append(&play);
    row.append(&shuffle);
    row
}

/// Songs in a rounded list: click one to play the list from it.
pub fn track_list(tracks: Rc<Vec<Track>>, numbered: bool) -> gtk::ListBox {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("boxed-list");
    for (index, track) in tracks.iter().enumerate() {
        list.append(&track_row(track, index, numbered, tracks.clone()));
    }
    list
}

fn track_row(track: &Track, index: usize, numbered: bool, all: Rc<Vec<Track>>) -> adw::ActionRow {
    let row = plain_row(&track.title, &track.subtitle());
    row.set_activatable(!track.is_missing());
    row.set_title_lines(1);
    row.set_subtitle_lines(1);
    if numbered {
        let number = gtk::Label::builder()
            .label((index + 1).to_string())
            .width_chars(3)
            .xalign(1.0)
            .build();
        number.add_css_class("track-number");
        number.add_css_class("numeric");
        row.add_prefix(&number);
    } else {
        row.add_prefix(&artwork(track.artwork_url.as_deref(), 44, false));
    }
    if track.is_missing() {
        row.add_css_class("dim-label");
        row.set_tooltip_text(Some("Not found in any of your sources"));
    }
    if let Some(ms) = track.duration_ms.filter(|ms| *ms > 0) {
        let length = gtk::Label::new(Some(&duration(ms)));
        length.add_css_class("dim-label");
        length.add_css_class("numeric");
        row.add_suffix(&length);
    }
    row.add_suffix(&track_menu(track));
    row.connect_activated(move |_| ctx().player.play_tracks(all.to_vec(), index));
    row
}

/// Play next, add to the queue, and the song's artist and album.
pub fn track_menu(track: &Track) -> gtk::MenuButton {
    let popover = gtk::Popover::new();
    let menu = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    let item = |label: &str, action: Box<dyn Fn()>| {
        let button = gtk::Button::builder().label(label).build();
        button.add_css_class("flat");
        if let Some(child) = button.child().and_downcast::<gtk::Label>() {
            child.set_xalign(0.0);
        }
        let popover = popover.clone();
        button.connect_clicked(move |_| {
            popover.popdown();
            action();
        });
        menu.append(&button);
    };
    let t = track.clone();
    item("Play next", Box::new(move || ctx().player.play_next(vec![t.clone()])));
    let t = track.clone();
    item("Add to queue", Box::new(move || ctx().player.add_to_queue(vec![t.clone()])));
    if !track.is_live() && !track.artist.is_empty() {
        let artist = track.artist.clone();
        item("Go to artist", Box::new(move || ctx().open_artist(None, &artist)));
        if let Some(album) = track.album.clone().filter(|a| !a.is_empty()) {
            let artist = track.artist.clone();
            item("Go to album", Box::new(move || ctx().open_album(None, &album, &artist)));
        }
    }
    popover.set_child(Some(&menu));
    let button = gtk::MenuButton::builder()
        .icon_name("view-more-symbolic")
        .popover(&popover)
        .valign(gtk::Align::Center)
        .tooltip_text("More")
        .build();
    button.add_css_class("flat");
    button
}

/// A card (cover, title, a line under it) in a grid of them.
pub fn card(art: Option<&str>, size: i32, round: bool, title: &str, subtitle: &str, on_click: impl Fn() + 'static) -> gtk::Button {
    let content = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    content.append(&artwork(art, size, round));
    let name = gtk::Label::builder()
        .label(title)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .max_width_chars(16)
        .xalign(if round { 0.5 } else { 0.0 })
        .build();
    name.add_css_class("card-title");
    content.append(&name);
    if !subtitle.is_empty() {
        let sub = gtk::Label::builder()
            .label(subtitle)
            .ellipsize(gtk::pango::EllipsizeMode::End)
            .max_width_chars(18)
            .xalign(if round { 0.5 } else { 0.0 })
            .build();
        sub.add_css_class("card-sub");
        content.append(&sub);
    }
    let button = gtk::Button::builder().child(&content).build();
    button.add_css_class("flat");
    button.add_css_class("card-tile");
    button.connect_clicked(move |_| on_click());
    button
}

pub fn card_grid() -> gtk::FlowBox {
    gtk::FlowBox::builder()
        .selection_mode(gtk::SelectionMode::None)
        .homogeneous(true)
        .min_children_per_line(2)
        .max_children_per_line(8)
        .column_spacing(8)
        .row_spacing(8)
        .valign(gtk::Align::Start)
        .build()
}
