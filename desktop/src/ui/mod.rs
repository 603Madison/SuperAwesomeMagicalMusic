//! The window: signing in, then the music (a sidebar, pages, and the player along the bottom).

mod devices;
mod listen;
mod login;
pub mod now_playing;
mod pages;
mod player_bar;
pub mod theme;
pub mod widgets;
mod window;

use std::cell::RefCell;
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gdk, gio, glib};

use crate::api::{Api, is_unauthorized};
use crate::config::Config;
use crate::player::Player;

/// The app's one of everything.
pub struct Ctx {
    pub window: adw::ApplicationWindow,
    pub toasts: adw::ToastOverlay,
    pub config: RefCell<Config>,
    pub player: Rc<Player>,
    api: RefCell<Option<Api>>,
    /// The music's pages (set once signed in).
    nav: RefCell<Option<adw::NavigationView>>,
    /// Signing in through the browser: (server, secret, state) until it hands back a code.
    pending_browser_sign_in: RefCell<Option<(String, String, String)>>,
}

thread_local! {
    static CTX: RefCell<Option<Rc<Ctx>>> = const { RefCell::new(None) };
}

/// The app's context (on the main thread, once the window exists).
pub fn ctx() -> Rc<Ctx> {
    CTX.with(|c| c.borrow().clone()).expect("the app's context")
}

/// The app's context, if the window exists yet.
pub fn try_ctx() -> Option<Rc<Ctx>> {
    CTX.with(|c| c.borrow().clone())
}

impl Ctx {
    pub fn api(&self) -> Option<Api> {
        self.api.borrow().clone()
    }

    pub fn toast(&self, text: &str) {
        let toast = adw::Toast::new(text);
        toast.set_timeout(4);
        self.toasts.add_toast(toast);
    }

    /// Something went wrong loading: a toast, or back to signing in if the sign-in is gone.
    pub fn failed(&self, error: &anyhow::Error) {
        if is_unauthorized(error) {
            self.toast("Your sign-in ended: sign in again");
            self.sign_out_here();
        } else {
            self.toast(&error.to_string());
        }
    }

    fn set_signed_in(&self, server: &str, token: Option<String>) {
        let api = Api::new(server, token);
        self.player.set_api(Some(api.clone()));
        // One of the person's devices, while signed in.
        if let Some(hub) = crate::connect::hub() {
            hub.start(&api, &Self::device_name());
        }
        self.api.replace(Some(api));
    }

    /// Signed in: keeps it, and shows the music.
    pub fn signed_in(self: &Rc<Self>, server: &str, token: String, user: crate::api::User) {
        {
            let mut config = self.config.borrow_mut();
            config.server = Some(server.to_string());
            config.token = Some(token.clone());
            config.user = Some(user);
            config.save();
        }
        self.set_signed_in(server, Some(token));
        self.show_music();
    }

    /// Signs out on the server too, then here.
    pub fn sign_out(self: &Rc<Self>) {
        if let Some(api) = self.api() {
            glib::spawn_future_local(async move {
                let _ = crate::rt::bg(async move { api.logout().await }).await;
            });
        }
        self.sign_out_here();
    }

    fn sign_out_here(&self) {
        if let Some(hub) = crate::connect::hub() {
            hub.stop();
        }
        self.player.set_api(None);
        self.api.replace(None);
        {
            let mut config = self.config.borrow_mut();
            config.token = None;
            config.user = None;
            config.save();
        }
        self.nav.replace(None);
        self.toasts.set_child(Some(&login::view()));
    }

    fn show_music(self: &Rc<Self>) {
        let (view, nav) = window::music_view();
        self.nav.replace(Some(nav));
        self.toasts.set_child(Some(&view));
    }

    pub fn device_name() -> String {
        format!("Linux desktop ({})", glib::host_name())
    }

    // ---- Opening pages ----

    fn push(&self, page: &adw::NavigationPage) {
        if let Some(nav) = self.nav.borrow().as_ref() {
            nav.push(page);
        }
    }

    /// Back to the page before (after deleting a playlist).
    pub fn back(&self) {
        if let Some(nav) = self.nav.borrow().as_ref() {
            nav.pop();
        }
    }

    /// What's playing, made to be looked at (from the player bar, the sidebar, or a shortcut).
    pub fn open_now_playing(&self) {
        let Some(nav) = self.nav.borrow().clone() else { return };
        now_playing::show_in(&nav);
    }

    pub fn open_artist(&self, id: Option<&str>, name: &str) {
        self.push(&pages::artist_page(id.map(str::to_string), name.to_string()));
    }

    pub fn open_album(&self, id: Option<&str>, title: &str, artist: &str) {
        self.push(&pages::album_page(id.map(str::to_string), title.to_string(), artist.to_string()));
    }

    pub fn open_playlist(&self, id: &str, name: &str) {
        // Already open: nothing to do.
        let open = self
            .nav
            .borrow()
            .as_ref()
            .and_then(|nav| nav.visible_page())
            .map(|p| p.widget_name());
        if open.as_deref() == Some(format!("playlist:{id}").as_str()) {
            return;
        }
        self.push(&pages::playlist_page(id.to_string(), name.to_string()));
    }
}

/// Thumbs for the songs on a personal station: not every icon theme has them, so the app brings its own.
const THUMB_UP: &str = r##"<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16"><path fill="#2e3436" d="M1 7h3v8H1zm4 0 3-6c1.1 0 2 .9 2 2v3h3.5c.9 0 1.6.8 1.5 1.7l-.9 5.6c-.1.8-.8 1.7-1.6 1.7H5z"/></svg>"##;

/// The app's icon when it isn't installed (run from the build, or the icon theme's cache is
/// old), and the icons it brings itself.
fn register_icon() {
    let Some(display) = gdk::Display::default() else { return };
    let theme = gtk::IconTheme::for_display(&display);
    let dir = glib::user_cache_dir().join("SuperAwesomeMagicalMusic").join("icons");
    if std::fs::create_dir_all(&dir).is_err() {
        return;
    }
    let down = THUMB_UP.replace("<path ", r#"<path transform="rotate(180 8 8)" "#);
    let icons = [
        ("samm-thumb-up-symbolic.svg", THUMB_UP),
        ("samm-thumb-down-symbolic.svg", down.as_str()),
        ("samm-devices-symbolic.svg", devices::DEVICES_ICON),
        ("samm-info-symbolic.svg", now_playing::INFO_ICON),
    ];
    // In the hicolor theme's layout (its scalable/apps), where GTK takes them as symbolic icons
    // and draws them in the text's colour; loose in the folder they'd keep their own grey.
    let themed = dir.join("hicolor").join("scalable").join("apps");
    let mut ok = std::fs::create_dir_all(&themed).is_ok();
    for (name, svg) in icons {
        ok &= std::fs::write(themed.join(name), svg).is_ok();
    }
    if !theme.has_icon(crate::APP_ID) {
        let png: &[u8] = include_bytes!("../../data/icons/hicolor/256x256/apps/io.github._603madison.SuperAwesomeMagicalMusic.png");
        ok |= std::fs::write(dir.join(format!("{}.png", crate::APP_ID)), png).is_ok();
    }
    if ok {
        theme.add_search_path(&dir);
    }
}

/// The window (made once; opening the app again brings it up).
pub fn activate(app: &adw::Application) {
    if let Some(window) = CTX.with(|c| c.borrow().as_ref().map(|c| c.window.clone())) {
        window.present();
        return;
    }
    register_icon();
    let mut config = Config::load();
    theme::apply(&config.palette, &config.style, config.bold_text);
    let window = adw::ApplicationWindow::builder()
        .application(app)
        .title("SuperAwesomeMagicalMusic")
        .icon_name(crate::APP_ID)
        .default_width(config.width)
        .default_height(config.height)
        .width_request(360)
        .height_request(480)
        .build();
    if config.maximized {
        window.maximize();
    }
    let toasts = adw::ToastOverlay::new();
    window.set_content(Some(&toasts));
    let player = Player::new();
    player.set_volume(config.volume);
    player.set_choices(config.kbps, config.thorough);
    player.set_levelling(config.levelling);
    if config.device_id.is_empty() {
        config.device_id = crate::connect::new_device_id();
        config.save();
    }
    crate::connect::install(player.clone(), config.device_id.clone());
    player.set_repeat(match config.repeat.as_str() {
        "all" => crate::player::Repeat::All,
        "one" => crate::player::Repeat::One,
        _ => crate::player::Repeat::Off,
    });
    let context = Rc::new(Ctx {
        window: window.clone(),
        toasts,
        config: RefCell::new(config),
        player: player.clone(),
        api: RefCell::new(None),
        nav: RefCell::new(None),
        pending_browser_sign_in: RefCell::new(None),
    });
    CTX.with(|c| c.replace(Some(context.clone())));

    let weak = Rc::downgrade(&context);
    player.on_error(move |message| {
        if let Some(ctx) = weak.upgrade() {
            ctx.toast(message);
        }
    });
    let server_of = Rc::downgrade(&context);
    let raise = window.downgrade();
    crate::mpris::start(
        player,
        move || server_of.upgrade().and_then(|c| c.config.borrow().server.clone()),
        move || {
            if let Some(w) = raise.upgrade() {
                w.present();
            }
        },
    );

    let (server, token) = {
        let config = context.config.borrow();
        (config.server.clone(), config.token.clone())
    };
    match (server, token) {
        (Some(server), Some(token)) => {
            context.set_signed_in(&server, Some(token));
            context.show_music();
        }
        _ => context.toasts.set_child(Some(&login::view())),
    }

    window.connect_close_request(move |window| {
        let ctx = ctx();
        {
            let mut config = ctx.config.borrow_mut();
            config.maximized = window.is_maximized();
            if !window.is_maximized() {
                let (w, h) = window.default_size();
                config.width = w;
                config.height = h;
            }
            let state = ctx.player.state();
            config.volume = state.volume;
            config.repeat = match state.repeat {
                crate::player::Repeat::Off => "off",
                crate::player::Repeat::All => "all",
                crate::player::Repeat::One => "one",
            }
            .into();
            config.save();
        }
        ctx.player.shutdown();
        glib::Propagation::Proceed
    });
    install_shortcuts(app);
    window.present();
}

fn install_shortcuts(app: &adw::Application) {
    let action = |name: &str, accels: &[&str], run: fn(&Rc<Ctx>)| {
        let a = gio::SimpleAction::new(name, None);
        a.connect_activate(move |_, _| run(&ctx()));
        app.add_action(&a);
        app.set_accels_for_action(&format!("app.{name}"), accels);
    };
    action("play-pause", &["<Primary>p"], |c| c.player.toggle());
    action("next", &["<Primary>Right"], |c| c.player.next());
    action("previous", &["<Primary>Left"], |c| c.player.previous());
    action("quit", &["<Primary>q"], |c| {
        c.window.close();
    });
    action("preferences", &["<Primary>comma"], window::preferences);
    action("about", &[], window::about);
    action("sign-out", &[], |c| c.sign_out());
}

/// `sammusic://signed-in?code=…&state=…`: the browser handing back a sign-in.
pub fn open_uris(app: &adw::Application, files: &[gio::File]) {
    activate(app);
    for file in files {
        let uri = file.uri().to_string();
        if let Ok(url) = url::Url::parse(&uri)
            && url.scheme() == "sammusic"
            && url.host_str() == Some("signed-in")
        {
            let query: std::collections::HashMap<_, _> = url.query_pairs().into_owned().collect();
            login::finish_browser_sign_in(
                query.get("code").cloned().unwrap_or_default(),
                query.get("state").cloned().unwrap_or_default(),
            );
        }
    }
}
