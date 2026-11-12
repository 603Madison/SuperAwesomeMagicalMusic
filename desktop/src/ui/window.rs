//! The music: a sidebar of sections (Search, Radio, Playlists, History), their pages (with back to
//! where you were), and the player along the bottom. Narrow windows fold the sidebar away.

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

use crate::api::{PlaylistSummary, Track};
use crate::rt::bg;
use crate::ui::widgets::{artwork, mosaic, remember_playlists, track_key};
use crate::ui::{Ctx, ctx, pages, player_bar};

struct Section {
    title: &'static str,
    icon: &'static str,
    make: fn() -> adw::NavigationPage,
}

const SECTIONS: [Section; 4] = [
    Section {
        title: "Search",
        icon: "system-search-symbolic",
        make: pages::search_page,
    },
    Section {
        title: "Radio",
        icon: "network-wireless-signal-excellent-symbolic",
        make: pages::radio_page,
    },
    Section {
        title: "Playlists",
        icon: "view-list-bullet-symbolic",
        make: pages::playlists_page,
    },
    Section {
        title: "History",
        icon: "document-open-recent-symbolic",
        make: pages::history_page,
    },
];

thread_local! {
    /// Fills the sidebar's radio and playlists again (after a change elsewhere).
    static REFRESH_SIDEBAR: RefCell<Option<Rc<dyn Fn()>>> = RefCell::new(None);
    /// Marks the section or playlist that's open.
    static MARK_SIDEBAR: RefCell<Option<Rc<dyn Fn()>>> = RefCell::new(None);
    /// Your favourite channels and stations (as keys), for the player's star.
    static FAVOURITES: RefCell<std::collections::HashSet<String>> = RefCell::new(Default::default());
}

/// Whether a live channel or station (its key) is among your favourites.
pub fn is_favourite(key: &str) -> bool {
    FAVOURITES.with(|f| f.borrow().contains(key))
}

fn mark_sidebar() {
    if let Some(mark) = MARK_SIDEBAR.with(|r| r.borrow().clone()) {
        mark();
    }
}

/// Shows your favourite stations and playlists in the sidebar as they are now.
pub fn refresh_sidebar() {
    if let Some(refresh) = REFRESH_SIDEBAR.with(|r| r.borrow().clone()) {
        refresh();
    }
}

/// A row of the sidebar's lists: a small cover and a name.
fn side_row(cover: &gtk::Widget, name: &str) -> gtk::ListBoxRow {
    let line = gtk::Box::builder().spacing(10).build();
    line.append(cover);
    let label = gtk::Label::builder()
        .label(name)
        .xalign(0.0)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .hexpand(true)
        .build();
    line.append(&label);
    gtk::ListBoxRow::builder().child(&line).tooltip_text(name).build()
}

fn sidebar_heading(text: &str) -> gtk::Label {
    let label = gtk::Label::builder().label(text.to_uppercase()).xalign(0.0).visible(false).build();
    label.add_css_class("sidebar-heading");
    label
}

/// The music's view, and its navigation (for opening artists, albums and playlists).
pub fn music_view() -> (gtk::Widget, adw::NavigationView) {
    let nav = adw::NavigationView::new();
    let content = adw::NavigationPage::builder().title("Music").child(&nav).build();

    // The sidebar, as the web player's: the brand, the sections as pills, your radio, your
    // playlists, and who's signed in.
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::Single).build();
    list.add_css_class("nav-list");
    for section in &SECTIONS {
        let row = gtk::Box::builder().spacing(14).build();
        row.append(&gtk::Image::from_icon_name(section.icon));
        row.append(&gtk::Label::new(Some(section.title)));
        list.append(&row);
    }
    column.append(&list);
    let radio_heading = sidebar_heading("Your radio");
    let radio_list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::Single).build();
    radio_list.add_css_class("side-list");
    column.append(&radio_heading);
    column.append(&radio_list);
    let playlists_heading = sidebar_heading("Your playlists");
    let playlists_list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::Single).build();
    playlists_list.add_css_class("side-list");
    column.append(&playlists_heading);
    column.append(&playlists_list);

    let sidebar_toolbar = adw::ToolbarView::new();
    sidebar_toolbar.add_css_class("sidebar-pane");
    let sidebar_header = adw::HeaderBar::builder().show_title(false).build();
    let brand = gtk::Box::builder().spacing(10).margin_start(6).build();
    let icon = gtk::Image::builder().icon_name(crate::APP_ID).pixel_size(32).build();
    brand.append(&icon);
    let name = gtk::Label::builder().label("SuperAwesome\nMagicalMusic").xalign(0.0).build();
    name.add_css_class("brand-name");
    brand.append(&name);
    sidebar_header.pack_start(&brand);
    sidebar_header.pack_end(&main_menu());
    sidebar_toolbar.add_top_bar(&sidebar_header);
    let scroll = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .child(&column)
        .vexpand(true)
        .build();
    sidebar_toolbar.set_content(Some(&scroll));
    let who = ctx().config.borrow().user.as_ref().map(|u| {
        if u.display_name.is_empty() {
            u.username.clone()
        } else {
            u.display_name.clone()
        }
    });
    if let Some(who) = who {
        let user = gtk::Label::builder()
            .label(who)
            .xalign(0.0)
            .ellipsize(gtk::pango::EllipsizeMode::End)
            .build();
        user.add_css_class("sidebar-user");
        sidebar_toolbar.add_bottom_bar(&user);
    }
    let sidebar = adw::NavigationPage::builder()
        .title("SuperAwesomeMagicalMusic")
        .child(&sidebar_toolbar)
        .build();

    let split = adw::NavigationSplitView::builder()
        .sidebar(&sidebar)
        .content(&content)
        .min_sidebar_width(220.0)
        .max_sidebar_width(270.0)
        .vexpand(true)
        .build();
    // Choosing a section starts its pages afresh (unless the sidebar is only being marked).
    let quiet = Rc::new(Cell::new(false));
    let split_for_rows = split.clone();
    let nav_for_rows = nav.clone();
    let q = quiet.clone();
    list.connect_row_selected(move |_, row| {
        let Some(row) = row else { return };
        if q.get() {
            return;
        }
        let index = row.index() as usize;
        if let Some(section) = SECTIONS.get(index) {
            let page = (section.make)();
            page.set_widget_name(&format!("section:{index}"));
            nav_for_rows.replace(&[page]);
            split_for_rows.set_show_content(true);
            refresh_sidebar();
        }
    });

    // Your radio and your playlists: click one to play it, or to open it.
    let stations: Rc<RefCell<Vec<Track>>> = Rc::default();
    let playlists: Rc<RefCell<Vec<PlaylistSummary>>> = Rc::default();
    let s = stations.clone();
    radio_list.connect_row_activated(move |_, row| {
        if let Some(station) = s.borrow().get(row.index() as usize) {
            ctx().player.play_tracks(vec![station.clone()], 0);
        }
    });
    let (p, split_for_playlists) = (playlists.clone(), split.clone());
    playlists_list.connect_row_activated(move |_, row| {
        if let Some(playlist) = p.borrow().get(row.index() as usize) {
            ctx().open_playlist(&playlist.id, &playlist.name);
            split_for_playlists.set_show_content(true);
        }
    });
    let refresh = {
        let (radio_list, radio_heading, stations) = (radio_list.clone(), radio_heading.clone(), stations.clone());
        let (playlists_list, playlists_heading, playlists) = (playlists_list.clone(), playlists_heading.clone(), playlists.clone());
        move || {
            let Some(api) = ctx().api() else { return };
            let (radio_list, radio_heading, stations) = (radio_list.clone(), radio_heading.clone(), stations.clone());
            let favourites_api = api.clone();
            glib::spawn_future_local(async move {
                let Ok(found) = bg(async move { favourites_api.live_favorites().await }).await else {
                    return;
                };
                let live: Vec<Track> = found.into_iter().filter(Track::is_live).collect();
                clear_list(&radio_list);
                for station in &live {
                    radio_list.append(&side_row(&artwork(station.artwork_url.as_deref(), 32, false), &station.title));
                }
                radio_heading.set_visible(!live.is_empty());
                FAVOURITES.with(|f| f.replace(live.iter().map(track_key).collect()));
                stations.replace(live);
                // The station playing, marked again in the new list.
                ctx().player.nudge();
            });
            let (playlists_list, playlists_heading, playlists) = (playlists_list.clone(), playlists_heading.clone(), playlists.clone());
            glib::spawn_future_local(async move {
                let Ok(mut found) = bg(async move { api.playlists().await }).await else {
                    return;
                };
                // Most recently played first, as on the web.
                found.sort_by(|a, b| {
                    b.played_at
                        .unwrap_or(0)
                        .cmp(&a.played_at.unwrap_or(0))
                        .then_with(|| a.name.cmp(&b.name))
                });
                clear_list(&playlists_list);
                for playlist in &found {
                    playlists_list.append(&side_row(&mosaic(&playlist.artwork, 32), &playlist.name));
                }
                playlists_heading.set_visible(!found.is_empty());
                remember_playlists(&found);
                playlists.replace(found);
                mark_sidebar();
            });
        }
    };
    REFRESH_SIDEBAR.with(|r| r.replace(Some(Rc::new(refresh))));
    list.select_row(list.row_at_index(0).as_ref());

    // As on the web: the playlist that's open is marked (and no section), and so is the station
    // that's playing.
    let marks = {
        let (list, playlists_list, playlists, nav, quiet) =
            (list.clone(), playlists_list.clone(), playlists.clone(), nav.clone(), quiet.clone());
        move || {
            let name = nav.visible_page().map(|p| p.widget_name().to_string()).unwrap_or_default();
            let open = name.strip_prefix("playlist:").map(str::to_string);
            quiet.set(true);
            if open.is_some() {
                list.unselect_all();
            } else if let Some(index) = name.strip_prefix("section:").and_then(|i| i.parse::<i32>().ok()) {
                list.select_row(list.row_at_index(index).as_ref());
            }
            quiet.set(false);
            let at = open.and_then(|id| playlists.borrow().iter().position(|p| p.id == id));
            match at.and_then(|i| playlists_list.row_at_index(i as i32)) {
                Some(row) => playlists_list.select_row(Some(&row)),
                None => playlists_list.unselect_all(),
            }
        }
    };
    let marks = Rc::new(marks);
    let m = marks.clone();
    nav.connect_visible_page_notify(move |_| m());
    MARK_SIDEBAR.with(|r| r.replace(Some(marks)));
    let (radio_list_weak, stations_marks) = (radio_list.downgrade(), Rc::downgrade(&stations));
    let playing_station: Rc<RefCell<Option<String>>> = Rc::default();
    ctx().player.subscribe(move |state| {
        // Gone after signing out.
        let (Some(radio_list_marks), Some(stations_marks)) = (radio_list_weak.upgrade(), stations_marks.upgrade()) else {
            return;
        };
        let now = state.current().filter(|t| t.is_live()).map(track_key);
        if *playing_station.borrow() == now && radio_list_marks.selected_row().is_some() == now.is_some() {
            return;
        }
        playing_station.replace(now.clone());
        let at = now.and_then(|key| stations_marks.borrow().iter().position(|t| track_key(t) == key));
        match at.and_then(|i| radio_list_marks.row_at_index(i as i32)) {
            Some(row) => radio_list_marks.select_row(Some(&row)),
            None => radio_list_marks.unselect_all(),
        }
    });

    let layout = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    layout.append(&split);
    let (bar, wide_only) = player_bar::player_bar();
    layout.append(&bar);

    // Narrow (as the web player's narrow layout): one pane at a time, and a smaller player bar.
    let window = ctx().window.clone();
    let narrow = adw::Breakpoint::new(adw::BreakpointCondition::parse("max-width: 760sp").expect("a breakpoint"));
    narrow.add_setter(&split, "collapsed", Some(&true.to_value()));
    for widget in wide_only {
        narrow.add_setter(&widget, "visible", Some(&false.to_value()));
    }
    window.add_breakpoint(narrow);

    (layout.upcast(), nav)
}

fn clear_list(list: &gtk::ListBox) {
    while let Some(child) = list.first_child() {
        list.remove(&child);
    }
}

fn main_menu() -> gtk::MenuButton {
    let menu = gio::Menu::new();
    menu.append(Some("Preferences"), Some("app.preferences"));
    menu.append(Some("About SuperAwesomeMagicalMusic"), Some("app.about"));
    let account = gio::Menu::new();
    account.append(Some("Sign out"), Some("app.sign-out"));
    menu.append_section(None, &account);
    gtk::MenuButton::builder()
        .icon_name("open-menu-symbolic")
        .menu_model(&menu)
        .tooltip_text("Menu")
        .primary(true)
        .build()
}

/// Saves a change to the look, and shows it.
fn restyle(change: impl FnOnce(&mut crate::config::Config)) {
    let ctx = crate::ui::ctx();
    let (palette, style, bold) = {
        let mut config = ctx.config.borrow_mut();
        change(&mut config);
        config.save();
        (config.palette.clone(), config.style.clone(), config.bold_text)
    };
    // Not while the settings are borrowed: turning dark reads them.
    crate::ui::theme::apply(&palette, &style, bold);
}

/// The theme (as two-colour swatches), light or dark, and bold text, as on the web and the phone.
fn appearance(ctx: &Rc<Ctx>) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder().title("Appearance").build();
    let config = ctx.config.borrow();

    let themes = gtk::FlowBox::builder()
        .selection_mode(gtk::SelectionMode::None)
        .column_spacing(8)
        .row_spacing(8)
        .min_children_per_line(2)
        .max_children_per_line(4)
        .margin_top(10)
        .margin_bottom(10)
        .margin_start(10)
        .margin_end(10)
        .build();
    let mut first: Option<gtk::ToggleButton> = None;
    for palette in &crate::ui::theme::PALETTES {
        let content = gtk::Box::builder().spacing(10).build();
        let swatch = gtk::Box::builder().valign(gtk::Align::Center).build();
        swatch.add_css_class("swatch");
        swatch.add_css_class(&format!("swatch-{}", palette.id));
        content.append(&swatch);
        content.append(&gtk::Label::new(Some(palette.name)));
        let button = gtk::ToggleButton::builder().child(&content).tooltip_text(palette.name).build();
        button.add_css_class("flat");
        button.add_css_class("theme-choice");
        match &first {
            Some(group) => button.set_group(Some(group)),
            None => first = Some(button.clone()),
        }
        button.set_active(config.palette == palette.id);
        let id = palette.id;
        button.connect_toggled(move |b| {
            if b.is_active() {
                restyle(|c| c.palette = id.to_string());
            }
        });
        themes.append(&button);
    }
    let theme_row = adw::PreferencesRow::builder().activatable(false).build();
    let theme_box = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    let theme_title = gtk::Label::builder()
        .label("Theme")
        .xalign(0.0)
        .margin_top(12)
        .margin_start(12)
        .build();
    theme_box.append(&theme_title);
    theme_box.append(&themes);
    theme_row.set_child(Some(&theme_box));
    group.add(&theme_row);

    let styles = ["system", "light", "dark"];
    let style = adw::ComboRow::builder()
        .title("Style")
        .model(&gtk::StringList::new(&["As the system is", "Light", "Dark"]))
        .selected(styles.iter().position(|s| *s == config.style).unwrap_or(0) as u32)
        .build();
    style.connect_selected_notify(move |row| {
        let chosen = styles.get(row.selected() as usize).copied().unwrap_or("system");
        restyle(|c| c.style = chosen.to_string());
    });
    group.add(&style);

    let bold = adw::SwitchRow::builder()
        .title("Bold text")
        .subtitle("All text in Lexend Bold")
        .active(config.bold_text)
        .build();
    bold.connect_active_notify(move |row| {
        let on = row.is_active();
        restyle(|c| c.bold_text = on);
    });
    group.add(&bold);
    group
}

/// The look, quality, and the account.
pub fn preferences(ctx: &Rc<Ctx>) {
    let dialog = adw::PreferencesDialog::new();
    let page = adw::PreferencesPage::new();
    page.add(&appearance(ctx));

    let playback = adw::PreferencesGroup::builder().title("Playback").build();
    let steps: [u32; 8] = [0, 320, 256, 192, 160, 128, 96, 64];
    let labels: Vec<String> = steps
        .iter()
        .map(|k| {
            if *k == 0 {
                "Original quality".into()
            } else {
                format!("Up to {k} kbps")
            }
        })
        .collect();
    let model = gtk::StringList::new(&labels.iter().map(String::as_str).collect::<Vec<_>>());
    let quality = adw::ComboRow::builder()
        .title("Quality")
        .subtitle("Songs above it are made smaller on your server")
        .model(&model)
        .build();
    let current = ctx.config.borrow().kbps;
    quality.set_selected(steps.iter().position(|k| *k == current).unwrap_or(0) as u32);
    quality.connect_selected_notify(move |row| {
        let ctx = crate::ui::ctx();
        let kbps = steps.get(row.selected() as usize).copied().unwrap_or(0);
        let thorough = {
            let mut config = ctx.config.borrow_mut();
            config.kbps = kbps;
            config.save();
            config.thorough
        };
        ctx.player.set_choices(kbps, thorough);
    });
    playback.add(&quality);
    let thorough = adw::SwitchRow::builder()
        .title("Compare every service for the best copy")
        .subtitle("Starts songs a moment later, but asks every service and picks the best copy that fits your quality")
        .active(ctx.config.borrow().thorough)
        .build();
    thorough.connect_active_notify(move |row| {
        let ctx = crate::ui::ctx();
        let kbps = {
            let mut config = ctx.config.borrow_mut();
            config.thorough = row.is_active();
            config.save();
            config.kbps
        };
        ctx.player.set_choices(kbps, row.is_active());
    });
    playback.add(&thorough);
    page.add(&playback);

    let account = adw::PreferencesGroup::builder().title("Account").build();
    let config = ctx.config.borrow();
    let who = config
        .user
        .as_ref()
        .map(|u| {
            if u.display_name.is_empty() {
                u.username.clone()
            } else {
                format!("{} ({})", u.display_name, u.username)
            }
        })
        .unwrap_or_default();
    let row = crate::ui::widgets::plain_row(who.as_str(), config.server.as_deref().unwrap_or_default());
    let sign_out = gtk::Button::builder().label("Sign out").valign(gtk::Align::Center).build();
    sign_out.add_css_class("destructive-action");
    let d = dialog.clone();
    sign_out.connect_clicked(move |_| {
        d.close();
        crate::ui::ctx().sign_out();
    });
    row.add_suffix(&sign_out);
    account.add(&row);
    page.add(&account);
    drop(config);

    dialog.add(&page);
    dialog.present(Some(&ctx.window));
}

pub fn about(ctx: &Rc<Ctx>) {
    let about = adw::AboutDialog::builder()
        .application_name("SuperAwesomeMagicalMusic")
        .application_icon(crate::APP_ID)
        .version(env!("CARGO_PKG_VERSION"))
        .comments("Your SuperAwesomeMagicalMusic server on the Linux desktop: your library, SiriusXM and radio.")
        .website("https://github.com/603Madison/SuperAwesomeMagicalMusic")
        .license_type(gtk::License::Gpl30Only)
        .build();
    about.present(Some(&ctx.window));
}

/// Keeps a page's loading tied to it: [load] runs once, and its result is shown if the page
/// still exists.
pub fn load_into<T: 'static>(
    holder: &gtk::Box,
    load: impl std::future::Future<Output = anyhow::Result<T>> + 'static,
    show: impl FnOnce(&gtk::Box, T) + 'static,
) {
    holder.append(&crate::ui::widgets::loading());
    let weak = holder.downgrade();
    glib::spawn_future_local(async move {
        let result = load.await;
        let Some(holder) = weak.upgrade() else { return };
        while let Some(child) = holder.first_child() {
            holder.remove(&child);
        }
        match result {
            Ok(value) => show(&holder, value),
            Err(e) => {
                holder.append(&crate::ui::widgets::status(
                    "dialog-warning-symbolic",
                    "Couldn't load this",
                    &e.to_string(),
                ));
                ctx().failed(&e);
            }
        }
    });
}
