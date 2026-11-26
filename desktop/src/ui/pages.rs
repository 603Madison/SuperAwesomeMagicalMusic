//! The pages, as the web player and the phone app lay them out: Search, an artist, an album,
//! Playlists and a playlist (Radio, History and Wrapped have their own modules).

use std::cell::Cell;
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

use crate::api::{AlbumSummary, Playlist, PlaylistSummary, SearchResults, TopResult, Track};
use crate::rt::bg;
use crate::ui::ctx;
use crate::ui::widgets::{
    ListStyle, PlaylistEdit, add_actions, artwork, button, card, card_grid, card_with, duration, heading, mosaic, nav_page, page_box,
    page_title, play_buttons, playlist_menu, scrolled, status, track_list, track_list_styled,
};
use crate::ui::window::load_into;

/// "1 song", "12 songs".
fn count(n: usize, thing: &str) -> String {
    if n == 1 { format!("1 {thing}") } else { format!("{n} {thing}s") }
}

fn clear(container: &gtk::Box) {
    while let Some(child) = container.first_child() {
        container.remove(&child);
    }
}

// ---- Search ----

fn search_start() -> gtk::Box {
    status(
        "system-search-symbolic",
        "Search all your music",
        "Artists, albums and songs from your library and every service on your server.",
    )
}

pub fn search_page() -> adw::NavigationPage {
    let content = page_box();
    content.append(&page_title("Search", ""));
    let entry = gtk::SearchEntry::builder()
        .placeholder_text("Artists, albums, songs")
        .search_delay(300)
        .margin_bottom(6)
        .build();
    entry.add_css_class("search-field");
    entry.set_hexpand(true);
    // Beside it, as on the web: Listen, for what's playing around you.
    let search_row = gtk::Box::builder().spacing(8).margin_bottom(6).build();
    entry.set_margin_bottom(0);
    search_row.append(&entry);
    search_row.append(&crate::ui::listen_start::listen_button());
    content.append(&search_row);
    let results = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    results.append(&search_start());
    content.append(&results);
    let (page, _) = nav_page("Search", &scrolled(&content));

    let asked = Rc::new(Cell::new(0u64));
    entry.connect_search_changed(move |entry| {
        let query = entry.text().trim().to_string();
        let number = asked.get() + 1;
        asked.set(number);
        clear(&results);
        if query.is_empty() {
            results.append(&search_start());
            return;
        }
        let Some(api) = ctx().api() else { return };
        results.append(&crate::ui::widgets::loading());
        let (results, asked) = (results.clone(), asked.clone());
        glib::spawn_future_local(async move {
            let q = query.clone();
            let found = bg(async move { api.search(&q).await }).await;
            // A newer search has started: this one's out of date.
            if asked.get() != number {
                return;
            }
            clear(&results);
            match found {
                Ok(found) => show_results(&results, &query, found),
                Err(e) => {
                    results.append(&status("dialog-warning-symbolic", "Couldn't search", &e.to_string()));
                    ctx().failed(&e);
                }
            }
        });
    });
    page.connect_shown(move |_| {
        entry.grab_focus();
    });
    page
}

fn show_results(into: &gtk::Box, query: &str, found: SearchResults) {
    if found.top.is_none() && found.songs.is_empty() && found.artists.is_empty() && found.albums.is_empty() {
        into.append(&status(
            "edit-find-symbolic",
            &format!("Nothing found for “{query}”"),
            "Try other words, or check your sources in Preferences.",
        ));
        return;
    }
    let songs = Rc::new(found.songs.clone());
    // The top result beside the first songs, as on the web.
    let top_row = gtk::Box::builder().spacing(24).build();
    if let Some(top) = found.top {
        let column = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .width_request(320)
            .build();
        column.append(&heading("Top result"));
        column.append(&top_result(top, songs.clone()));
        top_row.append(&column);
    }
    if !songs.is_empty() {
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).hexpand(true).build();
        column.append(&heading("Songs"));
        let first: Rc<Vec<Track>> = Rc::new(songs.iter().take(5).cloned().collect());
        column.append(&track_list(first, false));
        top_row.append(&column);
        // The rest under the first five, on asking (as the web's "All 20 songs").
        if songs.len() > 5 {
            let rest = gtk::Revealer::builder().transition_type(gtk::RevealerTransitionType::SlideDown).build();
            let more: Rc<Vec<Track>> = Rc::new(songs.iter().skip(5).cloned().collect());
            rest.set_child(Some(&track_list(more, false)));
            column.append(&rest);
            let all = format!("All {} songs", songs.len());
            let toggle = button(None, &all, "ghost");
            toggle.set_halign(gtk::Align::Start);
            toggle.connect_clicked(move |b| {
                let open = !rest.reveals_child();
                rest.set_reveal_child(open);
                if let Some(label) = b.child().and_then(|c| c.last_child()).and_downcast::<gtk::Label>() {
                    label.set_label(if open { "Fewer songs" } else { &all });
                }
            });
            column.append(&toggle);
        }
    }
    into.append(&top_row);
    if !found.artists.is_empty() {
        into.append(&heading("Artists"));
        let grid = card_grid();
        for artist in found.artists {
            let (id, name) = (artist.id.clone(), artist.name.clone());
            grid.append(&card(artist.artwork_url.as_deref(), 150, true, &artist.name, "Artist", move || {
                ctx().open_artist(Some(&id), &name)
            }));
        }
        into.append(&grid);
    }
    if !found.albums.is_empty() {
        into.append(&heading("Albums"));
        into.append(&album_grid(&found.albums));
    }
}

/// The likeliest match, large, in the theme's gradient (as the phone app shows it).
fn top_result(top: TopResult, songs: Rc<Vec<Track>>) -> gtk::Widget {
    let (title, kind, art, round): (String, String, Option<String>, bool) = match (&top.artist, &top.album, &top.track) {
        (Some(a), _, _) if top.kind == "artist" => (a.name.clone(), "Artist".into(), a.artwork_url.clone(), true),
        (_, Some(a), _) if top.kind == "album" => (a.title.clone(), format!("Album · {}", a.artist), a.artwork_url.clone(), false),
        (_, _, Some(t)) => (t.title.clone(), format!("Song · {}", t.artist), t.artwork_url.clone(), false),
        _ => return gtk::Box::new(gtk::Orientation::Horizontal, 0).upcast(),
    };
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(14).build();
    let art = artwork(art.as_deref(), 104, round);
    art.set_halign(gtk::Align::Start);
    column.append(&art);
    let name = gtk::Label::builder().label(&title).xalign(0.0).wrap(true).build();
    name.add_css_class("hero-title");
    column.append(&name);
    let line = gtk::Label::builder().label(&kind).xalign(0.0).build();
    line.add_css_class("top-kind");
    column.append(&line);
    let button = gtk::Button::builder().child(&column).build();
    button.add_css_class("flat");
    button.add_css_class("top-result");
    // Play, in its corner, as on the web: the artist's popular songs, the album, or the song.
    let play = gtk::Button::builder()
        .icon_name("media-playback-start-symbolic")
        .tooltip_text(format!("Play {title}"))
        .halign(gtk::Align::End)
        .valign(gtk::Align::End)
        .margin_end(16)
        .margin_bottom(16)
        .build();
    play.add_css_class("circular");
    play.add_css_class("suggested-action");
    play.add_css_class("play-button");
    {
        let (top, songs) = (top.clone(), songs.clone());
        play.connect_clicked(move |b| play_top(&top, &songs, b));
    }
    button.connect_clicked(move |_| match top.kind.as_str() {
        "artist" => {
            if let Some(a) = &top.artist {
                ctx().open_artist(Some(&a.id), &a.name);
            }
        }
        "album" => {
            if let Some(a) = &top.album {
                ctx().open_album(Some(&a.id), &a.title, &a.artist);
            }
        }
        _ => {
            if let Some(t) = &top.track {
                match songs.iter().position(|s| s == t) {
                    Some(i) => ctx().player.play_tracks(songs.to_vec(), i),
                    None => ctx().player.play_tracks(vec![t.clone()], 0),
                }
            }
        }
    });
    let overlay = gtk::Overlay::builder().child(&button).build();
    overlay.add_overlay(&play);
    overlay.upcast()
}

/// Plays the top result: the song, or the artist's popular songs, or the album.
fn play_top(top: &TopResult, songs: &Rc<Vec<Track>>, near: &gtk::Button) {
    if let Some(t) = &top.track {
        match songs.iter().position(|s| s == t) {
            Some(i) => ctx().player.play_tracks(songs.to_vec(), i),
            None => ctx().player.play_tracks(vec![t.clone()], 0),
        }
        return;
    }
    let Some(api) = ctx().api() else { return };
    let (artist, album) = (top.artist.clone(), top.album.clone());
    near.set_sensitive(false);
    let near = near.clone();
    glib::spawn_future_local(async move {
        let found = bg(async move {
            match (artist, album) {
                (Some(a), _) => api.artist(Some(&a.id), &a.name).await.map(|p| p.top_songs),
                (_, Some(a)) => api.album(Some(&a.id), &a.title, &a.artist).await.map(|p| p.tracks),
                _ => Ok(vec![]),
            }
        })
        .await;
        near.set_sensitive(true);
        match found {
            Ok(tracks) if !tracks.is_empty() => ctx().player.play_tracks(tracks, 0),
            Ok(_) => {}
            Err(e) => ctx().failed(&e),
        }
    });
}

fn album_grid(albums: &[AlbumSummary]) -> gtk::FlowBox {
    let grid = card_grid();
    for album in albums {
        let (id, title, artist) = (album.id.clone(), album.title.clone(), album.artist.clone());
        let under = match album.year {
            Some(year) => format!("{year} · {}", album.artist),
            None => album.artist.clone(),
        };
        grid.append(&card(album.artwork_url.as_deref(), 160, false, &album.title, &under, move || {
            ctx().open_album(Some(&id), &title, &artist)
        }));
    }
    grid
}

/// A page's top, as the web player's: the cover, a small label over the name, a line, and the
/// buttons.
fn hero(cover: gtk::Widget, eyebrow: &str, title: &str, line: &str, buttons: &impl IsA<gtk::Widget>) -> gtk::Box {
    let row = gtk::Box::builder().spacing(28).margin_top(8).margin_bottom(18).build();
    cover.add_css_class("hero");
    row.append(&cover);
    let text = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(8)
        .valign(gtk::Align::End)
        .hexpand(true)
        .build();
    let small = gtk::Label::builder().label(eyebrow.to_uppercase()).xalign(0.0).build();
    small.add_css_class("eyebrow");
    text.append(&small);
    let name = gtk::Label::builder()
        .label(title)
        .xalign(0.0)
        .wrap(true)
        .max_width_chars(32)
        .build();
    name.add_css_class("hero-title");
    text.append(&name);
    if !line.is_empty() {
        let under = gtk::Label::builder().label(line).xalign(0.0).wrap(true).build();
        under.add_css_class("meta");
        text.append(&under);
    }
    text.append(buttons);
    row.append(&text);
    row
}

/// "Add to playlist" for a whole list of songs (an artist's popular songs, an album).
fn add_all_button(tracks: &[Track]) -> gtk::MenuButton {
    let keepable: Rc<Vec<Track>> = Rc::new(tracks.iter().filter(|t| !t.is_live() && !t.is_missing()).cloned().collect());
    let group = gio::SimpleActionGroup::new();
    add_actions(&group, keepable.clone());
    let content = gtk::Box::builder().spacing(8).build();
    content.append(&gtk::Image::from_icon_name("list-add-symbolic"));
    content.append(&gtk::Label::new(Some("Add to playlist")));
    let button = gtk::MenuButton::builder().child(&content).menu_model(&playlist_menu("all")).build();
    button.add_css_class("pill");
    button.add_css_class("tonal");
    button.insert_action_group("all", Some(&group));
    button.set_sensitive(!keepable.is_empty());
    // Your playlists as they are when it opens.
    button.connect_notify_local(Some("active"), |b, _| {
        if b.is_active() {
            b.set_menu_model(Some(&playlist_menu("all")));
        }
    });
    button
}

// ---- An artist, an album ----

pub fn artist_page(id: Option<String>, name: String) -> adw::NavigationPage {
    let content = page_box();
    let (page, _) = nav_page(&name, &scrolled(&content));
    let Some(api) = ctx().api() else { return page };
    load_into(
        &content,
        async move { bg(async move { api.artist(id.as_deref(), &name).await }).await },
        |into, found| {
            let songs = Rc::new(found.top_songs.clone());
            let line = match (found.albums.len(), songs.len()) {
                (0, n) => count(n, "popular song"),
                (a, _) => count(a, "album"),
            };
            let buttons = play_buttons(songs.clone());
            if !songs.is_empty() {
                buttons.append(&add_all_button(&songs));
            }
            into.append(&hero(
                artwork(found.artist.artwork_url.as_deref(), 200, true),
                "Artist",
                &found.artist.name,
                &line,
                &buttons,
            ));
            if !songs.is_empty() {
                into.append(&heading("Popular"));
                let top: Rc<Vec<Track>> = Rc::new(songs.iter().take(10).cloned().collect());
                into.append(&track_list(top, false));
            }
            if !found.albums.is_empty() {
                into.append(&heading("Albums"));
                into.append(&album_grid(&found.albums));
            }
            if !found.singles.is_empty() {
                into.append(&heading("Singles and EPs"));
                into.append(&album_grid(&found.singles));
            }
        },
    );
    page
}

pub fn album_page(id: Option<String>, title: String, artist: String) -> adw::NavigationPage {
    let content = page_box();
    let (page, _) = nav_page(&title, &scrolled(&content));
    let Some(api) = ctx().api() else { return page };
    load_into(
        &content,
        async move { bg(async move { api.album(id.as_deref(), &title, &artist).await }).await },
        |into, found| {
            let tracks = Rc::new(found.tracks.clone());
            let length: i64 = tracks.iter().filter_map(|t| t.duration_ms).sum();
            let mut line = vec![found.album.artist.clone()];
            if let Some(year) = found.album.year {
                line.push(year.to_string());
            }
            line.push(count(tracks.len(), "song"));
            if length > 0 {
                line.push(duration(length));
            }
            let buttons = play_buttons(tracks.clone());
            if !tracks.is_empty() {
                buttons.append(&add_all_button(&tracks));
            }
            into.append(&hero(
                artwork(found.album.artwork_url.as_deref(), 200, false),
                "Album",
                &found.album.title,
                &line.join(" · "),
                &buttons,
            ));
            let artist = found.album.artist.clone();
            if !artist.is_empty() {
                let link = gtk::Button::builder()
                    .label(format!("More by {artist}"))
                    .halign(gtk::Align::Start)
                    .build();
                link.add_css_class("flat");
                link.connect_clicked(move |_| ctx().open_artist(None, &artist));
                into.append(&link);
            }
            into.append(&track_list(tracks, true));
        },
    );
    page
}

// ---- Playlists ----

pub fn playlists_page() -> adw::NavigationPage {
    let content = page_box();
    let top = gtk::Box::builder().spacing(12).build();
    let title = page_title("Playlists", "Yours, on every device: made here, on the web or on your phone.");
    title.set_hexpand(true);
    top.append(&title);
    // Import, and New playlist.
    top.append(&crate::ui::playlists::header_actions());
    content.append(&top);
    let holder = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    content.append(&holder);
    let (page, _) = nav_page("Playlists", &scrolled(&content));
    let Some(api) = ctx().api() else { return page };
    load_into(
        &holder,
        async move { bg(async move { api.playlists().await }).await },
        |into, playlists| {
            if playlists.is_empty() {
                into.append(&status(
                    "view-list-bullet-symbolic",
                    "No playlists yet",
                    "Make one, or import one from Spotify, Apple Music, Tidal, Deezer, YouTube and more. Songs are found in your own sources.",
                ));
                return;
            }
            let grid = card_grid();
            for playlist in playlists {
                let mut line = count(playlist.count as usize, "song");
                if let Some(note) = playlist.shared_note() {
                    line = note;
                } else if playlist.duration_ms > 0 {
                    line.push_str(&format!(" · {}", duration(playlist.duration_ms)));
                }
                let (id, name) = (playlist.id.clone(), playlist.name.clone());
                grid.append(&card_with(
                    &mosaic(&playlist.artwork, 160),
                    false,
                    &playlist.name,
                    &line,
                    move || ctx().open_playlist(&id, &name),
                ));
            }
            into.append(&grid);
        },
    );
    page
}

pub fn playlist_page(id: String, name: String) -> adw::NavigationPage {
    let content = page_box();
    let (page, _) = nav_page(&name, &scrolled(&content));
    // The sidebar marks the playlist that's open.
    page.set_widget_name(&format!("playlist:{id}"));
    let Some(api) = ctx().api() else { return page };
    let weak = page.downgrade();
    load_into(
        &content,
        async move { bg(async move { api.playlist(&id).await }).await },
        move |into, playlist| show_playlist(into, playlist, weak),
    );
    page
}

/// A playlist: its mosaic, name and line, its buttons (as the web player's), and its songs.
fn show_playlist(into: &gtk::Box, playlist: Playlist, page: glib::WeakRef<adw::NavigationPage>) {
    clear(into);
    let summary = playlist.summary.clone();
    if let Some(page) = page.upgrade() {
        page.set_title(&summary.name);
    }
    let tracks: Rc<Vec<Track>> = Rc::new(playlist.entries.iter().map(|e| e.track.clone()).collect());
    let length: i64 = tracks.iter().filter_map(|t| t.duration_ms).sum();
    let mut line = count(tracks.len(), "song");
    if length > 0 {
        line.push_str(&format!(" · {}", duration(length)));
    }
    // Its description, who it's shared between, and its songs, a line each (as on the web).
    let shared = crate::ui::playlists::is_shared(&summary);
    if shared {
        line = format!("{}\n{line}", crate::ui::playlists::people_line(&summary));
    }
    if !summary.description.is_empty() {
        line = format!("{}\n{line}", summary.description);
    }
    // Shows it as the server has it now (after sharing, or finding songs).
    let reload: Rc<dyn Fn()> = {
        let (holder, page, id) = (into.clone(), page.clone(), summary.id.clone());
        Rc::new(move || {
            let Some(api) = ctx().api() else { return };
            let (holder, page, id) = (holder.clone(), page.clone(), id.clone());
            glib::spawn_future_local(async move {
                if let Ok(playlist) = bg(async move { api.playlist(&id).await }).await {
                    show_playlist(&holder, playlist, page);
                }
            });
        })
    };
    // Playing it (on any device) puts it first among your playlists.
    let played_id = summary.id.clone();
    let played: Rc<dyn Fn()> = Rc::new(move || {
        let Some(api) = ctx().api() else { return };
        let id = played_id.clone();
        glib::spawn_future_local(async move {
            if bg(async move { api.playlist_played(&id).await }).await.is_ok() {
                crate::ui::window::refresh_sidebar();
            }
        });
    });

    let buttons = play_buttons(tracks.clone());
    let mut child = buttons.first_child();
    while let Some(widget) = child {
        child = widget.next_sibling();
        if let Ok(b) = widget.downcast::<gtk::Button>() {
            let played = played.clone();
            b.connect_clicked(move |_| played());
        }
    }
    let queue = button(None, "Add to queue", "ghost");
    queue.set_sensitive(!tracks.is_empty());
    let t = tracks.clone();
    queue.connect_clicked(move |_| {
        ctx().player.add_to_queue(t.iter().filter(|t| !t.is_missing()).cloned().collect());
        ctx().toast("Added to the queue");
    });
    buttons.append(&queue);
    // Someone else's shared playlist: its songs are everyone's, but sharing, renaming and deleting are its owner's.
    if summary.mine {
        let share = button(Some("system-users-symbolic"), "Share", "ghost");
        let (s, r) = (summary.clone(), reload.clone());
        share.connect_clicked(move |_| {
            let r = r.clone();
            crate::ui::playlists::share_dialog(
                &s,
                Rc::new(move || {
                    r();
                    crate::ui::window::refresh_sidebar();
                }),
            )
        });
        buttons.append(&share);
        let rename = button(Some("document-edit-symbolic"), "Rename", "ghost");
        let (holder, p, s) = (into.clone(), page.clone(), summary.clone());
        rename.connect_clicked(move |_| rename_playlist(&s, holder.clone(), p.clone()));
        buttons.append(&rename);
    }
    let export = button(Some("document-save-symbolic"), "Export CSV", "ghost");
    export.set_tooltip_text(Some(
        "Save it as a CSV file (Exportify's columns), for a spreadsheet or another service",
    ));
    let s = summary.clone();
    export.connect_clicked(move |_| export_playlist(&s));
    buttons.append(&export);
    if summary.mine {
        let delete = button(Some("user-trash-symbolic"), "Delete", "ghost");
        let s = summary.clone();
        delete.connect_clicked(move |_| delete_playlist(&s));
        buttons.append(&delete);
    } else {
        let leave = button(Some("system-log-out-symbolic"), "Leave", "ghost");
        let s = summary.clone();
        leave.connect_clicked(move |_| crate::ui::playlists::leave(&s));
        buttons.append(&leave);
    }
    // Many buttons: they wrap onto a second line rather than widen the page.
    let buttons = wrap_buttons(buttons);

    let kind = if shared { "Shared playlist" } else { "Playlist" };
    into.append(&hero(mosaic(&summary.artwork, 200), kind, &summary.name, &line, &buttons));
    if tracks.is_empty() {
        into.append(&status(
            "view-list-bullet-symbolic",
            "This playlist is empty",
            "Add songs from a song's menu (⋯, then Add to playlist), here, on the web or on your phone.",
        ));
        return;
    }
    let missing = tracks.iter().filter(|t| t.is_missing()).count();
    if missing > 0 {
        let (holder, p) = (into.clone(), page.clone());
        into.append(&crate::ui::playlists::missing_note(
            &summary.id,
            missing,
            Rc::new(move |playlist| show_playlist(&holder, playlist, p.clone())),
        ));
    }
    let notes = crate::ui::playlists::added_by_notes(&playlist);
    let holder = into.clone();
    let edit = PlaylistEdit {
        id: summary.id.clone(),
        revision: summary.revision,
        entry_ids: playlist.entries.iter().map(|e| e.entry_id.clone()).collect(),
        changed: Rc::new(move |playlist| show_playlist(&holder, playlist, page.clone())),
    };
    into.append(&track_list_styled(
        tracks,
        ListStyle {
            numbered: true,
            hide_album: false,
            playlist: Some(edit),
            on_play: Some(played),
            notes,
        },
    ));
}

/// A row of buttons as a flow that wraps when the page is narrow.
fn wrap_buttons(row: gtk::Box) -> gtk::FlowBox {
    let flow = gtk::FlowBox::builder()
        .selection_mode(gtk::SelectionMode::None)
        .column_spacing(8)
        .row_spacing(8)
        .min_children_per_line(2)
        .max_children_per_line(12)
        .margin_top(6)
        .build();
    while let Some(child) = row.first_child() {
        row.remove(&child);
        flow.append(&child);
    }
    flow
}

fn rename_playlist(summary: &PlaylistSummary, holder: gtk::Box, page: glib::WeakRef<adw::NavigationPage>) {
    let dialog = adw::AlertDialog::new(Some("Rename playlist"), None);
    let fields = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(10).build();
    let name = gtk::Entry::builder()
        .text(&summary.name)
        .placeholder_text("Name")
        .activates_default(true)
        .build();
    let description = gtk::Entry::builder()
        .text(&summary.description)
        .placeholder_text("Description (optional)")
        .activates_default(true)
        .build();
    fields.append(&name);
    fields.append(&description);
    dialog.set_extra_child(Some(&fields));
    dialog.add_responses(&[("cancel", "Cancel"), ("save", "Save")]);
    dialog.set_response_appearance("save", adw::ResponseAppearance::Suggested);
    dialog.set_default_response(Some("save"));
    dialog.set_close_response("cancel");
    let id = summary.id.clone();
    dialog.connect_response(None, move |_, response| {
        let (new_name, new_description) = (name.text().trim().to_string(), description.text().trim().to_string());
        if response != "save" || new_name.is_empty() {
            return;
        }
        let Some(api) = ctx().api() else { return };
        let (id, holder, page) = (id.clone(), holder.clone(), page.clone());
        glib::spawn_future_local(async move {
            let result = bg(async move {
                api.rename_playlist(&id, &new_name, &new_description).await?;
                api.playlist(&id).await
            })
            .await;
            match result {
                Ok(playlist) => {
                    show_playlist(&holder, playlist, page);
                    crate::ui::window::refresh_sidebar();
                }
                Err(e) => ctx().failed(&e),
            }
        });
    });
    dialog.present(Some(&ctx().window));
}

/// Saves the playlist as a CSV file where you choose (the server writes it, as for the web).
fn export_playlist(summary: &PlaylistSummary) {
    let safe: String = summary
        .name
        .chars()
        .map(|c| if c.is_alphanumeric() || " -_.()".contains(c) { c } else { '_' })
        .collect();
    let safe = safe.trim();
    let dialog = gtk::FileDialog::builder()
        .title("Export playlist")
        .initial_name(format!("{}.csv", if safe.is_empty() { "playlist" } else { safe }))
        .build();
    let id = summary.id.clone();
    dialog.save(Some(&ctx().window), gio::Cancellable::NONE, move |chosen| {
        let Some(path) = chosen.ok().and_then(|file| file.path()) else {
            return;
        };
        let Some(api) = ctx().api() else { return };
        let id = id.clone();
        glib::spawn_future_local(async move {
            match bg(async move { api.export_playlist(&id).await }).await {
                Ok(bytes) => match std::fs::write(&path, bytes) {
                    Ok(()) => ctx().toast(&format!(
                        "Saved {}",
                        path.file_name().map(|n| n.to_string_lossy()).unwrap_or_default()
                    )),
                    Err(e) => ctx().toast(&format!("Couldn't save it: {e}")),
                },
                Err(e) => ctx().failed(&e),
            }
        });
    });
}

fn delete_playlist(summary: &PlaylistSummary) {
    let dialog = adw::AlertDialog::new(
        Some(&format!("Delete “{}”?", summary.name)),
        Some(&crate::ui::playlists::delete_detail(summary)),
    );
    dialog.add_responses(&[("keep", "Keep it"), ("delete", "Delete")]);
    dialog.set_response_appearance("delete", adw::ResponseAppearance::Destructive);
    dialog.set_close_response("keep");
    let (id, name) = (summary.id.clone(), summary.name.clone());
    dialog.connect_response(Some("delete"), move |_, _| {
        let Some(api) = ctx().api() else { return };
        let (id, name) = (id.clone(), name.clone());
        glib::spawn_future_local(async move {
            match bg(async move { api.delete_playlist(&id).await }).await {
                Ok(_) => {
                    ctx().toast(&format!("Deleted “{name}”"));
                    ctx().back();
                    crate::ui::window::refresh_sidebar();
                }
                Err(e) => ctx().failed(&e),
            }
        });
    });
    dialog.present(Some(&ctx().window));
}

