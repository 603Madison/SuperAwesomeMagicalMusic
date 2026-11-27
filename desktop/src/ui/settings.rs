//! Preferences beyond the look and the quality, as the web player's Settings has them: your
//! sources, your account (name, password), where you're signed in (and signing a device in with
//! its code), Claude's connector and what was asked of Lidarr.

use std::rc::Rc;

use adw::prelude::*;
use gtk::{gdk, glib};

use crate::api::{DeviceRequest, SessionInfo, User};
use crate::ui::ctx;
use crate::ui::forms::{boxed_list, clear, failed_near, icon_button, note, run, text_field, toast_near, when};
use crate::ui::sources::{Scope, sources_section};

/// "The DJ talks", under Playback.
pub fn more_playback(group: &adw::PreferencesGroup) {
    let speaks = crate::ui::dj::can_speak();
    let row = adw::SwitchRow::builder()
        .title("The DJ talks")
        .subtitle(if speaks {
            "On your own stations (Radio › Made for you), the DJ introduces songs and gives the time, over the music. Off: what it says is only shown."
        } else {
            "On your own stations, the DJ introduces songs over the music. Needs the desktop's speech (speech-dispatcher's spd-say): what it says is only shown till it's installed."
        })
        .active(ctx().config.borrow().dj_voice)
        .build();
    row.connect_active_notify(|row| {
        let ctx = ctx();
        {
            let mut config = ctx.config.borrow_mut();
            config.dj_voice = row.is_active();
            config.save();
        }
        if !row.is_active() {
            crate::ui::dj::quiet();
        }
    });
    group.add(&row);
}

/// The Sources and Account pages, after General.
pub fn add_pages(dialog: &adw::PreferencesDialog) {
    crate::ui::style::ensure();
    let sources = adw::PreferencesPage::builder()
        .title("Sources")
        .icon_name("samm-music-symbolic")
        .name("sources")
        .build();
    let group = adw::PreferencesGroup::builder()
        .title("Sources")
        .description("Where your music comes from. Searches ask all of them; playback prefers your library, then the best copy within your quality setting.")
        .build();
    group.add(&sources_section(Scope::Personal));
    sources.add(&group);
    dialog.add(&sources);

    let account = adw::PreferencesPage::builder()
        .title("Account")
        .icon_name("samm-user-symbolic")
        .name("account")
        .build();
    let you = adw::PreferencesGroup::builder().title("Account").build();
    let you_box = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(16).build();
    you.add(&you_box);
    account.add(&you);
    show_account(&you_box);

    let signed_in = adw::PreferencesGroup::builder().title("Signed in on").build();
    let devices = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).build();
    signed_in.add(&devices);
    account.add(&signed_in);
    show_devices(&devices);

    let connector = adw::PreferencesGroup::builder().title("Claude connector").build();
    let connector_box = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).build();
    connector.add(&connector_box);
    account.add(&connector);
    show_connector(&connector_box, devices.clone());

    let lidarr = adw::PreferencesGroup::builder().title("Lidarr").build();
    let lidarr_box = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    lidarr.add(&lidarr_box);
    account.add(&lidarr);
    show_lidarr(&lidarr_box);
    dialog.add(&account);
}

/// Your name, and changing your password (or that single sign-on looks after it).
fn show_account(into: &gtk::Box) {
    let Some(api) = ctx().api() else { return };
    into.append(&crate::ui::widgets::loading());
    let into = into.clone();
    run(async move { api.me().await }, move |result| {
        clear(&into);
        let user = match result {
            Ok(me) => me.user,
            Err(e) => {
                into.append(&note(&e.to_string()));
                return failed_near(&into, &e);
            }
        };
        // Kept for the sidebar and the Admin section.
        {
            let ctx = ctx();
            let mut config = ctx.config.borrow_mut();
            config.user = Some(user.clone());
            config.save();
        }
        into.append(&profile(&user));
        if user.has_password {
            into.append(&password_change());
        } else if user.oidc {
            into.append(&note("You sign in through single sign-on; your password is managed there."));
        }
    });
}

fn profile(user: &User) -> gtk::Box {
    let help = format!("Username: {}{}", user.username, if user.is_admin() { " · Admin" } else { "" });
    let (name_field, name) = text_field("Name", Some(&help), &user.display_name, false);
    name_field.set_hexpand(true);
    let row = gtk::Box::builder().spacing(10).build();
    row.append(&name_field);
    let save = gtk::Button::builder()
        .label("Save")
        .valign(gtk::Align::Center)
        .sensitive(false)
        .build();
    save.add_css_class("pill");
    let saved = Rc::new(std::cell::RefCell::new(user.display_name.clone()));
    let (s, b) = (saved.clone(), save.clone());
    name.connect_changed(move |e| b.set_sensitive(e.text().trim() != s.borrow().as_str()));
    save.connect_clicked(move |button| {
        let Some(api) = ctx().api() else { return };
        let typed = name.text().trim().to_string();
        let (saved, button) = (saved.clone(), button.clone());
        button.set_sensitive(false);
        run(async move { api.update_profile(&typed).await }, move |result| match result {
            Ok(user) => {
                saved.replace(user.display_name.clone());
                {
                    let ctx = ctx();
                    let mut config = ctx.config.borrow_mut();
                    config.user = Some(user);
                    config.save();
                }
                toast_near(&button, "Saved");
            }
            Err(e) => {
                button.set_sensitive(true);
                failed_near(&button, &e);
            }
        });
    });
    row.append(&save);
    row
}

fn password_change() -> gtk::Box {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).build();
    let title = gtk::Label::builder().label("Change password").xalign(0.0).build();
    title.add_css_class("heading");
    column.append(&title);
    let (current_field, current) = text_field("Current password", None, "", true);
    let (next_field, next) = text_field("New password", Some("At least 8 characters."), "", true);
    column.append(&current_field);
    column.append(&next_field);
    let change = gtk::Button::builder().label("Change password").halign(gtk::Align::Start).build();
    change.add_css_class("pill");
    change.connect_clicked(move |button| {
        let (old, new) = (current.text().to_string(), next.text().to_string());
        if old.is_empty() || new.chars().count() < 8 {
            toast_near(button, "The new password needs at least 8 characters");
            return;
        }
        let Some(api) = ctx().api() else { return };
        let (button, current, next) = (button.clone(), current.clone(), next.clone());
        button.set_sensitive(false);
        run(async move { api.change_password(&old, &new).await }, move |result| {
            button.set_sensitive(true);
            match result {
                Ok(_) => {
                    current.set_text("");
                    next.set_text("");
                    toast_near(&button, "Password changed. Other devices were signed out.");
                }
                Err(e) => failed_near(&button, &e),
            }
        });
    });
    column.append(&change);
    column
}

fn session_icon(session: &SessionInfo) -> &'static str {
    match session.kind.as_str() {
        "APP" => "samm-smartphone-symbolic",
        k if k.starts_with("MCP") => "samm-bot-symbolic",
        _ => "samm-laptop-symbolic",
    }
}

/// Where you're signed in (each can be signed out), signing a device in with its code, and
/// signing out here.
fn show_devices(into: &gtk::Box) {
    clear(into);
    let Some(api) = ctx().api() else { return };
    into.append(&crate::ui::widgets::loading());
    let holder = into.clone();
    run(async move { api.sessions().await }, move |result| {
        clear(&holder);
        let sessions = match result {
            Ok(sessions) => sessions,
            Err(e) => {
                holder.append(&note(&e.to_string()));
                return failed_near(&holder, &e);
            }
        };
        let list = boxed_list();
        for session in sessions {
            let made = if session.is_connector() { "Link made" } else { "Signed in" };
            let row = crate::ui::widgets::plain_row(
                &session.name,
                &format!("{made} {} · last used {}", when(session.created_at), when(session.last_seen_at)),
            );
            row.add_prefix(&gtk::Image::from_icon_name(session_icon(&session)));
            if session.current {
                let badge = gtk::Label::builder().label("This computer").valign(gtk::Align::Center).build();
                badge.add_css_class("badge");
                row.add_suffix(&badge);
            } else {
                let tooltip = if session.is_connector() {
                    "Remove this connector link"
                } else {
                    "Sign out this device"
                };
                let out = icon_button("samm-log-out-symbolic", tooltip);
                let (id, row_for, list_for) = (session.id.clone(), row.clone(), list.clone());
                out.connect_clicked(move |button| {
                    let Some(api) = ctx().api() else { return };
                    let (id, row, list, button) = (id.clone(), row_for.clone(), list_for.clone(), button.clone());
                    run(async move { api.revoke_session(&id).await }, move |result| match result {
                        Ok(_) => list.remove(&row),
                        Err(e) => failed_near(&button, &e),
                    });
                });
                row.add_suffix(&out);
            }
            list.append(&row);
        }
        holder.append(&list);
        let buttons = gtk::Box::builder().spacing(8).build();
        // A flip phone, or anything awkward to type a password on, shows a code to type here.
        let link = crate::ui::widgets::button(Some("samm-smartphone-symbolic"), "Sign in a device with a code", "tonal");
        let devices = holder.clone();
        link.connect_clicked(move |button| link_device(button, devices.clone()));
        buttons.append(&link);
        let out = crate::ui::widgets::button(Some("samm-log-out-symbolic"), "Sign out here", "ghost");
        out.connect_clicked(|button| {
            if let Some(dialog) = button.ancestor(adw::Dialog::static_type()).and_downcast::<adw::Dialog>() {
                dialog.close();
            }
            ctx().sign_out();
        });
        buttons.append(&out);
        holder.append(&buttons);
    });
}

/// "bcdf ghjk" as "BCDF-GHJK": capitals, the dash put in, nothing else.
pub fn tidy_code(typed: &str) -> String {
    let raw: String = typed
        .chars()
        .filter(char::is_ascii_alphanumeric)
        .map(|c| c.to_ascii_uppercase())
        .take(8)
        .collect();
    if raw.len() > 4 {
        format!("{}-{}", &raw[..4], &raw[4..])
    } else {
        raw
    }
}

/// One page shown at a time, by name (a GtkStack sizes wrapping text oddly in a dialog).
#[derive(Clone)]
struct Pages {
    holder: gtk::Box,
    pages: Rc<std::cell::RefCell<Vec<(String, gtk::Widget)>>>,
}

impl Pages {
    fn new() -> Pages {
        Pages {
            holder: gtk::Box::builder().orientation(gtk::Orientation::Vertical).build(),
            pages: Rc::default(),
        }
    }

    fn add_named(&self, page: &impl IsA<gtk::Widget>, name: Option<&str>) {
        page.set_visible(self.pages.borrow().is_empty());
        self.holder.append(page);
        self.pages.borrow_mut().push((name.unwrap_or_default().to_string(), page.clone().upcast()));
    }

    fn set_visible_child_name(&self, name: &str) {
        for (page, widget) in self.pages.borrow().iter() {
            widget.set_visible(page == name);
        }
    }
}

/// Signing in another device with the code it shows: a flip phone (or anything awkward to type
/// a password on) is signed in to this account once you allow it.
fn link_device(near: &gtk::Button, devices: gtk::Box) {
    let dialog = adw::Dialog::builder().title("Sign in a device").content_width(440).build();
    let stack = Pages::new();
    let page = |title: &str, text: &str| {
        let column = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(14)
            .margin_top(8)
            .margin_bottom(28)
            .margin_start(28)
            .margin_end(28)
            .build();
        let icon = gtk::Image::builder()
            .icon_name("samm-smartphone-symbolic")
            .pixel_size(36)
            .halign(gtk::Align::Center)
            .build();
        let circle = gtk::Box::builder().halign(gtk::Align::Center).valign(gtk::Align::Center).build();
        circle.add_css_class("empty-icon");
        icon.set_hexpand(true);
        circle.append(&icon);
        column.append(&circle);
        let name = gtk::Label::builder()
            .label(title)
            .wrap(true)
            .max_width_chars(40)
            .justify(gtk::Justification::Center)
            .build();
        name.add_css_class("login-title");
        column.append(&name);
        let line = gtk::Label::builder()
            .label(text)
            .wrap(true)
            .max_width_chars(40)
            .use_markup(true)
            .justify(gtk::Justification::Center)
            .build();
        line.add_css_class("login-sub");
        column.append(&line);
        (column, name, line)
    };
    let who = ctx().config.borrow().user.clone();
    let (who_name, who_username) = who.map(|u| (u.name().to_string(), u.username.clone())).unwrap_or_default();

    // Typing the code.
    let (ask, _, _) = page("Sign in a device", "Type the code the device shows.");
    let code = gtk::Entry::builder()
        .placeholder_text("XXXX-XXXX")
        .max_length(9)
        .activates_default(true)
        .xalign(0.5)
        .build();
    code.add_css_class("code-entry");
    let tidying = Rc::new(std::cell::Cell::new(false));
    let t = tidying.clone();
    code.connect_changed(move |entry| {
        if t.replace(true) {
            return;
        }
        let tidy = tidy_code(&entry.text());
        if tidy != entry.text() {
            entry.set_text(&tidy);
            entry.set_position(-1);
        }
        t.set(false);
    });
    ask.append(&code);
    let error = note("");
    error.set_visible(false);
    error.add_css_class("error");
    ask.append(&error);
    let go = gtk::Button::builder().label("Continue").sensitive(false).build();
    go.add_css_class("suggested-action");
    go.add_css_class("pill");
    let g = go.clone();
    code.connect_changed(move |entry| g.set_sensitive(entry.text().len() == 9));
    ask.append(&go);
    let signed_as = note(&format!("Signed in as {who_name} ({who_username})."));
    signed_as.set_justify(gtk::Justification::Center);
    signed_as.set_halign(gtk::Align::Center);
    ask.append(&signed_as);
    stack.add_named(&ask, Some("ask"));

    // Allowing it.
    let (decide, _, decide_line) = page("Sign in this device?", "");
    let shown_code = gtk::Label::builder().halign(gtk::Align::Center).build();
    shown_code.add_css_class("link-code");
    decide.append(&shown_code);
    let decide_error = note("");
    decide_error.set_visible(false);
    decide.append(&decide_error);
    let allow = gtk::Button::builder().label("Allow").build();
    allow.add_css_class("suggested-action");
    allow.add_css_class("pill");
    let deny = gtk::Button::builder().label("Deny").build();
    deny.add_css_class("pill");
    deny.add_css_class("flat");
    decide.append(&allow);
    decide.append(&deny);
    let careful = note("Only allow it if the device is in front of you and you started signing in on it yourself.");
    careful.set_justify(gtk::Justification::Center);
    decide.append(&careful);
    stack.add_named(&decide, Some("decide"));

    // Done.
    let (done, done_title, done_line) = page("It's signed in", "");
    let close = gtk::Button::builder().label("Done").build();
    close.add_css_class("suggested-action");
    close.add_css_class("pill");
    let again = gtk::Button::builder().label("Sign in another device").build();
    again.add_css_class("pill");
    again.add_css_class("flat");
    done.append(&close);
    done.append(&again);
    stack.add_named(&done, Some("done"));

    let request: Rc<std::cell::RefCell<Option<DeviceRequest>>> = Rc::default();
    let server = ctx().config.borrow().server.clone().unwrap_or_else(|| "this server".into());
    {
        let (stack, request, code, error, decide_line, shown_code, go_button) = (
            stack.clone(),
            request.clone(),
            code.clone(),
            error.clone(),
            decide_line.clone(),
            shown_code.clone(),
            go.clone(),
        );
        let (who_name, who_username) = (who_name.clone(), who_username.clone());
        go.connect_clicked(move |_| {
            let Some(api) = ctx().api() else { return };
            let typed = code.text().to_string();
            go_button.set_sensitive(false);
            let (stack, request, error, decide_line, shown_code, go_button) = (
                stack.clone(),
                request.clone(),
                error.clone(),
                decide_line.clone(),
                shown_code.clone(),
                go_button.clone(),
            );
            let (who_name, who_username, server) = (who_name.clone(), who_username.clone(), server.clone());
            run(async move { api.device_request(&typed).await }, move |result| {
                go_button.set_sensitive(true);
                match result {
                    Ok(found) => {
                        error.set_visible(false);
                        decide_line.set_markup(&format!(
                            "<b>{}</b> wants to sign in to {} as <b>{}</b> ({}). It will see your playlists and sources.",
                            glib::markup_escape_text(&found.device_name),
                            glib::markup_escape_text(&server),
                            glib::markup_escape_text(&who_name),
                            glib::markup_escape_text(&who_username),
                        ));
                        shown_code.set_label(&found.user_code);
                        request.replace(Some(found));
                        stack.set_visible_child_name("decide");
                    }
                    Err(e) => {
                        error.set_label(&e.to_string());
                        error.set_visible(true);
                    }
                }
            });
        });
    }
    for (button, allowing) in [(allow.clone(), true), (deny.clone(), false)] {
        let (stack, request, decide_error, done_title, done_line, devices) = (
            stack.clone(),
            request.clone(),
            decide_error.clone(),
            done_title.clone(),
            done_line.clone(),
            devices.clone(),
        );
        let who_name = who_name.clone();
        let (allow, deny) = (allow.clone(), deny.clone());
        button.connect_clicked(move |_| {
            let Some(api) = ctx().api() else { return };
            let Some(found) = request.borrow().clone() else { return };
            allow.set_sensitive(false);
            deny.set_sensitive(false);
            let (stack, decide_error, done_title, done_line, devices, who_name) = (
                stack.clone(),
                decide_error.clone(),
                done_title.clone(),
                done_line.clone(),
                devices.clone(),
                who_name.clone(),
            );
            let (allow, deny) = (allow.clone(), deny.clone());
            let user_code = found.user_code.clone();
            run(async move { api.device_decide(&user_code, allowing).await }, move |result| {
                allow.set_sensitive(true);
                deny.set_sensitive(true);
                match result {
                    Ok(_) => {
                        let device = glib::markup_escape_text(&found.device_name);
                        if allowing {
                            done_title.set_label("It's signed in");
                            done_line.set_markup(&format!(
                                "<b>{device}</b> is signed in as {}. It's listed with your devices here, where you can sign it out.",
                                glib::markup_escape_text(&who_name)
                            ));
                            show_devices(&devices);
                        } else {
                            done_title.set_label("Turned down");
                            done_line.set_markup(&format!("<b>{device}</b> won't be signed in."));
                        }
                        stack.set_visible_child_name("done");
                    }
                    Err(e) => {
                        decide_error.set_label(&e.to_string());
                        decide_error.set_visible(true);
                    }
                }
            });
        });
    }
    let d = dialog.clone();
    close.connect_clicked(move |_| {
        d.close();
    });
    let (s, c, r) = (stack.clone(), code.clone(), request.clone());
    again.connect_clicked(move |_| {
        r.replace(None);
        c.set_text("");
        s.set_visible_child_name("ask");
        c.grab_focus();
    });

    let toolbar = adw::ToolbarView::new();
    toolbar.add_top_bar(&adw::HeaderBar::builder().show_title(false).build());
    toolbar.set_content(Some(&stack.holder));
    dialog.set_child(Some(&toolbar));
    dialog.set_default_widget(Some(&go));
    dialog.present(Some(near));
    code.grab_focus();
}

/// A link that lets Claude look at this server (it can't change anything), shown once.
fn show_connector(into: &gtk::Box, devices: gtk::Box) {
    into.append(&note(
        "Lets Claude look at this server to help troubleshoot it: its status and recent warnings, SiriusXM's stream log, stream tests, your devices, search and your listening history. It can only look, never change anything, and sees only what your account sees. In Claude, add it under Settings › Connectors › Add custom connector.",
    ));
    let admin = ctx().config.borrow().user.as_ref().is_some_and(User::is_admin);
    let everyone = adw::SwitchRow::builder()
        .title("See every account")
        .subtitle("As an admin, the link can see every account's sources, SiriusXM, devices and listening history (and the server's log), to troubleshoot anyone's. Off: only yours.")
        .active(true)
        .build();
    if admin {
        let list = boxed_list();
        list.append(&everyone);
        into.append(&list);
    }
    let make = crate::ui::widgets::button(Some("samm-bot-symbolic"), "Make a connector link", "tonal");
    make.set_halign(gtk::Align::Start);
    into.append(&make);
    let holder = into.clone();
    make.connect_clicked(move |button| {
        let Some(api) = ctx().api() else { return };
        let all = admin && everyone.is_active();
        button.set_sensitive(false);
        let (holder, button, devices) = (holder.clone(), button.clone(), devices.clone());
        run(async move { api.mcp_link(all).await }, move |result| match result {
            Ok(url) => {
                clear(&holder);
                let row = gtk::Box::builder().spacing(8).build();
                let text = gtk::Label::builder().label(&url).selectable(true).wrap(true).max_width_chars(40).wrap_mode(gtk::pango::WrapMode::Char).xalign(0.0).hexpand(true).build();
                text.add_css_class("copy-text");
                row.append(&text);
                let copy = crate::ui::widgets::button(Some("samm-copy-symbolic"), "Copy", "ghost");
                copy.set_valign(gtk::Align::Center);
                copy.connect_clicked(move |b| {
                    if let Some(display) = gdk::Display::default() {
                        display.clipboard().set_text(&url);
                    }
                    toast_near(b, "Copied");
                });
                row.append(&copy);
                holder.append(&row);
                holder.append(&note("This link is the key: keep it private. It's shown only now; it's listed above as \"Claude connector\", where you can remove it."));
                show_devices(&devices);
            }
            Err(e) => {
                button.set_sensitive(true);
                failed_near(&button, &e);
            }
        });
    });
}

/// The artists added to Lidarr because their songs were played (when it's set up).
fn show_lidarr(into: &gtk::Box) {
    let Some(api) = ctx().api() else { return };
    let into = into.clone();
    run(async move { api.lidarr_requests().await }, move |result| {
        let requests = result.unwrap_or_default();
        if requests.is_empty() {
            into.append(&note(
                "When you play songs that aren't in your library, their artists are added to Lidarr (if it's set up and adding is on). They'll show here.",
            ));
            return;
        }
        let list = boxed_list();
        for r in requests {
            let row = crate::ui::widgets::plain_row(&r.artist, &format!("{} · {}", r.detail, when(r.updated_at)));
            let label = match r.status.as_str() {
                "added" => "Added",
                "present" => "Already there",
                "not_found" => "Not found",
                _ => "Couldn't add",
            };
            let badge = gtk::Label::builder().label(label).valign(gtk::Align::Center).build();
            badge.add_css_class("badge");
            row.add_suffix(&badge);
            list.append(&row);
        }
        into.append(&list);
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_are_tidied_as_typed() {
        assert_eq!(tidy_code("bcdf ghjk"), "BCDF-GHJK");
        assert_eq!(tidy_code("bc"), "BC");
        assert_eq!(tidy_code("BCDF-GH"), "BCDF-GH");
        assert_eq!(tidy_code("bcdfghjkzz"), "BCDF-GHJK");
    }
}
