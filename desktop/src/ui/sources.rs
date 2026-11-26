//! Sources, as the web player's Settings and Admin list them (web/src/components/Sources.tsx):
//! "personal" is everything you can use (shared ones can be switched off for yourself, your own
//! edited, tested and removed); "global" is the admins' sources, shared with everyone.

use std::cell::RefCell;
use std::rc::Rc;

use adw::prelude::*;
use gtk::glib;
use serde_json::{Map, Value, json};

use crate::api::{FieldSpec, KindSpec, Kinds, SourceView};
use crate::ui::ctx;
use crate::ui::forms::{
    Form, boxed_list, choice, clear, confirm, failed_near, icon_button, letter, note, run, text_field, toast_near, toggle,
};

#[derive(Clone, Copy, PartialEq)]
pub enum Scope {
    Personal,
    Global,
}

impl Scope {
    fn name(self) -> &'static str {
        match self {
            Scope::Personal => "personal",
            Scope::Global => "global",
        }
    }
}

/// The kinds of source, in groups: where music comes from, where it plays, and what helps.
const KIND_GROUPS: [(&str, &[&str]); 4] = [
    ("Your music library", &["JELLYFIN", "SUBSONIC"]),
    (
        "Music services and radio",
        &["YOUTUBE_MUSIC", "QOBUZ", "SIRIUSXM", "RADIO", "TUNER"],
    ),
    ("Places to play", &["SONOS", "MUSIC_ASSISTANT"]),
    ("Helpers", &["LIDARR", "AUDD"]),
];

/// [kinds] in the picker's groups, with anything newer than the list under "More".
fn grouped(kinds: &Kinds) -> Vec<(String, Vec<KindSpec>)> {
    let mut groups: Vec<(String, Vec<KindSpec>)> = KIND_GROUPS
        .iter()
        .map(|(title, names)| (title.to_string(), names.iter().filter_map(|k| kinds.spec(k).cloned()).collect()))
        .collect();
    let known: Vec<&str> = KIND_GROUPS.iter().flat_map(|(_, names)| names.iter().copied()).collect();
    groups.push((
        "More".into(),
        kinds.kinds.iter().filter(|k| !known.contains(&k.kind.as_str())).cloned().collect(),
    ));
    groups.retain(|(_, specs)| !specs.is_empty());
    groups
}

/// Sources and kinds as last loaded, and how to show them again.
struct State {
    scope: Scope,
    sources: Vec<SourceView>,
    kinds: Option<Kinds>,
    holder: gtk::Box,
}

type Shared = Rc<RefCell<State>>;

/// The list of sources, with "Add a source" under it.
pub fn sources_section(scope: Scope) -> gtk::Box {
    crate::ui::style::ensure();
    let holder = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(12).build();
    let state: Shared = Rc::new(RefCell::new(State {
        scope,
        sources: vec![],
        kinds: None,
        holder: holder.clone(),
    }));
    reload(&state);
    holder
}

fn reload(state: &Shared) {
    let Some(api) = ctx().api() else { return };
    let holder = state.borrow().holder.clone();
    clear(&holder);
    holder.append(&crate::ui::widgets::loading());
    let state = state.clone();
    run(
        async move {
            let (sources, kinds) = tokio::join!(api.sources(), api.source_kinds());
            Ok((sources?, kinds.ok()))
        },
        move |result| match result {
            Ok((sources, kinds)) => {
                {
                    let mut s = state.borrow_mut();
                    s.sources = sources;
                    s.kinds = kinds;
                }
                show(&state);
            }
            Err(e) => {
                clear(&holder);
                holder.append(&note(&e.to_string()));
                failed_near(&holder, &e);
            }
        },
    );
}

fn show(state: &Shared) {
    let s = state.borrow();
    let holder = s.holder.clone();
    clear(&holder);
    let list: Vec<&SourceView> = s
        .sources
        .iter()
        .filter(|src| s.scope == Scope::Personal || src.scope == "global")
        .collect();
    if list.is_empty() {
        holder.append(&note(match s.scope {
            Scope::Global => "No sources yet. Add the services everyone on this server can use.",
            Scope::Personal => "No sources yet. Add a service to listen from.",
        }));
    } else {
        let rows = boxed_list();
        for source in list {
            rows.append(&source_row(state, source));
        }
        holder.append(&rows);
    }
    let can_add = s.kinds.as_ref().is_some_and(|k| match s.scope {
        Scope::Global => k.can_add_global,
        Scope::Personal => k.can_add_personal,
    });
    if can_add {
        let add = crate::ui::widgets::button(
            Some("list-add-symbolic"),
            if s.scope == Scope::Global {
                "Add a shared source"
            } else {
                "Add a source"
            },
            "tonal",
        );
        add.set_halign(gtk::Align::Start);
        let st = state.clone();
        add.connect_clicked(move |_| pick_kind(&st));
        holder.append(&add);
    } else if s.scope == Scope::Personal && s.kinds.is_some() {
        holder.append(&note(
            "This server's admins add the sources (music libraries such as Jellyfin or a Subsonic server, and places to play such as Sonos or Music Assistant), under Admin › Shared sources.",
        ));
    }
}

/// "Shared by the admins · Jellyfin · https://jellyfin.example.com · maddie".
fn source_line(source: &SourceView, kinds: Option<&Kinds>) -> String {
    let mut parts = vec![if source.scope == "global" {
        "Shared by the admins".to_string()
    } else {
        "Yours".to_string()
    }];
    parts.push(
        kinds
            .and_then(|k| k.spec(&source.kind))
            .map(|s| s.label.clone())
            .unwrap_or_else(|| source.kind.clone()),
    );
    for key in ["serverUrl", "username"] {
        if let Some(value) = source.settings.get(key).and_then(Value::as_str).filter(|v| !v.is_empty()) {
            parts.push(value.to_string());
        }
    }
    parts.join(" · ")
}

fn labelled_switch(label: &str, on: bool) -> (gtk::Box, gtk::Switch) {
    let row = gtk::Box::builder().spacing(6).valign(gtk::Align::Center).build();
    let text = gtk::Label::new(Some(label));
    text.add_css_class("dim-label");
    row.append(&text);
    let switch = gtk::Switch::builder().active(on).valign(gtk::Align::Center).build();
    row.append(&switch);
    (row, switch)
}

fn source_row(state: &Shared, source: &SourceView) -> adw::ActionRow {
    let s = state.borrow();
    let row = crate::ui::widgets::plain_row(&source.name, &source_line(source, s.kinds.as_ref()));
    row.set_subtitle_lines(2);
    row.add_prefix(&letter(&source.name, &source.kind));
    if !source.enabled || source.hidden {
        row.add_css_class("is-off");
    }
    let shared = source.scope == "global";
    let own = source.editable && (s.scope == Scope::Global || !shared);
    if shared && s.scope == Scope::Personal {
        let (holder, switch) = labelled_switch("Use", !source.hidden);
        let (st, id) = (state.clone(), source.id.clone());
        switch.connect_state_set(move |switch, use_it| {
            let Some(api) = ctx().api() else { return glib::Propagation::Stop };
            let (st, id, switch) = (st.clone(), id.clone(), switch.clone());
            run(async move { api.hide_source(&id, !use_it).await }, move |result| {
                changed(&st, &switch, result)
            });
            glib::Propagation::Proceed
        });
        row.add_suffix(&holder);
    }
    if own {
        let (holder, switch) = labelled_switch("On", source.enabled);
        let (st, id) = (state.clone(), source.id.clone());
        switch.connect_state_set(move |switch, on| {
            let Some(api) = ctx().api() else { return glib::Propagation::Stop };
            let (st, id, switch) = (st.clone(), id.clone(), switch.clone());
            run(
                async move { api.update_source(&id, json!({ "enabled": on })).await },
                move |result| changed(&st, &switch, result),
            );
            glib::Propagation::Proceed
        });
        row.add_suffix(&holder);
    }
    if source.kind != "YOUTUBE_MUSIC" {
        let test = icon_button("network-transmit-receive-symbolic", "Test");
        let id = source.id.clone();
        test.connect_clicked(move |button| {
            let Some(api) = ctx().api() else { return };
            button.set_sensitive(false);
            let (id, button) = (id.clone(), button.clone());
            run(async move { api.test_source(&id).await }, move |result| {
                button.set_sensitive(true);
                match result {
                    Ok(checked) => toast_near(&button, &checked.message),
                    Err(e) => failed_near(&button, &e),
                }
            });
        });
        row.add_suffix(&test);
    }
    if own && let Some(spec) = s.kinds.as_ref().and_then(|k| k.spec(&source.kind)).cloned() {
        let edit = icon_button("document-edit-symbolic", "Edit");
        let (st, source) = (state.clone(), source.clone());
        edit.connect_clicked(move |_| source_dialog(&st, spec.clone(), Some(source.clone())));
        row.add_suffix(&edit);
    }
    if own {
        let remove = icon_button("user-trash-symbolic", "Remove");
        let (st, source) = (state.clone(), source.clone());
        remove.connect_clicked(move |button| {
            let detail = format!(
                "{} Songs from it in playlists are found in your other sources where possible.",
                if source.scope == "global" {
                    "Nobody on the server will be able to use it any more."
                } else {
                    "Its saved sign-in is deleted."
                }
            );
            let (st, id, button) = (st.clone(), source.id.clone(), button.clone());
            confirm(&format!("Remove {}?", source.name), &detail, "Cancel", "Remove", move || {
                let Some(api) = ctx().api() else { return };
                let (st, id, button) = (st.clone(), id.clone(), button.clone());
                let gone = id.clone();
                run(async move { api.delete_source(&gone).await }, move |result| match result {
                    Ok(_) => {
                        st.borrow_mut().sources.retain(|s| s.id != id);
                        show(&st);
                    }
                    Err(e) => failed_near(&button, &e),
                });
            });
        });
        row.add_suffix(&remove);
    }
    row
}

/// A switch's change saved: the list shows the source as it is now (or the switch goes back).
fn changed(state: &Shared, switch: &gtk::Switch, result: anyhow::Result<SourceView>) {
    match result {
        Ok(next) => {
            let mut s = state.borrow_mut();
            if let Some(at) = s.sources.iter_mut().find(|s| s.id == next.id) {
                *at = next;
            }
            drop(s);
            // After the switch has finished moving.
            let st = state.clone();
            glib::idle_add_local_once(move || show(&st));
        }
        Err(e) => {
            failed_near(switch, &e);
            // Shown as it still is.
            reload(state);
        }
    }
}

/// Choosing what to add, in the web's groups.
fn pick_kind(state: &Shared) {
    let Some(kinds) = state.borrow().kinds.clone() else { return };
    let dialog = adw::Dialog::builder()
        .title("Add a source")
        .content_width(480)
        .content_height(620)
        .build();
    let body = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(8)
        .margin_top(6)
        .margin_bottom(24)
        .margin_start(20)
        .margin_end(20)
        .build();
    for (title, specs) in grouped(&kinds) {
        let heading = gtk::Label::builder().label(title.to_uppercase()).xalign(0.0).build();
        heading.add_css_class("kind-group-title");
        body.append(&heading);
        let list = boxed_list();
        for spec in specs {
            let row = crate::ui::widgets::plain_row(&spec.label, &spec.description);
            row.set_subtitle_lines(0);
            row.set_activatable(true);
            row.add_prefix(&letter(&spec.label, &spec.kind));
            row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
            let (st, d) = (state.clone(), dialog.clone());
            row.connect_activated(move |_| {
                d.close();
                source_dialog(&st, spec.clone(), None);
            });
            list.append(&row);
        }
        body.append(&list);
    }
    let toolbar = adw::ToolbarView::new();
    toolbar.add_top_bar(&adw::HeaderBar::new());
    toolbar.set_content(Some(
        &gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .child(&body)
            .build(),
    ));
    dialog.set_child(Some(&toolbar));
    dialog.present(Some(&ctx().window));
}

/// The text a saved setting shows in its field.
fn saved_text(value: Option<&Value>) -> Option<String> {
    match value? {
        Value::String(s) => Some(s.clone()),
        Value::Null => None,
        other => Some(other.to_string()),
    }
}

/// A field's label, as the web puts it: "(optional)" on those that may be left empty.
fn field_label(field: &FieldSpec, editing: Option<&SourceView>) -> String {
    let saved_secrets = editing.is_some_and(|s| !s.secrets_set.is_empty());
    if (field.required && !(field.secret && saved_secrets)) || field.secret {
        field.label.clone()
    } else {
        format!("{} (optional)", field.label)
    }
}

/// How to read a field's value back.
enum Input {
    Text(gtk::Editable),
    Switch(gtk::Switch),
    /// A drop-down of [values] (an empty value is left out).
    Pick(gtk::DropDown, Vec<String>),
}

impl Input {
    fn value(&self) -> Option<Value> {
        match self {
            Input::Text(e) => Some(e.text().to_string()).filter(|t| !t.is_empty()).map(Value::String),
            Input::Switch(s) => Some(Value::Bool(s.is_active())),
            Input::Pick(d, values) => values
                .get(d.selected() as usize)
                .filter(|v| !v.is_empty())
                .cloned()
                .map(Value::String),
        }
    }
}

/// Adding a source of [spec]'s kind, or editing [source].
fn source_dialog(state: &Shared, spec: KindSpec, source: Option<SourceView>) {
    let scope = state.borrow().scope;
    let title = match &source {
        Some(s) => format!("Edit {}", s.name),
        None => format!("Add {}{}", spec.label, if scope == Scope::Global { " for everyone" } else { "" }),
    };
    let form = Rc::new(Form::new(&title, if source.is_some() { "Save" } else { "Add" }));
    if !spec.description.is_empty() {
        form.note(&spec.description);
    }
    let (name_field, name) = text_field(
        "Name",
        Some("How it's shown in search results."),
        source.as_ref().map(|s| s.name.as_str()).unwrap_or(""),
        false,
    );
    if let Some(entry) = name.downcast_ref::<gtk::Entry>() {
        entry.set_placeholder_text(Some(&spec.label));
    }
    form.add(&name_field);
    let inputs: Rc<RefCell<Vec<(String, Input)>>> = Rc::default();
    let saved = |key: &str| source.as_ref().and_then(|s| saved_text(s.settings.get(key)));
    for f in &spec.fields {
        let help = f.help.as_deref();
        match f.kind.as_str() {
            "bool" => {
                let on = match source.as_ref().and_then(|s| s.settings.get(&f.key)) {
                    Some(v) => v.as_bool().unwrap_or_else(|| v.as_str() == Some("true")),
                    None => f.default.as_deref() != Some("false"),
                };
                let (row, switch) = toggle(&f.label, help, on);
                form.add(&row);
                inputs.borrow_mut().push((f.key.clone(), Input::Switch(switch)));
            }
            "select" => {
                let options = f.options.clone().unwrap_or_default();
                let current = saved(&f.key).or_else(|| f.default.clone()).unwrap_or_default();
                let at = options.iter().position(|o| *o == current).unwrap_or(0);
                let (row, drop) = choice(&f.label, help, &options, at);
                form.add(&row);
                inputs.borrow_mut().push((f.key.clone(), Input::Pick(drop, options)));
            }
            _ => {
                let value = if f.secret {
                    String::new()
                } else {
                    saved(&f.key).or_else(|| f.default.clone()).unwrap_or_default()
                };
                let help = if f.secret && source.is_some() {
                    Some(
                        format!("Leave empty to keep the saved one. {}", f.help.clone().unwrap_or_default())
                            .trim()
                            .to_string(),
                    )
                } else {
                    f.help.clone()
                };
                let (row, entry) = text_field(&field_label(f, source.as_ref()), help.as_deref(), &value, f.kind == "password");
                form.add(&row);
                inputs.borrow_mut().push((f.key.clone(), Input::Text(entry)));
            }
        }
    }
    // Lidarr's own choices, once it's been added.
    if spec.kind == "LIDARR"
        && let Some(src) = &source
        && let Some(api) = ctx().api()
    {
        let (id, form_for, inputs_for) = (src.id.clone(), form.clone(), inputs.clone());
        let settings = src.settings.clone();
        run(async move { api.lidarr_options(&id).await }, move |result| {
            let Ok(options) = result else { return };
            let lists = [
                (
                    "Quality profile",
                    "qualityProfileId",
                    "The root folder's default",
                    options.quality_profiles,
                ),
                (
                    "Metadata profile",
                    "metadataProfileId",
                    "The root folder's default",
                    options.metadata_profiles,
                ),
                ("Root folder", "rootFolderPath", "The first one", options.root_folders),
            ];
            for (label, key, none, choices) in lists {
                let mut names = vec![none.to_string()];
                let mut values = vec![String::new()];
                for c in choices {
                    names.push(c.name);
                    values.push(c.id);
                }
                let current = saved_text(settings.get(key)).unwrap_or_default();
                let at = values.iter().position(|v| *v == current).unwrap_or(0);
                let (row, drop) = choice(label, None, &names, at);
                form_for.add(&row);
                inputs_for.borrow_mut().push((key.to_string(), Input::Pick(drop, values)));
            }
        });
    }
    let (st, f) = (state.clone(), form.clone());
    form.action.connect_clicked(move |_| {
        let Some(api) = ctx().api() else { return };
        let mut fields = Map::new();
        for (key, input) in inputs.borrow().iter() {
            if let Some(value) = input.value() {
                fields.insert(key.clone(), value);
            }
        }
        let typed = name.text().trim().to_string();
        f.busy(true);
        let (st, f, kind, editing) = (st.clone(), f.clone(), spec.kind.clone(), source.clone());
        let scope = scope.name();
        run(
            async move {
                match &editing {
                    Some(s) => {
                        let name = if typed.is_empty() { Value::Null } else { Value::String(typed) };
                        api.update_source(&s.id, json!({ "name": name, "fields": fields })).await
                    }
                    None => api.add_source(&kind, &typed, scope, Value::Object(fields)).await,
                }
            },
            move |result| match result {
                Ok(saved) => {
                    let holder = st.borrow().holder.clone();
                    let editing = st.borrow().sources.iter().any(|s| s.id == saved.id);
                    toast_near(&holder, &format!("{} {}", if editing { "Saved" } else { "Added" }, saved.name));
                    {
                        let mut s = st.borrow_mut();
                        match s.sources.iter_mut().find(|s| s.id == saved.id) {
                            Some(at) => *at = saved,
                            None => s.sources.push(saved),
                        }
                    }
                    show(&st);
                    f.close();
                }
                Err(e) => f.error(&e.to_string()),
            },
        );
    });
    form.present();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kinds_are_grouped_as_on_the_web() {
        let kinds: Kinds = serde_json::from_value(json!({
            "kinds": [
                { "kind": "LIDARR", "label": "Lidarr" }, { "kind": "JELLYFIN", "label": "Jellyfin" },
                { "kind": "SONOS", "label": "Sonos" }, { "kind": "SOMETHING_NEW", "label": "New" }
            ]
        }))
        .unwrap();
        let groups: Vec<(String, Vec<String>)> = grouped(&kinds)
            .into_iter()
            .map(|(t, s)| (t, s.into_iter().map(|k| k.kind).collect()))
            .collect();
        assert_eq!(
            groups,
            vec![
                ("Your music library".to_string(), vec!["JELLYFIN".to_string()]),
                ("Places to play".to_string(), vec!["SONOS".to_string()]),
                ("Helpers".to_string(), vec!["LIDARR".to_string()]),
                ("More".to_string(), vec!["SOMETHING_NEW".to_string()]),
            ]
        );
    }

    #[test]
    fn optional_fields_say_so() {
        let field = |required: bool, secret: bool| FieldSpec {
            key: "k".into(),
            label: "Token".into(),
            kind: "text".into(),
            required,
            secret,
            help: None,
            options: None,
            default: None,
        };
        assert_eq!(field_label(&field(true, false), None), "Token");
        assert_eq!(field_label(&field(false, false), None), "Token (optional)");
        assert_eq!(field_label(&field(false, true), None), "Token");
    }
}
