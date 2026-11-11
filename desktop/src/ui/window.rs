//! The music: a sidebar of sections (Search, Radio, Playlists, History), their pages (with back to
//! where you were), and the player along the bottom. Narrow windows fold the sidebar away.

use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

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

/// The music's view, and its navigation (for opening artists, albums and playlists).
pub fn music_view() -> (gtk::Widget, adw::NavigationView) {
    let nav = adw::NavigationView::new();
    let content = adw::NavigationPage::builder().title("Music").child(&nav).build();

    // The sidebar.
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::Single).build();
    list.add_css_class("navigation-sidebar");
    for section in &SECTIONS {
        let row = gtk::Box::builder()
            .spacing(12)
            .margin_top(6)
            .margin_bottom(6)
            .margin_start(6)
            .build();
        row.append(&gtk::Image::from_icon_name(section.icon));
        row.append(&gtk::Label::new(Some(section.title)));
        list.append(&row);
    }
    let sidebar_toolbar = adw::ToolbarView::new();
    let sidebar_header = adw::HeaderBar::new();
    let name = gtk::Label::new(Some("SuperAwesomeMagicalMusic"));
    name.add_css_class("sidebar-title");
    sidebar_header.set_title_widget(Some(&name));
    sidebar_header.pack_end(&main_menu());
    sidebar_toolbar.add_top_bar(&sidebar_header);
    let scroll = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .child(&list)
        .vexpand(true)
        .build();
    sidebar_toolbar.set_content(Some(&scroll));
    let sidebar = adw::NavigationPage::builder()
        .title("SuperAwesomeMagicalMusic")
        .child(&sidebar_toolbar)
        .build();

    let split = adw::NavigationSplitView::builder()
        .sidebar(&sidebar)
        .content(&content)
        .min_sidebar_width(200.0)
        .max_sidebar_width(260.0)
        .vexpand(true)
        .build();
    let split_for_rows = split.clone();
    let nav_for_rows = nav.clone();
    list.connect_row_selected(move |_, row| {
        let Some(row) = row else { return };
        if let Some(section) = SECTIONS.get(row.index() as usize) {
            nav_for_rows.replace(&[(section.make)()]);
            split_for_rows.set_show_content(true);
        }
    });
    list.select_row(list.row_at_index(0).as_ref());

    let layout = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    layout.append(&split);
    layout.append(&player_bar::player_bar());

    // Narrow: one pane at a time.
    let window = ctx().window.clone();
    let narrow = adw::Breakpoint::new(adw::BreakpointCondition::parse("max-width: 640sp").expect("a breakpoint"));
    narrow.add_setter(&split, "collapsed", Some(&true.to_value()));
    window.add_breakpoint(narrow);

    (layout.upcast(), nav)
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

/// Quality, and the account.
pub fn preferences(ctx: &Rc<Ctx>) {
    let dialog = adw::PreferencesDialog::new();
    let page = adw::PreferencesPage::new();

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
        .subtitle("Songs above this are converted down on your server, or a smaller copy is picked")
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
