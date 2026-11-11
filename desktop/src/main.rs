//! SuperAwesomeMagicalMusic for the Linux desktop: your server's music in a native GTK 4 app.

mod api;
mod config;
mod mpris;
mod player;
mod rt;
mod ui;

use adw::prelude::*;
use gtk::{gio, glib};

/// The app's id: its D-Bus name, its .desktop file and its icon.
pub const APP_ID: &str = "io.github._603madison.SuperAwesomeMagicalMusic";

fn main() -> glib::ExitCode {
    // The window's class matches the .desktop file, so docks show the app's icon for it.
    glib::set_prgname(Some(APP_ID));
    glib::set_application_name("SuperAwesomeMagicalMusic");
    if let Err(e) = gst::init() {
        eprintln!("GStreamer couldn't start: {e}");
        return glib::ExitCode::FAILURE;
    }
    let app = adw::Application::builder()
        .application_id(APP_ID)
        .flags(gio::ApplicationFlags::HANDLES_OPEN)
        .build();
    app.connect_activate(ui::activate);
    app.connect_open(|app, files, _| ui::open_uris(app, files));
    app.run()
}
