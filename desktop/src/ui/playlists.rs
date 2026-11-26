//! What the web player's playlist pages do besides listing (web/src/pages/Playlists.tsx):
//! making one, importing one (a link, a list typed, pasted or from a CSV file, or a playlist on
//! your Subsonic server), sharing it with people on the server (or leaving one shared with you),
//! and looking again for the songs that weren't found.

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::gio;

use crate::api::{ImportFrom, Playlist, PlaylistPerson, PlaylistSummary, SourcePlaylist};
use crate::ui::ctx;
use crate::ui::forms::{Form, boxed_list, choice, clear, confirm, field, note, plural, run, text_field};
use crate::ui::widgets::button;

/// Import and New playlist, beside the Playlists page's title.
pub fn header_actions() -> gtk::Box {
    let row = gtk::Box::builder().spacing(8).valign(gtk::Align::Center).build();
    let import = button(Some("document-open-symbolic"), "Import", "tonal");
    import.connect_clicked(|_| import_dialog());
    row.append(&import);
    let new = button(Some("list-add-symbolic"), "New playlist", "suggested");
    new.connect_clicked(|_| new_playlist());
    row.append(&new);
    row
}

/// Makes an empty playlist, and opens it.
pub fn new_playlist() {
    let form = Rc::new(Form::new("New playlist", "Create"));
    let (name_field, name) = text_field("Name", None, "", false);
    if let Some(entry) = name.downcast_ref::<gtk::Entry>() {
        entry.set_placeholder_text(Some("Road trip"));
        entry.set_max_length(200);
    }
    form.add(&name_field);
    let focus = name.clone();
    let f = form.clone();
    form.action.connect_clicked(move |_| {
        let Some(api) = ctx().api() else { return };
        let typed = name.text().trim().to_string();
        let typed = if typed.is_empty() { "New playlist".to_string() } else { typed };
        f.busy(true);
        let f = f.clone();
        run(async move { api.create_playlist(&typed, &[]).await }, move |result| match result {
            Ok(made) => {
                f.close();
                ctx().toast(&format!("Made “{}”", made.summary.name));
                crate::ui::window::refresh_sidebar();
                ctx().open_playlist(&made.summary.id, &made.summary.name);
            }
            Err(e) => f.error(&e.to_string()),
        });
    });
    form.present();
    focus.grab_focus();
}

// ---- Importing ----

fn import_dialog() {
    let form = Rc::new(Form::new("Import a playlist", "Import"));
    // The three ways in, one shown at a time.
    let pages = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    let mode = Rc::new(RefCell::new("link".to_string()));

    // From a link.
    let (link_field, link) = text_field(
        "Playlist or album link",
        Some("Spotify, Apple Music, Tidal, Deezer, YouTube or ListenBrainz. Each song is found in your own sources, your library first."),
        "",
        false,
    );
    if let Some(entry) = link.downcast_ref::<gtk::Entry>() {
        entry.set_placeholder_text(Some("https://…"));
    }
    pages.append(&link_field);
    let focus = link.clone();

    // From a list: typed, pasted, or a file's.
    let list_box = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(14).build();
    let (name_field, name) = text_field("Name", None, "", false);
    if let Some(entry) = name.downcast_ref::<gtk::Entry>() {
        entry.set_placeholder_text(Some("Imported playlist"));
        entry.set_max_length(200);
    }
    list_box.append(&name_field);
    let songs = gtk::TextView::builder()
        .wrap_mode(gtk::WrapMode::WordChar)
        .top_margin(8)
        .bottom_margin(8)
        .left_margin(10)
        .right_margin(10)
        .monospace(true)
        .build();
    songs.buffer().set_text("");
    let songs_scroll = gtk::ScrolledWindow::builder().min_content_height(180).child(&songs).build();
    songs_scroll.add_css_class("card");
    list_box.append(&field(
        "Songs",
        Some("One a line, like “Artist - Title”; or a CSV export (from Exportify, TuneMyMusic, Soundiiz…) with title and artist columns: choose the file, or paste it. For Qobuz and other services, export the playlist with TuneMyMusic or Soundiiz."),
        &songs_scroll,
    ));
    let pick = button(Some("document-open-symbolic"), "Choose a file (CSV or text)", "tonal");
    pick.set_halign(gtk::Align::Start);
    {
        let (songs, name, f) = (songs.clone(), name.clone(), form.clone());
        pick.connect_clicked(move |b| choose_file(b, &songs, &name, &f));
    }
    list_box.append(&pick);
    list_box.set_visible(false);
    pages.append(&list_box);

    // From your library: a Subsonic server's playlists.
    let library = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(14).visible(false).build();
    pages.append(&library);
    let by_id: Vec<(&str, gtk::Widget)> = vec![
        ("link", link_field.clone().upcast()),
        ("list", list_box.clone().upcast()),
        ("library", library.clone().upcast()),
    ];
    let by_id = Rc::new(by_id);
    let picked: Rc<RefCell<Option<(String, String)>>> = Rc::default();

    let tabs = gtk::Box::builder().halign(gtk::Align::Start).build();
    tabs.add_css_class("segmented");
    let mut first: Option<gtk::ToggleButton> = None;
    for (id, label) in [("link", "From a link"), ("list", "From a list"), ("library", "From your library")] {
        let tab = gtk::ToggleButton::builder().label(label).active(id == "link").build();
        match &first {
            Some(group) => tab.set_group(Some(group)),
            None => first = Some(tab.clone()),
        }
        if id == "library" {
            // Only with a Subsonic server (Navidrome and the like) among your sources.
            tab.set_visible(false);
            show_libraries(&tab, &library, picked.clone());
        }
        let (by_id, mode) = (by_id.clone(), mode.clone());
        tab.connect_toggled(move |t| {
            if t.is_active() {
                for (page, widget) in by_id.iter() {
                    widget.set_visible(*page == id);
                }
                mode.replace(id.to_string());
            }
        });
        tabs.append(&tab);
    }
    form.add(&tabs);
    form.add(&pages);
    let finding = gtk::Box::builder().spacing(8).visible(false).build();
    finding.append(&gtk::Spinner::builder().spinning(true).build());
    finding.append(&note("Finding the songs… this takes a moment for long playlists."));
    form.add(&finding);

    let f = form.clone();
    form.action.connect_clicked(move |_| {
        let from = match mode.borrow().as_str() {
            "link" => Some(link.text().trim().to_string()).filter(|u| !u.is_empty()).map(ImportFrom::Link),
            "list" => {
                let buffer = songs.buffer();
                let text = buffer.text(&buffer.start_iter(), &buffer.end_iter(), false).to_string();
                (!text.trim().is_empty()).then(|| ImportFrom::List {
                    text,
                    name: name.text().trim().to_string(),
                })
            }
            _ => picked
                .borrow()
                .clone()
                .map(|(source, playlist)| ImportFrom::Library { source, playlist }),
        };
        let Some(from) = from else { return };
        let Some(api) = ctx().api() else { return };
        f.busy(true);
        finding.set_visible(true);
        let (f, finding) = (f.clone(), finding.clone());
        run(async move { api.import_playlist(&from).await }, move |result| {
            finding.set_visible(false);
            match result {
                Ok(imported) => {
                    crate::ui::window::refresh_sidebar();
                    ctx().toast(&format!("Imported {}", plural(imported.found, "song")));
                    f.close();
                    let summary = imported.playlist.summary.clone();
                    if imported.missing.is_empty() && imported.note.is_none() {
                        ctx().open_playlist(&summary.id, &summary.name);
                    } else {
                        import_report(&summary, &imported.missing, imported.note.as_deref());
                    }
                }
                Err(e) => f.error(&e.to_string()),
            }
        });
    });
    form.present();
    focus.grab_focus();
}

/// A CSV or text file's songs into the list (and its name as the playlist's, unless one's typed).
fn choose_file(near: &gtk::Button, songs: &gtk::TextView, name: &gtk::Editable, form: &Rc<Form>) {
    let filter = gtk::FileFilter::new();
    filter.set_name(Some("CSV or text"));
    for pattern in ["*.csv", "*.tsv", "*.txt", "*.m3u", "*.m3u8"] {
        filter.add_pattern(pattern);
    }
    for mime in ["text/csv", "text/plain", "text/tab-separated-values"] {
        filter.add_mime_type(mime);
    }
    let filters = gio::ListStore::new::<gtk::FileFilter>();
    filters.append(&filter);
    let dialog = gtk::FileDialog::builder()
        .title("Choose a playlist file")
        .filters(&filters)
        .modal(true)
        .build();
    let (songs, name, form, near) = (songs.clone(), name.clone(), form.clone(), near.clone());
    dialog.open(Some(&ctx().window), gio::Cancellable::NONE, move |chosen| {
        let Some(path) = chosen.ok().and_then(|f| f.path()) else { return };
        if std::fs::metadata(&path).map(|m| m.len() > 1_000_000).unwrap_or(false) {
            form.error("That file is too big (1 MB at most)");
            return;
        }
        match std::fs::read(&path) {
            Ok(bytes) => {
                songs.buffer().set_text(&String::from_utf8_lossy(&bytes));
                let file = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
                if let Some(label) = near.child().and_then(|c| c.last_child()).and_downcast::<gtk::Label>() {
                    label.set_label(&format!("From {file}"));
                }
                // The file's name names the playlist, unless one was typed.
                if name.text().trim().is_empty() {
                    let stem = path.file_stem().map(|n| n.to_string_lossy().replace('_', " ")).unwrap_or_default();
                    name.set_text(stem.trim());
                }
            }
            Err(e) => form.error(&format!("Couldn't read it: {e}")),
        }
    });
}

/// "From your library": your Subsonic servers' playlists, to pick one.
fn show_libraries(tab: &gtk::ToggleButton, into: &gtk::Box, picked: Rc<RefCell<Option<(String, String)>>>) {
    let Some(api) = ctx().api() else { return };
    let (tab, into) = (tab.clone(), into.clone());
    run(async move { api.sources().await }, move |result| {
        let servers: Vec<_> = result
            .unwrap_or_default()
            .into_iter()
            .filter(|s| s.kind == "SUBSONIC" && s.enabled && !s.hidden)
            .collect();
        if servers.is_empty() {
            return;
        }
        tab.set_visible(true);
        let lists = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
        let load = {
            let (lists, picked) = (lists.clone(), picked.clone());
            Rc::new(move |source: String| {
                clear(&lists);
                picked.replace(None);
                let Some(api) = ctx().api() else { return };
                lists.append(&crate::ui::widgets::loading());
                let (lists, picked) = (lists.clone(), picked.clone());
                let id = source.clone();
                run(async move { api.source_playlists(&id).await }, move |result| {
                    clear(&lists);
                    match result {
                        Ok(found) if found.is_empty() => lists.append(&note("There are no playlists on this server.")),
                        Ok(found) => lists.append(&server_playlists(&source, found, picked)),
                        Err(e) => lists.append(&note(&e.to_string())),
                    }
                });
            })
        };
        if servers.len() > 1 {
            let names: Vec<String> = servers.iter().map(|s| s.name.clone()).collect();
            let (row, drop) = choice("Server", None, &names, 0);
            let (l, ids) = (load.clone(), servers.iter().map(|s| s.id.clone()).collect::<Vec<_>>());
            drop.connect_selected_notify(move |d| {
                if let Some(id) = ids.get(d.selected() as usize) {
                    l(id.clone());
                }
            });
            into.append(&row);
        }
        into.append(&lists);
        load(servers[0].id.clone());
    });
}

fn server_playlists(source: &str, playlists: Vec<SourcePlaylist>, picked: Rc<RefCell<Option<(String, String)>>>) -> gtk::Box {
    let mut names = vec!["Choose a playlist…".to_string()];
    names.extend(playlists.iter().map(|p| match p.song_count {
        Some(n) => format!("{} ({})", p.name, plural(n, "song")),
        None => p.name.clone(),
    }));
    let (row, drop) = choice("Playlist", Some("Its songs come in as your library has them."), &names, 0);
    let source = source.to_string();
    drop.connect_selected_notify(move |d| {
        let at = d.selected() as usize;
        picked.replace(
            at.checked_sub(1)
                .and_then(|i| playlists.get(i))
                .map(|p| (source.clone(), p.id.clone())),
        );
    });
    row
}

/// What an import didn't find (they're in the playlist anyway, greyed out), and its note.
fn import_report(summary: &PlaylistSummary, missing: &[String], said: Option<&str>) {
    let form = Rc::new(Form::new("Imported", "Open the playlist"));
    if let Some(said) = said {
        let notice = note(&format!("{said}."));
        notice.add_css_class("notice");
        form.add(&notice);
    }
    if !missing.is_empty() {
        form.note(&format!(
            "These {} weren't found in your sources. They're in the playlist anyway, greyed out, and are looked for again every few hours (or with “Look for them again”):",
            plural(missing.len() as i64, "song")
        ));
        let list = boxed_list();
        for song in missing {
            list.append(&crate::ui::widgets::plain_row(song, ""));
        }
        form.add(&list);
    }
    let (f, id, name) = (form.clone(), summary.id.clone(), summary.name.clone());
    let open = move || {
        f.close();
        ctx().open_playlist(&id, &name);
    };
    form.action.connect_clicked(move |_| open());
    form.present();
}

// ---- Sharing ----

/// "Sam, Alex and Jordan", or "Sam, Alex and 3 more".
pub fn names(people: &[PlaylistPerson]) -> String {
    let all: Vec<&str> = people.iter().map(PlaylistPerson::shown).collect();
    match all.len() {
        0 | 1 => all.join(""),
        n if n <= 3 => format!("{} and {}", all[..n - 1].join(", "), all[n - 1]),
        n => format!("{} and {} more", all[..2].join(", "), n - 2),
    }
}

/// Whether a playlist is shared (with you, or by you with someone).
pub fn is_shared(summary: &PlaylistSummary) -> bool {
    !summary.mine || !summary.members.is_empty()
}

/// Who a shared playlist is between, for its page.
pub fn people_line(summary: &PlaylistSummary) -> String {
    if !summary.mine
        && let Some(owner) = &summary.owner
    {
        let more = if summary.members.len() > 1 {
            format!(" and {} more", summary.members.len() - 1)
        } else {
            String::new()
        };
        return format!(
            "{}'s, shared with you{more}. Everyone adds, removes and reorders songs.",
            owner.shown()
        );
    }
    format!(
        "Shared with {}. Everyone adds, removes and reorders songs.",
        names(&summary.members)
    )
}

/// Sharing a playlist (yours): who it's shared with (each can be taken off), and adding someone.
pub fn share_dialog(summary: &PlaylistSummary, changed: Rc<dyn Fn()>) {
    let dialog = adw::Dialog::builder()
        .title(format!("Share “{}”", summary.name))
        .content_width(480)
        .build();
    let body = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(10)
        .margin_top(6)
        .margin_bottom(24)
        .margin_start(24)
        .margin_end(24)
        .build();
    body.append(&note(
        "Everyone it's shared with sees it with their playlists, and can add, remove and reorder its songs. Only you can rename it, delete it or share it.",
    ));
    let lists = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(10).build();
    body.append(&lists);
    let members: Rc<RefCell<Vec<PlaylistPerson>>> = Rc::new(RefCell::new(summary.members.clone()));
    let people: Rc<RefCell<Option<Vec<PlaylistPerson>>>> = Rc::default();
    let show: Rc<RefCell<Option<Rc<dyn Fn()>>>> = Rc::default();
    let render: Rc<dyn Fn()> = {
        let (lists, members, people, show, id) = (lists.clone(), members.clone(), people.clone(), show.clone(), summary.id.clone());
        Rc::new(move || {
            clear(&lists);
            let again = show.borrow().clone();
            let row = |person: &PlaylistPerson, label: &str, icon: &str, kind: &str, sharing: bool| {
                let row = crate::ui::widgets::plain_row(person.shown(), &person.username);
                let b = button(Some(icon), label, kind);
                b.set_valign(gtk::Align::Center);
                let (members, person, id, again, changed) = (members.clone(), person.clone(), id.clone(), again.clone(), changed.clone());
                b.connect_clicked(move |b| {
                    let Some(api) = ctx().api() else { return };
                    b.set_sensitive(false);
                    let (members, person, id, again, changed, b) = (
                        members.clone(),
                        person.clone(),
                        id.clone(),
                        again.clone(),
                        changed.clone(),
                        b.clone(),
                    );
                    let who = person.id.clone();
                    run(
                        async move {
                            if sharing {
                                api.share_playlist(&id, &who).await.map(|s| Some(s.members))
                            } else {
                                api.unshare_playlist(&id, &who).await.map(|_| None)
                            }
                        },
                        move |result| match result {
                            Ok(next) => {
                                match next {
                                    Some(list) => {
                                        members.replace(list);
                                        ctx().toast(&format!("Shared with {}", person.shown()));
                                    }
                                    None => members.borrow_mut().retain(|m| m.id != person.id),
                                }
                                changed();
                                if let Some(again) = &again {
                                    again();
                                }
                            }
                            Err(e) => {
                                b.set_sensitive(true);
                                ctx().failed(&e);
                            }
                        },
                    );
                });
                row.add_suffix(&b);
                row
            };
            if !members.borrow().is_empty() {
                lists.append(&crate::ui::forms::small_heading("Shared with"));
                let list = boxed_list();
                for m in members.borrow().iter() {
                    list.append(&row(m, "Remove", "window-close-symbolic", "ghost", false));
                }
                lists.append(&list);
            }
            lists.append(&crate::ui::forms::small_heading("Share with"));
            match people.borrow().as_ref() {
                None => lists.append(&crate::ui::widgets::loading()),
                Some(everyone) => {
                    let others: Vec<&PlaylistPerson> = everyone.iter().filter(|p| !members.borrow().iter().any(|m| m.id == p.id)).collect();
                    if others.is_empty() {
                        lists.append(&note(if everyone.is_empty() {
                            "There's no one else on this server yet."
                        } else {
                            "It's shared with everyone on this server."
                        }));
                    } else {
                        let list = boxed_list();
                        for p in others {
                            list.append(&row(p, "Share", "list-add-symbolic", "tonal", true));
                        }
                        lists.append(&list);
                    }
                }
            }
        })
    };
    show.replace(Some(render.clone()));
    render();
    if let Some(api) = ctx().api() {
        let (people, render) = (people.clone(), render.clone());
        run(async move { api.people().await }, move |result| match result {
            Ok(found) => {
                people.replace(Some(found));
                render();
            }
            Err(e) => ctx().failed(&e),
        });
    }
    let header = adw::HeaderBar::builder()
        .show_start_title_buttons(false)
        .show_end_title_buttons(false)
        .build();
    let done = gtk::Button::with_label("Done");
    done.add_css_class("suggested-action");
    let d = dialog.clone();
    done.connect_clicked(move |_| {
        d.close();
    });
    header.pack_end(&done);
    let toolbar = adw::ToolbarView::new();
    toolbar.add_top_bar(&header);
    toolbar.set_content(Some(
        &gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .propagate_natural_height(true)
            .max_content_height(600)
            .child(&body)
            .build(),
    ));
    dialog.set_child(Some(&toolbar));
    dialog.present(Some(&ctx().window));
    // Kept alive with the dialog.
    let keep = Cell::new(Some(show));
    dialog.connect_closed(move |_| {
        if let Some(show) = keep.take() {
            show.replace(None);
        }
    });
}

/// Leaving a playlist someone shared with you.
pub fn leave(summary: &PlaylistSummary) {
    let Some(me) = ctx().config.borrow().user.as_ref().map(|u| u.id.clone()) else {
        return;
    };
    let owner = summary
        .owner
        .as_ref()
        .map(|o| format!(", and it stays {}'s", o.shown()))
        .unwrap_or_default();
    let (id, name) = (summary.id.clone(), summary.name.clone());
    confirm(
        &format!("Leave “{name}”?"),
        &format!("It goes from your playlists. The songs you added stay in it{owner}."),
        "Stay",
        "Leave",
        move || {
            let Some(api) = ctx().api() else { return };
            let (id, me) = (id.clone(), me.clone());
            run(async move { api.unshare_playlist(&id, &me).await }, |result| match result {
                Ok(_) => {
                    crate::ui::window::refresh_sidebar();
                    ctx().back();
                }
                Err(e) => ctx().failed(&e),
            });
        },
    );
}

/// What deleting it does, as the web says it.
pub fn delete_detail(summary: &PlaylistSummary) -> String {
    format!(
        "The playlist goes for good, on every device{}. The songs stay in their sources.",
        if summary.members.is_empty() {
            ""
        } else {
            ", and for everyone it's shared with"
        }
    )
}

// ---- Songs that weren't found ----

/// The songs not found (greyed out), and looking for them again.
pub fn missing_note(id: &str, missing: usize, changed: Rc<dyn Fn(Playlist)>) -> gtk::Box {
    let row = gtk::Box::builder().spacing(12).build();
    row.add_css_class("missing-note");
    let text = if missing == 1 {
        "1 song wasn't found in your sources: it's greyed out and skipped. It's looked for again every few hours, in case it's turned up (Lidarr may be getting it).".to_string()
    } else {
        format!(
            "{missing} songs weren't found in your sources: they're greyed out and skipped. They're looked for again every few hours, in case they've turned up (Lidarr may be getting them)."
        )
    };
    let label = gtk::Label::builder()
        .label(text)
        .xalign(0.0)
        .wrap(true)
        .max_width_chars(60)
        .hexpand(true)
        .build();
    row.append(&label);
    let again = button(Some("system-search-symbolic"), "Look for them again", "tonal");
    again.set_valign(gtk::Align::Center);
    let id = id.to_string();
    again.connect_clicked(move |b| {
        let Some(api) = ctx().api() else { return };
        b.set_sensitive(false);
        let (b, id, changed) = (b.clone(), id.clone(), changed.clone());
        run(async move { api.find_missing(&id).await }, move |result| {
            b.set_sensitive(true);
            match result {
                Ok(found) => {
                    ctx().toast(&if found.found > 0 {
                        let still = if found.still_missing > 0 {
                            format!("; {} still missing", found.still_missing)
                        } else {
                            String::new()
                        };
                        format!("Found {}{still}", plural(found.found, "song"))
                    } else {
                        "Still not found anywhere".into()
                    });
                    crate::ui::window::refresh_sidebar();
                    changed(found.playlist);
                }
                Err(e) => ctx().failed(&e),
            }
        });
    });
    row.append(&again);
    row
}

/// "added by Sam" (or "added by you") for each song on a shared playlist.
pub fn added_by_notes(playlist: &Playlist) -> Option<Rc<Vec<String>>> {
    if !is_shared(&playlist.summary) {
        return None;
    }
    let me = ctx().config.borrow().user.as_ref().map(|u| u.id.clone()).unwrap_or_default();
    Some(Rc::new(
        playlist
            .entries
            .iter()
            .map(|e| match &e.added_by {
                Some(p) if p.id == me => "added by you".to_string(),
                Some(p) => format!("added by {}", p.shown()),
                None => String::new(),
            })
            .collect(),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn person(name: &str) -> PlaylistPerson {
        PlaylistPerson {
            id: name.to_lowercase(),
            username: name.to_lowercase(),
            name: name.into(),
        }
    }

    #[test]
    fn people_are_named_as_on_the_web() {
        assert_eq!(names(&[person("Sam")]), "Sam");
        assert_eq!(names(&[person("Sam"), person("Alex")]), "Sam and Alex");
        assert_eq!(names(&[person("Sam"), person("Alex"), person("Jo")]), "Sam, Alex and Jo");
        assert_eq!(
            names(&[person("Sam"), person("Alex"), person("Jo"), person("Kim")]),
            "Sam, Alex and 2 more"
        );
        let nameless = PlaylistPerson {
            id: "x".into(),
            username: "x".into(),
            name: String::new(),
        };
        assert_eq!(names(&[nameless]), "x");
    }
}
