//! Wrapped: the year in music (web/src/pages/Wrapped.tsx), as a recap to look things up in (the
//! year's numbers, top songs, artists, albums and stations, when you listen and where it came
//! from) and a story to play through, a slide at a time. Earlier years are a choice away.

use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::time::Duration;

use adw::prelude::*;
use gtk::glib;

use crate::api::{Track, Wrapped, WrappedArtist, WrappedShare};
use crate::ui::ctx;
use crate::ui::forms::{clear, note, run};
use crate::ui::widgets::{
    artwork, button, card, card_grid, heading, loading, nav_page, page_box, page_title, scrolled, status, track_list,
};

/// 12345 as "12,345".
pub fn num(n: i64) -> String {
    let digits = n.unsigned_abs().to_string();
    let mut out = String::new();
    for (i, c) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(c);
    }
    if n < 0 { format!("-{out}") } else { out }
}

/// 0–23 as people say it.
pub fn hour_name(hour: usize) -> String {
    match hour {
        0 => "midnight".into(),
        12 => "noon".into(),
        h if h < 12 => format!("{h} am"),
        h => format!("{} pm", h - 12),
    }
}

fn short_hour(hour: usize) -> String {
    match hour {
        0 => "12a".into(),
        12 => "12p".into(),
        h if h < 12 => format!("{h}a"),
        h => format!("{}p", h - 12),
    }
}

const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const WEEKDAYS: [&str; 7] = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

fn source_name(name: &str) -> String {
    match name {
        "JELLYFIN" => "Jellyfin",
        "QOBUZ" => "Qobuz",
        "YOUTUBE_MUSIC" => "YouTube Music",
        "SIRIUSXM" => "SiriusXM",
        "RADIO" => "Radio",
        "SUBSONIC" => "Subsonic",
        other => other,
    }
    .to_string()
}

fn device_name(name: &str) -> String {
    match name {
        "web" => "Web player",
        "android" => "Phone app",
        "desktop" => "Desktop app",
        other => other,
    }
    .to_string()
}

/// Where the most is: the index of the largest value.
fn peak(values: &[i64]) -> usize {
    values
        .iter()
        .enumerate()
        .fold(0, |best, (i, v)| if *v > values[best] { i } else { best })
}

/// "2026-03-14" as "Saturday, March 14".
fn day_name(date: &str) -> String {
    let mut parts = date.split('-').filter_map(|p| p.parse::<i32>().ok());
    let (Some(y), Some(m), Some(d)) = (parts.next(), parts.next(), parts.next()) else {
        return date.to_string();
    };
    glib::DateTime::from_local(y, m, d, 12, 0, 0.0)
        .ok()
        .and_then(|t| t.format("%A, %B %-e").ok())
        .map(|s| s.to_string())
        .unwrap_or_else(|| date.to_string())
}

/// Days of [minutes], to one decimal place when there are few.
fn days_of(minutes: i64) -> String {
    let days = minutes as f64 / 1440.0;
    if days < 10.0 {
        let text = format!("{days:.1}");
        text.strip_suffix(".0").map(str::to_string).unwrap_or(text)
    } else {
        num(days.round() as i64)
    }
}

fn plural(n: i64, one: &str, many: &str) -> String {
    format!("{} {}", num(n), if n == 1 { one } else { many })
}

fn when_of(data: &Wrapped) -> String {
    format!("{}{}", data.year, if data.so_far { " so far" } else { "" })
}

fn save_top_songs(data: &Wrapped) {
    let Some(api) = ctx().api() else { return };
    let name = format!("Your Top Songs {}", data.year);
    let tracks: Vec<Track> = data.top_songs.iter().map(|s| s.track.clone()).collect();
    let count = tracks.len();
    run(
        {
            let name = name.clone();
            async move { api.create_playlist(&name, &tracks).await }
        },
        move |result| match result {
            Ok(_) => {
                ctx().toast(&format!("Saved \"{name}\" with {count} songs"));
                crate::ui::window::refresh_sidebar();
            }
            Err(e) => ctx().failed(&e),
        },
    );
}

pub fn wrapped_page() -> adw::NavigationPage {
    crate::ui::style::ensure();
    let content = page_box();
    let top = gtk::Box::builder().spacing(12).build();
    let title = page_title("Wrapped", "Your year in music, from everything you played here (live radio too).");
    title.set_hexpand(true);
    top.append(&title);
    let years = gtk::DropDown::builder()
        .valign(gtk::Align::Center)
        .visible(false)
        .tooltip_text("Year")
        .build();
    top.append(&years);
    content.append(&top);
    let holder = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    content.append(&holder);
    let (page, _) = nav_page("Wrapped", &scrolled(&content));
    // The years there are, as the drop-down lists them (changing it loads that year).
    let shown_years: Rc<RefCell<Vec<i32>>> = Rc::default();
    let quiet = Rc::new(Cell::new(false));
    let load: Rc<dyn Fn(Option<i32>)> = {
        let (holder, years, shown_years, quiet) = (holder.clone(), years.clone(), shown_years.clone(), quiet.clone());
        Rc::new(move |year| {
            let Some(api) = ctx().api() else { return };
            clear(&holder);
            holder.append(&loading());
            let (holder, years, shown_years, quiet) = (holder.clone(), years.clone(), shown_years.clone(), quiet.clone());
            run(async move { api.wrapped(year).await }, move |result| {
                clear(&holder);
                let data = match result {
                    Ok(data) => data,
                    Err(e) => {
                        holder.append(&status("dialog-warning-symbolic", "Couldn't load this", &e.to_string()));
                        return ctx().failed(&e);
                    }
                };
                if data.years.len() > 1 {
                    quiet.set(true);
                    let names: Vec<String> = data.years.iter().map(i32::to_string).collect();
                    let names: Vec<&str> = names.iter().map(String::as_str).collect();
                    years.set_model(Some(&gtk::StringList::new(&names)));
                    years.set_selected(data.years.iter().position(|y| *y == data.year).unwrap_or(0) as u32);
                    shown_years.replace(data.years.clone());
                    years.set_visible(true);
                    quiet.set(false);
                }
                show(&holder, Rc::new(data));
            });
        })
    };
    let l = load.clone();
    years.connect_selected_notify(move |d| {
        if quiet.get() {
            return;
        }
        let year = shown_years.borrow().get(d.selected() as usize).copied();
        l(year);
    });
    load(None);
    page
}

fn show(into: &gtk::Box, data: Rc<Wrapped>) {
    if data.listens == 0 {
        into.append(&status(
            "starred-symbolic",
            &format!("Nothing to wrap for {} yet", data.year),
            "Songs you listen to for a while, and songs heard on live radio, build up your year here.",
        ));
        return;
    }
    into.append(&hero(&data));

    let songs = column();
    songs.append(&heading("Top songs"));
    let top: Vec<Track> = data.top_songs.iter().take(10).map(|s| s.track.clone()).collect();
    songs.append(&track_list(Rc::new(top), true));

    let artists = column();
    artists.append(&heading("Top artists"));
    let ranks = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    ranks.add_css_class("track-list");
    for (i, artist) in data.top_artists.iter().enumerate() {
        ranks.append(&artist_rank(artist, i + 1));
    }
    let names: Vec<String> = data.top_artists.iter().map(|a| a.name.clone()).collect();
    ranks.connect_row_activated(move |_, row| {
        if let Some(name) = names.get(row.index() as usize) {
            ctx().open_artist(None, name);
        }
    });
    artists.append(&ranks);

    into.append(&two_columns(&songs, &artists));

    if !data.top_albums.is_empty() {
        into.append(&heading("Top albums"));
        let grid = card_grid();
        for album in &data.top_albums {
            let (title, artist) = (album.title.clone(), album.artist.clone());
            grid.append(&card(
                album.artwork_url.as_deref(),
                150,
                false,
                &album.title,
                &format!("{} · {} min", album.artist, num(album.minutes)),
                move || ctx().open_album(None, &title, &artist),
            ));
        }
        into.append(&grid);
    }

    let when = panel("When you listen");
    let mut line = format!("Mostly around {}", hour_name(peak(&data.hours)));
    if data.weekdays.iter().any(|m| *m > 0) {
        line.push_str(&format!(", and most on {}s", WEEKDAYS[peak(&data.weekdays).min(6)]));
    }
    when.append(&note(&format!("{line}.")));
    when.append(&bar_chart("Minutes by hour of the day", &data.hours, hour_name, |h| {
        if h % 6 == 0 { short_hour(h) } else { String::new() }
    }));
    when.append(&bar_chart(
        "Minutes by month",
        &data.months,
        |m| MONTHS.get(m).copied().unwrap_or_default().to_string(),
        |m| MONTHS.get(m).map(|n| n[..1].to_string()).unwrap_or_default(),
    ));

    let from = panel("Where it came from");
    from.append(&shares(&data.sources, source_name));
    from.append(&panel_sub("On"));
    from.append(&shares(&data.devices, device_name));
    if !data.top_stations.is_empty() {
        from.append(&panel_sub("Live radio"));
        from.append(&shares(&data.top_stations, str::to_string));
    }

    let panels = two_columns(&when, &from);
    panels.set_margin_top(18);
    into.append(&panels);

    into.append(&heading("Highlights"));
    let tiles = gtk::FlowBox::builder()
        .selection_mode(gtk::SelectionMode::None)
        .homogeneous(true)
        .min_children_per_line(1)
        .max_children_per_line(3)
        .column_spacing(14)
        .row_spacing(14)
        .build();
    if let Some(day) = &data.biggest_day {
        tiles.append(&tile(
            "Biggest day",
            &format!("{} min", num(day.minutes)),
            &format!("{}, {} listens", day_name(&day.date), day.listens),
        ));
    }
    tiles.append(&tile(
        "Longest streak",
        &plural(data.longest_streak, "day", "days"),
        "in a row with music",
    ));
    if let Some(repeat) = &data.on_repeat {
        tiles.append(&tile(
            "On repeat",
            &format!("{}×", repeat.plays),
            &format!("{} on {}", repeat.track.title, day_name(&repeat.date)),
        ));
    }
    if let Some(first) = &data.first_song {
        tiles.append(&tile("First song of the year", &first.track.title, &first.track.artist));
    }
    if data.new_artists > 0 {
        let find = data.discoveries.first().map(|a| a.name.as_str()).unwrap_or_default();
        tiles.append(&tile(
            "Discoveries",
            &format!("{} new artists", num(data.new_artists)),
            &format!("Favourite find: {find}"),
        ));
    }
    tiles.append(&tile(
        "Your listening personality",
        &data.personality.name,
        &data.personality.description,
    ));
    into.append(&tiles);
}

/// Two columns side by side, one over the other when the window is narrow (as the web's).
fn two_columns(left: &impl IsA<gtk::Widget>, right: &impl IsA<gtk::Widget>) -> gtk::Box {
    let row = gtk::Box::builder().spacing(28).homogeneous(true).build();
    row.append(left);
    row.append(right);
    let window = ctx().window.clone();
    let fit = {
        let row = row.downgrade();
        move |window: &adw::ApplicationWindow| {
            let Some(row) = row.upgrade() else { return };
            let width = if window.width() > 0 {
                window.width()
            } else {
                window.default_width()
            };
            // The sidebar takes its share of a wide window.
            let wide = width >= 1040;
            row.set_orientation(if wide {
                gtk::Orientation::Horizontal
            } else {
                gtk::Orientation::Vertical
            });
            row.set_homogeneous(wide);
        }
    };
    fit(&window);
    let later = {
        let fit = fit.clone();
        move |w: &adw::ApplicationWindow| {
            let (w, fit) = (w.clone(), fit.clone());
            // Once the window has its new size.
            glib::idle_add_local_once(move || fit(&w));
        }
    };
    let ids = [
        window.connect_default_width_notify(later.clone()),
        window.connect_maximized_notify(later.clone()),
        window.connect_fullscreened_notify(later),
    ];
    let ids = std::cell::Cell::new(Some(ids));
    row.connect_destroy(move |_| {
        for id in ids.take().into_iter().flatten() {
            window.disconnect(id);
        }
    });
    row
}

fn column() -> gtk::Box {
    gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .valign(gtk::Align::Start)
        .build()
}

/// The year's numbers over the theme's gradient, with the story and saving the top songs.
fn hero(data: &Rc<Wrapped>) -> gtk::Box {
    let hero = gtk::Box::builder().spacing(20).margin_top(6).build();
    hero.add_css_class("wrapped-hero");
    let text = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(6)
        .hexpand(true)
        .valign(gtk::Align::End)
        .build();
    let eyebrow = gtk::Label::builder().label(when_of(data).to_uppercase()).xalign(0.0).build();
    eyebrow.add_css_class("wrapped-eyebrow");
    text.append(&eyebrow);
    let big = gtk::Label::builder()
        .label(format!("{} minutes of music", num(data.minutes)))
        .xalign(0.0)
        .wrap(true)
        .build();
    big.add_css_class("wrapped-big");
    text.append(&big);
    let line = gtk::Label::builder()
        .label(format!(
            "{} songs · {} artists · {} days with music",
            num(data.songs),
            num(data.artists),
            num(data.days)
        ))
        .xalign(0.0)
        .wrap(true)
        .build();
    line.add_css_class("wrapped-line");
    text.append(&line);
    if !data.personality.name.is_empty() {
        let chip = gtk::Box::builder().spacing(6).halign(gtk::Align::Start).margin_top(6).build();
        chip.add_css_class("wrapped-chip");
        chip.append(&gtk::Image::from_icon_name("starred-symbolic"));
        chip.append(&gtk::Label::new(Some(&data.personality.name)));
        text.append(&chip);
    }
    hero.append(&text);
    let actions = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(10)
        .valign(gtk::Align::End)
        .build();
    let story = button(Some("media-playback-start-symbolic"), "Play your story", "plain");
    story.remove_css_class("tonal");
    story.add_css_class("light");
    let d = data.clone();
    story.connect_clicked(move |_| play_story(d.clone()));
    actions.append(&story);
    if !data.top_songs.is_empty() {
        let save = button(Some("list-add-symbolic"), "Save top songs as a playlist", "plain");
        save.remove_css_class("tonal");
        save.add_css_class("clear");
        let d = data.clone();
        save.connect_clicked(move |_| save_top_songs(&d));
        actions.append(&save);
    }
    hero.append(&actions);
    hero
}

fn artist_rank(artist: &WrappedArtist, rank: usize) -> gtk::ListBoxRow {
    let line = gtk::Box::builder().spacing(12).build();
    let n = gtk::Label::builder().label(rank.to_string()).width_chars(2).xalign(1.0).build();
    n.add_css_class("wrapped-rank-n");
    n.add_css_class("numeric");
    line.append(&n);
    line.append(&artwork(artist.artwork_url.as_deref(), 48, true));
    let text = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .valign(gtk::Align::Center)
        .hexpand(true)
        .build();
    let name = gtk::Label::builder()
        .label(&artist.name)
        .xalign(0.0)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .build();
    name.add_css_class("track-title");
    text.append(&name);
    let sub = gtk::Label::builder()
        .label(format!("{} min · {} plays", num(artist.minutes), num(artist.plays)))
        .xalign(0.0)
        .build();
    sub.add_css_class("track-sub");
    text.append(&sub);
    line.append(&text);
    let row = gtk::ListBoxRow::builder().child(&line).build();
    row.add_css_class("wrapped-rank");
    row
}

fn panel(title: &str) -> gtk::Box {
    let panel = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(12)
        .valign(gtk::Align::Start)
        .build();
    panel.add_css_class("wrapped-panel");
    let name = gtk::Label::builder().label(title).xalign(0.0).build();
    name.add_css_class("panel-title");
    panel.append(&name);
    panel
}

fn panel_sub(title: &str) -> gtk::Label {
    let label = gtk::Label::builder().label(title).xalign(0.0).build();
    label.add_css_class("panel-sub");
    label
}

/// The labels under a chart of [count] bars: [tick] names the bars it labels, and the bars
/// between share its space (every sixth hour, every month).
fn ticks_of(count: usize, tick: impl Fn(usize) -> String) -> Vec<String> {
    let named: Vec<usize> = (0..count).filter(|i| !tick(*i).is_empty()).collect();
    let step = named.get(1).zip(named.first()).map(|(b, a)| b - a).unwrap_or(1).max(1);
    (0..count).step_by(step).map(tick).collect()
}

/// One series of minutes as thin bars on a baseline; each bar says its value when pointed at.
fn bar_chart(title: &str, values: &[i64], label: impl Fn(usize) -> String, tick: impl Fn(usize) -> String) -> gtk::Box {
    const HEIGHT: i32 = 96;
    let chart = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    let caption = gtk::Label::builder().label(title).xalign(0.0).build();
    caption.add_css_class("wrapped-caption");
    chart.append(&caption);
    let max = values.iter().copied().max().unwrap_or(0).max(1);
    let bars = gtk::Box::builder().spacing(2).homogeneous(true).height_request(HEIGHT).build();
    bars.add_css_class("wrapped-bars");
    let ticks = gtk::Box::builder().spacing(2).homogeneous(true).build();
    for (i, value) in values.iter().enumerate() {
        let bar = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .hexpand(true)
            .tooltip_text(format!("{}: {} min", label(i), num(*value)))
            .build();
        bar.add_css_class("wrapped-bar");
        let height = if *value > 0 {
            ((*value as f64 / max as f64) * HEIGHT as f64).round().max(3.0) as i32
        } else {
            0
        };
        let fill = gtk::Box::builder().height_request(height).build();
        if height > 0 {
            fill.add_css_class("wrapped-bar-fill");
        }
        // Standing on the baseline.
        bar.append(&gtk::Box::builder().vexpand(true).build());
        bar.append(&fill);
        bars.append(&bar);
    }
    // The ticks, evenly spaced under the bars they name (a few, so the chart can be narrow).
    for name in ticks_of(values.len(), tick) {
        let t = gtk::Label::builder().label(name).xalign(0.0).build();
        t.add_css_class("wrapped-tick");
        ticks.append(&t);
    }
    chart.append(&bars);
    chart.append(&ticks);
    chart
}

/// Shares of the year's minutes, largest first, with minutes and percent.
fn shares(items: &[WrappedShare], name: impl Fn(&str) -> String) -> gtk::Box {
    let list = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(10).build();
    let total = items.iter().map(|s| s.minutes).sum::<i64>().max(1);
    let max = items.iter().map(|s| s.minutes).max().unwrap_or(0).max(1);
    for share in items.iter().take(6) {
        let item = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(5).build();
        let line = gtk::Box::builder().spacing(12).build();
        let label = gtk::Label::builder()
            .label(name(&share.name))
            .xalign(0.0)
            .hexpand(true)
            .ellipsize(gtk::pango::EllipsizeMode::End)
            .build();
        label.add_css_class("track-title");
        line.append(&label);
        let percent = (share.minutes as f64 / total as f64 * 100.0).round() as i64;
        let value = gtk::Label::builder()
            .label(format!("{} min · {percent}%", num(share.minutes)))
            .build();
        value.add_css_class("wrapped-share-value");
        value.add_css_class("numeric");
        line.append(&value);
        item.append(&line);
        let bar = gtk::ProgressBar::builder().fraction(share.minutes as f64 / max as f64).build();
        bar.add_css_class("wrapped-share-bar");
        item.append(&bar);
        list.append(&item);
    }
    list
}

fn tile(label: &str, value: &str, under: &str) -> gtk::Box {
    let tile = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).build();
    tile.add_css_class("wrapped-tile");
    // Kept narrow enough for three tiles a row on a wide page.
    let name = gtk::Label::builder()
        .label(label.to_uppercase())
        .xalign(0.0)
        .wrap(true)
        .max_width_chars(24)
        .build();
    name.add_css_class("wrapped-tile-label");
    tile.append(&name);
    let big = gtk::Label::builder()
        .label(value)
        .xalign(0.0)
        .wrap(true)
        .wrap_mode(gtk::pango::WrapMode::WordChar)
        .max_width_chars(20)
        .build();
    big.add_css_class("wrapped-tile-value");
    tile.append(&big);
    if !under.is_empty() {
        let line = gtk::Label::builder()
            .label(under)
            .xalign(0.0)
            .wrap(true)
            .max_width_chars(28)
            .build();
        line.add_css_class("meta");
        tile.append(&line);
    }
    tile
}

// ---- The story ----

const SLIDE: Duration = Duration::from_secs(7);

fn text(value: &str, class: &str) -> gtk::Label {
    let label = gtk::Label::builder()
        .label(value)
        .wrap(true)
        .wrap_mode(gtk::pango::WrapMode::WordChar)
        .justify(gtk::Justification::Center)
        .max_width_chars(40)
        .build();
    label.add_css_class(class);
    label
}

fn slide() -> gtk::Box {
    gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(12)
        .valign(gtk::Align::Center)
        .halign(gtk::Align::Center)
        .margin_start(48)
        .margin_end(48)
        .build()
}

/// A top-five list on a slide.
fn story_list(items: Vec<(Option<String>, String, String)>, round: bool) -> gtk::Box {
    let list = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(10)
        .margin_top(8)
        .build();
    for (i, (art, title, under)) in items.into_iter().enumerate() {
        let row = gtk::Box::builder().spacing(14).build();
        let n = gtk::Label::builder().label((i + 1).to_string()).width_chars(2).build();
        n.add_css_class("story-rank");
        row.append(&n);
        row.append(&artwork(art.as_deref(), 52, round));
        let column = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .valign(gtk::Align::Center)
            .build();
        let name = gtk::Label::builder()
            .label(&title)
            .xalign(0.0)
            .ellipsize(gtk::pango::EllipsizeMode::End)
            .max_width_chars(30)
            .build();
        name.add_css_class("story-mid");
        column.append(&name);
        let sub = gtk::Label::builder().label(&under).xalign(0.0).build();
        sub.add_css_class("story-text");
        column.append(&sub);
        row.append(&column);
        list.append(&row);
    }
    list
}

fn slides(data: &Wrapped) -> Vec<gtk::Box> {
    let mut slides = vec![];
    let when = when_of(data).to_uppercase();

    let s = slide();
    s.append(&gtk::Image::builder().icon_name("starred-symbolic").pixel_size(48).build());
    s.append(&text(&when, "story-eyebrow"));
    s.append(&text("Your year in music", "story-big"));
    s.append(&text("Let's look back at what you played.", "story-text"));
    slides.push(s);

    let s = slide();
    s.append(&text("YOU LISTENED FOR", "story-eyebrow"));
    s.append(&text(&num(data.minutes), "story-huge"));
    s.append(&text("minutes", "story-mid"));
    s.append(&text(
        &format!(
            "That's {} days of music: {} songs by {} artists, on {} different days.",
            days_of(data.minutes),
            num(data.songs),
            num(data.artists),
            num(data.days)
        ),
        "story-text",
    ));
    slides.push(s);

    if let Some(song) = data.top_songs.first() {
        let s = slide();
        s.append(&text("YOUR SONG OF THE YEAR", "story-eyebrow"));
        s.append(&artwork(song.track.artwork_url.as_deref(), 200, false));
        s.append(&text(&song.track.title, "story-big"));
        s.append(&text(&song.track.artist, "story-text"));
        s.append(&text(&format!("Played {}", plural(song.plays, "time", "times")), "story-text"));
        slides.push(s);
    }
    if data.top_songs.len() > 1 {
        let s = slide();
        s.append(&text("YOUR TOP SONGS", "story-eyebrow"));
        s.append(&story_list(
            data.top_songs
                .iter()
                .take(5)
                .map(|t| (t.track.artwork_url.clone(), t.track.title.clone(), t.track.artist.clone()))
                .collect(),
            false,
        ));
        slides.push(s);
    }
    if let Some(artist) = data.top_artists.first() {
        let s = slide();
        s.append(&text("YOUR TOP ARTIST", "story-eyebrow"));
        s.append(&artwork(artist.artwork_url.as_deref(), 200, true));
        s.append(&text(&artist.name, "story-big"));
        let most = artist
            .top_song
            .as_ref()
            .map(|t| format!(", most of all \"{t}\""))
            .unwrap_or_default();
        s.append(&text(&format!("{} minutes together{most}.", num(artist.minutes)), "story-text"));
        slides.push(s);
    }
    if data.top_artists.len() > 1 {
        let s = slide();
        s.append(&text("YOUR TOP ARTISTS", "story-eyebrow"));
        s.append(&story_list(
            data.top_artists
                .iter()
                .take(5)
                .map(|a| (a.artwork_url.clone(), a.name.clone(), format!("{} min", num(a.minutes))))
                .collect(),
            true,
        ));
        slides.push(s);
    }
    if let Some(station) = data.top_stations.first() {
        let s = slide();
        s.append(&text("YOUR STATION OF THE YEAR", "story-eyebrow"));
        s.append(&text(&station.name, "story-big"));
        let then: Vec<&str> = data.top_stations.iter().skip(1).take(2).map(|s| s.name.as_str()).collect();
        let then = if then.is_empty() {
            String::new()
        } else {
            format!(", then {}", then.join(" and "))
        };
        s.append(&text(
            &format!("{} hours tuned in{then}.", num((station.minutes as f64 / 60.0).round() as i64)),
            "story-text",
        ));
        slides.push(s);
    }
    if data.new_artists > 0 {
        let s = slide();
        s.append(&text("NEW TO YOU", "story-eyebrow"));
        s.append(&text(&num(data.new_artists), "story-huge"));
        s.append(&text(
            if data.new_artists == 1 {
                "artist discovered"
            } else {
                "artists discovered"
            },
            "story-mid",
        ));
        if let Some(find) = data.discoveries.first() {
            s.append(&text(&format!("Your favourite find: {}.", find.name), "story-text"));
        }
        slides.push(s);
    }
    let s = slide();
    s.append(&text("YOUR MUSIC CLOCK", "story-eyebrow"));
    s.append(&text(&format!("Peak time: {}", hour_name(peak(&data.hours))), "story-big"));
    let bars = gtk::Box::builder()
        .spacing(3)
        .homogeneous(true)
        .height_request(120)
        .width_request(420)
        .margin_top(8)
        .build();
    let max = data.hours.iter().copied().max().unwrap_or(0).max(1);
    for m in &data.hours {
        let height = if *m > 0 {
            ((*m as f64 / max as f64) * 120.0).max(4.0) as i32
        } else {
            0
        };
        let fill = gtk::Box::builder().height_request(height).valign(gtk::Align::End).build();
        if height > 0 {
            fill.add_css_class("story-bar-fill");
        }
        bars.append(&fill);
    }
    s.append(&bars);
    if let Some(day) = &data.biggest_day {
        s.append(&text(
            &format!("Your biggest day was {}, with {} minutes.", day_name(&day.date), num(day.minutes)),
            "story-text",
        ));
    }
    slides.push(s);

    let s = slide();
    s.append(&text("KEEPING IT GOING", "story-eyebrow"));
    s.append(&text(&data.longest_streak.to_string(), "story-huge"));
    s.append(&text(if data.longest_streak == 1 { "day" } else { "days in a row" }, "story-mid"));
    s.append(&text(
        &match &data.on_repeat {
            Some(r) => format!("And one day you played \"{}\" {} times. No judgement.", r.track.title, r.plays),
            None => "Your longest run of days with music.".into(),
        },
        "story-text",
    ));
    slides.push(s);

    let s = slide();
    s.append(&text("YOUR LISTENING PERSONALITY", "story-eyebrow"));
    s.append(&text(&data.personality.name, "story-big"));
    s.append(&text(&data.personality.description, "story-text"));
    slides.push(s);

    // The last: the year on one screen.
    let s = slide();
    s.append(&text(&format!("{} WRAPPED", when), "story-eyebrow"));
    let columns = gtk::Box::builder().spacing(48).halign(gtk::Align::Center).margin_top(8).build();
    let list = |title: &str, names: Vec<String>| {
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(4).build();
        let heading = gtk::Label::builder().label(title).xalign(0.0).build();
        heading.add_css_class("story-eyebrow");
        column.append(&heading);
        for (i, name) in names.into_iter().enumerate() {
            let line = gtk::Label::builder()
                .label(format!("{}  {name}", i + 1))
                .xalign(0.0)
                .ellipsize(gtk::pango::EllipsizeMode::End)
                .max_width_chars(24)
                .build();
            line.add_css_class("story-text");
            column.append(&line);
        }
        column
    };
    columns.append(&list(
        "TOP ARTISTS",
        data.top_artists.iter().take(5).map(|a| a.name.clone()).collect(),
    ));
    columns.append(&list(
        "TOP SONGS",
        data.top_songs.iter().take(5).map(|s| s.track.title.clone()).collect(),
    ));
    s.append(&columns);
    let numbers = gtk::Box::builder().spacing(48).halign(gtk::Align::Center).margin_top(12).build();
    for (title, value) in [("MINUTES", num(data.minutes)), ("PERSONALITY", data.personality.name.clone())] {
        let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(2).build();
        let heading = gtk::Label::new(Some(title));
        heading.add_css_class("story-eyebrow");
        column.append(&heading);
        column.append(&text(&value, "story-mid"));
        numbers.append(&column);
    }
    s.append(&numbers);
    slides.push(s);
    slides
}

/// The story: a slide at a time over its own colours, on by itself every few seconds (← → to
/// move, space to pause, Esc to close).
fn play_story(data: Rc<Wrapped>) {
    let slides = slides(&data);
    let count = slides.len();
    let dialog = adw::Dialog::builder()
        .title(format!("{} Wrapped", data.year))
        .content_width(760)
        .content_height(600)
        .build();
    let stack = gtk::Stack::builder()
        .transition_type(gtk::StackTransitionType::Crossfade)
        .transition_duration(350)
        .vexpand(true)
        .build();
    for (i, slide) in slides.iter().enumerate() {
        stack.add_named(slide, Some(&i.to_string()));
    }
    let frame = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    frame.add_css_class("story");
    frame.add_css_class("story-0");
    // The progress, a dash for each slide.
    let dots = gtk::Box::builder()
        .spacing(4)
        .homogeneous(true)
        .margin_top(12)
        .margin_start(16)
        .margin_end(16)
        .build();
    let mut dashes = vec![];
    for _ in 0..count {
        let dash = gtk::ProgressBar::builder().fraction(0.0).build();
        dots.append(&dash);
        dashes.push(dash);
    }
    frame.append(&dots);
    let controls = gtk::Box::builder()
        .spacing(8)
        .halign(gtk::Align::End)
        .margin_top(8)
        .margin_end(16)
        .build();
    let pause = gtk::Button::builder()
        .icon_name("media-playback-pause-symbolic")
        .tooltip_text("Pause")
        .build();
    pause.add_css_class("circular");
    let close = gtk::Button::builder()
        .icon_name("window-close-symbolic")
        .tooltip_text("Close")
        .build();
    close.add_css_class("circular");
    controls.append(&pause);
    controls.append(&close);
    frame.append(&controls);
    let middle = gtk::Box::builder().vexpand(true).build();
    let back = gtk::Button::builder()
        .icon_name("go-previous-symbolic")
        .tooltip_text("Previous")
        .valign(gtk::Align::Center)
        .margin_start(16)
        .build();
    back.add_css_class("circular");
    let next = gtk::Button::builder()
        .icon_name("go-next-symbolic")
        .tooltip_text("Next")
        .valign(gtk::Align::Center)
        .margin_end(16)
        .build();
    next.add_css_class("circular");
    stack.set_hexpand(true);
    middle.append(&back);
    middle.append(&stack);
    middle.append(&next);
    frame.append(&middle);
    // On the last slide: save, again, done.
    let actions = gtk::Box::builder().spacing(10).halign(gtk::Align::Center).margin_bottom(24).build();
    if !data.top_songs.is_empty() {
        let save = button(Some("list-add-symbolic"), "Save top songs as a playlist", "plain");
        save.remove_css_class("tonal");
        save.add_css_class("light");
        let d = data.clone();
        save.connect_clicked(move |_| save_top_songs(&d));
        actions.append(&save);
    }
    let again = button(Some("view-refresh-symbolic"), "Watch again", "plain");
    actions.append(&again);
    let done = button(None, "Done", "plain");
    actions.append(&done);
    let spacer = gtk::Box::builder().height_request(48).build();
    frame.append(&spacer);
    frame.append(&actions);

    let index = Rc::new(Cell::new(0usize));
    let paused = Rc::new(Cell::new(false));
    // Each move starts the slide's clock afresh; older clocks see it moved on and stop.
    let generation = Rc::new(Cell::new(0u64));
    let go: Rc<dyn Fn(usize)> = Rc::new({
        let (stack, frame, dashes, index, paused, generation, actions, spacer, pause) = (
            stack.clone(),
            frame.clone(),
            dashes.clone(),
            index.clone(),
            paused.clone(),
            generation.clone(),
            actions.clone(),
            spacer.clone(),
            pause.clone(),
        );
        let (forward, backward) = (next.clone(), back.clone());
        let weak_dialog = dialog.downgrade();
        move |to: usize| {
            let to = to.min(count - 1);
            let before = index.replace(to);
            frame.remove_css_class(&format!("story-{}", before % 6));
            frame.add_css_class(&format!("story-{}", to % 6));
            stack.set_visible_child_name(&to.to_string());
            for (i, dash) in dashes.iter().enumerate() {
                dash.set_fraction(if i < to { 1.0 } else { 0.0 });
            }
            let last = to == count - 1;
            actions.set_visible(last);
            spacer.set_visible(!last);
            pause.set_visible(!last);
            forward.set_opacity(if last { 0.0 } else { 1.0 });
            forward.set_sensitive(!last);
            backward.set_opacity(if to == 0 { 0.0 } else { 1.0 });
            backward.set_sensitive(to > 0);
            if last {
                dashes[to].set_fraction(1.0);
            }
            let ticket = generation.get() + 1;
            generation.set(ticket);
            if last {
                return;
            }
            // The slide's dash fills as its time passes; then on to the next.
            let (dashes, index, paused, generation, weak_dialog) = (
                dashes.clone(),
                index.clone(),
                paused.clone(),
                generation.clone(),
                weak_dialog.clone(),
            );
            let elapsed = Rc::new(Cell::new(Duration::ZERO));
            let step = Duration::from_millis(100);
            glib::timeout_add_local(step, move || {
                if generation.get() != ticket || weak_dialog.upgrade().is_none() {
                    return glib::ControlFlow::Break;
                }
                if !paused.get() {
                    elapsed.set(elapsed.get() + step);
                    dashes[to].set_fraction((elapsed.get().as_secs_f64() / SLIDE.as_secs_f64()).min(1.0));
                    if elapsed.get() >= SLIDE {
                        if let Some(dialog) = weak_dialog.upgrade() {
                            dialog.activate_action("story.next", None).ok();
                        }
                        return glib::ControlFlow::Break;
                    }
                }
                let _ = index.get();
                glib::ControlFlow::Continue
            });
        }
    });
    let actions_group = gtk::gio::SimpleActionGroup::new();
    let next_action = gtk::gio::SimpleAction::new("next", None);
    let (g, i) = (go.clone(), index.clone());
    next_action.connect_activate(move |_, _| g(i.get() + 1));
    actions_group.add_action(&next_action);
    dialog.insert_action_group("story", Some(&actions_group));
    let (g, i) = (go.clone(), index.clone());
    next.connect_clicked(move |_| g(i.get() + 1));
    let (g, i) = (go.clone(), index.clone());
    back.connect_clicked(move |_| g(i.get().saturating_sub(1)));
    let g = go.clone();
    again.connect_clicked(move |_| g(0));
    let toggle: Rc<dyn Fn()> = Rc::new({
        let (paused, pause) = (paused.clone(), pause.clone());
        move || {
            let now = !paused.get();
            paused.set(now);
            pause.set_icon_name(if now {
                "media-playback-start-symbolic"
            } else {
                "media-playback-pause-symbolic"
            });
            pause.set_tooltip_text(Some(if now { "Play" } else { "Pause" }));
        }
    });
    let t = toggle.clone();
    pause.connect_clicked(move |_| t());
    for button in [&close, &done] {
        let d = dialog.clone();
        button.connect_clicked(move |_| {
            d.close();
        });
    }
    let keys = gtk::EventControllerKey::new();
    let (g, i) = (go.clone(), index.clone());
    keys.connect_key_pressed(move |_, key, _, _| match key {
        gtk::gdk::Key::Right => {
            g(i.get() + 1);
            glib::Propagation::Stop
        }
        gtk::gdk::Key::Left => {
            g(i.get().saturating_sub(1));
            glib::Propagation::Stop
        }
        gtk::gdk::Key::space => {
            toggle();
            glib::Propagation::Stop
        }
        _ => glib::Propagation::Proceed,
    });
    dialog.add_controller(keys);
    dialog.set_child(Some(&frame));
    dialog.present(Some(&ctx().window));
    go(0);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn numbers_and_times_read_as_on_the_web() {
        assert_eq!(num(0), "0");
        assert_eq!(num(999), "999");
        assert_eq!(num(10_538), "10,538");
        assert_eq!(num(1_234_567), "1,234,567");
        assert_eq!(hour_name(0), "midnight");
        assert_eq!(hour_name(12), "noon");
        assert_eq!(hour_name(8), "8 am");
        assert_eq!(hour_name(20), "8 pm");
        assert_eq!(peak(&[1, 5, 3, 5]), 1);
        assert_eq!(days_of(1440), "1");
        assert_eq!(days_of(2160), "1.5");
        assert_eq!(days_of(14_400 * 2), "20");
        assert_eq!(device_name("android"), "Phone app");
        assert_eq!(source_name("YOUTUBE_MUSIC"), "YouTube Music");
        assert_eq!(day_name("2026-03-14"), "Saturday, March 14");
        let hours = ticks_of(24, |h| if h % 6 == 0 { short_hour(h) } else { String::new() });
        assert_eq!(hours, vec!["12a", "6a", "12p", "6p"]);
        assert_eq!(ticks_of(12, |m| MONTHS[m][..1].to_string()).len(), 12);
    }
}
