//! Admin, for admins (web/src/pages/Admin.tsx): people (adding them, their role, turning them
//! off, setting a password, signing them out, deleting them), the sources shared with everyone,
//! and the server's settings, single sign-on included.

use std::rc::Rc;

use adw::prelude::*;
use gtk::{gdk, gio};
use serde_json::{Value, json};

use crate::api::{AdminSettings, User};
use crate::ui::ctx;
use crate::ui::forms::{Form, boxed_list, clear, confirm, note, run, text_field, toggle, when};
use crate::ui::sources::{Scope, sources_section};
use crate::ui::widgets::{button, heading, loading, nav_page, page_box, page_title, scrolled, status};

pub fn admin_page() -> adw::NavigationPage {
    crate::ui::style::ensure();
    let content = page_box();
    content.append(&page_title("Admin", "Accounts, shared sources and how people sign in."));

    content.append(&heading("People"));
    let people = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).build();
    content.append(&people);
    show_people(&people);

    content.append(&heading("Shared sources"));
    content.append(&note(
        "Everyone on the server can use these (each person can switch them off for themselves).",
    ));
    let sources = sources_section(Scope::Global);
    sources.set_margin_top(6);
    content.append(&sources);

    let server = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    content.append(&server);
    show_server(&server);
    let (page, _) = nav_page("Admin", &scrolled(&content));
    page
}

// ---- People ----

fn show_people(into: &gtk::Box) {
    clear(into);
    let Some(api) = ctx().api() else { return };
    into.append(&loading());
    let into = into.clone();
    run(async move { api.users().await }, move |result| {
        clear(&into);
        let users = match result {
            Ok(users) => users,
            Err(e) => {
                into.append(&status("samm-triangle-alert-symbolic", "Couldn't load this", &e.to_string()));
                return ctx().failed(&e);
            }
        };
        let list = boxed_list();
        for user in &users {
            list.append(&person_row(user, &into));
        }
        into.append(&list);
        let add = button(Some("samm-plus-symbolic"), "Add a person", "tonal");
        add.set_halign(gtk::Align::Start);
        let holder = into.clone();
        add.connect_clicked(move |_| add_person(holder.clone()));
        into.append(&add);
        into.append(&note(
            "People who sign in through single sign-on get an account the first time (if that's on below).",
        ));
    });
}

/// "maddie · break-glass · turned off · Password · Last signed in 3 hr ago".
fn person_line(user: &User) -> String {
    let mut parts = vec![user.username.clone()];
    if user.break_glass {
        parts.push("break-glass".into());
    }
    if user.disabled {
        parts.push("turned off".into());
    }
    parts.push(if user.oidc { "Single sign-on".into() } else { "Password".into() });
    parts.push(match user.last_login_at {
        Some(at) => format!("last signed in {}", when(at)),
        None => "never signed in".into(),
    });
    parts.join(" · ")
}

fn person_row(user: &User, people: &gtk::Box) -> adw::ActionRow {
    let row = crate::ui::widgets::plain_row(user.name(), &person_line(user));
    if user.disabled {
        row.add_css_class("is-off");
    }
    let role = gtk::Label::builder()
        .label(if user.is_admin() { "Admin" } else { "User" })
        .valign(gtk::Align::Center)
        .build();
    role.add_css_class("badge");
    if user.is_admin() {
        role.add_css_class("role-admin");
    }
    row.add_suffix(&role);
    let me = ctx().config.borrow().user.as_ref().map(|u| u.id.clone()).unwrap_or_default();
    if user.id != me {
        row.add_suffix(&person_menu(user, people));
    } else {
        // Where the menu would be, so the badges line up.
        row.add_suffix(&gtk::Box::builder().width_request(34).build());
    }
    row
}

fn person_menu(user: &User, people: &gtk::Box) -> gtk::MenuButton {
    let group = gio::SimpleActionGroup::new();
    let action = |name: &str, run_it: Box<dyn Fn()>| {
        let a = gio::SimpleAction::new(name, None);
        a.connect_activate(move |_, _| run_it());
        group.add_action(&a);
    };
    let change = |change: Value| {
        let (id, people) = (user.id.clone(), people.clone());
        Box::new(move || {
            let Some(api) = ctx().api() else { return };
            let (id, people, change) = (id.clone(), people.clone(), change.clone());
            run(async move { api.update_user(&id, change).await }, move |result| match result {
                Ok(_) => show_people(&people),
                Err(e) => ctx().failed(&e),
            });
        }) as Box<dyn Fn()>
    };
    action("admin", change(json!({ "role": "ADMIN" })));
    action("user", change(json!({ "role": "USER" })));
    action("on", change(json!({ "disabled": false })));
    action("off", change(json!({ "disabled": true })));
    let u = user.clone();
    action("password", Box::new(move || set_password(&u)));
    let (id, username) = (user.id.clone(), user.username.clone());
    action(
        "sign-out",
        Box::new(move || {
            let Some(api) = ctx().api() else { return };
            let (id, username) = (id.clone(), username.clone());
            run(async move { api.sign_out_user(&id).await }, move |result| match result {
                Ok(_) => ctx().toast(&format!("Signed {username} out")),
                Err(e) => ctx().failed(&e),
            });
        }),
    );
    let (id, username, people_for) = (user.id.clone(), user.username.clone(), people.clone());
    action(
        "delete",
        Box::new(move || {
            let (id, people) = (id.clone(), people_for.clone());
            confirm(
                &format!("Delete {username}?"),
                "Their playlists, history and own sources are deleted too. To keep them but stop them signing in, turn the account off instead.",
                "Cancel",
                "Delete",
                move || {
                    let Some(api) = ctx().api() else { return };
                    let (id, people) = (id.clone(), people.clone());
                    run(async move { api.delete_user(&id).await }, move |result| match result {
                        Ok(_) => show_people(&people),
                        Err(e) => ctx().failed(&e),
                    });
                },
            );
        }),
    );

    let menu = gio::Menu::new();
    if user.break_glass {
        menu.append(Some("Set a password"), Some("user.password"));
    } else {
        let main = gio::Menu::new();
        if user.is_admin() {
            main.append(Some("Make a user"), Some("user.user"));
        } else {
            main.append(Some("Make an admin"), Some("user.admin"));
        }
        if user.disabled {
            main.append(Some("Turn on"), Some("user.on"));
        } else {
            main.append(Some("Turn off (and sign out)"), Some("user.off"));
        }
        if !user.oidc || user.has_password {
            main.append(Some("Set a password"), Some("user.password"));
        }
        main.append(Some("Sign out everywhere"), Some("user.sign-out"));
        menu.append_section(None, &main);
        let danger = gio::Menu::new();
        danger.append(Some("Delete"), Some("user.delete"));
        menu.append_section(None, &danger);
    }
    let button = gtk::MenuButton::builder()
        .icon_name("samm-ellipsis-symbolic")
        .menu_model(&menu)
        .valign(gtk::Align::Center)
        .tooltip_text(format!("More for {}", user.username))
        .build();
    button.add_css_class("flat");
    button.add_css_class("circular");
    button.insert_action_group("user", Some(&group));
    button
}

fn add_person(people: gtk::Box) {
    let form = Rc::new(Form::new("Add a person", "Add"));
    let (username_field, username) = text_field("Username", None, "", false);
    let (name_field, name) = text_field("Name", None, "", false);
    let (password_field, password) = text_field(
        "Password",
        Some("At least 8 characters. They can change it in Preferences or the web player's Settings."),
        "",
        true,
    );
    let focus = username.clone();
    let (admin_field, admin) = toggle("Admin", Some("Admins manage people, shared sources and these settings."), false);
    for field in [&username_field, &name_field, &password_field] {
        form.add(field);
    }
    form.add(&admin_field);
    let f = form.clone();
    form.action.connect_clicked(move |_| {
        let Some(api) = ctx().api() else { return };
        let (username, name, password) = (
            username.text().trim().to_string(),
            name.text().trim().to_string(),
            password.text().to_string(),
        );
        let role = if admin.is_active() { "ADMIN" } else { "USER" };
        f.busy(true);
        let (f, people) = (f.clone(), people.clone());
        run(
            async move { api.create_user(&username, &name, &password, role).await },
            move |result| match result {
                Ok(_) => {
                    f.close();
                    show_people(&people);
                }
                Err(e) => f.error(&e.to_string()),
            },
        );
    });
    form.present();
    focus.grab_focus();
}

fn set_password(user: &User) {
    let form = Rc::new(Form::new(&format!("New password for {}", user.username), "Set password"));
    let (field, password) = text_field("Password", Some("At least 8 characters."), "", true);
    form.add(&field);
    let (f, id, username) = (form.clone(), user.id.clone(), user.username.clone());
    form.action.connect_clicked(move |_| {
        let Some(api) = ctx().api() else { return };
        let typed = password.text().to_string();
        f.busy(true);
        let (f, id, username) = (f.clone(), id.clone(), username.clone());
        run(async move { api.reset_password(&id, &typed).await }, move |result| match result {
            Ok(_) => {
                f.close();
                ctx().toast(&format!("New password set for {username}; they were signed out"));
            }
            Err(e) => f.error(&e.to_string()),
        });
    });
    form.present();
}

// ---- The server ----

/// A field of the server's settings: where it is in them, and how to read it back.
enum Bound {
    Text(Vec<&'static str>, gtk::Editable),
    Switch(Vec<&'static str>, gtk::Switch),
}

fn get<'a>(settings: &'a Value, path: &[&str]) -> Option<&'a Value> {
    path.iter().try_fold(settings, |v, key| v.get(key))
}

fn set(settings: &mut Value, path: &[&str], value: Value) {
    let Some((last, parents)) = path.split_last() else { return };
    let mut at = settings;
    for key in parents {
        if !at.get(key).is_some_and(Value::is_object) {
            at[*key] = json!({});
        }
        at = &mut at[*key];
    }
    at[*last] = value;
}

fn show_server(into: &gtk::Box) {
    clear(into);
    let Some(api) = ctx().api() else { return };
    into.append(&loading());
    let into = into.clone();
    run(async move { api.admin_settings().await }, move |result| {
        clear(&into);
        match result {
            Ok(admin) => server_form(&into, Rc::new(admin)),
            Err(e) => {
                into.append(&status(
                    "samm-triangle-alert-symbolic",
                    "Couldn't load the server's settings",
                    &e.to_string(),
                ));
                ctx().failed(&e);
            }
        }
    });
}

fn panel(into: &gtk::Box, title: &str) -> gtk::Box {
    into.append(&heading(title));
    let panel = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(16).build();
    panel.add_css_class("panel");
    into.append(&panel);
    panel
}

fn server_form(into: &gtk::Box, admin: Rc<AdminSettings>) {
    let settings = &admin.settings;
    let env = admin.from_environment.clone();
    let locked = |key: &str| {
        if env.iter().any(|e| e == key) {
            format!(" Set by {key}, which wins at every start.")
        } else {
            String::new()
        }
    };
    let text = |path: &[&str]| get(settings, path).and_then(Value::as_str).unwrap_or_default().to_string();
    let on = |path: &[&str]| get(settings, path).and_then(Value::as_bool).unwrap_or(false);
    let mut bound: Vec<Bound> = vec![];

    let server = panel(into, "Server");
    let mut add_text = |panel: &gtk::Box, path: Vec<&'static str>, label: &str, help: Option<String>, placeholder: Option<&str>| {
        let (field, entry) = text_field(label, help.as_deref(), &text(&path), false);
        if let (Some(placeholder), Some(e)) = (placeholder, entry.downcast_ref::<gtk::Entry>()) {
            e.set_placeholder_text(Some(placeholder));
        }
        panel.append(&field);
        bound.push(Bound::Text(path, entry));
    };
    add_text(
        &server,
        vec!["serverName"],
        "Server name",
        Some("Shown on the sign-in page.".into()),
        None,
    );
    let base = ctx().api().map(|a| a.base().to_string()).unwrap_or_default();
    add_text(
        &server,
        vec!["publicUrl"],
        "Public address",
        Some(format!(
            "The address people reach this server at, e.g. https://music.example.com. Needed for single sign-on behind a proxy.{}",
            locked("SAMM_PUBLIC_URL")
        )),
        Some(&base),
    );
    let mut switches: Vec<Bound> = vec![];
    let mut add_switch = |panel: &gtk::Box, path: Vec<&'static str>, label: &str, help: Option<&str>| {
        let (row, switch) = toggle(label, help, on(&path));
        panel.append(&row);
        switches.push(Bound::Switch(path, switch));
    };
    add_switch(
        &server,
        vec!["localLogin"],
        "People can sign in with a password",
        Some("Off: everyone but the break-glass admin signs in through single sign-on."),
    );
    add_switch(
        &server,
        vec!["allowPersonalSources"],
        "People can add their own sources",
        Some("Their own Jellyfin, Lidarr, SiriusXM… Their sign-ins are encrypted and only they can use them."),
    );
    add_switch(
        &server,
        vec!["learnLibrary"],
        "Learn the libraries for Listen",
        Some(
            "The server goes through every song in your Jellyfin libraries (in the background, a few seconds each) so Listen can recognise them, without anything leaving the server. It takes about 15 MB for every 100 songs. Off: it stops learning, and keeps using what it learned.",
        ),
    );

    let sso = panel(into, "Single sign-on (OpenID Connect)");
    sso.append(&note(
        "For Authentik: create an OAuth2/OpenID Provider (confidential client) and an Application for it, and add this redirect URI. Who may use the app is set in Authentik (bindings) and, optionally, with the user group below.",
    ));
    let copy_row = gtk::Box::builder().spacing(8).build();
    let uri = gtk::Label::builder()
        .label(&admin.redirect_uri)
        .selectable(true)
        .xalign(0.0)
        .hexpand(true)
        .wrap(true)
        .max_width_chars(50)
        .wrap_mode(gtk::pango::WrapMode::Char)
        .build();
    uri.add_css_class("copy-text");
    copy_row.append(&uri);
    let copy = button(Some("samm-copy-symbolic"), "Copy", "ghost");
    copy.set_valign(gtk::Align::Center);
    let redirect = admin.redirect_uri.clone();
    copy.connect_clicked(move |_| {
        if let Some(display) = gdk::Display::default() {
            display.clipboard().set_text(&redirect);
        }
        ctx().toast("Copied");
    });
    copy_row.append(&copy);
    sso.append(&copy_row);
    add_switch(&sso, vec!["oidc", "enabled"], "Turn on single sign-on", None);
    add_text(
        &sso,
        vec!["oidc", "issuer"],
        "Issuer",
        Some(format!(
            "Authentik: https://auth.example.com/application/o/<application slug>/{}",
            locked("SAMM_OIDC_ISSUER")
        )),
        Some("https://auth.example.com/application/o/music/"),
    );
    let client_help = locked("SAMM_OIDC_CLIENT_ID");
    add_text(
        &sso,
        vec!["oidc", "clientId"],
        "Client ID",
        Some(client_help).filter(|h| !h.is_empty()),
        None,
    );
    let (secret_field, secret) = text_field(
        "Client secret",
        Some(if admin.oidc_client_secret_set {
            "Saved. Leave empty to keep it."
        } else {
            "Not set."
        }),
        "",
        true,
    );
    sso.append(&secret_field);
    add_text(&sso, vec!["oidc", "buttonLabel"], "Button label", None, None);
    add_text(&sso, vec!["oidc", "scopes"], "Scopes", None, None);
    add_text(
        &sso,
        vec!["oidc", "userGroup"],
        "Group allowed to sign in",
        Some("Blank: anyone the provider lets through.".into()),
        Some("music-users"),
    );
    add_text(
        &sso,
        vec!["oidc", "adminGroup"],
        "Admin group",
        Some("Members are admins; everyone else is a user. Blank: set roles here.".into()),
        Some("music-admins"),
    );
    add_text(&sso, vec!["oidc", "usernameClaim"], "Username claim", None, None);
    add_text(&sso, vec!["oidc", "groupsClaim"], "Groups claim", None, None);
    add_switch(
        &sso,
        vec!["oidc", "autoCreateUsers"],
        "Make an account on first sign-in",
        Some("Off: an admin adds people first."),
    );
    bound.extend(switches);
    let bound = Rc::new(bound);
    // What's on the form, over the settings as the server sent them.
    let current: Rc<dyn Fn() -> Value> = {
        let (bound, settings) = (bound.clone(), admin.settings.clone());
        Rc::new(move || {
            let mut next = settings.clone();
            for b in bound.iter() {
                match b {
                    Bound::Text(path, entry) => set(&mut next, path, Value::String(entry.text().trim().to_string())),
                    Bound::Switch(path, switch) => set(&mut next, path, Value::Bool(switch.is_active())),
                }
            }
            next
        })
    };
    let result = note("");
    result.set_visible(false);
    result.add_css_class("notice");
    let check = button(None, "Check the issuer", "tonal");
    check.set_halign(gtk::Align::Start);
    {
        let (current, result) = (current.clone(), result.clone());
        check.connect_clicked(move |b| {
            let Some(api) = ctx().api() else { return };
            let oidc = current().get("oidc").cloned().unwrap_or_default();
            b.set_sensitive(false);
            let (b, result) = (b.clone(), result.clone());
            run(async move { api.test_oidc(&oidc).await }, move |answer| {
                b.set_sensitive(true);
                match answer {
                    Ok(checked) => {
                        result.set_label(&checked.message);
                        result.remove_css_class(if checked.ok { "bad" } else { "ok" });
                        result.add_css_class(if checked.ok { "ok" } else { "bad" });
                        result.set_visible(true);
                    }
                    Err(e) => ctx().failed(&e),
                }
            });
        });
    }
    sso.append(&result);
    sso.append(&check);

    let bar = gtk::Box::builder().spacing(12).margin_top(18).build();
    let version = gtk::Label::builder()
        .label(format!("Server {}", admin.version))
        .xalign(0.0)
        .hexpand(true)
        .build();
    version.add_css_class("meta");
    bar.append(&version);
    let save = button(None, "Save server settings", "suggested");
    let holder = into.clone();
    save.connect_clicked(move |b| {
        let Some(api) = ctx().api() else { return };
        let next = current();
        let typed = secret.text().to_string();
        b.set_sensitive(false);
        let (b, holder) = (b.clone(), holder.clone());
        run(
            async move { api.save_settings(&next, Some(typed.as_str()).filter(|s| !s.is_empty())).await },
            move |answer| {
                b.set_sensitive(true);
                match answer {
                    Ok(saved) => {
                        ctx().toast("Saved");
                        clear(&holder);
                        server_form(&holder, Rc::new(saved));
                    }
                    Err(e) => ctx().failed(&e),
                }
            },
        );
    });
    bar.append(&save);
    into.append(&bar);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_are_changed_in_place() {
        let mut settings = json!({ "serverName": "Music", "oidc": { "enabled": false, "issuer": "" }, "somethingNew": 3 });
        set(&mut settings, &["oidc", "enabled"], Value::Bool(true));
        set(&mut settings, &["serverName"], json!("Home"));
        assert_eq!(
            settings,
            json!({ "serverName": "Home", "oidc": { "enabled": true, "issuer": "" }, "somethingNew": 3 })
        );
        assert_eq!(get(&settings, &["oidc", "enabled"]), Some(&Value::Bool(true)));
        assert_eq!(get(&settings, &["nope", "x"]), None);
    }

    #[test]
    fn people_are_described_as_on_the_web() {
        let user: User = serde_json::from_value(json!({
            "id": "1", "username": "sam", "displayName": "Sam", "role": "USER", "disabled": true
        }))
        .unwrap();
        assert_eq!(person_line(&user), "sam · turned off · Password · never signed in");
    }
}
