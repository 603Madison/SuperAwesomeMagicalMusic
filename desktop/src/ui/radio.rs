//! Radio, as the web player has it (web/src/pages/Radio.tsx): your own stations, your
//! favourites, then SiriusXM's channels, Pandora artist stations (started from an artist, and
//! kept as favourites) and local radio (popular where you are, near you, or found by name, your
//! FM tuner's stations among them).

use std::cell::RefCell;
use std::collections::BTreeMap;
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

use crate::api::{ArtistView, ChannelView, PersonalStation, SourceView, Track};
use crate::rt::bg;
use crate::ui::ctx;
use crate::ui::forms::{clear, note, run};
use crate::ui::widgets::{
    artwork, card_grid, card_with, heading, loading, nav_page, page_box, page_title, plain_row, scrolled, status, track_key,
};

thread_local! {
    /// Your SiriusXM accounts (for starting Pandora stations from a song's menu).
    static SIRIUSXM: RefCell<Vec<SourceView>> = const { RefCell::new(Vec::new()) };
}

fn usable(sources: Vec<SourceView>) -> Vec<SourceView> {
    sources.into_iter().filter(|s| s.enabled && !s.hidden).collect()
}

/// Notes which SiriusXM accounts can start stations (once signed in).
pub fn remember_sources() {
    let Some(api) = ctx().api() else { return };
    run(async move { api.sources().await }, |result| {
        if let Ok(sources) = result {
            SIRIUSXM.with(|s| s.replace(usable(sources).into_iter().filter(|s| s.kind == "SIRIUSXM").collect()));
        }
    });
}

/// Whether there's a SiriusXM account to start Pandora artist stations with.
pub fn can_start_stations() -> bool {
    SIRIUSXM.with(|s| !s.borrow().is_empty())
}

/// Starts a Pandora station from [artist] (as the Radio page does, from the artist SiriusXM finds).
pub fn start_artist_station(artist: &str) {
    let Some(source) = SIRIUSXM.with(|s| s.borrow().first().map(|s| s.id.clone())) else {
        return;
    };
    let Some(api) = ctx().api() else { return };
    let name = artist.to_string();
    ctx().toast(&format!("Starting a station from {name}…"));
    run(
        async move {
            let artists = api.sxm_artists(&source, &name).await?;
            let found = artists
                .iter()
                .find(|a| a.name.eq_ignore_ascii_case(&name))
                .or(artists.first())
                .cloned()
                .ok_or_else(|| anyhow::anyhow!("SiriusXM has no station for {name}"))?;
            api.artist_station(&source, &found).await
        },
        |result| match result.and_then(|s| {
            if s.tracks.is_empty() {
                Err(anyhow::anyhow!("SiriusXM sent nothing for this station"))
            } else {
                Ok(s)
            }
        }) {
            Ok(station) => ctx().player.play_tracks(station.tracks, 0),
            Err(e) => ctx().failed(&e),
        },
    );
}

/// Plays a favourite: tunes a channel or station in, or starts a Pandora station.
pub fn play_favourite(track: &Track) {
    if !track.is_artist_station() {
        ctx().player.play_tracks(vec![track.clone()], 0);
        return;
    }
    let (Some(api), Some(via)) = (ctx().api(), track.via.clone()) else {
        return;
    };
    let id = track.source_id[crate::api::ARTIST_STATION.len()..].to_string();
    let name = track.title.clone();
    run(async move { api.station_tracks(&via, &id, &name).await }, |result| match result {
        Ok(station) if !station.tracks.is_empty() => ctx().player.play_tracks(station.tracks, 0),
        Ok(_) => ctx().toast("SiriusXM sent nothing for this station"),
        Err(e) => ctx().failed(&e),
    });
}

/// The page's favourites (one list, kept up to date), and the stars that show them.
struct Radio {
    favourites: RefCell<Vec<Track>>,
    /// The Favourites section, shown again when they change.
    section: gtk::Box,
    stars: RefCell<Vec<(glib::WeakRef<gtk::Button>, String)>>,
}

impl Radio {
    fn is_favourite(&self, key: &str) -> bool {
        self.favourites.borrow().iter().any(|f| track_key(f) == key)
    }

    fn set(self: &Rc<Self>, track: &Track, on: bool) {
        let Some(api) = ctx().api() else { return };
        let (radio, t, sent) = (self.clone(), track.clone(), track.clone());
        run(async move { api.set_live_favorite(&sent, on).await }, move |result| match result {
            Ok(list) => {
                ctx().toast(&if on {
                    format!("{} is in your favourites, at the top of this page", t.title)
                } else {
                    format!("Removed {} from your favourites", t.title)
                });
                radio.changed(list);
                crate::ui::window::refresh_sidebar();
            }
            Err(e) => ctx().failed(&e),
        });
    }

    fn changed(self: &Rc<Self>, list: Vec<Track>) {
        self.favourites.replace(list.into_iter().filter(Track::is_live).collect());
        self.stars.borrow_mut().retain(|(star, key)| {
            let Some(star) = star.upgrade() else { return false };
            show_star(&star, self.is_favourite(key));
            true
        });
        show_favourites(self);
    }

    /// A star that keeps [track] among your favourites (on every device).
    fn star(self: &Rc<Self>, track: &Track) -> gtk::Button {
        let key = track_key(track);
        let button = gtk::Button::builder().valign(gtk::Align::Center).build();
        button.add_css_class("flat");
        button.add_css_class("circular");
        show_star(&button, self.is_favourite(&key));
        self.stars.borrow_mut().push((button.downgrade(), key.clone()));
        let (radio, track) = (self.clone(), track.clone());
        button.connect_clicked(move |_| radio.set(&track, !radio.is_favourite(&key)));
        button
    }
}

fn show_star(button: &gtk::Button, on: bool) {
    button.set_icon_name(if on { "starred-symbolic" } else { "non-starred-symbolic" });
    button.set_tooltip_text(Some(if on { "Favourite (click to remove)" } else { "Add to favourites" }));
}

/// A card with a star over its corner.
/// A cover with a star over its corner.
fn starred_cover(cover: &gtk::Widget, star: &gtk::Button) -> gtk::Overlay {
    star.add_css_class("card-star");
    star.set_halign(gtk::Align::End);
    star.set_valign(gtk::Align::Start);
    let overlay = gtk::Overlay::builder().child(cover).halign(gtk::Align::Center).build();
    overlay.add_overlay(star);
    overlay
}

fn starred(card: &gtk::Button, star: &gtk::Button) -> gtk::Overlay {
    star.add_css_class("card-star");
    star.set_halign(gtk::Align::End);
    star.set_valign(gtk::Align::Start);
    let overlay = gtk::Overlay::builder().child(card).build();
    overlay.add_overlay(star);
    overlay
}

/// Toggle buttons that act as one switch, as a pill (the web's tabs).
fn segmented(choices: &[(&str, &str)], stack: &gtk::Stack, first: &str) -> gtk::Box {
    let bar = gtk::Box::builder().halign(gtk::Align::Start).build();
    bar.add_css_class("segmented");
    let mut group: Option<gtk::ToggleButton> = None;
    for (name, label) in choices {
        let button = gtk::ToggleButton::builder().label(*label).build();
        match &group {
            Some(g) => button.set_group(Some(g)),
            None => group = Some(button.clone()),
        }
        button.set_active(*name == first);
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
    crate::ui::style::ensure();
    let content = page_box();
    content.append(&page_title(
        "Radio",
        "SiriusXM, local and internet radio, and your favourite stations.",
    ));
    let made_for_you = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).build();
    content.append(&made_for_you);
    let section = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).build();
    content.append(&section);
    let tabs = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(6)
        .margin_top(18)
        .build();
    content.append(&tabs);
    let (page, _) = nav_page("Radio", &scrolled(&content));
    let Some(api) = ctx().api() else { return page };
    let radio = Rc::new(Radio {
        favourites: RefCell::default(),
        section,
        stars: RefCell::default(),
    });
    tabs.append(&loading());
    let (r, holder) = (radio.clone(), made_for_you.clone());
    run(
        async move {
            let (favourites, sources, personal) = tokio::join!(api.live_favorites(), api.sources(), api.personal_stations());
            Ok((favourites?, sources?, personal.unwrap_or_default()))
        },
        move |result| {
            clear(&tabs);
            let (favourites, sources, personal) = match result {
                Ok(found) => found,
                Err(e) => {
                    tabs.append(&status("dialog-warning-symbolic", "Couldn't load this", &e.to_string()));
                    return ctx().failed(&e);
                }
            };
            show_made_for_you(&r, &holder, personal);
            r.changed(favourites);
            show_tabs(&r, &tabs, usable(sources));
        },
    );
    page
}

/// The tabs: SiriusXM, Pandora stations and Local radio.
fn show_tabs(radio: &Rc<Radio>, into: &gtk::Box, sources: Vec<SourceView>) {
    let siriusxm: Vec<SourceView> = sources.iter().filter(|s| s.kind == "SIRIUSXM").cloned().collect();
    SIRIUSXM.with(|s| s.replace(siriusxm.clone()));
    // Radio Browser's directory (with the person's tuner stations in it), or a tuner on its own.
    let local = sources
        .iter()
        .find(|s| s.kind == "RADIO")
        .or_else(|| sources.iter().find(|s| s.kind == "TUNER"))
        .cloned();
    let tuner = sources.iter().any(|s| s.kind == "TUNER");
    let stack = gtk::Stack::builder()
        .transition_type(gtk::StackTransitionType::Crossfade)
        .vhomogeneous(false)
        .build();
    let channels = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    let stations = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    let local_box = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    stack.add_named(&channels, Some("sxm"));
    stack.add_named(&stations, Some("pandora"));
    stack.add_named(&local_box, Some("radio"));
    let first = if siriusxm.is_empty() { "radio" } else { "sxm" };
    let bar = gtk::Box::builder().spacing(12).margin_bottom(10).build();
    bar.append(&segmented(
        &[("sxm", "SiriusXM"), ("pandora", "Pandora stations"), ("radio", "Local radio")],
        &stack,
        first,
    ));
    stack.set_visible_child_name(first);
    into.append(&bar);
    into.append(&stack);

    let show_sxm = {
        let (radio, channels, stations) = (radio.clone(), channels.clone(), stations.clone());
        move |source: Option<SourceView>| {
            clear(&channels);
            clear(&stations);
            match source {
                Some(source) => {
                    siriusxm_channels(&radio, &channels, &source);
                    pandora_stations(&radio, &stations, &source);
                }
                None => {
                    for holder in [&channels, &stations] {
                        holder.append(&status(
                            "network-wireless-signal-excellent-symbolic",
                            "No SiriusXM account",
                            "Add your SiriusXM streaming account in Preferences › Sources to hear its channels and Pandora artist stations here.",
                        ));
                    }
                }
            }
        }
    };
    show_sxm(siriusxm.first().cloned());
    // More than one SiriusXM account: which one.
    if siriusxm.len() > 1 {
        let names: Vec<&str> = siriusxm.iter().map(|s| s.name.as_str()).collect();
        let pick = gtk::DropDown::from_strings(&names);
        pick.set_valign(gtk::Align::Center);
        pick.set_tooltip_text(Some("SiriusXM account"));
        pick.connect_selected_notify(move |d| show_sxm(siriusxm.get(d.selected() as usize).cloned()));
        bar.append(&pick);
    }
    match local {
        Some(source) => local_radio(radio, &local_box, &source, tuner),
        None => local_box.append(&status(
            "audio-speakers-symbolic",
            "Radio is off",
            "An admin can add the Radio source in Admin › Shared sources.",
        )),
    }
}

/// The person's own stations (their Radio, Rewind and Discovery), made by the server from what
/// they play and like: one click to tune in.
fn show_made_for_you(radio: &Rc<Radio>, into: &gtk::Box, stations: Vec<PersonalStation>) {
    if stations.is_empty() {
        return;
    }
    into.append(&heading("Made for you"));
    let row = gtk::FlowBox::builder()
        .selection_mode(gtk::SelectionMode::None)
        .homogeneous(true)
        .min_children_per_line(1)
        .max_children_per_line(3)
        .column_spacing(12)
        .row_spacing(12)
        .build();
    for station in stations {
        let content = gtk::Box::builder().spacing(14).build();
        content.append(&artwork(station.track.artwork_url.as_deref(), 72, false));
        let text = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(3)
            .valign(gtk::Align::Center)
            .hexpand(true)
            .build();
        let label = |value: &str, class: &str, lines: i32| {
            let l = gtk::Label::builder()
                .label(value)
                .xalign(0.0)
                .wrap(lines > 1)
                .lines(lines)
                .max_width_chars(24)
                .ellipsize(gtk::pango::EllipsizeMode::End)
                .build();
            l.add_css_class(class);
            l
        };
        text.append(&label(&station.name, "personal-name", 1));
        let tagline = if station.ready {
            station.tagline.clone()
        } else {
            "Play some music, and it fills up with what you love".into()
        };
        text.append(&label(&tagline, "personal-tagline", 2));
        if let Some(on) = &station.on_air {
            text.append(&label(&format!("On air: {} · {}", on.title, on.artist), "personal-on", 1));
        }
        content.append(&text);
        let card = gtk::Button::builder()
            .child(&content)
            .sensitive(station.ready)
            .tooltip_text(if station.ready {
                format!("Tune in to {}", station.name)
            } else {
                "Listen to some music first".into()
            })
            .build();
        card.add_css_class("personal-card");
        card.add_css_class(&station.kind);
        let track = station.track.clone();
        card.connect_clicked(move |_| ctx().player.play_tracks(vec![track.clone()], 0));
        if station.ready {
            row.append(&starred(&card, &radio.star(&station.track)));
        } else {
            row.append(&card);
        }
    }
    into.append(&row);
}

/// Your favourite channels and stations, from wherever they are: one click to play.
fn show_favourites(radio: &Rc<Radio>) {
    let into = &radio.section;
    clear(into);
    let favourites = radio.favourites.borrow().clone();
    if favourites.is_empty() {
        return;
    }
    into.append(&heading("Favourites"));
    let grid = card_grid();
    for station in favourites {
        let t = station.clone();
        let cover = starred_cover(&artwork(station.artwork_url.as_deref(), 136, false), &radio.star(&station));
        let card = card_with(cover.upcast_ref(), false, &station.title, &station.artist, move || {
            play_favourite(&t)
        });
        card.set_tooltip_text(Some(&format!("Play {}", station.title)));
        grid.append(&card);
    }
    into.append(&grid);
}

fn siriusxm_channels(radio: &Rc<Radio>, into: &gtk::Box, source: &SourceView) {
    let row = gtk::Box::builder().spacing(10).margin_bottom(6).build();
    let filter = gtk::SearchEntry::builder()
        .placeholder_text("Filter channels: name, number or genre")
        .hexpand(true)
        .build();
    filter.add_css_class("search-field");
    row.append(&filter);
    let locked_button = gtk::ToggleButton::builder().visible(false).valign(gtk::Align::Center).build();
    locked_button.add_css_class("flat");
    row.append(&locked_button);
    into.append(&row);
    let list = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).build();
    into.append(&list);
    let Some(api) = ctx().api() else { return };
    let channels: Rc<RefCell<Vec<ChannelView>>> = Rc::default();
    let show = {
        let (list, channels, radio, filter, locked_button) =
            (list.clone(), channels.clone(), radio.clone(), filter.clone(), locked_button.clone());
        Rc::new(move || {
            clear(&list);
            let words: Vec<String> = filter.text().to_lowercase().split_whitespace().map(String::from).collect();
            let show_locked = locked_button.is_active();
            let locked = channels.borrow().iter().filter(|c| c.locked).count();
            locked_button.set_visible(locked > 0);
            locked_button.set_label(&format!("{} {locked} not in your plan", if show_locked { "Hide" } else { "Show" }));
            let mut by_category: BTreeMap<String, Vec<ChannelView>> = BTreeMap::new();
            for channel in channels.borrow().iter() {
                if channel.locked && !show_locked {
                    continue;
                }
                let text = format!(
                    "{} {} {}",
                    channel.track.title,
                    channel.number.map(|n| n.to_string()).unwrap_or_default(),
                    channel.category
                )
                .to_lowercase();
                if words.iter().all(|w| text.contains(w)) {
                    let category = if channel.category.is_empty() {
                        "Other".into()
                    } else {
                        channel.category.clone()
                    };
                    by_category.entry(category).or_default().push(channel.clone());
                }
            }
            if by_category.is_empty() && !channels.borrow().is_empty() {
                list.append(&status("edit-find-symbolic", "No channels match", "Try another word."));
            }
            for (category, channels) in by_category {
                list.append(&heading(&category));
                let group = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
                group.add_css_class("boxed-list");
                for channel in channels {
                    let title = match channel.number {
                        Some(n) => format!("{} · Ch {n}", channel.track.title),
                        None => channel.track.title.clone(),
                    };
                    let row = plain_row(title.as_str(), channel.track.artist.as_str());
                    row.set_activatable(!channel.locked);
                    row.set_subtitle_lines(1);
                    row.add_prefix(&artwork(channel.track.artwork_url.as_deref(), 44, false));
                    if channel.locked {
                        row.add_suffix(&gtk::Image::from_icon_name("system-lock-screen-symbolic"));
                        row.set_tooltip_text(Some("Not in your SiriusXM plan"));
                        row.add_css_class("is-off");
                    } else {
                        row.add_suffix(&radio.star(&channel.track));
                    }
                    let track = channel.track.clone();
                    row.connect_activated(move |_| ctx().player.play_tracks(vec![track.clone()], 0));
                    group.append(&row);
                }
                list.append(&group);
            }
        })
    };
    list.append(&loading());
    list.append(&note("Signing in to SiriusXM…"));
    let (show_now, id, holder) = (show.clone(), source.id.clone(), list.clone());
    run(async move { api.channels(&id).await }, move |result| match result {
        Ok(found) => {
            channels.replace(found);
            show_now();
        }
        Err(e) => {
            clear(&holder);
            holder.append(&status("dialog-warning-symbolic", "Couldn't load the channels", &e.to_string()));
        }
    });
    let s = show.clone();
    filter.connect_search_changed(move |_| s());
    locked_button.connect_toggled(move |_| show());
}

/// Pandora artist stations, through SiriusXM: find an artist, start their station, or keep it.
fn pandora_stations(radio: &Rc<Radio>, into: &gtk::Box, source: &SourceView) {
    into.append(&note(
        "Music by and like one artist, through your SiriusXM account. Thumbs up and down shape what plays.",
    ));
    let entry = gtk::SearchEntry::builder()
        .placeholder_text("An artist to start a station from")
        .margin_top(6)
        .margin_bottom(6)
        .build();
    entry.add_css_class("search-field");
    into.append(&entry);
    let results = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    into.append(&results);
    let (radio, source) = (radio.clone(), source.clone());
    entry.connect_activate(move |entry| {
        let query = entry.text().trim().to_string();
        if query.is_empty() {
            return;
        }
        let Some(api) = ctx().api() else { return };
        clear(&results);
        results.append(&loading());
        let (radio, source, results) = (radio.clone(), source.clone(), results.clone());
        let id = source.id.clone();
        run(async move { api.sxm_artists(&id, &query).await }, move |result| {
            clear(&results);
            let artists = match result {
                Ok(artists) => artists,
                Err(e) => return ctx().failed(&e),
            };
            if artists.is_empty() {
                results.append(&status("edit-find-symbolic", "No stations found", "Try the artist's full name."));
                return;
            }
            let grid = card_grid();
            for artist in artists {
                let sub = if artist.station_id.is_some() { "Pandora station" } else { "Artist" };
                let (a, src) = (artist.clone(), source.id.clone());
                let cover = starred_cover(
                    &artwork(artist.artwork_url.as_deref(), 160, false),
                    &station_star(&radio, &source, &artist),
                );
                grid.append(&card_with(cover.upcast_ref(), false, &artist.name, sub, move || {
                    start_station(&src, &a)
                }));
            }
            results.append(&grid);
        });
    });
}

fn start_station(source: &str, artist: &ArtistView) {
    let Some(api) = ctx().api() else { return };
    let (source, artist) = (source.to_string(), artist.clone());
    ctx().toast(&format!("Starting {}'s station…", artist.name));
    run(async move { api.artist_station(&source, &artist).await }, |result| match result {
        Ok(station) if !station.tracks.is_empty() => ctx().player.play_tracks(station.tracks, 0),
        Ok(_) => ctx().toast("SiriusXM sent nothing for this station"),
        Err(e) => ctx().failed(&e),
    });
}

/// The favourite that is [artist]'s station, if it's one.
fn saved_station(radio: &Radio, source: &SourceView, artist: &ArtistView) -> Option<Track> {
    let id = artist.station_id.as_deref().map(|id| format!(":{id}"));
    radio
        .favourites
        .borrow()
        .iter()
        .find(|f| {
            f.is_artist_station()
                && f.via.as_deref() == Some(source.id.as_str())
                && (id.as_deref().is_some_and(|id| f.source_id.ends_with(id)) || f.title == artist.name)
        })
        .cloned()
}

/// A star on an artist's Pandora station: the station is made (or found) when it's starred.
fn station_star(radio: &Rc<Radio>, source: &SourceView, artist: &ArtistView) -> gtk::Button {
    let button = gtk::Button::builder().valign(gtk::Align::Center).build();
    show_star(&button, saved_station(radio, source, artist).is_some());
    let (radio, source, artist) = (radio.clone(), source.clone(), artist.clone());
    button.connect_clicked(move |button| {
        if let Some(saved) = saved_station(&radio, &source, &artist) {
            radio.set(&saved, false);
            show_star(button, false);
            return;
        }
        let Some(api) = ctx().api() else { return };
        button.set_sensitive(false);
        let (radio, src, artist, button) = (radio.clone(), source.id.clone(), artist.clone(), button.clone());
        run(async move { api.artist_station(&src, &artist).await }, move |result| {
            button.set_sensitive(true);
            match result {
                Ok(found) => {
                    radio.set(&found.station, true);
                    show_star(&button, true);
                }
                Err(e) => ctx().failed(&e),
            }
        });
    });
    button
}

/// The country this computer is set up for ("US" from en_US.UTF-8), for what's popular here.
pub fn country() -> Option<String> {
    ["LC_ALL", "LC_MESSAGES", "LANG"]
        .iter()
        .filter_map(|v| std::env::var(v).ok())
        .find(|v| !v.is_empty())
        .and_then(|locale| country_of(&locale))
}

fn country_of(locale: &str) -> Option<String> {
    let region = locale.split(['.', '@']).next()?.split(['_', '-']).nth(1)?;
    (region.len() == 2 && region.chars().all(|c| c.is_ascii_alphabetic())).then(|| region.to_ascii_uppercase())
}

/// A country's name, for the common ones (its code otherwise).
fn region_name(code: &str) -> String {
    const NAMES: [(&str, &str); 24] = [
        ("US", "the United States"),
        ("GB", "the United Kingdom"),
        ("CA", "Canada"),
        ("AU", "Australia"),
        ("NZ", "New Zealand"),
        ("IE", "Ireland"),
        ("DE", "Germany"),
        ("FR", "France"),
        ("ES", "Spain"),
        ("IT", "Italy"),
        ("NL", "the Netherlands"),
        ("BE", "Belgium"),
        ("CH", "Switzerland"),
        ("AT", "Austria"),
        ("SE", "Sweden"),
        ("NO", "Norway"),
        ("DK", "Denmark"),
        ("FI", "Finland"),
        ("PL", "Poland"),
        ("PT", "Portugal"),
        ("BR", "Brazil"),
        ("MX", "Mexico"),
        ("JP", "Japan"),
        ("IN", "India"),
    ];
    NAMES
        .iter()
        .find(|(c, _)| *c == code)
        .map(|(_, n)| n.to_string())
        .unwrap_or_else(|| code.to_string())
}

/// Finding stations (on the network runtime).
type Finding = std::pin::Pin<Box<dyn std::future::Future<Output = anyhow::Result<Vec<Track>>> + Send>>;

fn local_radio(radio: &Rc<Radio>, into: &gtk::Box, source: &SourceView, tuner: bool) {
    let row = gtk::Box::builder().spacing(10).margin_bottom(6).build();
    let entry = gtk::SearchEntry::builder()
        .placeholder_text("Station name, e.g. WFMU or jazz")
        .hexpand(true)
        .build();
    entry.add_css_class("search-field");
    row.append(&entry);
    let near = crate::ui::widgets::button(Some("find-location-symbolic"), "Near me", "tonal");
    near.set_valign(gtk::Align::Center);
    row.append(&near);
    into.append(&row);
    let title = heading("Popular");
    into.append(&title);
    let results = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    into.append(&results);
    if source.kind == "RADIO" {
        into.append(&note("Stations from Radio Browser (radio-browser.info), a free, open directory."));
    }
    if tuner {
        into.append(&note(
            "Stations marked FM or HD are on your tuner. One that's also online plays from the tuner while it comes in clearly, and from the internet while it's static.",
        ));
    }
    // Shows what [load] finds under [label].
    let show: Rc<dyn Fn(String, Finding)> = {
        let (radio, results, title) = (radio.clone(), results.clone(), title.clone());
        Rc::new(move |label, load| {
            clear(&results);
            results.append(&loading());
            let (radio, results, title) = (radio.clone(), results.clone(), title.clone());
            glib::spawn_future_local(async move {
                let found = bg(load).await;
                clear(&results);
                match found {
                    Ok(stations) => {
                        title.set_label(&label);
                        show_stations(&radio, &results, stations);
                    }
                    Err(e) => {
                        results.append(&status("dialog-warning-symbolic", "Couldn't find stations", &e.to_string()));
                        ctx().failed(&e);
                    }
                }
            });
        })
    };
    let id = source.id.clone();
    if let Some(api) = ctx().api() {
        let country = country();
        let label = match &country {
            Some(c) => format!("Popular in {}", region_name(c)),
            None => "Popular".into(),
        };
        let src = id.clone();
        show(label, Box::pin(async move { api.radio_find(&src, "", country.as_deref()).await }));
    }
    let (s, src) = (show.clone(), id.clone());
    entry.connect_activate(move |entry| {
        let query = entry.text().trim().to_string();
        let Some(api) = ctx().api().filter(|_| !query.is_empty()) else {
            return;
        };
        let src = src.clone();
        s(
            format!("“{query}”"),
            Box::pin(async move { api.radio_find(&src, &query, None).await }),
        );
    });
    near.connect_clicked(move |button| {
        button.set_sensitive(false);
        let (show, id, button) = (show.clone(), id.clone(), button.clone());
        glib::spawn_future_local(async move {
            let place = locate().await;
            button.set_sensitive(true);
            match place {
                Ok((lat, lon)) => {
                    let Some(api) = ctx().api() else { return };
                    show("Near you".into(), Box::pin(async move { api.radio_nearby(&id, lat, lon).await }));
                }
                Err(e) => ctx().toast(&e.to_string()),
            }
        });
    });
}

fn show_stations(radio: &Rc<Radio>, into: &gtk::Box, stations: Vec<Track>) {
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
        let sub = match station.album.as_deref().filter(|a| !a.is_empty()) {
            Some(album) => format!("{} · {album}", station.artist),
            None => station.artist.clone(),
        };
        let row = plain_row(station.title.as_str(), &sub);
        row.set_activatable(true);
        row.set_subtitle_lines(1);
        row.add_prefix(&artwork(station.artwork_url.as_deref(), 44, false));
        let tuner = station.tuner_badge();
        let badge = gtk::Label::builder()
            .label(tuner.clone().unwrap_or_else(|| {
                if station.source_id.starts_with("hls:") {
                    "HLS".into()
                } else {
                    "Live".into()
                }
            }))
            .valign(gtk::Align::Center)
            .build();
        badge.add_css_class("badge");
        if tuner.is_some() {
            badge.set_tooltip_text(Some("On your tuner"));
        }
        row.add_suffix(&badge);
        row.add_suffix(&radio.star(&station));
        let t = station.clone();
        row.connect_activated(move |_| ctx().player.play_tracks(vec![t.clone()], 0));
        list.append(&row);
    }
    into.append(&list);
}

/// Where this computer is, from the desktop's location service (GeoClue), as a browser asks.
async fn locate() -> anyhow::Result<(f64, f64)> {
    let refused = || anyhow::anyhow!("Location wasn't shared, so stations near you can't be found");
    let bus = gio::bus_get_future(gio::BusType::System).await.map_err(|_| refused())?;
    let proxy = |path: String, interface: &'static str| {
        gio::DBusProxy::new_future(
            &bus,
            gio::DBusProxyFlags::NONE,
            None,
            Some("org.freedesktop.GeoClue2"),
            &path,
            interface,
        )
    };
    let manager = proxy("/org/freedesktop/GeoClue2/Manager".into(), "org.freedesktop.GeoClue2.Manager")
        .await
        .map_err(|_| anyhow::anyhow!("This computer can't tell where it is (its location service, GeoClue, isn't running)"))?;
    let client = manager
        .call_future("GetClient", None, gio::DBusCallFlags::NONE, 5000)
        .await
        .map_err(|_| anyhow::anyhow!("This computer can't tell where it is (its location service, GeoClue, isn't running)"))?;
    let path = client.child_value(0).str().map(str::to_string).ok_or_else(refused)?;
    let set = |name: &str, value: glib::Variant| {
        bus.call_future(
            Some("org.freedesktop.GeoClue2"),
            &path,
            "org.freedesktop.DBus.Properties",
            "Set",
            Some(&("org.freedesktop.GeoClue2.Client", name, value).to_variant()),
            None,
            gio::DBusCallFlags::NONE,
            5000,
        )
    };
    set("DesktopId", crate::APP_ID.to_variant()).await.map_err(|_| refused())?;
    // A town's worth of accuracy is plenty for radio.
    set("RequestedAccuracyLevel", 4u32.to_variant()).await.map_err(|_| refused())?;
    let client = proxy(path.clone(), "org.freedesktop.GeoClue2.Client")
        .await
        .map_err(|_| refused())?;
    client
        .call_future("Start", None, gio::DBusCallFlags::NONE, 15_000)
        .await
        .map_err(|_| refused())?;
    let mut found = None;
    for _ in 0..30 {
        let location = client
            .cached_property("Location")
            .and_then(|v| v.str().map(str::to_string))
            .filter(|p| p != "/");
        if let Some(location) = location {
            let place = proxy(location, "org.freedesktop.GeoClue2.Location").await.map_err(|_| refused())?;
            let lat = place.cached_property("Latitude").and_then(|v| v.get::<f64>());
            let lon = place.cached_property("Longitude").and_then(|v| v.get::<f64>());
            found = lat.zip(lon);
            break;
        }
        glib::timeout_future(std::time::Duration::from_millis(500)).await;
    }
    let _ = client.call_future("Stop", None, gio::DBusCallFlags::NONE, 5000).await;
    found.ok_or_else(refused)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_country_comes_from_the_locale() {
        assert_eq!(country_of("en_US.UTF-8").as_deref(), Some("US"));
        assert_eq!(country_of("de_DE@euro").as_deref(), Some("DE"));
        assert_eq!(country_of("pt-br").as_deref(), Some("BR"));
        assert_eq!(country_of("C.UTF-8"), None);
        assert_eq!(country_of("POSIX"), None);
        assert_eq!(region_name("GB"), "the United Kingdom");
        assert_eq!(region_name("ZZ"), "ZZ");
    }
}
