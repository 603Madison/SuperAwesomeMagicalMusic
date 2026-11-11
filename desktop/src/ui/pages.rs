//! The pages, as the web player and the phone app lay them out: Search, an artist, an album,
//! Playlists and a playlist, Radio, and History.

use std::cell::{Cell, RefCell};
use std::collections::BTreeMap;
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::api::{AlbumSummary, ChannelView, SearchResults, SourceView, TopResult, Track};
use crate::rt::bg;
use crate::ui::ctx;
use crate::ui::widgets::{
    artwork, card, card_grid, duration, heading, nav_page, page_box, page_title, plain_row, play_buttons, scrolled, status, track_list,
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
    content.append(&entry);
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
            "Try other words, or check your sources on the server.",
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
    }
    into.append(&top_row);
    if songs.len() > 5 {
        into.append(&heading("More songs"));
        let rest: Rc<Vec<Track>> = Rc::new(songs.iter().skip(5).take(10).cloned().collect());
        into.append(&track_list(rest, false));
    }
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
    button.upcast()
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

/// A page's top, as the web player's: the cover, a small label over the name, a line, and Play.
fn hero(art: Option<&str>, round: bool, eyebrow: &str, title: &str, line: &str, tracks: Rc<Vec<Track>>) -> gtk::Box {
    let row = gtk::Box::builder().spacing(28).margin_top(8).margin_bottom(10).build();
    let cover = artwork(art, 200, round);
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
    text.append(&play_buttons(tracks));
    row.append(&text);
    row
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
            into.append(&hero(
                found.artist.artwork_url.as_deref(),
                true,
                "Artist",
                &found.artist.name,
                &line,
                songs.clone(),
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
            into.append(&hero(
                found.album.artwork_url.as_deref(),
                false,
                "Album",
                &found.album.title,
                &line.join(" · "),
                tracks.clone(),
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
    content.append(&page_title(
        "Playlists",
        "Yours, on every device: made here, on the web or on your phone.",
    ));
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
                    "Make one in the web player or the phone app; it shows up here.",
                ));
                return;
            }
            let grid = card_grid();
            for playlist in playlists {
                let mut line = count(playlist.count as usize, "song");
                if playlist.duration_ms > 0 {
                    line.push_str(&format!(" · {}", duration(playlist.duration_ms)));
                }
                let (id, name) = (playlist.id.clone(), playlist.name.clone());
                grid.append(&card(
                    playlist.artwork.first().map(String::as_str),
                    160,
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
    let Some(api) = ctx().api() else { return page };
    let played_id = id.clone();
    load_into(
        &content,
        async move { bg(async move { api.playlist(&id).await }).await },
        move |into, playlist| {
            let tracks: Rc<Vec<Track>> = Rc::new(playlist.entries.iter().map(|e| e.track.clone()).collect());
            let length: i64 = tracks.iter().filter_map(|t| t.duration_ms).sum();
            let mut line = count(tracks.len(), "song");
            if length > 0 {
                line.push_str(&format!(" · {}", duration(length)));
            }
            if !playlist.summary.description.is_empty() {
                line = format!("{} — {line}", playlist.summary.description);
            }
            into.append(&hero(
                playlist.summary.artwork.first().map(String::as_str),
                false,
                "Playlist",
                &playlist.summary.name,
                &line,
                tracks.clone(),
            ));
            if tracks.is_empty() {
                into.append(&status(
                    "view-list-bullet-symbolic",
                    "No songs yet",
                    "Add songs from the web player or the phone app.",
                ));
                return;
            }
            into.append(&track_list(tracks, true));
            // Marks it played (the order of playlists elsewhere), quietly.
            if let Some(api) = ctx().api() {
                glib::spawn_future_local(async move {
                    if bg(async move { api.playlist_played(&played_id).await }).await.is_ok() {
                        crate::ui::window::refresh_sidebar();
                    }
                });
            }
        },
    );
    page
}

// ---- Radio ----

/// Toggle buttons that act as one switch, as a pill (the web's tabs, the phone's segmented control).
fn segmented(choices: &[(&str, &str)], stack: &gtk::Stack) -> gtk::Box {
    let bar = gtk::Box::builder().halign(gtk::Align::Start).margin_bottom(10).build();
    bar.add_css_class("segmented");
    let mut first: Option<gtk::ToggleButton> = None;
    for (name, label) in choices {
        let button = gtk::ToggleButton::builder().label(*label).build();
        if let Some(group) = &first {
            button.set_group(Some(group));
        } else {
            button.set_active(true);
            first = Some(button.clone());
        }
        let (stack, name) = (stack.clone(), name.to_string());
        button.connect_toggled(move |b| {
            if b.is_active() {
                stack.set_visible_child_name(&name);
            }
        });
        bar.append(&button);
    }
    bar
}

pub fn radio_page() -> adw::NavigationPage {
    let content = page_box();
    content.append(&page_title(
        "Radio",
        "SiriusXM, local and internet radio, and your favourite stations.",
    ));
    let stack = gtk::Stack::builder()
        .transition_type(gtk::StackTransitionType::Crossfade)
        .vhomogeneous(false)
        .build();
    let favourites = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    let siriusxm = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    let radio = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    stack.add_named(&favourites, Some("favourites"));
    stack.add_named(&siriusxm, Some("siriusxm"));
    stack.add_named(&radio, Some("radio"));
    content.append(&segmented(
        &[("favourites", "Favourites"), ("siriusxm", "SiriusXM"), ("radio", "Local radio")],
        &stack,
    ));
    content.append(&stack);
    let (page, _) = nav_page("Radio", &scrolled(&content));

    let Some(api) = ctx().api() else { return page };
    for holder in [&favourites, &siriusxm, &radio] {
        holder.append(&crate::ui::widgets::loading());
    }
    glib::spawn_future_local(async move {
        let found = bg(async move { tokio::join!(api.live_favorites(), api.sources()) }).await;
        for holder in [&favourites, &siriusxm, &radio] {
            clear(holder);
        }
        let (stations, sources) = match found {
            (Ok(stations), Ok(sources)) => (stations, sources),
            (Err(e), _) | (_, Err(e)) => {
                favourites.append(&status("dialog-warning-symbolic", "Couldn't load this", &e.to_string()));
                return ctx().failed(&e);
            }
        };
        let starred: Starred = Rc::new(RefCell::new(stations.iter().map(favourite_key).collect()));
        show_favourites(&favourites, stations);
        let sources: Vec<SourceView> = sources.into_iter().filter(|s| s.enabled && !s.hidden).collect();
        let sxm: Vec<SourceView> = sources.iter().filter(|s| s.kind == "SIRIUSXM").cloned().collect();
        let stations: Vec<SourceView> = sources.iter().filter(|s| s.kind == "RADIO").cloned().collect();
        siriusxm_channels(&siriusxm, sxm, &starred);
        local_radio(&radio, stations, &starred);
        // Back on Favourites after starring something: shows it as it is now.
        stack.connect_visible_child_name_notify(move |stack| {
            if stack.visible_child_name().as_deref() != Some("favourites") {
                return;
            }
            let Some(api) = ctx().api() else { return };
            clear(&favourites);
            load_into(
                &favourites,
                async move { bg(async move { api.live_favorites().await }).await },
                show_favourites,
            );
        });
    });
    page
}

/// Your favourite channels and stations (as keys), so their stars show lit.
type Starred = Rc<RefCell<std::collections::HashSet<String>>>;

fn favourite_key(track: &Track) -> String {
    format!("{}:{}", track.source, track.source_id)
}

fn show_favourites(into: &gtk::Box, stations: Vec<Track>) {
    let live: Vec<Track> = stations.into_iter().filter(Track::is_live).collect();
    if live.is_empty() {
        into.append(&status(
            "starred-symbolic",
            "No favourites yet",
            "Star channels and stations (here, on the web or on your phone) to keep them here.",
        ));
        return;
    }
    let grid = card_grid();
    for station in live {
        let under = if station.is_channel() { "SiriusXM" } else { "Radio" };
        let t = station.clone();
        grid.append(&card(
            station.artwork_url.as_deref(),
            150,
            false,
            &station.title,
            under,
            move || ctx().player.play_tracks(vec![t.clone()], 0),
        ));
    }
    into.append(&grid);
}

fn siriusxm_channels(into: &gtk::Box, sources: Vec<SourceView>, starred: &Starred) {
    let Some(source) = sources.into_iter().next() else {
        into.append(&status(
            "network-wireless-signal-excellent-symbolic",
            "No SiriusXM",
            "Add your SiriusXM account to your server's sources to listen here.",
        ));
        return;
    };
    let filter = gtk::SearchEntry::builder()
        .placeholder_text("Find a channel")
        .margin_bottom(6)
        .build();
    filter.add_css_class("search-field");
    into.append(&filter);
    let list = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).build();
    into.append(&list);
    let Some(api) = ctx().api() else { return };
    let channels: Rc<RefCell<Vec<ChannelView>>> = Rc::new(RefCell::new(vec![]));
    let show = {
        let (list, channels, starred) = (list.clone(), channels.clone(), starred.clone());
        move |filter: &str| {
            clear(&list);
            let words: Vec<String> = filter.to_lowercase().split_whitespace().map(String::from).collect();
            let mut by_category: BTreeMap<String, Vec<ChannelView>> = BTreeMap::new();
            for channel in channels.borrow().iter() {
                let text = format!(
                    "{} {} {}",
                    channel.track.title,
                    channel.category,
                    channel.number.map(|n| n.to_string()).unwrap_or_default()
                )
                .to_lowercase();
                if words.iter().all(|w| text.contains(w)) {
                    by_category
                        .entry(if channel.category.is_empty() {
                            "Channels".into()
                        } else {
                            channel.category.clone()
                        })
                        .or_default()
                        .push(channel.clone());
                }
            }
            if by_category.is_empty() {
                list.append(&status("edit-find-symbolic", "No channels match", ""));
            }
            for (category, channels) in by_category {
                list.append(&heading(&category));
                let group = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
                group.add_css_class("boxed-list");
                for channel in channels {
                    let title = match channel.number {
                        Some(n) => format!("{n} · {}", channel.track.title),
                        None => channel.track.title.clone(),
                    };
                    let row = plain_row(title.as_str(), channel.track.artist.as_str());
                    row.set_activatable(!channel.locked);
                    row.set_subtitle_lines(1);
                    row.add_prefix(&artwork(channel.track.artwork_url.as_deref(), 44, false));
                    if channel.locked {
                        row.add_suffix(&gtk::Image::from_icon_name("system-lock-screen-symbolic"));
                        row.set_tooltip_text(Some("Not in your SiriusXM subscription"));
                    } else {
                        row.add_suffix(&favourite_button(&channel.track, &starred));
                    }
                    let track = channel.track.clone();
                    row.connect_activated(move |_| ctx().player.play_tracks(vec![track.clone()], 0));
                    group.append(&row);
                }
                list.append(&group);
            }
        }
    };
    list.append(&crate::ui::widgets::loading());
    let show_now = show.clone();
    let id = source.id.clone();
    glib::spawn_future_local(async move {
        match bg(async move { api.channels(&id).await }).await {
            Ok(found) => {
                channels.replace(found);
                show_now("");
            }
            Err(e) => ctx().failed(&e),
        }
    });
    filter.connect_search_changed(move |entry| show(&entry.text()));
}

/// A star: keeps the channel or station among your favourites (on every device).
fn favourite_button(track: &Track, starred: &Starred) -> gtk::ToggleButton {
    let on = starred.borrow().contains(&favourite_key(track));
    let button = gtk::ToggleButton::builder()
        .icon_name(if on { "starred-symbolic" } else { "non-starred-symbolic" })
        .active(on)
        .valign(gtk::Align::Center)
        .tooltip_text("Favourite")
        .build();
    button.add_css_class("flat");
    let (track, starred) = (track.clone(), starred.clone());
    button.connect_toggled(move |b| {
        let on = b.is_active();
        b.set_icon_name(if on { "starred-symbolic" } else { "non-starred-symbolic" });
        if on {
            starred.borrow_mut().insert(favourite_key(&track));
        } else {
            starred.borrow_mut().remove(&favourite_key(&track));
        }
        let Some(api) = ctx().api() else { return };
        let t = track.clone();
        glib::spawn_future_local(async move {
            match bg(async move { api.set_live_favorite(&t, on).await }).await {
                Ok(_) => crate::ui::window::refresh_sidebar(),
                Err(e) => ctx().failed(&e),
            }
        });
    });
    button
}

fn local_radio(into: &gtk::Box, sources: Vec<SourceView>, starred: &Starred) {
    let Some(source) = sources.into_iter().next() else {
        into.append(&status(
            "audio-speakers-symbolic",
            "No radio",
            "Turn on Radio in your server's sources to find stations here.",
        ));
        return;
    };
    let entry = gtk::SearchEntry::builder()
        .placeholder_text("Find a station by name, city or genre")
        .search_delay(400)
        .margin_bottom(6)
        .build();
    entry.add_css_class("search-field");
    into.append(&entry);
    let results = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    results.append(&status(
        "audio-speakers-symbolic",
        "Local and internet radio",
        "Search for a station to play it.",
    ));
    into.append(&results);
    let id = source.id.clone();
    let starred = starred.clone();
    entry.connect_search_changed(move |entry| {
        let query = entry.text().trim().to_string();
        clear(&results);
        if query.len() < 2 {
            return;
        }
        let Some(api) = ctx().api() else { return };
        let (id, starred) = (id.clone(), starred.clone());
        load_into(
            &results,
            async move { bg(async move { api.radio_search(&id, &query).await }).await },
            move |into, stations| {
                if stations.is_empty() {
                    into.append(&status(
                        "edit-find-symbolic",
                        "No stations found",
                        "Try another name, a city or a genre.",
                    ));
                    return;
                }
                let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
                list.add_css_class("boxed-list");
                for station in stations {
                    let row = plain_row(station.title.as_str(), station.artist.as_str());
                    row.set_activatable(true);
                    row.set_subtitle_lines(1);
                    row.add_prefix(&artwork(station.artwork_url.as_deref(), 44, false));
                    row.add_suffix(&favourite_button(&station, &starred));
                    let t = station.clone();
                    row.connect_activated(move |_| ctx().player.play_tracks(vec![t.clone()], 0));
                    list.append(&row);
                }
                into.append(&list);
            },
        );
    });
}

// ---- History ----

pub fn history_page() -> adw::NavigationPage {
    let content = page_box();
    content.append(&page_title("History", "What you've listened to, on every device."));
    let holder = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    content.append(&holder);
    let (page, _) = nav_page("History", &scrolled(&content));
    let Some(api) = ctx().api() else { return page };
    load_into(
        &holder,
        async move { bg(async move { api.history().await }).await },
        |into, listens| {
            if listens.is_empty() {
                into.append(&status(
                    "document-open-recent-symbolic",
                    "Nothing yet",
                    "Songs show up here once you've listened to a good part of them, on any device.",
                ));
                return;
            }
            let mut days: Vec<(String, Vec<Track>)> = vec![];
            for listen in listens {
                let day = glib::DateTime::from_unix_local(listen.played_at / 1000)
                    .ok()
                    .and_then(|d| d.format("%A, %B %-e").ok())
                    .map(|s| s.to_string())
                    .unwrap_or_default();
                if days.last().map(|(d, _)| d != &day).unwrap_or(true) {
                    days.push((day, vec![]));
                }
                days.last_mut().expect("a day").1.push(listen.track);
            }
            for (day, tracks) in days {
                into.append(&heading(&day));
                into.append(&track_list(Rc::new(tracks), false));
            }
        },
    );
    page
}
