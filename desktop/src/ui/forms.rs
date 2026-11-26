//! Forms as the web player draws them (a label, the field, a line of help under it) and the
//! dialogs that hold them, for sources, people, sharing, importing and the server's settings.

use adw::prelude::*;
use gtk::glib;

use crate::ui::ctx;

/// A dialog with Cancel and its action in the header, a banner for what went wrong, and room
/// for fields.
pub struct Form {
    pub dialog: adw::Dialog,
    /// The fields go here.
    pub body: gtk::Box,
    pub action: gtk::Button,
    banner: adw::Banner,
}

impl Form {
    pub fn new(title: &str, action: &str) -> Form {
        let dialog = adw::Dialog::builder().title(title).content_width(520).build();
        let header = adw::HeaderBar::builder()
            .show_start_title_buttons(false)
            .show_end_title_buttons(false)
            .build();
        let cancel = gtk::Button::with_label("Cancel");
        let d = dialog.clone();
        cancel.connect_clicked(move |_| {
            d.close();
        });
        header.pack_start(&cancel);
        let button = gtk::Button::with_label(action);
        button.add_css_class("suggested-action");
        header.pack_end(&button);
        let banner = adw::Banner::builder().revealed(false).build();
        banner.add_css_class("error");
        let body = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(16)
            .margin_top(12)
            .margin_bottom(24)
            .margin_start(24)
            .margin_end(24)
            .build();
        let scroll = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .propagate_natural_height(true)
            .max_content_height(640)
            .child(&body)
            .build();
        let toolbar = adw::ToolbarView::new();
        toolbar.add_top_bar(&header);
        toolbar.add_top_bar(&banner);
        toolbar.set_content(Some(&scroll));
        dialog.set_child(Some(&toolbar));
        dialog.set_default_widget(Some(&button));
        Form {
            dialog,
            body,
            action: button,
            banner,
        }
    }

    /// A line of plain text (what the dialog is about).
    pub fn note(&self, text: &str) {
        self.body.append(&note(text));
    }

    pub fn add(&self, widget: &impl IsA<gtk::Widget>) {
        self.body.append(widget);
    }

    /// Shows what went wrong (the dialog stays open to try again).
    pub fn error(&self, message: &str) {
        self.banner.set_title(message);
        self.banner.set_revealed(true);
        self.busy(false);
    }

    /// The action is under way: it can't be pressed again till it's done.
    pub fn busy(&self, on: bool) {
        self.action.set_sensitive(!on);
        if on {
            self.banner.set_revealed(false);
        }
    }

    pub fn close(&self) {
        self.dialog.close();
    }

    pub fn present(&self) {
        self.dialog.present(Some(&ctx().window));
    }
}

/// Muted text that wraps.
pub fn note(text: &str) -> gtk::Label {
    let label = gtk::Label::builder().label(text).xalign(0.0).wrap(true).build();
    label.add_css_class("meta");
    label
}

fn help_label(help: &str) -> gtk::Label {
    let line = gtk::Label::builder().label(help).xalign(0.0).wrap(true).build();
    line.add_css_class("field-help");
    line
}

/// A field: its label over [input], and its help under it.
pub fn field(label: &str, help: Option<&str>, input: &impl IsA<gtk::Widget>) -> gtk::Box {
    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
    let name = gtk::Label::builder().label(label).xalign(0.0).build();
    name.add_css_class("field-label");
    column.append(&name);
    column.append(input);
    if let Some(help) = help.filter(|h| !h.trim().is_empty()) {
        column.append(&help_label(help));
    }
    column
}

/// A text field (a password's dots for [secret]): the field to put in a form, and its text.
pub fn text_field(label: &str, help: Option<&str>, value: &str, secret: bool) -> (gtk::Box, gtk::Editable) {
    let input: gtk::Editable = if secret {
        let entry = gtk::PasswordEntry::builder().show_peek_icon(true).activates_default(true).build();
        entry.set_text(value);
        entry.upcast()
    } else {
        let entry = gtk::Entry::builder().text(value).activates_default(true).build();
        entry.upcast()
    };
    (field(label, help, &input), input)
}

/// A switch with its label and help beside it, as the web's toggles.
pub fn toggle(label: &str, help: Option<&str>, on: bool) -> (gtk::Box, gtk::Switch) {
    let row = gtk::Box::builder().spacing(14).build();
    let text = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(4)
        .hexpand(true)
        .valign(gtk::Align::Center)
        .build();
    let name = gtk::Label::builder().label(label).xalign(0.0).wrap(true).build();
    name.add_css_class("field-label");
    text.append(&name);
    if let Some(help) = help.filter(|h| !h.trim().is_empty()) {
        text.append(&help_label(help));
    }
    row.append(&text);
    let switch = gtk::Switch::builder().active(on).valign(gtk::Align::Center).build();
    row.append(&switch);
    (row, switch)
}

/// A choice among [options] (as shown), starting at [selected].
pub fn choice(label: &str, help: Option<&str>, options: &[String], selected: usize) -> (gtk::Box, gtk::DropDown) {
    let names: Vec<&str> = options.iter().map(String::as_str).collect();
    let drop = gtk::DropDown::from_strings(&names);
    drop.set_selected(selected.min(options.len().saturating_sub(1)) as u32);
    (field(label, help, &drop), drop)
}

/// Asks [question] ([detail] under it); [yes] runs when [action] (a destructive one) is chosen.
pub fn confirm(question: &str, detail: &str, keep: &str, action: &str, yes: impl Fn() + 'static) {
    let dialog = adw::AlertDialog::new(Some(question), Some(detail));
    dialog.add_responses(&[("keep", keep), ("go", action)]);
    dialog.set_response_appearance("go", adw::ResponseAppearance::Destructive);
    dialog.set_close_response("keep");
    dialog.connect_response(Some("go"), move |_, _| yes());
    dialog.present(Some(&ctx().window));
}

/// A small button with an icon and a tooltip, for a row's actions.
pub fn icon_button(icon: &str, tooltip: &str) -> gtk::Button {
    let button = gtk::Button::builder()
        .icon_name(icon)
        .tooltip_text(tooltip)
        .valign(gtk::Align::Center)
        .build();
    button.add_css_class("flat");
    button
}

/// The first letter of [name] in a round badge of the kind's colour, as the web marks sources.
pub fn letter(name: &str, kind: &str) -> gtk::Label {
    let first: String = name.chars().next().map(|c| c.to_uppercase().collect()).unwrap_or_default();
    let label = gtk::Label::builder()
        .label(first)
        .width_request(36)
        .height_request(36)
        .valign(gtk::Align::Center)
        .build();
    label.add_css_class("source-icon");
    label.add_css_class(&format!("kind-{}", kind.to_lowercase()));
    label
}

/// A boxed list of rows.
pub fn boxed_list() -> gtk::ListBox {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("boxed-list");
    list
}

/// "just now", "5 min ago", "3 hr ago", "Mar 4", as the web player says when.
pub fn when(ms: i64) -> String {
    let Ok(then) = glib::DateTime::from_unix_local(ms / 1000) else {
        return String::new();
    };
    let Ok(now) = glib::DateTime::now_local() else {
        return String::new();
    };
    let seconds = now.to_unix() - then.to_unix();
    if seconds < 60 {
        "just now".into()
    } else if seconds < 3600 {
        format!("{} min ago", seconds / 60)
    } else if seconds < 86_400 {
        format!("{} hr ago", seconds / 3600)
    } else {
        let format = if then.year() == now.year() { "%b %-e" } else { "%b %-e, %Y" };
        then.format(format).map(|s| s.to_string()).unwrap_or_default()
    }
}

/// "1 song", "12 songs".
pub fn plural(n: i64, word: &str) -> String {
    if n == 1 { format!("1 {word}") } else { format!("{n} {word}s") }
}

/// Says [text] where it's seen: in Preferences when [near] is in it (the window's toasts are
/// under it), or in the window.
pub fn toast_near(near: &impl IsA<gtk::Widget>, text: &str) {
    match near
        .as_ref()
        .ancestor(adw::PreferencesDialog::static_type())
        .and_downcast::<adw::PreferencesDialog>()
    {
        Some(dialog) => {
            let toast = adw::Toast::new(text);
            toast.set_timeout(4);
            dialog.add_toast(toast);
        }
        None => ctx().toast(text),
    }
}

/// Something went wrong: said near [near], or back to signing in if the sign-in is gone.
pub fn failed_near(near: &impl IsA<gtk::Widget>, error: &anyhow::Error) {
    if crate::api::is_unauthorized(error) {
        ctx().failed(error);
    } else {
        toast_near(near, &error.to_string());
    }
}

/// Runs [work] on the network, then [done] with its result (here, on the main thread).
pub fn run<T: Send + 'static>(
    work: impl std::future::Future<Output = anyhow::Result<T>> + Send + 'static,
    done: impl FnOnce(anyhow::Result<T>) + 'static,
) {
    glib::spawn_future_local(async move {
        done(crate::rt::bg(work).await);
    });
}

/// A small heading in capitals, for a dialog's lists.
pub fn small_heading(text: &str) -> gtk::Label {
    let label = gtk::Label::builder().label(text.to_uppercase()).xalign(0.0).margin_top(8).build();
    label.add_css_class("kind-group-title");
    label
}

/// Removes every child of [container].
pub fn clear(container: &gtk::Box) {
    while let Some(child) = container.first_child() {
        container.remove(&child);
    }
}
