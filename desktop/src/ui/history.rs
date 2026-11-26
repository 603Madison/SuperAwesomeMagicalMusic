//! History, as the web player has it (web/src/pages/History.tsx): what you've listened to on
//! every device, by day, a hundred at a time, with your Wrapped above it and clearing it all.

use std::cell::RefCell;
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;

use crate::api::{HISTORY_PAGE, Listen, Track};
use crate::ui::ctx;
use crate::ui::forms::{clear, confirm, run};
use crate::ui::widgets::{heading, loading, nav_page, page_box, page_title, scrolled, status, track_list};

/// "Today", "Yesterday", or "Tuesday, March 4".
fn day_label(played_at: i64, today: &glib::DateTime) -> String {
    let Ok(date) = glib::DateTime::from_unix_local(played_at / 1000) else {
        return String::new();
    };
    let same = |a: &glib::DateTime, b: &glib::DateTime| a.ymd() == b.ymd();
    if same(&date, today) {
        return "Today".into();
    }
    if today.add_days(-1).is_ok_and(|y| same(&date, &y)) {
        return "Yesterday".into();
    }
    date.format("%A, %B %-e").map(|s| s.to_string()).unwrap_or_default()
}

/// [listens] by day, newest first.
fn by_day(listens: &[Listen], today: &glib::DateTime) -> Vec<(String, Vec<Track>)> {
    let mut days: Vec<(String, Vec<Track>)> = vec![];
    for listen in listens {
        let day = day_label(listen.played_at, today);
        if days.last().is_none_or(|(d, _)| *d != day) {
            days.push((day, vec![]));
        }
        if let Some((_, tracks)) = days.last_mut() {
            tracks.push(listen.track.clone());
        }
    }
    days
}

/// A banner to the year's Wrapped.
fn wrapped_banner() -> gtk::Button {
    let row = gtk::Box::builder().spacing(14).build();
    row.append(&gtk::Image::builder().icon_name("starred-symbolic").pixel_size(22).build());
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).hexpand(true).build();
    let year = glib::DateTime::now_local().map(|d| d.year()).unwrap_or_default();
    let title = gtk::Label::builder().label(format!("Your {year} Wrapped")).xalign(0.0).build();
    title.add_css_class("banner-title");
    text.append(&title);
    let sub = gtk::Label::builder()
        .label("Top songs, artists and stations, and your year's story")
        .xalign(0.0)
        .wrap(true)
        .build();
    sub.add_css_class("banner-sub");
    text.append(&sub);
    row.append(&text);
    row.append(&gtk::Image::from_icon_name("go-next-symbolic"));
    let button = gtk::Button::builder().child(&row).margin_bottom(8).build();
    button.add_css_class("wrapped-banner");
    button.connect_clicked(|_| crate::ui::window::show_section("Wrapped"));
    button
}

pub fn history_page() -> adw::NavigationPage {
    crate::ui::style::ensure();
    let content = page_box();
    let top = gtk::Box::builder().spacing(12).build();
    let title = page_title("History", "What you've listened to, on every device.");
    title.set_hexpand(true);
    top.append(&title);
    let clear_button = crate::ui::widgets::button(None, "Clear history", "ghost");
    clear_button.set_valign(gtk::Align::Center);
    clear_button.set_visible(false);
    top.append(&clear_button);
    content.append(&top);
    let holder = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    content.append(&holder);
    let (page, _) = nav_page("History", &scrolled(&content));
    let listens: Rc<RefCell<Vec<Listen>>> = Rc::default();

    let show: Rc<dyn Fn()> = {
        let (holder, listens, clear_button) = (holder.clone(), listens.clone(), clear_button.clone());
        Rc::new(move || {
            clear(&holder);
            let all = listens.borrow();
            clear_button.set_visible(!all.is_empty());
            if all.is_empty() {
                holder.append(&status(
                    "document-open-recent-symbolic",
                    "Nothing yet",
                    "Songs show up here once you've listened to a good part of them, on any device.",
                ));
                return;
            }
            holder.append(&wrapped_banner());
            let Ok(today) = glib::DateTime::now_local() else { return };
            for (day, tracks) in by_day(&all, &today) {
                holder.append(&heading(&day));
                holder.append(&track_list(Rc::new(tracks), false));
            }
        })
    };
    // "Show older", while there may be more.
    let more = crate::ui::widgets::button(None, "Show older", "tonal");
    more.set_halign(gtk::Align::Center);
    more.set_margin_top(16);
    more.set_visible(false);
    content.append(&more);
    let load: Rc<dyn Fn()> = {
        let (listens, show, more, holder) = (listens.clone(), show.clone(), more.clone(), holder.clone());
        Rc::new(move || {
            let Some(api) = ctx().api() else { return };
            let before = listens.borrow().last().map(|l| l.played_at);
            if before.is_none() {
                holder.append(&loading());
            }
            more.set_sensitive(false);
            let (listens, show, more, holder) = (listens.clone(), show.clone(), more.clone(), holder.clone());
            run(async move { api.history_before(before).await }, move |result| {
                more.set_sensitive(true);
                match result {
                    Ok(older) => {
                        listens.borrow_mut().extend(older);
                        let count = listens.borrow().len();
                        more.set_visible(count >= HISTORY_PAGE && count % HISTORY_PAGE == 0);
                        show();
                    }
                    Err(e) => {
                        if before.is_none() {
                            clear(&holder);
                            holder.append(&status("dialog-warning-symbolic", "Couldn't load this", &e.to_string()));
                        }
                        ctx().failed(&e);
                    }
                }
            });
        })
    };
    let l = load.clone();
    more.connect_clicked(move |_| l());
    load();

    clear_button.connect_clicked(move |_| {
        let (listens, show, more) = (listens.clone(), show.clone(), more.clone());
        confirm(
            "Clear your history?",
            "Everything you've listened to is forgotten, on every device.",
            "Cancel",
            "Clear",
            move || {
                let Some(api) = ctx().api() else { return };
                let (listens, show, more) = (listens.clone(), show.clone(), more.clone());
                run(async move { api.clear_history().await }, move |result| match result {
                    Ok(_) => {
                        listens.borrow_mut().clear();
                        more.set_visible(false);
                        show();
                    }
                    Err(e) => ctx().failed(&e),
                });
            },
        );
    });
    page
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn listens_are_grouped_by_day() {
        let today = glib::DateTime::from_local(2026, 11, 26, 15, 0, 0.0).unwrap();
        let at = |d: i32, h: i32| glib::DateTime::from_local(2026, 11, d, h, 0, 0.0).unwrap().to_unix() * 1000;
        let listen = |played_at: i64, title: &str| Listen {
            track: Track {
                title: title.into(),
                ..Default::default()
            },
            played_at,
            listened_ms: 0,
            client: "desktop".into(),
            station: None,
        };
        let days = by_day(
            &[
                listen(at(26, 14), "a"),
                listen(at(26, 9), "b"),
                listen(at(25, 20), "c"),
                listen(at(20, 8), "d"),
            ],
            &today,
        );
        let labels: Vec<&str> = days.iter().map(|(d, _)| d.as_str()).collect();
        assert_eq!(labels, vec!["Today", "Yesterday", "Friday, November 20"]);
        assert_eq!(days[0].1.len(), 2);
    }
}
