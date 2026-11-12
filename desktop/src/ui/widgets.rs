//! Building blocks: covers loaded from the server (cached), song rows, cards and small helpers.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;
use std::sync::OnceLock;

use adw::prelude::*;
use gtk::{gdk, gio, glib};

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

/// A button in the web player's styles: "suggested" (the theme's colour), "tonal" (grey) or
/// "ghost" (the theme's colour as text), with an icon before its label.
pub fn button(icon: Option<&str>, label: &str, kind: &str) -> gtk::Button {
    let content = gtk::Box::builder().spacing(8).halign(gtk::Align::Center).build();
    if let Some(icon) = icon {
        content.append(&gtk::Image::from_icon_name(icon));
    }
    content.append(&gtk::Label::new(Some(label)));
    let button = gtk::Button::builder().child(&content).build();
    button.add_css_class("pill");
    button.add_css_class(match kind {
        "suggested" => "suggested-action",
        "ghost" => "ghost",
        _ => "tonal",
    });
    button
}

/// Play and Shuffle, for a list of songs (and whatever else the page puts beside them).
pub fn play_buttons(tracks: Rc<Vec<Track>>) -> gtk::Box {
    let row = gtk::Box::builder().spacing(8).margin_top(6).build();
    let playable: Vec<Track> = tracks.iter().filter(|t| !t.is_missing()).cloned().collect();
    let play = button(Some("media-playback-start-symbolic"), "Play", "suggested");
    play.set_sensitive(!playable.is_empty());
    let all = playable.clone();
    play.connect_clicked(move |_| ctx().player.play_tracks(all.clone(), 0));
    let shuffle = button(None, "Shuffle", "tonal");
    shuffle.set_sensitive(playable.len() > 1);
    shuffle.connect_clicked(move |_| ctx().player.play_shuffled(playable.clone()));
    row.append(&play);
    row.append(&shuffle);
    row
}

// ---- Song lists, as the web player's ----

/// What a song is, for telling it apart in lists and the player (as the web player keys songs).
pub fn track_key(track: &Track) -> String {
    format!("{}|{}:{}", track.via.as_deref().unwrap_or(""), track.source, track.source_id)
}

/// A song row the player marks while it plays.
struct Marked {
    row: glib::WeakRef<gtk::ListBoxRow>,
    icon: glib::WeakRef<gtk::Image>,
    key: String,
}

thread_local! {
    static MARKED: RefCell<Vec<Marked>> = const { RefCell::new(Vec::new()) };
    /// What's playing (its key) and whether it's playing, as last marked.
    static SHOWN: RefCell<(Option<String>, bool)> = const { RefCell::new((None, false)) };
    static MARKING: Cell<bool> = const { Cell::new(false) };
    /// Your playlists (id, name), for "Add to playlist".
    static PLAYLISTS: RefCell<Vec<(String, String)>> = const { RefCell::new(Vec::new()) };
}

/// Keeps your playlists for the songs' "Add to playlist" menus.
pub fn remember_playlists(playlists: &[crate::api::PlaylistSummary]) {
    PLAYLISTS.with(|p| p.replace(playlists.iter().map(|p| (p.id.clone(), p.name.clone())).collect()));
}

fn mark(entry: &Marked, shown: &(Option<String>, bool)) -> bool {
    let (Some(row), Some(icon)) = (entry.row.upgrade(), entry.icon.upgrade()) else {
        return false;
    };
    let current = shown.0.as_deref() == Some(entry.key.as_str());
    if current {
        row.add_css_class("current");
    } else {
        row.remove_css_class("current");
    }
    icon.set_icon_name(Some(if current && shown.1 {
        "media-playback-pause-symbolic"
    } else {
        "media-playback-start-symbolic"
    }));
    true
}

/// Follows the player: the row of the song that's playing is marked, in every list on screen.
fn follow_player() {
    if MARKING.with(|m| m.replace(true)) {
        return;
    }
    ctx().player.subscribe(|state| {
        let now = (state.current().map(track_key), state.playing);
        let changed = SHOWN.with(|s| {
            let mut shown = s.borrow_mut();
            if *shown == now {
                false
            } else {
                *shown = now.clone();
                true
            }
        });
        if changed {
            MARKED.with(|m| m.borrow_mut().retain(|entry| mark(entry, &now)));
        }
    });
}

fn remember_row(row: &gtk::ListBoxRow, icon: &gtk::Image, key: String) {
    follow_player();
    let entry = Marked {
        row: row.downgrade(),
        icon: icon.downgrade(),
        key,
    };
    SHOWN.with(|s| mark(&entry, &s.borrow()));
    MARKED.with(|m| {
        let mut marked = m.borrow_mut();
        // Now and then, forget rows that are gone.
        if marked.len() > 400 {
            marked.retain(|e| e.row.upgrade().is_some());
        }
        marked.push(entry);
    });
}

/// A playlist's own items for its songs' menus: move up, move down, remove.
#[derive(Clone)]
pub struct PlaylistEdit {
    pub id: String,
    pub revision: i64,
    pub entry_ids: Vec<String>,
    /// Shows the playlist as the server now has it.
    pub changed: Rc<dyn Fn(crate::api::Playlist)>,
}

/// How a song list looks: numbered (albums, playlists), with the album under each song, and a
/// playlist's own menu items.
#[derive(Clone, Default)]
pub struct ListStyle {
    pub numbered: bool,
    pub hide_album: bool,
    pub playlist: Option<PlaylistEdit>,
    /// Told when a song in the list starts playing (a playlist notes it was played).
    pub on_play: Option<Rc<dyn Fn()>>,
}

/// Songs, as the web player lists them: click one to play the list from it (or to pause it).
pub fn track_list(tracks: Rc<Vec<Track>>, numbered: bool) -> gtk::ListBox {
    track_list_styled(
        tracks,
        ListStyle {
            numbered,
            hide_album: numbered,
            ..Default::default()
        },
    )
}

pub fn track_list_styled(tracks: Rc<Vec<Track>>, style: ListStyle) -> gtk::ListBox {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("track-list");
    for (index, track) in tracks.iter().enumerate() {
        list.append(&track_row(track, index, &style));
    }
    let on_play = style.on_play.clone();
    list.connect_row_activated(move |_, row| {
        let index = row.index() as usize;
        let Some(track) = tracks.get(index) else { return };
        if track.is_missing() {
            return;
        }
        let player = ctx().player.clone();
        if player.state().current().map(track_key) == Some(track_key(track)) {
            return player.toggle();
        }
        // Played from here, the songs not found are left out: start at this one among the rest.
        let playable: Vec<Track> = tracks.iter().filter(|t| !t.is_missing()).cloned().collect();
        let start = tracks[..index].iter().filter(|t| !t.is_missing()).count();
        player.play_tracks(playable, start);
        if let Some(on_play) = &on_play {
            on_play();
        }
    });
    list
}

fn track_row(track: &Track, index: usize, style: &ListStyle) -> gtk::ListBoxRow {
    let line = gtk::Box::builder().spacing(14).build();
    if style.numbered {
        let number = gtk::Label::builder()
            .label((index + 1).to_string())
            .width_chars(3)
            .xalign(1.0)
            .build();
        number.add_css_class("track-number");
        number.add_css_class("numeric");
        line.append(&number);
    }
    let cover = gtk::Overlay::builder()
        .child(&artwork(track.artwork_url.as_deref(), 44, false))
        .build();
    let icon = gtk::Image::builder()
        .icon_name("media-playback-start-symbolic")
        .pixel_size(18)
        .width_request(44)
        .height_request(44)
        .build();
    icon.add_css_class("track-play-icon");
    cover.add_overlay(&icon);
    line.append(&cover);

    let text = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .valign(gtk::Align::Center)
        .hexpand(true)
        .build();
    let title = gtk::Label::builder()
        .label(&track.title)
        .xalign(0.0)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .build();
    title.add_css_class("track-title");
    text.append(&title);
    let under = if style.hide_album { track.artist.clone() } else { track.subtitle() };
    if !under.is_empty() {
        let sub = gtk::Label::builder()
            .label(&under)
            .xalign(0.0)
            .ellipsize(gtk::pango::EllipsizeMode::End)
            .build();
        sub.add_css_class("track-sub");
        text.append(&sub);
    }
    line.append(&text);

    // Where a song plays from is the app's business; radio is worth saying.
    let badge = if track.is_missing() {
        Some("Not found")
    } else if track.is_channel() {
        Some("SiriusXM")
    } else if track.is_live() {
        Some("Radio")
    } else {
        None
    };
    if let Some(badge) = badge {
        let label = gtk::Label::builder().label(badge).valign(gtk::Align::Center).build();
        label.add_css_class("badge");
        line.append(&label);
    }
    let length = gtk::Label::builder()
        .label(track.duration_ms.filter(|ms| *ms > 0).map(duration).unwrap_or_default())
        .width_chars(5)
        .xalign(1.0)
        .build();
    length.add_css_class("track-duration");
    length.add_css_class("numeric");
    line.append(&length);
    line.append(&track_menu(track, index, style.playlist.as_ref()));

    let row = gtk::ListBoxRow::builder().child(&line).activatable(!track.is_missing()).build();
    row.add_css_class("track-row");
    if track.is_missing() {
        row.add_css_class("missing");
        row.set_tooltip_text(Some("Not found in your sources yet: it's looked for again every few hours"));
    }
    remember_row(&row, &icon, track_key(track));
    row
}

/// Asks for a name, then makes a playlist of [tracks].
pub fn new_playlist(tracks: Vec<Track>) {
    let dialog = adw::AlertDialog::new(Some("New playlist"), None);
    let entry = gtk::Entry::builder().placeholder_text("Name").activates_default(true).build();
    dialog.set_extra_child(Some(&entry));
    dialog.add_responses(&[("cancel", "Cancel"), ("make", "Make it")]);
    dialog.set_response_appearance("make", adw::ResponseAppearance::Suggested);
    dialog.set_default_response(Some("make"));
    dialog.set_close_response("cancel");
    let e = entry.clone();
    dialog.connect_response(None, move |_, response| {
        let name = e.text().trim().to_string();
        if response != "make" || name.is_empty() {
            return;
        }
        let Some(api) = ctx().api() else { return };
        let tracks = tracks.clone();
        glib::spawn_future_local(async move {
            let made = name.clone();
            match bg(async move { api.create_playlist(&made, &tracks).await }).await {
                Ok(_) => {
                    ctx().toast(&format!("Made “{name}”"));
                    crate::ui::window::refresh_sidebar();
                }
                Err(e) => ctx().failed(&e),
            }
        });
    });
    dialog.present(Some(&ctx().window));
    entry.grab_focus();
}

/// Adds [tracks] to a playlist, and says so.
pub fn add_to_playlist(id: String, name: String, tracks: Vec<Track>) {
    let Some(api) = ctx().api() else { return };
    let said = if tracks.len() == 1 {
        format!("Added “{}” to {name}", tracks[0].title)
    } else {
        format!("Added {} songs to {name}", tracks.len())
    };
    glib::spawn_future_local(async move {
        match bg(async move { api.add_to_playlist(&id, &tracks).await }).await {
            Ok(_) => {
                ctx().toast(&said);
                crate::ui::window::refresh_sidebar();
            }
            Err(e) => ctx().failed(&e),
        }
    });
}

/// "Add to playlist": New playlist…, then each of yours.
pub fn playlist_menu(prefix: &str) -> gio::Menu {
    let menu = gio::Menu::new();
    menu.append(Some("New playlist…"), Some(&format!("{prefix}.new-playlist")));
    let yours = gio::Menu::new();
    PLAYLISTS.with(|p| {
        for (id, name) in p.borrow().iter() {
            let item = gio::MenuItem::new(Some(name), None);
            item.set_action_and_target_value(Some(&format!("{prefix}.add-to")), Some(&id.to_variant()));
            yours.append_item(&item);
        }
    });
    menu.append_section(None, &yours);
    menu
}

fn playlist_named(id: &str) -> String {
    PLAYLISTS.with(|p| {
        p.borrow()
            .iter()
            .find(|(i, _)| i == id)
            .map(|(_, n)| n.clone())
            .unwrap_or_else(|| "the playlist".into())
    })
}

/// The actions "Add to playlist" calls, for [tracks].
pub fn add_actions(group: &gio::SimpleActionGroup, tracks: Rc<Vec<Track>>) {
    let new = gio::SimpleAction::new("new-playlist", None);
    let t = tracks.clone();
    new.connect_activate(move |_, _| new_playlist(t.to_vec()));
    group.add_action(&new);
    let add = gio::SimpleAction::new("add-to", Some(glib::VariantTy::STRING));
    add.connect_activate(move |_, id| {
        let Some(id) = id.and_then(|v| v.get::<String>()) else { return };
        let name = playlist_named(&id);
        add_to_playlist(id, name, tracks.to_vec());
    });
    group.add_action(&add);
}

/// Play next, add to the queue or a playlist, the song's artist and album, and a playlist's own.
fn track_menu(track: &Track, index: usize, playlist: Option<&PlaylistEdit>) -> gtk::MenuButton {
    let group = gio::SimpleActionGroup::new();
    let action = |name: &str, run: Box<dyn Fn()>| {
        let a = gio::SimpleAction::new(name, None);
        a.connect_activate(move |_, _| run());
        group.add_action(&a);
    };
    let live = track.is_live();
    let missing = track.is_missing();
    let t = track.clone();
    action(
        "play-next",
        Box::new(move || {
            ctx().player.play_next(vec![t.clone()]);
            ctx().toast(&format!("“{}” plays next", t.title));
        }),
    );
    let t = track.clone();
    action(
        "queue",
        Box::new(move || {
            ctx().player.add_to_queue(vec![t.clone()]);
            ctx().toast(&format!("Added “{}” to the queue", t.title));
        }),
    );
    let artist = track.artist.clone();
    action("artist", Box::new(move || ctx().open_artist(None, &artist)));
    let (album, artist) = (track.album.clone().unwrap_or_default(), track.artist.clone());
    action("album", Box::new(move || ctx().open_album(None, &album, &artist)));
    add_actions(&group, Rc::new(vec![track.clone()]));

    if let Some(edit) = playlist {
        let count = edit.entry_ids.len();
        let reorder = |to: Option<usize>| {
            let edit = edit.clone();
            Box::new(move || {
                let Some(to) = to.filter(|to| *to < count) else { return };
                let mut order = edit.entry_ids.clone();
                order.swap(index, to);
                let Some(api) = ctx().api() else { return };
                let edit = edit.clone();
                glib::spawn_future_local(async move {
                    let (id, revision) = (edit.id.clone(), edit.revision);
                    match bg(async move { api.reorder_playlist(&id, revision, &order).await }).await {
                        Ok(playlist) => (edit.changed)(playlist),
                        Err(e) => ctx().failed(&e),
                    }
                });
            }) as Box<dyn Fn()>
        };
        action("up", reorder(index.checked_sub(1)));
        action("down", reorder(Some(index + 1)));
        let edit = edit.clone();
        action(
            "remove",
            Box::new(move || {
                let Some(entry) = edit.entry_ids.get(index).cloned() else { return };
                let Some(api) = ctx().api() else { return };
                let edit = edit.clone();
                glib::spawn_future_local(async move {
                    let id = edit.id.clone();
                    match bg(async move { api.remove_from_playlist(&id, &[entry]).await }).await {
                        Ok(playlist) => {
                            (edit.changed)(playlist);
                            crate::ui::window::refresh_sidebar();
                        }
                        Err(e) => ctx().failed(&e),
                    }
                });
            }),
        );
    }

    let menu = gio::Menu::new();
    if !missing {
        let play = gio::Menu::new();
        play.append(Some("Play next"), Some("song.play-next"));
        play.append(Some("Add to queue"), Some("song.queue"));
        if !live {
            play.append_submenu(Some("Add to playlist"), &playlist_menu("song"));
        }
        menu.append_section(None, &play);
    }
    if !live && !track.artist.is_empty() {
        let browse = gio::Menu::new();
        browse.append(Some("Go to artist"), Some("song.artist"));
        if track.album.as_deref().is_some_and(|a| !a.is_empty()) {
            browse.append(Some("Go to album"), Some("song.album"));
        }
        menu.append_section(None, &browse);
    }
    if let Some(edit) = playlist {
        let own = gio::Menu::new();
        if index > 0 {
            own.append(Some("Move up"), Some("song.up"));
        }
        if index + 1 < edit.entry_ids.len() {
            own.append(Some("Move down"), Some("song.down"));
        }
        own.append(Some("Remove from this playlist"), Some("song.remove"));
        menu.append_section(None, &own);
    }
    let button = gtk::MenuButton::builder()
        .icon_name("view-more-horizontal-symbolic")
        .menu_model(&menu)
        .valign(gtk::Align::Center)
        .tooltip_text("More")
        .build();
    button.add_css_class("flat");
    button.add_css_class("circular");
    button.insert_action_group("song", Some(&group));
    // The playlists may have changed since: the menu lists them as they are when it opens.
    if !live && !missing {
        let menu_for_open = menu.clone();
        button.connect_notify_local(Some("active"), move |b, _| {
            if b.is_active()
                && let Some(play) = menu_for_open.item_link(0, gio::MENU_LINK_SECTION).and_downcast::<gio::Menu>()
            {
                play.remove(2);
                play.append_submenu(Some("Add to playlist"), &playlist_menu("song"));
            }
        });
    }
    button
}

/// Covers of a playlist: four in a square, as the web player draws them (or the first, with fewer).
pub fn mosaic(urls: &[String], size: i32) -> gtk::Widget {
    if urls.len() < 4 {
        return artwork(urls.first().map(String::as_str), size, false);
    }
    let grid = gtk::Grid::builder()
        .width_request(size)
        .height_request(size)
        .halign(gtk::Align::Center)
        .valign(gtk::Align::Center)
        .build();
    grid.set_hexpand(false);
    grid.set_vexpand(false);
    grid.set_overflow(gtk::Overflow::Hidden);
    grid.add_css_class("artwork");
    grid.add_css_class("mosaic");
    let half = size / 2;
    for (i, url) in urls.iter().take(4).enumerate() {
        grid.attach(&artwork(Some(url), half, false), (i % 2) as i32, (i / 2) as i32, 1, 1);
    }
    grid.upcast()
}

/// A card (cover, title, a line under it) in a grid of them.
pub fn card(art: Option<&str>, size: i32, round: bool, title: &str, subtitle: &str, on_click: impl Fn() + 'static) -> gtk::Button {
    card_with(&artwork(art, size, round), round, title, subtitle, on_click)
}

/// A card with its own cover (a playlist's mosaic).
pub fn card_with(cover: &gtk::Widget, round: bool, title: &str, subtitle: &str, on_click: impl Fn() + 'static) -> gtk::Button {
    let content = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    content.append(cover);
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
