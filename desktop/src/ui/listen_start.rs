//! Listen from Search, as the web's search box has it: a button that opens Listen and starts
//! listening at once.

use adw::prelude::*;
use gtk::glib;

use crate::ui::ctx;

/// The first widget under [widget] with the style class [class].
fn find(widget: &gtk::Widget, class: &str) -> Option<gtk::Widget> {
    if widget.has_css_class(class) {
        return Some(widget.clone());
    }
    let mut child = widget.first_child();
    while let Some(c) = child {
        if let Some(found) = find(&c, class) {
            return Some(found);
        }
        child = c.next_sibling();
    }
    None
}

pub fn listen_button() -> gtk::Button {
    let button = gtk::Button::builder()
        .icon_name("samm-mic-symbolic")
        .tooltip_text("Listen: what's playing around you?")
        .valign(gtk::Align::Center)
        .build();
    button.add_css_class("circular");
    button.add_css_class("flat");
    button.connect_clicked(|_| {
        crate::ui::window::show_section("Listen");
        // Once Listen is on screen: its big button, pressed.
        glib::idle_add_local_once(|| {
            let window: gtk::Widget = ctx().window.clone().upcast();
            if let Some(big) = find(&window, "listen-button").and_downcast::<gtk::Button>() {
                big.emit_clicked();
            }
        });
    });
    button
}
