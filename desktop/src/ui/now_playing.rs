//! What's playing, made to be looked at, as the web player's Now playing page
//! (web/src/pages/NowPlaying.tsx): the cover large, the page washed in its colours, the song with
//! its artist and album, its buttons and where it is, and beside it the lyrics following the song
//! (click a line to play from there) or what's up next. On live radio: the channel, the song on
//! it (and its lyrics, when the channel says which), and what it played before.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;
use std::time::{Duration, Instant};

use adw::prelude::*;
use gtk::{gdk, glib};

use crate::api::{LyricsView, NowView, Track};
use crate::player::State;
use crate::rt::bg;
use crate::ui::ctx;
use crate::ui::widgets::{artwork, cover_url, duration, track_key, track_menu};

/// Scrolling the lyrics by hand stops them following the song for a while.
const HAND_SCROLL_PAUSE: Duration = Duration::from_secs(4);
/// The page's colour when the cover has none to give: the app's own, as on dark.
const DEFAULT_ACCENT: &str = "#cfbdff";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum View {
    Cover,
    Lyrics,
    Queue,
}

impl View {
    fn id(self) -> &'static str {
        match self {
            View::Cover => "cover",
            View::Lyrics => "lyrics",
            View::Queue => "queue",
        }
    }

    fn of(id: &str) -> Option<View> {
        match id {
            "cover" => Some(View::Cover),
            "lyrics" => Some(View::Lyrics),
            "queue" => Some(View::Queue),
            _ => None,
        }
    }
}

/// What the page shows of the player's state: on live radio, the song on now.
#[derive(Debug, Clone, PartialEq)]
struct Shown {
    /// The queue's song, channel or station.
    base: Track,
    live: bool,
    personal: bool,
    title: String,
    artist: String,
    artwork: Option<String>,
    album: Option<String>,
    /// The song itself (on live radio, when the channel says which): for its artist and album
    /// pages, its menu and its lyrics.
    song: Option<Track>,
}

fn shown_of(state: &State) -> Option<Shown> {
    let base = state.current()?.clone();
    let live = base.is_live();
    let personal = base.is_personal();
    Some(match (&state.on_air, live) {
        (Some(on), true) => Shown {
            title: if on.title.is_empty() { base.title.clone() } else { on.title.clone() },
            artist: if on.artist.is_empty() { base.title.clone() } else { on.artist.clone() },
            artwork: on.artwork_url.clone().or_else(|| base.artwork_url.clone()),
            album: on.album.clone().filter(|a| !a.is_empty()),
            song: on.song.clone(),
            base,
            live,
            personal,
        },
        (None, true) => Shown {
            title: base.title.clone(),
            artist: base.artist.clone(),
            artwork: base.artwork_url.clone(),
            album: None,
            song: None,
            base,
            live,
            personal,
        },
        _ => Shown {
            title: base.title.clone(),
            artist: base.artist.clone(),
            artwork: base.artwork_url.clone(),
            album: base.album.clone().filter(|a| !a.is_empty()),
            song: Some(base.clone()),
            base,
            live,
            personal,
        },
    })
}

/// Where the song is, for its lyrics: in a file, where it's played to (a moment early, as the
/// singer breathes in); on live radio, how far into the song on now the stream is, when the
/// channel says when it started.
fn lyrics_clock(state: &State, live_clock: Option<i64>) -> Option<i64> {
    if state.live() {
        let on = state.on_air.as_ref().filter(|o| o.start_ms > 0)?;
        Some(live_clock? - on.start_ms + 250)
    } else {
        Some((state.position * 1000.0) as i64 + 250)
    }
}

/// The line being sung at [ms]: the last one started by then.
fn current_line(lines: &[Option<i64>], ms: Option<i64>) -> Option<usize> {
    let ms = ms?;
    lines.iter().rposition(|t| t.is_some_and(|t| t <= ms))
}

/// "Just now", "4 min ago", "1 h 12 min ago".
fn ago(ms: i64) -> String {
    let minutes = (ms as f64 / 60_000.0).round() as i64;
    if minutes < 1 {
        "Just now".into()
    } else if minutes < 60 {
        format!("{minutes} min ago")
    } else if minutes % 60 == 0 {
        format!("{} h ago", minutes / 60)
    } else {
        format!("{} h {} min ago", minutes / 60, minutes % 60)
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

// ---- The cover's colours ----

/// The most colourful hue in [rgb] (weighted towards vivid ones) as a light, readable colour
/// (as the web player's pickAccent); None for black and white, which keeps the app's own.
fn pick_accent(rgb: &[u8]) -> Option<(u8, u8, u8)> {
    let (mut x, mut y, mut weight, mut saturation_total) = (0.0f64, 0.0f64, 0.0f64, 0.0f64);
    let pixels = rgb.len() / 3;
    for p in rgb.chunks_exact(3) {
        let (h, s, l) = hsl(p[0], p[1], p[2]);
        if !(0.08..=0.95).contains(&l) {
            continue;
        }
        let w = s * s * (1.0 - (l - 0.5).abs());
        x += (h * std::f64::consts::TAU).cos() * w;
        y += (h * std::f64::consts::TAU).sin() * w;
        weight += w;
        saturation_total += s;
    }
    if weight < 0.5 || pixels == 0 {
        return None;
    }
    let hue = (y.atan2(x) / std::f64::consts::TAU + 1.0) % 1.0;
    let saturation = (saturation_total / pixels as f64 + 0.3).clamp(0.5, 0.9);
    Some(hsl_to_rgb(hue, saturation, 0.74))
}

fn hsl(r: u8, g: u8, b: u8) -> (f64, f64, f64) {
    let (r, g, b) = (r as f64 / 255.0, g as f64 / 255.0, b as f64 / 255.0);
    let (max, min) = (r.max(g).max(b), r.min(g).min(b));
    let l = (max + min) / 2.0;
    if max == min {
        return (0.0, 0.0, l);
    }
    let d = max - min;
    let s = if l > 0.5 { d / (2.0 - max - min) } else { d / (max + min) };
    let h = if max == r {
        (g - b) / d + if g < b { 6.0 } else { 0.0 }
    } else if max == g {
        (b - r) / d + 2.0
    } else {
        (r - g) / d + 4.0
    };
    (h / 6.0, s, l)
}

fn hsl_to_rgb(h: f64, s: f64, l: f64) -> (u8, u8, u8) {
    let q = if l < 0.5 { l * (1.0 + s) } else { l + s - l * s };
    let p = 2.0 * l - q;
    let channel = |t: f64| {
        let t = (t + 1.0) % 1.0;
        let v = if t < 1.0 / 6.0 {
            p + (q - p) * 6.0 * t
        } else if t < 0.5 {
            q
        } else if t < 2.0 / 3.0 {
            p + (q - p) * (2.0 / 3.0 - t) * 6.0
        } else {
            p
        };
        (v * 255.0).round().clamp(0.0, 255.0) as u8
    };
    (channel(h + 1.0 / 3.0), channel(h), channel(h - 1.0 / 3.0))
}

/// [rgb] ([side] pixels square) blurred: a few passes of a box blur, which makes the soft blobs
/// of colour the backdrop is.
fn blur(rgb: &[u8], side: usize, radius: usize, passes: usize) -> Vec<u8> {
    let mut now: Vec<f32> = rgb.iter().map(|v| *v as f32).collect();
    let mut next = now.clone();
    let at = |x: usize, y: usize, c: usize| (y * side + x) * 3 + c;
    for _ in 0..passes {
        for horizontal in [true, false] {
            for y in 0..side {
                for x in 0..side {
                    for c in 0..3 {
                        let (mut sum, mut n) = (0.0, 0.0);
                        for d in -(radius as isize)..=(radius as isize) {
                            let (sx, sy) = if horizontal { (x as isize + d, y as isize) } else { (x as isize, y as isize + d) };
                            if sx < 0 || sy < 0 || sx >= side as isize || sy >= side as isize {
                                continue;
                            }
                            sum += now[at(sx as usize, sy as usize, c)];
                            n += 1.0;
                        }
                        next[at(x, y, c)] = sum / n;
                    }
                }
            }
            std::mem::swap(&mut now, &mut next);
        }
    }
    now.iter().map(|v| v.round().clamp(0.0, 255.0) as u8).collect()
}

/// The RGB pixels of [pixbuf] scaled to [side] square.
fn pixels_of(pixbuf: &gtk::gdk_pixbuf::Pixbuf, side: i32) -> Option<Vec<u8>> {
    let small = pixbuf.scale_simple(side, side, gtk::gdk_pixbuf::InterpType::Bilinear)?;
    let (channels, stride) = (small.n_channels() as usize, small.rowstride() as usize);
    let bytes = small.read_pixel_bytes();
    let mut rgb = Vec::with_capacity((side * side * 3) as usize);
    for y in 0..side as usize {
        for x in 0..side as usize {
            let i = y * stride + x * channels;
            rgb.extend_from_slice(bytes.get(i..i + 3)?);
        }
    }
    Some(rgb)
}

/// The backdrop and the accent for a cover's bytes.
fn colours_of(bytes: &[u8]) -> Option<(gdk::Texture, Option<(u8, u8, u8)>)> {
    let loader = gtk::gdk_pixbuf::PixbufLoader::new();
    loader.write(bytes).ok()?;
    loader.close().ok()?;
    let pixbuf = loader.pixbuf()?;
    let accent = pixels_of(&pixbuf, 24).and_then(|p| pick_accent(&p));
    const SIDE: usize = 40;
    let blurred = blur(&pixels_of(&pixbuf, SIDE as i32)?, SIDE, 4, 3);
    let small = gtk::gdk_pixbuf::Pixbuf::from_bytes(
        &glib::Bytes::from_owned(blurred),
        gtk::gdk_pixbuf::Colorspace::Rgb,
        false,
        8,
        SIDE as i32,
        SIDE as i32,
        (SIDE * 3) as i32,
    );
    let big = small.scale_simple(360, 360, gtk::gdk_pixbuf::InterpType::Bilinear)?;
    let texture = gdk::MemoryTexture::new(
        360,
        360,
        gdk::MemoryFormat::R8g8b8,
        &big.read_pixel_bytes(),
        big.rowstride() as usize,
    );
    Some((texture.upcast(), accent))
}

fn image_client() -> &'static reqwest::Client {
    static CLIENT: std::sync::OnceLock<reqwest::Client> = std::sync::OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .user_agent(concat!("SuperAwesomeMagicalMusic-Desktop/", env!("CARGO_PKG_VERSION")))
            .timeout(Duration::from_secs(20))
            .build()
            .expect("the image client")
    })
}

async fn fetch(address: String) -> Option<Vec<u8>> {
    bg(async move {
        let response = image_client().get(&address).send().await.ok()?;
        if !response.status().is_success() {
            return None;
        }
        response.bytes().await.ok().map(|b| b.to_vec())
    })
    .await
}

// ---- The look ----

thread_local! {
    static PROVIDER: RefCell<Option<gtk::CssProvider>> = const { RefCell::new(None) };
    static PAGE: RefCell<Option<Rc<Np>>> = const { RefCell::new(None) };
    static LYRICS: RefCell<HashMap<String, LyricsView>> = RefCell::new(HashMap::new());
}

/// The page's look: always dark, whatever the theme, with the cover's colour ([accent]) for the
/// line being sung, the glow around the cover and the progress.
fn css(accent: &str) -> String {
    format!(
        r#"
@define-color np_accent {accent};
.np {{ background-color: #0c0a10; color: white; }}
.np-backdrop {{ opacity: 0.8; filter: saturate(1.7); }}
.np-shade {{
  background-image:
    radial-gradient(ellipse at 25% 30%, alpha(@np_accent, 0.22), transparent 65%),
    radial-gradient(ellipse at 50% 45%, transparent 35%, rgba(6, 4, 10, 0.55)),
    linear-gradient(180deg, rgba(6, 4, 10, 0.3), rgba(6, 4, 10, 0.5) 55%, rgba(6, 4, 10, 0.74));
}}
.np headerbar {{ background: none; box-shadow: none; color: white; }}
.np label {{ color: inherit; }}
.np button {{ color: white; }}
.np button.flat:hover, .np menubutton.flat > button:hover {{ background: rgba(255, 255, 255, 0.1); }}
.np button.on {{ color: @np_accent; }}
.np-art {{
  border-radius: 22px; background: rgba(255, 255, 255, 0.06);
  box-shadow: 0 30px 70px -24px rgba(0, 0, 0, 0.75), 0 0 140px -40px @np_accent;
  transform: scale(0.94); transition: transform 700ms cubic-bezier(0.2, 0.9, 0.25, 1), box-shadow 700ms;
}}
.np-art.playing {{ transform: none; box-shadow: 0 40px 90px -24px rgba(0, 0, 0, 0.8), 0 0 160px -30px @np_accent; }}
.np-title {{ font-size: 2.1em; font-weight: 700; letter-spacing: -0.015em; }}
.np-artist {{ font-size: 1.12em; color: rgba(255, 255, 255, 0.74); }}
.np-artist link {{ color: rgba(255, 255, 255, 0.86); }}
.np-quality, .np-faint {{ font-size: 0.78em; color: rgba(255, 255, 255, 0.5); }}
.np-muted {{ color: rgba(255, 255, 255, 0.74); }}
.np-dj {{ color: rgba(255, 255, 255, 0.86); font-style: italic; }}
.np-channel-name {{ font-weight: 600; }}
.np-channel-number {{ color: rgba(255, 255, 255, 0.74); font-size: 0.9em; }}
.np-channel .artwork {{ border-radius: 7px; }}
.np-progress-time {{ font-size: 0.78em; color: rgba(255, 255, 255, 0.5); }}
.np scale trough {{ min-height: 5px; border-radius: 999px; background: rgba(255, 255, 255, 0.22); }}
.np scale highlight {{ min-height: 5px; border-radius: 999px; background: white; }}
.np scale slider {{ min-width: 15px; min-height: 15px; margin: -5px; background: white; box-shadow: none; border: none; }}
.np-live-bar trough {{ min-height: 4px; border-radius: 999px; background: rgba(255, 255, 255, 0.22); }}
.np-live-bar progress {{ min-height: 4px; border-radius: 999px; background: white; }}
.np-transport > button {{ min-width: 44px; min-height: 44px; border-radius: 9999px; }}
.np-transport > button.np-play {{ min-width: 64px; min-height: 64px; background: white; color: #140d18; }}
.np-transport > button.np-play:hover {{ background: #f0ecf6; }}
.np-transport > button:disabled {{ opacity: 0.3; }}
.np-tabs {{ background: rgba(255, 255, 255, 0.08); border-radius: 999px; padding: 4px; }}
.np-tabs > button {{ border-radius: 999px; padding: 7px 18px; background: none; box-shadow: none; color: rgba(255, 255, 255, 0.74); font-size: 0.9em; }}
.np-tabs > button:hover {{ color: white; }}
.np-tabs > button:checked {{ background: rgba(255, 255, 255, 0.18); color: white; }}
.np-mini .artwork {{ border-radius: 10px; }}
.np-mini-title {{ font-weight: 600; }}
.np-panel-card {{ background: rgba(255, 255, 255, 0.06); border-radius: 22px; padding: 14px; }}
.np-note-title {{ font-size: 1.4em; font-weight: 700; color: white; }}
.np-lyric {{
  background: none; box-shadow: none; border-radius: 14px; padding: 6px 12px;
  font-size: 1.9em; font-weight: 700; letter-spacing: -0.012em;
  color: rgba(255, 255, 255, 0.36); transition: color 450ms, text-shadow 450ms, filter 450ms;
}}
button.np-lyric {{ color: rgba(255, 255, 255, 0.36); }}
button.np-lyric:hover {{ background: rgba(255, 255, 255, 0.07); }}
.np-lyric.current, button.np-lyric.current {{ color: white; text-shadow: 0 0 32px alpha(@np_accent, 0.75); }}
.np-lyric.far {{ filter: blur(1.1px); }}
.np-lyric.far:hover {{ filter: none; }}
.np-lyrics.plain .np-lyric {{ font-size: 1.35em; font-weight: 600; color: rgba(255, 255, 255, 0.86); }}
.np-back {{ background: rgba(255, 255, 255, 0.92); color: #140d18; border-radius: 999px; padding: 9px 18px; font-weight: 600; box-shadow: 0 10px 30px rgba(0, 0, 0, 0.4); }}
.np-back:hover {{ background: white; }}
.np .track-list > row {{ border-radius: 12px; padding: 6px 8px; }}
.np .track-list > row:hover {{ background: rgba(255, 255, 255, 0.07); }}
.np .track-list > row.current .track-title {{ color: @np_accent; }}
.np .track-sub, .np .queue-section {{ color: rgba(255, 255, 255, 0.6); }}
.np .queue-heading {{ font-size: 1.25em; font-weight: 700; }}
.np .live-pill {{ background: rgba(255, 255, 255, 0.16); color: white; }}
.np .artwork {{ background: rgba(255, 255, 255, 0.08); color: rgba(255, 255, 255, 0.6); }}
.queue-section {{ font-size: 0.78em; font-weight: 700; letter-spacing: 0.06em; margin: 12px 8px 4px 8px; }}
.queue-heading {{ font-size: 1.15em; font-weight: 700; }}
"#
    )
}

fn set_accent(accent: Option<(u8, u8, u8)>) {
    let colour = accent
        .map(|(r, g, b)| format!("#{r:02x}{g:02x}{b:02x}"))
        .unwrap_or_else(|| DEFAULT_ACCENT.into());
    PROVIDER.with(|p| {
        let mut p = p.borrow_mut();
        let provider = p.get_or_insert_with(|| {
            let provider = gtk::CssProvider::new();
            if let Some(display) = gdk::Display::default() {
                gtk::style_context_add_provider_for_display(&display, &provider, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION + 1);
            }
            provider
        });
        provider.load_from_string(&css(&colour));
    });
}

// ---- The page ----

/// The Now playing page (one, kept: opened again, it's where it was).
pub fn page() -> adw::NavigationPage {
    np().page.clone()
}

/// Shows the page in [nav]: brought back if it's further down, pushed if it isn't there.
pub fn show_in(nav: &adw::NavigationView) {
    let np = np();
    if nav.visible_page().as_ref() == Some(&np.page) {
        return;
    }
    let stack = nav.navigation_stack();
    let there = (0..stack.n_items()).any(|i| stack.item(i).as_ref() == Some(np.page.upcast_ref()));
    if there {
        nav.pop_to_page(&np.page);
    } else {
        if let Some(index) = crate::ui::window::section_of("Now playing") {
            np.page.set_widget_name(&format!("section:{index}"));
        }
        nav.push(&np.page);
    }
}

/// Opens the page on its lyrics.
pub fn show_lyrics() {
    let np = np();
    np.choose(View::Lyrics);
    ctx().open_now_playing();
}

fn np() -> Rc<Np> {
    if let Some(np) = PAGE.with(|p| p.borrow().clone()) {
        return np;
    }
    set_accent(None);
    let np = Np::new();
    PAGE.with(|p| p.replace(Some(np.clone())));
    let weak = Rc::downgrade(&np);
    ctx().player.subscribe(move |state| {
        if let Some(np) = weak.upgrade() {
            np.update(state);
        }
    });
    np.update(&ctx().player.state());
    np
}

fn label(text: &str, class: &str) -> gtk::Label {
    let l = gtk::Label::builder().label(text).xalign(0.0).build();
    if !class.is_empty() {
        l.add_css_class(class);
    }
    l
}

fn icon_button(icon: &str, tooltip: &str) -> gtk::Button {
    let b = gtk::Button::builder().icon_name(icon).tooltip_text(tooltip).valign(gtk::Align::Center).build();
    b.add_css_class("flat");
    b.add_css_class("circular");
    b
}

fn clear(container: &gtk::Box) {
    while let Some(child) = container.first_child() {
        container.remove(&child);
    }
}

struct Np {
    page: adw::NavigationPage,
    content: gtk::Stack,
    backdrop: gtk::Stack,
    body: gtk::Box,
    hero: gtk::Widget,
    side: gtk::Box,
    cover_frame: gtk::Box,
    cover: gtk::Picture,
    channel: gtk::Box,
    channel_art: gtk::Box,
    channel_name: gtk::Label,
    channel_number: gtk::Label,
    title: gtk::Label,
    artist: gtk::Label,
    thumb_down: gtk::Button,
    thumb_up: gtk::Button,
    star: gtk::ToggleButton,
    quiet_star: Cell<bool>,
    menu_holder: gtk::Box,
    quality: gtk::Label,
    live_song: gtk::Box,
    live_into: gtk::Label,
    live_bar: gtk::ProgressBar,
    live_length: gtk::Label,
    dj: gtk::Box,
    dj_text: gtk::Label,
    progress: gtk::Box,
    elapsed: gtk::Label,
    scale: gtk::Scale,
    total: gtk::Label,
    previous: gtk::Button,
    play: gtk::Button,
    next: gtk::Button,
    tabs: [gtk::ToggleButton; 3],
    mini: gtk::Box,
    mini_art: gtk::Box,
    mini_title: gtk::Label,
    mini_artist: gtk::Label,
    panel: gtk::Stack,
    list_holder: gtk::Box,
    lyrics: Rc<Lyrics>,
    view: Cell<View>,
    narrow: Cell<bool>,
    // What's on screen, to change only what changed.
    shown: RefCell<Option<Shown>>,
    art_shown: RefCell<Option<Option<String>>>,
    list_key: RefCell<String>,
    /// SiriusXM's "played before" (asked again when the song on the channel changes).
    recent: RefCell<(String, Option<Vec<NowView>>)>,
}

impl Np {
    fn new() -> Rc<Np> {
        // The cover, large, and what's around it.
        let cover = gtk::Picture::builder()
            .content_fit(gtk::ContentFit::Cover)
            .can_shrink(true)
            .hexpand(true)
            .vexpand(true)
            .build();
        let cover_frame = gtk::Box::builder().hexpand(true).vexpand(true).build();
        cover_frame.set_overflow(gtk::Overflow::Hidden);
        cover_frame.add_css_class("np-art");
        let placeholder = gtk::Image::builder().icon_name("folder-music-symbolic").pixel_size(96).hexpand(true).build();
        placeholder.add_css_class("dim-label");
        let cover_stack = gtk::Stack::new();
        cover_stack.add_named(&placeholder, Some("none"));
        cover_stack.add_named(&cover, Some("cover"));
        cover_frame.append(&cover_stack);
        let aspect = gtk::AspectFrame::builder()
            .ratio(1.0)
            .obey_child(false)
            .child(&cover_frame)
            .vexpand(true)
            .hexpand(true)
            .build();

        // The channel (on live radio), the song with its buttons, how it plays.
        let channel = gtk::Box::builder().spacing(8).visible(false).build();
        channel.add_css_class("np-channel");
        let channel_art = gtk::Box::new(gtk::Orientation::Horizontal, 0);
        let channel_name = label("", "np-channel-name");
        channel_name.set_ellipsize(gtk::pango::EllipsizeMode::End);
        let channel_number = label("", "np-channel-number");
        let live_pill = gtk::Label::builder().label("LIVE").valign(gtk::Align::Center).build();
        live_pill.add_css_class("live-pill");
        channel.append(&channel_art);
        channel.append(&channel_name);
        channel.append(&channel_number);
        channel.append(&live_pill);

        let title = label("", "np-title");
        title.set_wrap(true);
        title.set_lines(2);
        title.set_ellipsize(gtk::pango::EllipsizeMode::End);
        title.set_wrap_mode(gtk::pango::WrapMode::WordChar);
        let artist = label("", "np-artist");
        artist.set_ellipsize(gtk::pango::EllipsizeMode::End);
        artist.connect_activate_link(|_, uri| {
            if let Some(np) = PAGE.with(|p| p.borrow().clone()) {
                np.follow_link(uri);
            }
            glib::Propagation::Stop
        });
        let titles = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(5).hexpand(true).build();
        titles.append(&title);
        titles.append(&artist);
        let actions = gtk::Box::builder().spacing(2).valign(gtk::Align::Center).build();
        let thumb_down = icon_button("samm-thumb-down-symbolic", "Thumbs down (never play it again)");
        let thumb_up = icon_button("samm-thumb-up-symbolic", "Thumbs up (more like this)");
        for (b, direction) in [(&thumb_down, "DOWN"), (&thumb_up, "UP")] {
            b.connect_clicked(move |_| ctx().player.thumb(direction));
        }
        let star = gtk::ToggleButton::builder()
            .icon_name("non-starred-symbolic")
            .tooltip_text("Add to favourites")
            .valign(gtk::Align::Center)
            .build();
        star.add_css_class("flat");
        star.add_css_class("circular");
        let info = icon_button("dialog-information-symbolic", "Details");
        info.connect_clicked(|_| {
            let state = ctx().player.state();
            if let Some(track) = state.current() {
                track_info(track);
            }
        });
        let menu_holder = gtk::Box::new(gtk::Orientation::Horizontal, 0);
        for w in [thumb_down.upcast_ref::<gtk::Widget>(), thumb_up.upcast_ref(), star.upcast_ref(), info.upcast_ref(), menu_holder.upcast_ref()] {
            actions.append(w);
        }
        let meta_row = gtk::Box::builder().spacing(12).build();
        meta_row.append(&titles);
        meta_row.append(&actions);
        let quality = label("", "np-quality");
        quality.set_ellipsize(gtk::pango::EllipsizeMode::End);
        let live_song = gtk::Box::builder().spacing(10).visible(false).build();
        let live_into = label("", "np-progress-time");
        let live_bar = gtk::ProgressBar::builder().hexpand(true).valign(gtk::Align::Center).build();
        live_bar.add_css_class("np-live-bar");
        let live_length = label("", "np-progress-time");
        live_song.append(&live_into);
        live_song.append(&live_bar);
        live_song.append(&live_length);
        let dj = gtk::Box::builder().spacing(8).visible(false).build();
        dj.append(&gtk::Image::from_icon_name("audio-input-microphone-symbolic"));
        let dj_text = label("", "np-dj");
        dj_text.set_wrap(true);
        dj.append(&dj_text);
        let meta = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(6).build();
        meta.append(&channel);
        meta.append(&meta_row);
        meta.append(&quality);
        meta.append(&live_song);
        meta.append(&dj);

        // Where it is, and play, previous and next.
        let progress = gtk::Box::builder().spacing(12).build();
        let elapsed = label("0:00", "np-progress-time");
        elapsed.add_css_class("numeric");
        elapsed.set_width_chars(5);
        elapsed.set_xalign(1.0);
        let scale = gtk::Scale::with_range(gtk::Orientation::Horizontal, 0.0, 1.0, 1.0);
        scale.set_hexpand(true);
        scale.set_draw_value(false);
        scale.connect_change_value(|_, _, value| {
            ctx().player.seek(value);
            glib::Propagation::Proceed
        });
        let total = label("0:00", "np-progress-time");
        total.add_css_class("numeric");
        total.set_width_chars(5);
        progress.append(&elapsed);
        progress.append(&scale);
        progress.append(&total);
        let transport = gtk::Box::builder().spacing(28).halign(gtk::Align::Center).build();
        transport.add_css_class("np-transport");
        let previous = icon_button("media-skip-backward-symbolic", "Previous");
        previous.connect_clicked(|_| ctx().player.previous());
        let play = gtk::Button::builder().icon_name("media-playback-start-symbolic").tooltip_text("Play").build();
        play.add_css_class("circular");
        play.add_css_class("np-play");
        play.connect_clicked(|_| ctx().player.toggle());
        let next = icon_button("media-skip-forward-symbolic", "Next");
        next.connect_clicked(|_| ctx().player.next());
        transport.append(&previous);
        transport.append(&play);
        transport.append(&next);
        let controls = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(10).build();
        controls.append(&progress);
        controls.append(&transport);

        let hero_box = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(22)
            .valign(gtk::Align::Fill)
            .build();
        hero_box.append(&aspect);
        hero_box.append(&meta);
        hero_box.append(&controls);
        let hero = adw::Clamp::builder().maximum_size(560).tightening_threshold(560).child(&hero_box).hexpand(true).build();

        // Beside it: the tabs, and the lyrics or what's next.
        let tab_box = gtk::Box::builder().halign(gtk::Align::Start).build();
        tab_box.add_css_class("np-tabs");
        let tabs = [
            gtk::ToggleButton::with_label("Song"),
            gtk::ToggleButton::with_label("Lyrics"),
            gtk::ToggleButton::with_label("Up next"),
        ];
        for tab in &tabs[1..] {
            tab.set_group(Some(&tabs[0]));
        }
        for tab in &tabs {
            tab_box.append(tab);
        }
        let mini = gtk::Box::builder().spacing(12).visible(false).build();
        mini.add_css_class("np-mini");
        let mini_art = gtk::Box::new(gtk::Orientation::Horizontal, 0);
        let mini_text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).valign(gtk::Align::Center).hexpand(true).build();
        let mini_title = label("", "np-mini-title");
        mini_title.set_ellipsize(gtk::pango::EllipsizeMode::End);
        let mini_artist = label("", "np-muted");
        mini_artist.set_ellipsize(gtk::pango::EllipsizeMode::End);
        mini_text.append(&mini_title);
        mini_text.append(&mini_artist);
        mini.append(&mini_art);
        mini.append(&mini_text);
        let mini_click = gtk::GestureClick::new();
        mini_click.connect_released(|_, _, _, _| {
            if let Some(np) = PAGE.with(|p| p.borrow().clone()) {
                np.choose(View::Cover);
            }
        });
        mini.add_controller(mini_click);
        mini.set_cursor_from_name(Some("pointer"));

        let lyrics = Lyrics::new();
        let list_holder = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
        let list_scroll = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .vexpand(true)
            .child(&list_holder)
            .build();
        let panel = gtk::Stack::builder()
            .transition_type(gtk::StackTransitionType::Crossfade)
            .vexpand(true)
            .build();
        panel.add_named(&lyrics.root, Some("lyrics"));
        panel.add_named(&list_scroll, Some("list"));
        let side = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(14).hexpand(true).build();
        side.append(&tab_box);
        side.append(&mini);
        side.append(&panel);

        let body = gtk::Box::builder()
            .spacing(64)
            .homogeneous(true)
            .margin_top(56)
            .margin_bottom(28)
            .margin_start(48)
            .margin_end(48)
            .build();
        body.append(&hero);
        body.append(&side);

        // Nothing playing.
        let empty = crate::ui::widgets::status("folder-music-symbolic", "Nothing playing", "Pick something to play, and it shows here.");
        let find = crate::ui::widgets::button(None, "Find something", "suggested");
        find.set_halign(gtk::Align::Center);
        find.connect_clicked(|_| {
            let page = crate::ui::pages::search_page();
            page.set_widget_name("section:0");
            if let Some(nav) = ctx().nav.borrow().as_ref() {
                nav.replace(&[page]);
            }
        });
        empty.append(&find);

        let breakpoints = adw::BreakpointBin::builder().width_request(320).height_request(360).child(&body).build();
        let narrow = adw::Breakpoint::new(adw::BreakpointCondition::parse("max-width: 880sp").expect("a breakpoint"));
        breakpoints.add_breakpoint(narrow.clone());
        let content = gtk::Stack::builder().transition_type(gtk::StackTransitionType::Crossfade).build();
        content.add_named(&empty, Some("empty"));
        content.add_named(&breakpoints, Some("playing"));

        // Behind everything: the cover, blurred and huge, and a shade to read over.
        let backdrop = gtk::Stack::builder()
            .transition_type(gtk::StackTransitionType::Crossfade)
            .transition_duration(1200)
            .build();
        backdrop.add_css_class("np-backdrop");
        backdrop.add_named(&gtk::Box::new(gtk::Orientation::Horizontal, 0), Some("none"));
        let shade = gtk::Box::builder().can_target(false).build();
        shade.add_css_class("np-shade");
        let overlay = gtk::Overlay::builder().child(&backdrop).build();
        overlay.add_overlay(&shade);
        overlay.add_overlay(&content);
        overlay.add_css_class("np");

        let header = adw::HeaderBar::builder().show_title(false).build();
        let fullscreen = icon_button("view-fullscreen-symbolic", "Full screen");
        fullscreen.connect_clicked(|b| {
            let window = ctx().window.clone();
            if window.is_fullscreen() {
                window.unfullscreen();
                b.set_icon_name("view-fullscreen-symbolic");
                b.set_tooltip_text(Some("Full screen"));
            } else {
                window.fullscreen();
                b.set_icon_name("view-restore-symbolic");
                b.set_tooltip_text(Some("Leave full screen"));
            }
        });
        header.pack_end(&fullscreen);
        let toolbar = adw::ToolbarView::builder().extend_content_to_top_edge(true).content(&overlay).build();
        toolbar.add_top_bar(&header);
        toolbar.add_css_class("np");
        let page = adw::NavigationPage::builder().title("Now playing").child(&toolbar).build();

        let saved = ctx().config.borrow().now_playing_view.clone();
        let np = Rc::new(Np {
            page,
            content,
            backdrop,
            body,
            hero: hero.upcast(),
            side,
            cover_frame,
            cover,
            channel,
            channel_art,
            channel_name,
            channel_number,
            title,
            artist,
            thumb_down,
            thumb_up,
            star,
            quiet_star: Cell::new(false),
            menu_holder,
            quality,
            live_song,
            live_into,
            live_bar,
            live_length,
            dj,
            dj_text,
            progress,
            elapsed,
            scale,
            total,
            previous,
            play,
            next,
            tabs,
            mini,
            mini_art,
            mini_title,
            mini_artist,
            panel,
            list_holder,
            lyrics,
            view: Cell::new(View::of(&saved).unwrap_or(View::Lyrics)),
            narrow: Cell::new(false),
            shown: RefCell::new(None),
            art_shown: RefCell::new(None),
            list_key: RefCell::new(String::new()),
            recent: RefCell::new((String::new(), None)),
        });
        let _ = cover_stack;

        for (tab, view) in np.tabs.iter().zip([View::Cover, View::Lyrics, View::Queue]) {
            let weak = Rc::downgrade(&np);
            tab.connect_toggled(move |tab| {
                if tab.is_active()
                    && let Some(np) = weak.upgrade()
                    && np.view.get() != view
                {
                    np.choose(view);
                    let ctx = ctx();
                    let mut config = ctx.config.borrow_mut();
                    config.now_playing_view = view.id().into();
                    config.save();
                }
            });
        }
        let weak = Rc::downgrade(&np);
        narrow.connect_apply(move |_| {
            if let Some(np) = weak.upgrade() {
                np.narrow.set(true);
                np.layout();
            }
        });
        let weak = Rc::downgrade(&np);
        narrow.connect_unapply(move |_| {
            if let Some(np) = weak.upgrade() {
                np.narrow.set(false);
                np.layout();
            }
        });
        let weak = Rc::downgrade(&np);
        np.star.connect_toggled(move |b| {
            let Some(np) = weak.upgrade() else { return };
            b.set_icon_name(if b.is_active() { "starred-symbolic" } else { "non-starred-symbolic" });
            b.set_tooltip_text(Some(if b.is_active() { "Favourite (click to remove)" } else { "Add to favourites" }));
            if np.quiet_star.get() {
                return;
            }
            let Some(track) = ctx().player.state().current().cloned().filter(|t| t.is_live()) else { return };
            let Some(api) = ctx().api() else { return };
            let on = b.is_active();
            glib::spawn_future_local(async move {
                let title = track.title.clone();
                match bg(async move { api.set_live_favorite(&track, on).await }).await {
                    Ok(_) => {
                        crate::ui::window::refresh_sidebar();
                        ctx().toast(&if on {
                            format!("{title} is in your favourites, on the Radio page")
                        } else {
                            format!("Removed {title} from your favourites")
                        });
                    }
                    Err(e) => ctx().failed(&e),
                }
            });
        });
        // Shown again (after another page): the lyrics find their place.
        let weak = Rc::downgrade(&np);
        np.page.connect_shown(move |_| {
            if let Some(np) = weak.upgrade() {
                np.lyrics.follow(false);
            }
        });
        np.layout();
        np
    }

    /// Shows [view]: in a wide window the cover is always there, so "Song" shows the lyrics.
    fn choose(self: &Rc<Self>, view: View) {
        self.view.set(view);
        let index = match view {
            View::Cover => 0,
            View::Lyrics => 1,
            View::Queue => 2,
        };
        if !self.tabs[index].is_active() {
            self.tabs[index].set_active(true);
        }
        self.layout();
        let state = ctx().player.state();
        self.list_key.borrow_mut().clear();
        self.update(&state);
    }

    /// The view that's showing (in a wide window, the cover is always there).
    fn effective(&self) -> View {
        match self.view.get() {
            View::Cover if !self.narrow.get() => View::Lyrics,
            view => view,
        }
    }

    fn layout(&self) {
        let narrow = self.narrow.get();
        let view = self.effective();
        self.body.set_orientation(if narrow { gtk::Orientation::Vertical } else { gtk::Orientation::Horizontal });
        self.body.set_homogeneous(!narrow);
        self.body.set_spacing(if narrow { 18 } else { 64 });
        self.body.set_margin_start(if narrow { 20 } else { 48 });
        self.body.set_margin_end(if narrow { 20 } else { 48 });
        // Narrow: the tabs on top, then the cover or what was chosen.
        if narrow {
            self.body.reorder_child_after(&self.hero, Some(&self.side));
        } else {
            self.body.reorder_child_after(&self.side, Some(&self.hero));
        }
        self.tabs[0].set_visible(narrow);
        if !narrow && self.tabs[0].is_active() {
            self.tabs[1].set_active(true);
        }
        let cover_view = narrow && view == View::Cover;
        self.hero.set_visible(!narrow || cover_view);
        self.panel.set_visible(!cover_view);
        self.side.set_vexpand(!cover_view);
        self.side.set_halign(if cover_view { gtk::Align::Center } else { gtk::Align::Fill });
        self.mini.set_visible(narrow && !cover_view);
        if let Some(tabs) = self.tabs[0].parent() {
            tabs.set_halign(if narrow { gtk::Align::Center } else { gtk::Align::Start });
        }
    }

    fn follow_link(&self, uri: &str) {
        let Some(shown) = self.shown.borrow().clone() else { return };
        let Some(song) = shown.song.as_ref().filter(|s| !s.artist.is_empty()) else { return };
        match uri {
            "artist" => ctx().open_artist(None, &song.artist),
            "album" => ctx().open_album(None, shown.album.as_deref().unwrap_or_default(), &song.artist),
            _ => {}
        }
    }

    /// Follows the player (four times a second while playing): what changed is redrawn.
    fn update(self: &Rc<Self>, state: &State) {
        let Some(shown) = shown_of(state) else {
            self.content.set_visible_child_name("empty");
            self.shown.replace(None);
            self.show_art(None);
            return;
        };
        self.content.set_visible_child_name("playing");
        let changed = self.shown.borrow().as_ref() != Some(&shown);
        if changed {
            self.show_song(&shown);
            self.shown.replace(Some(shown.clone()));
        }
        let live_clock = ctx().player.live_clock();

        // How it plays.
        if !shown.live {
            let text = if state.description.is_empty() && state.loading {
                "Finding the best copy…".to_string()
            } else {
                state.description.clone()
            };
            self.quality.set_text(&text);
        }
        self.quality.set_visible(!shown.live);
        // On live radio, how far into the song on now it is, when the channel says.
        let into = state
            .on_air
            .as_ref()
            .filter(|o| shown.live && o.start_ms > 0 && o.duration_ms > 0)
            .and_then(|o| Some(((live_clock? - o.start_ms).clamp(0, o.duration_ms), o.duration_ms)));
        self.live_song.set_visible(into.is_some());
        if let Some((into, length)) = into {
            self.live_into.set_text(&duration(into));
            self.live_length.set_text(&duration(length));
            self.live_bar.set_fraction(into as f64 / length as f64);
        }
        let dj = state.personal.as_ref().and_then(|p| p.dj.as_ref()).filter(|_| shown.personal);
        self.dj.set_visible(dj.is_some());
        if let Some(dj) = dj {
            self.dj_text.set_text(&dj.text);
        }
        // Thumbs, where the station takes them.
        let thumbs = state.thumbs_now();
        let station_song = shown.base.is_station_track();
        for (button, direction) in [(&self.thumb_down, "DOWN"), (&self.thumb_up, "UP")] {
            button.set_visible(thumbs.is_some());
            button.set_sensitive(thumbs.as_ref().is_some_and(|(can, _)| *can));
            if thumbs.as_ref().and_then(|(_, t)| t.as_deref()) == Some(direction) {
                button.add_css_class("on");
            } else {
                button.remove_css_class("on");
            }
        }
        if station_song {
            self.thumb_down.set_tooltip_text(Some("Thumbs down (skips it)"));
            self.thumb_up.set_tooltip_text(Some("Thumbs up"));
        } else {
            self.thumb_down.set_tooltip_text(Some("Thumbs down (never play it again)"));
            self.thumb_up.set_tooltip_text(Some("Thumbs up (more like this)"));
        }
        // The channel or station's star.
        self.star.set_visible(shown.live);
        if shown.live {
            let on = crate::ui::window::is_favourite(&track_key(&shown.base));
            if self.star.is_active() != on {
                self.quiet_star.set(true);
                self.star.set_active(on);
                self.quiet_star.set(false);
            }
        }

        // The controls.
        if state.playing {
            self.cover_frame.add_css_class("playing");
        } else {
            self.cover_frame.remove_css_class("playing");
        }
        self.play.set_icon_name(if state.playing { "media-playback-pause-symbolic" } else { "media-playback-start-symbolic" });
        self.play.set_tooltip_text(Some(if state.playing { "Pause" } else { "Play" }));
        self.previous.set_sensitive(!shown.live);
        self.next.set_sensitive(state.has_next() && (!shown.live || shown.personal));
        self.next.set_tooltip_text(Some(if shown.personal { "Skip this song" } else { "Next" }));
        let seekable = !shown.live && state.duration > 0.0;
        self.progress.set_visible(seekable);
        if seekable {
            self.scale.set_range(0.0, state.duration.max(1.0));
            self.scale.set_value(state.position);
            self.elapsed.set_text(&duration((state.position * 1000.0) as i64));
            self.total.set_text(&duration((state.duration * 1000.0) as i64));
        }

        // Beside it.
        self.tabs[2].set_label(if shown.live && !shown.personal { "Played" } else { "Up next" });
        match self.effective() {
            View::Lyrics | View::Cover => {
                self.panel.set_visible_child_name("lyrics");
                let lyrics_for = if shown.live { shown.song.clone() } else { Some(shown.base.clone()) };
                self.lyrics.show(lyrics_for.as_ref(), !shown.live, lyrics_clock(state, live_clock));
            }
            View::Queue => {
                self.panel.set_visible_child_name("list");
                self.show_list(state, &shown, live_clock);
            }
        }
    }

    fn show_song(&self, shown: &Shown) {
        self.title.set_text(&shown.title);
        self.title.set_tooltip_text(Some(&shown.title));
        let escape = |s: &str| glib::markup_escape_text(s).to_string();
        let linked = shown.song.as_ref().is_some_and(|s| !s.artist.is_empty());
        let mut markup = if linked {
            format!("<a href=\"artist\">{}</a>", escape(&shown.artist))
        } else {
            escape(&shown.artist)
        };
        if let Some(album) = &shown.album {
            markup += "<span alpha=\"60%\"> · </span>";
            markup += &if linked { format!("<a href=\"album\">{}</a>", escape(album)) } else { escape(album) };
        }
        self.artist.set_markup(&markup);
        self.artist.set_tooltip_text(None);
        self.mini_title.set_text(&shown.title);
        self.mini_artist.set_text(&shown.artist);
        clear(&self.mini_art);
        self.mini_art.append(&artwork(shown.artwork.as_deref(), 52, false));
        // The channel above the song.
        self.channel.set_visible(shown.live);
        if shown.live {
            clear(&self.channel_art);
            self.channel_art.append(&artwork(shown.base.artwork_url.as_deref(), 28, false));
            self.channel_name.set_text(&shown.base.title);
            let number = if shown.base.source == "SIRIUSXM" {
                shown.base.artist.trim_start_matches("SiriusXM").trim_start_matches(" · ").to_string()
            } else {
                String::new()
            };
            self.channel_number.set_text(&number);
            self.channel_number.set_visible(!number.is_empty());
        }
        // The song's menu (not for a channel).
        clear(&self.menu_holder);
        if let Some(song) = shown.song.as_ref().filter(|s| !s.source_id.starts_with("channel:") && !s.is_live()) {
            self.menu_holder.append(&track_menu(song, 0, None));
        }
        self.show_art(shown.artwork.clone());
    }

    /// The cover, large, and its colours over the page.
    fn show_art(&self, url: Option<String>) {
        if self.art_shown.borrow().as_ref() == Some(&url) {
            return;
        }
        self.art_shown.replace(Some(url.clone()));
        let Some(url) = url.filter(|u| !u.is_empty()) else {
            self.cover.set_paintable(None::<&gdk::Paintable>);
            self.set_cover_stack("none");
            self.backdrop.set_visible_child_name("none");
            set_accent(None);
            return;
        };
        let address = cover_url(&url, 1024);
        let wanted = Some(url.clone());
        glib::spawn_future_local(async move {
            let bytes = fetch(address).await;
            let Some(np) = PAGE.with(|p| p.borrow().clone()) else { return };
            if *np.art_shown.borrow() != Some(wanted) {
                return;
            }
            let Some(bytes) = bytes else {
                np.set_cover_stack("none");
                return;
            };
            match gdk::Texture::from_bytes(&glib::Bytes::from(&bytes)) {
                Ok(texture) => {
                    np.cover.set_paintable(Some(&texture));
                    np.set_cover_stack("cover");
                }
                Err(_) => np.set_cover_stack("none"),
            }
            let (backdrop, accent) = match colours_of(&bytes) {
                Some((texture, accent)) => (Some(texture), accent),
                None => (None, None),
            };
            set_accent(accent);
            match backdrop {
                Some(texture) => {
                    // Songs crossfade into each other.
                    let picture = gtk::Picture::builder()
                        .paintable(&texture)
                        .content_fit(gtk::ContentFit::Cover)
                        .can_shrink(true)
                        .build();
                    let name = format!("art{}", glib::random_int());
                    np.backdrop.add_named(&picture, Some(&name));
                    np.backdrop.set_visible_child_name(&name);
                    // The ones before go once faded out.
                    let backdrop = np.backdrop.clone();
                    glib::timeout_add_local_once(Duration::from_millis(1500), move || {
                        let mut child = backdrop.first_child();
                        while let Some(c) = child {
                            child = c.next_sibling();
                            if backdrop.visible_child().as_ref() != Some(&c) && backdrop.page(&c).name().is_some_and(|n| n.starts_with("art")) {
                                backdrop.remove(&c);
                            }
                        }
                    });
                }
                None => np.backdrop.set_visible_child_name("none"),
            }
        });
    }

    fn set_cover_stack(&self, name: &str) {
        if let Some(stack) = self.cover_frame.first_child().and_downcast::<gtk::Stack>() {
            stack.set_visible_child_name(name);
        }
    }

    /// Up next, or for live radio what it played (and on the person's own stations, what's coming).
    fn show_list(&self, state: &State, shown: &Shown, live_clock: Option<i64>) {
        let key = if !shown.live {
            let keys: Vec<String> = state.queue.iter().map(track_key).collect();
            format!("queue|{:?}|{}", state.index, keys.join(","))
        } else if shown.personal {
            let p = state.personal.as_ref();
            let titles = |v: Option<&Vec<NowView>>| v.map(|v| v.iter().map(|n| n.title.clone()).collect::<Vec<_>>().join(",")).unwrap_or_default();
            format!("coming|{}|{}|{}", p.is_some(), titles(p.map(|p| &p.next)), titles(p.map(|p| &p.recent)))
        } else {
            let on = state.on_air.as_ref().map(|o| o.title.clone()).unwrap_or_default();
            let history: Vec<String> = state.live_history.iter().map(|h| h.title.clone()).collect();
            let recent = self.recent.borrow();
            format!("played|{}|{on}|{}|{}|{}", track_key(&shown.base), history.join(","), recent.0, recent.1.as_ref().map(|r| r.len() as i64).unwrap_or(-1))
        };
        if *self.list_key.borrow() == key {
            return;
        }
        self.list_key.replace(key);
        clear(&self.list_holder);
        if !shown.live {
            let card = queue_view(state, None);
            card.add_css_class("np-panel-card");
            self.list_holder.append(&card);
        } else if shown.personal {
            self.list_holder.append(&coming_up(state, &shown.base));
        } else {
            self.list_holder.append(&self.played(state, shown, live_clock));
        }
    }

    /// What the channel played before: SiriusXM's last hours, or for a radio station what it
    /// said since it was tuned in. Any song can be saved, played or looked up.
    fn played(&self, state: &State, shown: &Shown, live_clock: Option<i64>) -> gtk::Widget {
        let channel = &shown.base;
        let sxm = channel.is_channel() && channel.via.is_some();
        let rows: Vec<(String, String, Option<String>, Option<Track>, Option<i64>)> = if sxm {
            let on = state.on_air.as_ref().map(|o| format!("{}|{}", o.title, o.artist)).unwrap_or_default();
            let wanted = format!("{}|{on}", track_key(channel));
            let asked = self.recent.borrow().0 == wanted;
            if !asked {
                self.recent.replace((wanted.clone(), None));
                if let (Some(api), Some((kind, id)), Some(via)) = (ctx().api(), channel.channel_parts(), channel.via.clone()) {
                    let at = live_clock.unwrap_or_else(now_ms);
                    glib::spawn_future_local(async move {
                        let songs = bg(async move { api.channel_recent(&via, &kind, &id, at).await }).await.unwrap_or_default();
                        let Some(np) = PAGE.with(|p| p.borrow().clone()) else { return };
                        if np.recent.borrow().0 != wanted {
                            return;
                        }
                        np.recent.replace((wanted, Some(songs)));
                        np.list_key.borrow_mut().clear();
                        ctx().player.nudge();
                    });
                }
            }
            let recent = self.recent.borrow();
            let Some(songs) = recent.1.as_ref() else { return crate::ui::widgets::loading() };
            let clock = live_clock.unwrap_or_else(now_ms);
            songs
                .iter()
                .map(|r| (r.title.clone(), r.artist.clone(), r.artwork_url.clone(), r.song.clone(), Some(clock - r.start_ms)))
                .collect()
        } else {
            state
                .live_history
                .iter()
                .map(|h| (h.title.clone(), h.artist.clone(), h.artwork_url.clone(), h.song.clone(), (h.heard_at > 0).then(|| now_ms() - h.heard_at)))
                .collect()
        };
        if rows.is_empty() {
            return note(
                "Nothing yet",
                if sxm {
                    "The channel hasn't said what it played before this."
                } else {
                    "Songs the station plays while you listen are listed here."
                },
            );
        }
        let list = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(2).build();
        for (title, artist, art, song, at) in rows {
            list.append(&played_row(&title, &artist, art.as_deref().or(channel.artwork_url.as_deref()), song.as_ref(), at.map(ago)));
        }
        list.add_css_class("np-panel-card");
        list.upcast()
    }
}

/// A note in the panel: a title and a line.
fn note(title: &str, text: &str) -> gtk::Widget {
    let b = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(6)
        .valign(gtk::Align::Center)
        .vexpand(true)
        .margin_top(80)
        .margin_start(6)
        .build();
    if !title.is_empty() {
        b.append(&label(title, "np-note-title"));
    }
    let line = label(text, "np-muted");
    line.set_wrap(true);
    b.append(&line);
    b.upcast()
}

/// A song the station played or will play: its cover, title and artist, when, and its menu.
fn played_row(title: &str, artist: &str, art: Option<&str>, song: Option<&Track>, when: Option<String>) -> gtk::Widget {
    let row = gtk::Box::builder().spacing(12).margin_top(4).margin_bottom(4).margin_start(4).margin_end(4).build();
    row.append(&artwork(art, 48, false));
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).valign(gtk::Align::Center).hexpand(true).build();
    let t = label(title, "track-title");
    t.set_ellipsize(gtk::pango::EllipsizeMode::End);
    let a = label(artist, "track-sub");
    a.set_ellipsize(gtk::pango::EllipsizeMode::End);
    text.append(&t);
    text.append(&a);
    row.append(&text);
    if let Some(when) = when {
        row.append(&label(&when, "np-faint"));
    }
    if let Some(song) = song {
        row.append(&track_menu(song, 0, None));
    }
    row.upcast()
}

/// On one of the person's own stations: what's coming up, and what it played before.
fn coming_up(state: &State, channel: &Track) -> gtk::Widget {
    let Some(info) = state.personal.as_ref() else { return crate::ui::widgets::loading() };
    if info.next.is_empty() && info.recent.is_empty() {
        return note("Warming up", "The station's line-up shows here in a moment.");
    }
    let list = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(2).build();
    list.add_css_class("np-panel-card");
    let section = |title: &str| {
        let l = label(&title.to_uppercase(), "queue-section");
        list.append(&l);
    };
    if !info.next.is_empty() {
        section("Coming up");
        for r in &info.next {
            list.append(&played_row(&r.title, &r.artist, r.artwork_url.as_deref().or(channel.artwork_url.as_deref()), r.song.as_ref(), None));
        }
    }
    if !info.recent.is_empty() {
        section("Played");
        for r in &info.recent {
            let when = (info.server_time > 0 && r.start_ms > 0).then(|| ago(info.server_time - r.start_ms));
            list.append(&played_row(&r.title, &r.artist, r.artwork_url.as_deref().or(channel.artwork_url.as_deref()), r.song.as_ref(), when));
        }
    }
    list.upcast()
}

/// The queue, as the web player's: what's playing and what's next, to play from (click), move
/// up or down, or take out. [on_jump] is told when a song is chosen (a popover closes).
pub fn queue_view(state: &State, on_jump: Option<Rc<dyn Fn()>>) -> gtk::Box {
    let view = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(2).build();
    let head = gtk::Box::builder().spacing(8).margin_start(8).margin_end(4).build();
    let heading = label("Queue", "queue-heading");
    heading.set_hexpand(true);
    head.append(&heading);
    let upcoming = state.upcoming().len();
    if upcoming > 0 {
        let clear_button = crate::ui::widgets::button(None, "Clear", "ghost");
        clear_button.connect_clicked(|_| ctx().player.clear_upcoming());
        head.append(&clear_button);
    }
    view.append(&head);
    let Some(index) = state.index.filter(|i| *i < state.queue.len()) else {
        view.append(&crate::ui::widgets::status("view-list-symbolic", "The queue is empty", "Play something from search or a playlist."));
        return view;
    };
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::None).build();
    list.add_css_class("track-list");
    let section = |text: &str| {
        let row = gtk::ListBoxRow::builder().activatable(false).selectable(false).build();
        row.set_child(Some(&label(&text.to_uppercase(), "queue-section")));
        row
    };
    list.append(&section("Now playing"));
    let mut indexes: Vec<Option<usize>> = vec![None];
    list.append(&queue_row(&state.queue[index], index, true, state.queue.len()));
    indexes.push(Some(index));
    if upcoming > 0 {
        list.append(&section(&format!("Next up · {upcoming}")));
        indexes.push(None);
        // A long queue shows its next few hundred.
        for i in (index + 1..state.queue.len()).take(300) {
            list.append(&queue_row(&state.queue[i], i, false, state.queue.len()));
            indexes.push(Some(i));
        }
    }
    list.connect_row_activated(move |_, row| {
        if let Some(Some(i)) = indexes.get(row.index() as usize) {
            ctx().player.jump_to(*i);
            if let Some(on_jump) = &on_jump {
                on_jump();
            }
        }
    });
    view.append(&list);
    view
}

fn queue_row(track: &Track, index: usize, current: bool, count: usize) -> gtk::ListBoxRow {
    let line = gtk::Box::builder().spacing(12).build();
    line.append(&artwork(track.artwork_url.as_deref(), 40, false));
    let text = gtk::Box::builder().orientation(gtk::Orientation::Vertical).valign(gtk::Align::Center).hexpand(true).build();
    let title = label(&track.title, "track-title");
    title.set_ellipsize(gtk::pango::EllipsizeMode::End);
    let artist = label(&track.artist, "track-sub");
    artist.set_ellipsize(gtk::pango::EllipsizeMode::End);
    text.append(&title);
    text.append(&artist);
    line.append(&text);
    if current {
        let playing = gtk::Image::from_icon_name("media-playback-start-symbolic");
        playing.add_css_class("accent");
        line.append(&playing);
    } else {
        let up = icon_button("go-up-symbolic", "Move up");
        up.set_sensitive(index > 0);
        up.connect_clicked(move |_| ctx().player.move_item(index, index - 1));
        let down = icon_button("go-down-symbolic", "Move down");
        down.set_sensitive(index + 1 < count);
        down.connect_clicked(move |_| ctx().player.move_item(index, index + 1));
        let remove = icon_button("window-close-symbolic", "Remove from the queue");
        remove.connect_clicked(move |_| ctx().player.remove(index));
        line.append(&up);
        line.append(&down);
        line.append(&remove);
    }
    let row = gtk::ListBoxRow::builder().child(&line).activatable(true).tooltip_text(format!("Play {}", track.title)).build();
    if current {
        row.add_css_class("current");
    }
    row
}

// ---- Lyrics ----

/// The song's lyrics. Timed ones follow along (the line being sung stays in view, a third of the
/// way down) and any line can be clicked to play from there; scrolling by hand pauses the
/// following for a few seconds.
struct Lyrics {
    root: gtk::Overlay,
    stack: gtk::Stack,
    scroll: gtk::ScrolledWindow,
    content: gtk::Box,
    list: gtk::Box,
    lead: gtk::Box,
    tail: gtk::Box,
    source: gtk::Label,
    note_holder: gtk::Box,
    back: gtk::Button,
    /// The lines' times, and their widgets.
    lines: RefCell<Vec<(Option<i64>, gtk::Widget)>>,
    synced: Cell<bool>,
    current: Cell<Option<usize>>,
    hand_at: Cell<Option<Instant>>,
    animation: RefCell<Option<adw::TimedAnimation>>,
    /// The song whose lyrics are shown (or asked for), and whether they can be jumped about in.
    key: RefCell<Option<String>>,
    seekable: Cell<bool>,
}

impl Lyrics {
    fn new() -> Rc<Lyrics> {
        let lead = gtk::Box::new(gtk::Orientation::Vertical, 0);
        let tail = gtk::Box::new(gtk::Orientation::Vertical, 0);
        let list = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(8).build();
        let source = label("", "np-faint");
        source.set_margin_top(40);
        source.set_margin_start(12);
        source.set_wrap(true);
        let content = gtk::Box::builder().orientation(gtk::Orientation::Vertical).margin_end(8).build();
        content.append(&lead);
        content.append(&list);
        content.append(&source);
        content.append(&tail);
        let scroll = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .vscrollbar_policy(gtk::PolicyType::External)
            .vexpand(true)
            .child(&content)
            .build();
        let note_holder = gtk::Box::builder().orientation(gtk::Orientation::Vertical).vexpand(true).build();
        let stack = gtk::Stack::builder().transition_type(gtk::StackTransitionType::Crossfade).vexpand(true).build();
        stack.add_named(&scroll, Some("lines"));
        stack.add_named(&note_holder, Some("note"));
        stack.add_named(&crate::ui::widgets::loading(), Some("loading"));
        let back = gtk::Button::builder()
            .label("Back to the song")
            .halign(gtk::Align::Center)
            .valign(gtk::Align::End)
            .margin_bottom(18)
            .visible(false)
            .build();
        back.add_css_class("np-back");
        let root = gtk::Overlay::builder().child(&stack).vexpand(true).build();
        root.add_overlay(&back);
        root.add_css_class("np-lyrics");
        let lyrics = Rc::new(Lyrics {
            root,
            stack,
            scroll,
            content,
            list,
            lead,
            tail,
            source,
            note_holder,
            back,
            lines: RefCell::new(vec![]),
            synced: Cell::new(false),
            current: Cell::new(None),
            hand_at: Cell::new(None),
            animation: RefCell::new(None),
            key: RefCell::new(None),
            seekable: Cell::new(false),
        });
        // Scrolling by hand: the lyrics stop following for a while, and offer to go back.
        let scrolled = gtk::EventControllerScroll::new(gtk::EventControllerScrollFlags::VERTICAL);
        scrolled.set_propagation_phase(gtk::PropagationPhase::Capture);
        let weak = Rc::downgrade(&lyrics);
        scrolled.connect_scroll(move |_, _, _| {
            if let Some(l) = weak.upgrade()
                && l.synced.get()
            {
                l.hand_at.set(Some(Instant::now()));
                if let Some(animation) = l.animation.borrow_mut().take() {
                    animation.pause();
                }
                l.back.set_visible(l.current.get().is_some());
            }
            glib::Propagation::Proceed
        });
        lyrics.scroll.add_controller(scrolled);
        let weak = Rc::downgrade(&lyrics);
        lyrics.back.connect_clicked(move |_| {
            if let Some(l) = weak.upgrade() {
                l.hand_at.set(None);
                l.back.set_visible(false);
                l.follow(true);
            }
        });
        // Room above the first line and below the last, as parts of the panel: the line being
        // sung sits a third of the way down, whatever the window's size.
        let weak = Rc::downgrade(&lyrics);
        lyrics.scroll.vadjustment().connect_page_size_notify(move |adjustment| {
            if let Some(l) = weak.upgrade() {
                let page = adjustment.page_size();
                let synced = l.synced.get();
                l.lead.set_height_request(if synced { (page * 0.36) as i32 } else { (page * 0.04) as i32 });
                l.tail.set_height_request(if synced { (page * 0.52) as i32 } else { (page * 0.12) as i32 });
                let me = l.clone();
                glib::idle_add_local_once(move || me.follow(false));
            }
        });
        lyrics
    }

    /// Shows the lyrics of [track] (asking for them if they're another song's), the line at [ms].
    fn show(self: &Rc<Self>, track: Option<&Track>, seekable: bool, ms: Option<i64>) {
        let key = track.map(track_key);
        if *self.key.borrow() != key || self.seekable.get() != seekable {
            self.key.replace(key.clone());
            self.seekable.set(seekable);
            self.load(track.cloned(), seekable);
            return;
        }
        if !self.synced.get() {
            return;
        }
        let times: Vec<Option<i64>> = self.lines.borrow().iter().map(|(t, _)| *t).collect();
        let now = current_line(&times, ms);
        if now == self.current.get() {
            return;
        }
        self.current.set(now);
        self.mark();
        let handled = self.hand_at.get().is_some_and(|at| at.elapsed() < HAND_SCROLL_PAUSE);
        if !handled {
            self.hand_at.set(None);
            self.back.set_visible(false);
            self.follow(true);
        }
    }

    fn load(self: &Rc<Self>, track: Option<Track>, seekable: bool) {
        self.lines.borrow_mut().clear();
        self.current.set(None);
        self.synced.set(false);
        self.hand_at.set(None);
        self.back.set_visible(false);
        while let Some(child) = self.list.first_child() {
            self.list.remove(&child);
        }
        let Some(track) = track else {
            self.note(
                "",
                "When the channel says which song is on, its lyrics show here.",
            );
            return;
        };
        let key = track_key(&track);
        if let Some(found) = LYRICS.with(|l| l.borrow().get(&key).cloned()) {
            self.fill(found, seekable);
            return;
        }
        self.stack.set_visible_child_name("loading");
        let Some(api) = ctx().api() else { return };
        let me = self.clone();
        glib::spawn_future_local(async move {
            let asked = track.clone();
            let found = bg(async move { api.lyrics(&asked).await }).await.unwrap_or_default();
            LYRICS.with(|l| {
                let mut l = l.borrow_mut();
                if l.len() > 40 {
                    l.clear();
                }
                l.insert(key.clone(), found.clone());
            });
            if *me.key.borrow() != Some(key) {
                return;
            }
            me.fill(found, seekable);
            ctx().player.nudge();
        });
    }

    fn note(&self, title: &str, text: &str) {
        while let Some(child) = self.note_holder.first_child() {
            self.note_holder.remove(&child);
        }
        self.note_holder.append(&note(title, text));
        self.stack.set_visible_child_name("note");
    }

    fn fill(self: &Rc<Self>, lyrics: LyricsView, seekable: bool) {
        if lyrics.lines.is_empty() {
            self.note("No lyrics for this one", "Neither your library nor LRCLIB has them. Enjoy the music.");
            return;
        }
        let synced = lyrics.synced;
        self.synced.set(synced);
        if synced {
            self.root.remove_css_class("plain");
        } else {
            self.root.add_css_class("plain");
        }
        let mut lines = vec![];
        for line in &lyrics.lines {
            let text = line.text.trim();
            let words = gtk::Label::builder()
                .label(if text.is_empty() { "•  •  •" } else { text })
                .xalign(0.0)
                .wrap(true)
                .wrap_mode(gtk::pango::WrapMode::WordChar)
                .build();
            let can_seek = synced && seekable && line.time_ms.is_some();
            let widget: gtk::Widget = if can_seek {
                let button = gtk::Button::builder().child(&words).tooltip_text(format!("Play from {}", duration(line.time_ms.unwrap_or(0)))).build();
                let at = line.time_ms.unwrap_or(0);
                let me = Rc::downgrade(self);
                button.connect_clicked(move |_| {
                    ctx().player.seek(at as f64 / 1000.0);
                    if let Some(me) = me.upgrade() {
                        me.hand_at.set(None);
                        me.back.set_visible(false);
                    }
                });
                button.upcast()
            } else {
                let holder = gtk::Box::new(gtk::Orientation::Vertical, 0);
                holder.append(&words);
                holder.upcast()
            };
            widget.add_css_class("np-lyric");
            self.list.append(&widget);
            lines.push((line.time_ms, widget));
        }
        self.lines.replace(lines);
        self.source.set_text(&format!(
            "{}Lyrics from {}",
            if synced { "" } else { "These lyrics aren't timed. " },
            if lyrics.source.is_empty() { "the web" } else { &lyrics.source }
        ));
        let page = self.scroll.vadjustment().page_size();
        self.lead.set_height_request(if synced { (page * 0.36) as i32 } else { (page * 0.04) as i32 });
        self.tail.set_height_request(if synced { (page * 0.52) as i32 } else { (page * 0.12) as i32 });
        self.stack.set_visible_child_name("lines");
        self.scroll.vadjustment().set_value(0.0);
        self.mark();
    }

    /// The line being sung, bright; the far ones, soft.
    fn mark(&self) {
        let current = self.current.get();
        for (i, (_, widget)) in self.lines.borrow().iter().enumerate() {
            if Some(i) == current {
                widget.add_css_class("current");
            } else {
                widget.remove_css_class("current");
            }
            let far = current.is_some_and(|c| i.abs_diff(c) > 2);
            if far {
                widget.add_css_class("far");
            } else {
                widget.remove_css_class("far");
            }
        }
    }

    /// Scrolls so the line being sung is a third of the way down (only the lyrics move).
    fn follow(&self, smooth: bool) {
        if !self.synced.get() || self.hand_at.get().is_some_and(|at| at.elapsed() < HAND_SCROLL_PAUSE) {
            return;
        }
        let lines = self.lines.borrow();
        let Some((_, line)) = lines.get(self.current.get().unwrap_or(0)) else { return };
        let Some(point) = line.compute_point(&self.content, &gtk::graphene::Point::new(0.0, 0.0)) else { return };
        let adjustment = self.scroll.vadjustment();
        let target = (point.y() as f64 - adjustment.page_size() * 0.36 + line.height() as f64 / 2.0)
            .clamp(0.0, (adjustment.upper() - adjustment.page_size()).max(0.0));
        if let Some(animation) = self.animation.borrow_mut().take() {
            animation.pause();
        }
        if !smooth || !self.root.is_mapped() || (adjustment.value() - target).abs() < 1.0 {
            adjustment.set_value(target);
            return;
        }
        let moving = adjustment.clone();
        let animation = adw::TimedAnimation::builder()
            .widget(&self.scroll)
            .value_from(adjustment.value())
            .value_to(target)
            .duration(450)
            .easing(adw::Easing::EaseOutCubic)
            .target(&adw::CallbackAnimationTarget::new(move |v| moving.set_value(v)))
            .build();
        animation.play();
        self.animation.replace(Some(animation));
    }
}

// ---- The song's details ----

fn source_name(source: &str) -> &str {
    match source {
        "JELLYFIN" => "Jellyfin",
        "YOUTUBE_MUSIC" => "YouTube Music",
        "QOBUZ" => "Qobuz",
        "SIRIUSXM" => "SiriusXM",
        "RADIO" => "Radio station",
        "SUBSONIC" => "Subsonic library",
        other => other,
    }
}

fn source_of(track: &Track) -> String {
    if track.is_channel() {
        "SiriusXM live channel".into()
    } else if track.is_station_track() {
        "Pandora station, through SiriusXM".into()
    } else {
        source_name(&track.source).into()
    }
}

/// "44.1 kHz · 16-bit · stereo", from the decoded audio's caps.
fn decoded(heard: &crate::player::Heard) -> Option<String> {
    let rate = heard.rate?;
    let mut parts = vec![if rate % 1000 == 0 { format!("{} kHz", rate / 1000) } else { format!("{:.1} kHz", rate as f64 / 1000.0) }];
    if let Some(format) = &heard.format {
        let bits: String = format.chars().filter(|c| c.is_ascii_digit()).collect();
        if format.starts_with('F') {
            parts.push(format!("{bits}-bit float"));
        } else if !bits.is_empty() {
            parts.push(format!("{bits}-bit"));
        }
    }
    match heard.channels {
        Some(1) => parts.push("mono".into()),
        Some(2) => parts.push("stereo".into()),
        Some(n) if n > 2 => parts.push(format!("{n} channels")),
        _ => {}
    }
    Some(parts.join(" · "))
}

/// The rows of the song's details: about the song, and (while it plays) the copy playing.
fn info_rows(track: &Track, state: &State, kbps_cap: u32, levelling: (bool, f64, bool)) -> (Vec<(String, String)>, Option<Vec<(String, String)>>) {
    let current = state.current().is_some_and(|c| track_key(c) == track_key(track));
    let live = current && state.live();
    let mut rows = vec![];
    if !live && !track.artist.is_empty() {
        rows.push(("Artist".to_string(), track.artist.clone()));
    }
    if let Some(album) = track.album.as_ref().filter(|a| !a.is_empty()) {
        rows.push(("Album".into(), album.clone()));
    }
    if let Some(ms) = track.duration_ms.filter(|ms| *ms > 0) {
        rows.push(("Length".into(), duration(ms)));
    }
    rows.push(("From".into(), source_of(track)));
    rows.push((format!("{} id", source_name(&track.source)), track.source_id.clone()));
    if !current {
        return (rows, None);
    }
    let mut now = vec![("Copy".to_string(), state.description.clone())];
    if live && let Some(on) = &state.on_air {
        let said: Vec<&str> = [on.title.as_str(), on.artist.as_str()].into_iter().filter(|s| !s.is_empty()).collect();
        now.push(("On now".into(), said.join(" · ")));
    }
    if live {
        now.push((
            "Stream".into(),
            if track.is_channel() {
                "Live stream (HLS), through your server".into()
            } else {
                "Live stream, through your server".into()
            },
        ));
    }
    let copy = state.copy.as_ref();
    if let Some(kbps) = copy.and_then(|c| c.kbps) {
        let converted = if copy.is_some_and(|c| c.transcoded) { " (converted on your server)" } else { "" };
        now.push(("Bitrate".into(), format!("{kbps} kbps{converted}")));
    } else if let Some(bits) = state.heard.bitrate {
        now.push(("Bitrate".into(), format!("{} kbps", bits / 1000)));
    }
    if let Some(ext) = copy.map(|c| c.extension.clone()).filter(|e| !e.is_empty()) {
        now.push(("Format".into(), ext.to_uppercase()));
    }
    if let Some(codec) = &state.heard.codec {
        let container = state.heard.container.as_ref().map(|c| format!(" in {c}")).unwrap_or_default();
        now.push(("Codec".into(), format!("{codec}{container}")));
    }
    if let Some(d) = decoded(&state.heard) {
        now.push(("Decoded".into(), d));
    }
    if !live {
        let cap = if kbps_cap == 0 { "Original".to_string() } else { format!("{kbps_cap} kbps") };
        let over = if copy.is_some_and(|c| !c.within_cap) { " (no copy fits it: the closest plays)" } else { "" };
        now.push(("Quality cap".into(), format!("{cap}{over}")));
    }
    let (on, gain, known) = levelling;
    now.push((
        "Volume levelling".into(),
        if !on {
            "Off".into()
        } else {
            format!(
                "{}{gain:.1} dB ({})",
                if gain >= 0.0 { "+" } else { "" },
                if known { "from your library's loudness value" } else { "measured while playing" }
            )
        },
    ));
    (rows, Some(now))
}

/// Where a song comes from and, while it plays, exactly what's playing: the copy your server
/// picked, its format and bitrate, what GStreamer decodes, the quality cap and volume levelling.
fn track_info(track: &Track) {
    let dialog = adw::Dialog::builder().title(&track.title).content_width(460).build();
    let page = adw::PreferencesPage::new();
    let about = adw::PreferencesGroup::new();
    let now_group = adw::PreferencesGroup::new();
    page.add(&about);
    page.add(&now_group);
    let row = |key: &str, value: &str| {
        let r = adw::ActionRow::builder().title(key).subtitle(value).subtitle_selectable(true).build();
        r.set_use_markup(false);
        r.add_css_class("property");
        r
    };
    let shown_rows: Rc<RefCell<Vec<adw::ActionRow>>> = Rc::default();
    let fill = {
        let (track, about, now_group, shown_rows) = (track.clone(), about.clone(), now_group.clone(), shown_rows.clone());
        move || {
            let state = ctx().player.state();
            let cap = ctx().config.borrow().kbps;
            let (rows, now) = info_rows(&track, &state, cap, ctx().player.levelling());
            for r in shown_rows.borrow_mut().drain(..) {
                if r.parent().is_some_and(|p| p.is_ancestor(&about)) {
                    about.remove(&r);
                } else {
                    now_group.remove(&r);
                }
            }
            for (k, v) in rows {
                let r = row(&k, &v);
                about.add(&r);
                shown_rows.borrow_mut().push(r);
            }
            match now {
                Some(now) => {
                    now_group.set_title("Playing now");
                    now_group.set_description(None);
                    for (k, v) in now {
                        let r = row(&k, &v);
                        now_group.add(&r);
                        shown_rows.borrow_mut().push(r);
                    }
                }
                None => {
                    now_group.set_title("Not playing");
                    now_group.set_description(Some(
                        "When it plays, your server picks the copy: your library first, then the other services, for the best one within your quality setting.",
                    ));
                }
            }
        }
    };
    fill();
    // The levelling moves along while it's open.
    let weak = dialog.downgrade();
    glib::timeout_add_local(Duration::from_secs(1), move || {
        if weak.upgrade().is_none() {
            return glib::ControlFlow::Break;
        }
        fill();
        glib::ControlFlow::Continue
    });
    let toolbar = adw::ToolbarView::new();
    toolbar.add_top_bar(&adw::HeaderBar::new());
    toolbar.set_content(Some(&page));
    dialog.set_child(Some(&toolbar));
    dialog.present(Some(&ctx().window));
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::player::OnAir;

    fn song(title: &str) -> Track {
        Track {
            source: "JELLYFIN".into(),
            source_id: title.into(),
            title: title.into(),
            artist: "Velvet Satellites".into(),
            album: Some("Neon Tidepool".into()),
            via: Some("lib".into()),
            ..Default::default()
        }
    }

    fn channel() -> Track {
        Track {
            source: "SIRIUSXM".into(),
            source_id: "channel:xtra:altap".into(),
            title: "Alt Nation".into(),
            artist: "SiriusXM · 36".into(),
            via: Some("sxm".into()),
            ..Default::default()
        }
    }

    #[test]
    fn the_line_being_sung_is_the_last_one_started() {
        let times = [Some(0), Some(5_000), None, Some(12_000)];
        assert_eq!(current_line(&times, Some(-1)), None);
        assert_eq!(current_line(&times, Some(4_999)), Some(0));
        assert_eq!(current_line(&times, Some(9_000)), Some(1));
        assert_eq!(current_line(&times, Some(60_000)), Some(3));
        assert_eq!(current_line(&times, None), None);
    }

    #[test]
    fn live_radio_shows_the_song_on_now_and_its_lyrics_follow_the_song() {
        let mut state = State {
            queue: vec![channel()],
            index: Some(0),
            position: 300.0,
            ..Default::default()
        };
        let shown = shown_of(&state).unwrap();
        assert_eq!((shown.title.as_str(), shown.artist.as_str()), ("Alt Nation", "SiriusXM · 36"));
        assert!(shown.song.is_none(), "no lyrics until the channel says which song");
        state.on_air = Some(OnAir {
            title: "Midnight Signal".into(),
            artist: "Velvet Satellites".into(),
            song: Some(song("Midnight Signal")),
            start_ms: 1_000_000,
            duration_ms: 200_000,
            ..Default::default()
        });
        let shown = shown_of(&state).unwrap();
        assert_eq!(shown.title, "Midnight Signal");
        assert_eq!(shown.song.unwrap().title, "Midnight Signal");
        // 20 seconds into the song on the channel, not five minutes since tuning in.
        assert_eq!(lyrics_clock(&state, Some(1_020_000)), Some(20_250));
        assert_eq!(lyrics_clock(&state, None), None);
        let file = State {
            queue: vec![song("a")],
            index: Some(0),
            position: 10.0,
            ..Default::default()
        };
        assert_eq!(lyrics_clock(&file, None), Some(10_250));
        assert_eq!(shown_of(&file).unwrap().album.as_deref(), Some("Neon Tidepool"));
    }

    #[test]
    fn ago_reads_as_the_web_player_s() {
        assert_eq!(ago(10_000), "Just now");
        assert_eq!(ago(4 * 60_000), "4 min ago");
        assert_eq!(ago(72 * 60_000), "1 h 12 min ago");
        assert_eq!(ago(120 * 60_000), "2 h ago");
    }

    #[test]
    fn the_accent_is_the_cover_s_most_colourful_hue_made_light() {
        // Mostly a vivid red, with some grey: a light red.
        let mut pixels = vec![];
        for i in 0..100 {
            pixels.extend_from_slice(if i % 4 == 0 { &[128, 128, 128] } else { &[220, 30, 40] });
        }
        let (r, g, b) = pick_accent(&pixels).unwrap();
        assert!(r > 200 && g < 170 && b < 170, "{r} {g} {b}");
        // Black and white: the app's own colours stay.
        assert!(pick_accent(&[20, 20, 20, 240, 240, 240, 128, 128, 128].repeat(50)).is_none());
    }

    #[test]
    fn blurring_spreads_colour_and_keeps_the_size() {
        let side = 8;
        let mut rgb = vec![0u8; side * side * 3];
        rgb[(3 * side + 3) * 3] = 255;
        let soft = blur(&rgb, side, 2, 2);
        assert_eq!(soft.len(), rgb.len());
        assert!(soft[(3 * side + 3) * 3] < 255);
        assert!(soft[(3 * side + 4) * 3] > 0);
        assert_eq!(hsl_to_rgb(0.0, 1.0, 0.5), (255, 0, 0));
    }

    #[test]
    fn the_details_say_what_plays_as_the_web_player_s() {
        let track = song("a");
        let state = State {
            queue: vec![track.clone()],
            index: Some(0),
            description: "Jellyfin FLAC".into(),
            copy: Some(crate::player::Copy {
                kbps: Some(900),
                within_cap: true,
                gain_db: Some(-4.0),
                transcoded: false,
                extension: "flac".into(),
            }),
            heard: crate::player::Heard {
                codec: Some("Free Lossless Audio Codec (FLAC)".into()),
                rate: Some(44_100),
                channels: Some(2),
                format: Some("S16LE".into()),
                ..Default::default()
            },
            ..Default::default()
        };
        let (about, now) = info_rows(&track, &state, 0, (true, -2.0, true));
        assert!(about.contains(&("From".into(), "Jellyfin".into())));
        assert!(about.contains(&("Jellyfin id".into(), "a".into())));
        let now = now.unwrap();
        assert!(now.contains(&("Bitrate".into(), "900 kbps".into())));
        assert!(now.contains(&("Format".into(), "FLAC".into())));
        assert!(now.contains(&("Decoded".into(), "44.1 kHz · 16-bit · stereo".into())));
        assert!(now.contains(&("Quality cap".into(), "Original".into())));
        assert!(now.contains(&("Volume levelling".into(), "-2.0 dB (from your library's loudness value)".into())));
        let (_, not_playing) = info_rows(&song("b"), &state, 320, (false, 0.0, false));
        assert!(not_playing.is_none());
    }
}
