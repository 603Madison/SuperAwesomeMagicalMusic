//! The pages: Search, an artist, an album, Playlists and a playlist, Radio, and History.

use std::cell::{Cell, RefCell};
use std::collections::BTreeMap;
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::api::{AlbumSummary, ChannelView, SearchResults, SourceView, Track};
use crate::rt::bg;
use crate::ui::ctx;
use crate::ui::widgets::{
    artwork, card, card_grid, duration, heading, nav_page, page_box, plain_row, play_buttons, scrolled, status, track_list,
};
use crate::ui::window::load_into;

// ---- Search ----

pub fn search_page() -> adw::NavigationPage {
    let content = page_box();
    let results = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    content.append(&results);
    let start = status(
        "system-search-symbolic",
        "Search all your music",
        "Artists, albums and songs from your library and every service on your server.",
    );
    results.append(&start);

    let (page, header) = nav_page("Search", &scrolled(&content));
    let entry = gtk::SearchEntry::builder()
        .placeholder_text("Artists, albums, songs")
        .width_request(320)
        .search_delay(300)
        .build();
    header.set_title_widget(Some(&entry));
    let asked = Rc::new(Cell::new(0u64));
    entry.connect_search_changed(move |entry| {
        let query = entry.text().trim().to_string();
        let number = asked.get() + 1;
        asked.set(number);
        while let Some(child) = results.first_child() {
            results.remove(&child);
        }
        if query.is_empty() {
            results.append(&status(
                "system-search-symbolic",
                "Search all your music",
                "Artists, albums and songs from your library and every service on your server.",
            ));
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
            while let Some(child) = results.first_child() {
                results.remove(&child);
            }
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
    if let Some(top) = found.top {
        into.append(&heading("Top result"));
        into.append(&top_result(top, songs.clone()));
    }
    if !songs.is_empty() {
        into.append(&heading("Songs"));
        let first: Rc<Vec<Track>> = Rc::new(songs.iter().take(8).cloned().collect());
        into.append(&track_list(first, false));
    }
    if !found.artists.is_empty() {
        into.append(&heading("Artists"));
        let grid = card_grid();
        for artist in found.artists {
            let (id, name) = (artist.id.clone(), artist.name.clone());
            grid.append(&card(artist.artwork_url.as_deref(), 140, true, &artist.name, "Artist", move || {
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

fn top_result(top: crate::api::TopResult, songs: Rc<Vec<Track>>) -> gtk::Widget {
    let (title, label, art, round): (String, &str, Option<String>, bool) = match (&top.artist, &top.album, &top.track) {
        (Some(a), _, _) if top.kind == "artist" => (a.name.clone(), "Artist", a.artwork_url.clone(), true),
        (_, Some(a), _) if top.kind == "album" => (a.title.clone(), "Album", a.artwork_url.clone(), false),
        (_, _, Some(t)) => (t.title.clone(), "Song", t.artwork_url.clone(), false),
        _ => return gtk::Box::new(gtk::Orientation::Horizontal, 0).upcast(),
    };
    let row = gtk::Box::builder().spacing(18).build();
    row.append(&artwork(art.as_deref(), 112, round));
    let text = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(4)
        .valign(gtk::Align::Center)
        .build();
    let name = gtk::Label::builder().label(&title).xalign(0.0).wrap(true).build();
    name.add_css_class("hero-title");
    let kind = gtk::Label::builder()
        .label(match &top.track {
            Some(t) if top.kind == "song" => format!("Song · {}", t.artist),
            _ => label.to_string(),
        })
        .xalign(0.0)
        .build();
    kind.add_css_class("dim-label");
    text.append(&name);
    text.append(&kind);
    row.append(&text);
    let button = gtk::Button::builder().child(&row).build();
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
                let index = songs.iter().position(|s| s == t);
                match index {
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
        grid.append(&card(album.artwork_url.as_deref(), 150, false, &album.title, &under, move || {
            ctx().open_album(Some(&id), &title, &artist)
        }));
    }
    grid
}

/// A page's top: its cover, name, a line, and Play/Shuffle.
fn hero(art: Option<&str>, round: bool, title: &str, line: &str, tracks: Rc<Vec<Track>>) -> gtk::Box {
    let row = gtk::Box::builder().spacing(24).margin_bottom(6).build();
    row.append(&artwork(art, 180, round));
    let text = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(6)
        .valign(gtk::Align::End)
        .build();
    let name = gtk::Label::builder().label(title).xalign(0.0).wrap(true).build();
    name.add_css_class("hero-title");
    text.append(&name);
    if !line.is_empty() {
        let under = gtk::Label::builder().label(line).xalign(0.0).wrap(true).build();
        under.add_css_class("dim-label");
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
            into.append(&hero(
                found.artist.artwork_url.as_deref(),
                true,
                &found.artist.name,
                "Artist",
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
            line.push(format!("{} songs", tracks.len()));
            if length > 0 {
                line.push(duration(length));
            }
            into.append(&hero(
                found.album.artwork_url.as_deref(),
                false,
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
    let (page, _) = nav_page("Playlists", &scrolled(&content));
    let Some(api) = ctx().api() else { return page };
    load_into(
        &content,
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
            let list = gtk::ListBox::builder()
                .selection_mode(gtk::SelectionMode::None)
                .margin_top(6)
                .build();
            list.add_css_class("boxed-list");
            for playlist in playlists {
                let mut line = format!("{} songs", playlist.count);
                if playlist.duration_ms > 0 {
                    line.push_str(&format!(" · {}", duration(playlist.duration_ms)));
                }
                let row = plain_row(playlist.name.as_str(), line.as_str());
                row.set_activatable(true);
                row.add_prefix(&artwork(playlist.artwork.first().map(String::as_str), 56, false));
                row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
                let (id, name) = (playlist.id.clone(), playlist.name.clone());
                row.connect_activated(move |_| ctx().open_playlist(&id, &name));
                list.append(&row);
            }
            into.append(&list);
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
            let mut line = format!("{} songs", tracks.len());
            if length > 0 {
                line.push_str(&format!(" · {}", duration(length)));
            }
            if !playlist.summary.description.is_empty() {
                line = format!("{} — {line}", playlist.summary.description);
            }
            into.append(&hero(
                playlist.summary.artwork.first().map(String::as_str),
                false,
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
            // Marks it played (the sidebar's order elsewhere), quietly.
            if let Some(api) = ctx().api() {
                glib::spawn_future_local(async move {
                    let _ = bg(async move { api.playlist_played(&played_id).await }).await;
                });
            }
        },
    );
    page
}

// ---- Radio ----

pub fn radio_page() -> adw::NavigationPage {
    let stack = adw::ViewStack::new();
    let favourites = page_box();
    let siriusxm = page_box();
    let radio = page_box();
    stack.add_titled_with_icon(&scrolled(&favourites), Some("favourites"), "Favourites", "starred-symbolic");
    stack.add_titled_with_icon(
        &scrolled(&siriusxm),
        Some("siriusxm"),
        "SiriusXM",
        "network-wireless-signal-excellent-symbolic",
    );
    stack.add_titled_with_icon(&scrolled(&radio), Some("radio"), "Local radio", "audio-speakers-symbolic");
    let (page, header) = nav_page("Radio", &stack);
    let switcher = adw::ViewSwitcher::builder()
        .stack(&stack)
        .policy(adw::ViewSwitcherPolicy::Wide)
        .build();
    header.set_title_widget(Some(&switcher));

    let Some(api) = ctx().api() else { return page };
    let favourites_api = api.clone();
    load_into(
        &favourites,
        async move { bg(async move { favourites_api.live_favorites().await }).await },
        |into, stations| {
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
                    140,
                    false,
                    &station.title,
                    under,
                    move || ctx().player.play_tracks(vec![t.clone()], 0),
                ));
            }
            into.append(&grid);
        },
    );

    let sources_api = api.clone();
    let (siriusxm_holder, radio_holder) = (siriusxm.clone(), radio.clone());
    glib::spawn_future_local(async move {
        let sources: Vec<SourceView> = match bg(async move { sources_api.sources().await }).await {
            Ok(s) => s.into_iter().filter(|s| s.enabled && !s.hidden).collect(),
            Err(e) => return ctx().failed(&e),
        };
        let sxm: Vec<SourceView> = sources.iter().filter(|s| s.kind == "SIRIUSXM").cloned().collect();
        let stations: Vec<SourceView> = sources.iter().filter(|s| s.kind == "RADIO").cloned().collect();
        siriusxm_channels(&siriusxm_holder, sxm);
        local_radio(&radio_holder, stations);
    });
    page
}

fn siriusxm_channels(into: &gtk::Box, sources: Vec<SourceView>) {
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
    into.append(&filter);
    let list = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(18).build();
    into.append(&list);
    let Some(api) = ctx().api() else { return };
    let channels: Rc<RefCell<Vec<ChannelView>>> = Rc::new(RefCell::new(vec![]));
    let show = {
        let (list, channels) = (list.clone(), channels.clone());
        move |filter: &str| {
            while let Some(child) = list.first_child() {
                list.remove(&child);
            }
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
                let group = adw::PreferencesGroup::builder()
                    .title(glib::markup_escape_text(&category).as_str())
                    .build();
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
                        row.add_suffix(&favourite_button(&channel.track));
                    }
                    let track = channel.track.clone();
                    row.connect_activated(move |_| ctx().player.play_tracks(vec![track.clone()], 0));
                    group.add(&row);
                }
                list.append(&group);
            }
        }
    };
    let loading = crate::ui::widgets::loading();
    list.append(&loading);
    let show_now = show.clone();
    let id = source.id.clone();
    glib::spawn_future_local(async move {
        match bg(async move { api.channels(&id).await }).await {
            Ok(found) => {
                channels.replace(found);
                show_now("");
            }
            Err(e) => {
                ctx().failed(&e);
            }
        }
    });
    filter.connect_search_changed(move |entry| show(&entry.text()));
}

/// A star: keeps the channel or station among your favourites (on every device).
fn favourite_button(track: &Track) -> gtk::ToggleButton {
    let button = gtk::ToggleButton::builder()
        .icon_name("non-starred-symbolic")
        .valign(gtk::Align::Center)
        .tooltip_text("Favourite")
        .build();
    button.add_css_class("flat");
    let track = track.clone();
    button.connect_toggled(move |b| {
        b.set_icon_name(if b.is_active() {
            "starred-symbolic"
        } else {
            "non-starred-symbolic"
        });
        let Some(api) = ctx().api() else { return };
        let (t, on) = (track.clone(), b.is_active());
        glib::spawn_future_local(async move {
            if let Err(e) = bg(async move { api.set_live_favorite(&t, on).await }).await {
                ctx().failed(&e);
            }
        });
    });
    button
}

fn local_radio(into: &gtk::Box, sources: Vec<SourceView>) {
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
    into.append(&entry);
    let results = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    results.append(&status(
        "audio-speakers-symbolic",
        "Local and internet radio",
        "Search for a station to play it.",
    ));
    into.append(&results);
    let id = source.id.clone();
    entry.connect_search_changed(move |entry| {
        let query = entry.text().trim().to_string();
        while let Some(child) = results.first_child() {
            results.remove(&child);
        }
        if query.len() < 2 {
            return;
        }
        let Some(api) = ctx().api() else { return };
        let id = id.clone();
        load_into(
            &results,
            async move { bg(async move { api.radio_search(&id, &query).await }).await },
            |into, stations| {
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
                    row.add_suffix(&favourite_button(&station));
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
    let (page, _) = nav_page("History", &scrolled(&content));
    let Some(api) = ctx().api() else { return page };
    load_into(
        &content,
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
            let mut days: Vec<(String, Vec<Track>, Vec<Option<String>>)> = vec![];
            for listen in listens {
                let day = glib::DateTime::from_unix_local(listen.played_at / 1000)
                    .ok()
                    .and_then(|d| d.format("%A, %B %-e").ok())
                    .map(|s| s.to_string())
                    .unwrap_or_default();
                if days.last().map(|(d, _, _)| d != &day).unwrap_or(true) {
                    days.push((day, vec![], vec![]));
                }
                let last = days.last_mut().expect("a day");
                last.1.push(listen.track);
                last.2.push(listen.station);
            }
            for (day, tracks, _) in days {
                into.append(&heading(&day));
                into.append(&track_list(Rc::new(tracks), false));
            }
        },
    );
    page
}
