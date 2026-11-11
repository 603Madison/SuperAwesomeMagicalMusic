//! The look, shared with the web player and the phone app: Lexend, cool slate neutrals, rounded
//! cards, and a soft glow of the theme at the top of every page. The themes are the same as theirs:
//! Aurora (purple and green, the default), Violet, Ocean and Ember, each light and dark.

use std::cell::RefCell;
use std::ffi::{CString, c_char, c_int, c_void};

use gtk::{gdk, glib};

/// A theme's colours in one scheme.
struct Tones {
    primary: &'static str,
    on_primary: &'static str,
    primary_container: &'static str,
    on_primary_container: &'static str,
    accent: &'static str,
    accent_container: &'static str,
    glow_a: &'static str,
    glow_b: &'static str,
}

pub struct Palette {
    pub id: &'static str,
    pub name: &'static str,
    /// The two colours shown for it in Preferences.
    pub swatch: (&'static str, &'static str),
    dark: Tones,
    light: Tones,
}

pub const PALETTES: [Palette; 4] = [
    Palette {
        id: "aurora",
        name: "Aurora",
        swatch: ("#6a3fd6", "#12a37c"),
        dark: Tones {
            primary: "#cdbdff",
            on_primary: "#37197f",
            primary_container: "#4f2fb8",
            on_primary_container: "#e8deff",
            accent: "#62e0b3",
            accent_container: "#00513b",
            glow_a: "#5b3fd1",
            glow_b: "#12a37c",
        },
        light: Tones {
            primary: "#6a3fd6",
            on_primary: "#ffffff",
            primary_container: "#e8deff",
            on_primary_container: "#22005d",
            accent: "#00805e",
            accent_container: "#b5f2d9",
            glow_a: "#b9a6ff",
            glow_b: "#8fe6c8",
        },
    },
    Palette {
        id: "violet",
        name: "Violet",
        swatch: ("#5b3fd1", "#8e3f5c"),
        dark: Tones {
            primary: "#cfbdff",
            on_primary: "#341a86",
            primary_container: "#4b33a8",
            on_primary_container: "#e9ddff",
            accent: "#ffb1c8",
            accent_container: "#7b2949",
            glow_a: "#5b3fd1",
            glow_b: "#8e3f5c",
        },
        light: Tones {
            primary: "#5b3fd1",
            on_primary: "#ffffff",
            primary_container: "#e9ddff",
            on_primary_container: "#1d0060",
            accent: "#8e3f5c",
            accent_container: "#ffd9e2",
            glow_a: "#c8b8ff",
            glow_b: "#ffc2d4",
        },
    },
    Palette {
        id: "ocean",
        name: "Ocean",
        swatch: ("#1d5fc9", "#0e8fa3"),
        dark: Tones {
            primary: "#a6c8ff",
            on_primary: "#00315e",
            primary_container: "#1f4f8f",
            on_primary_container: "#d5e3ff",
            accent: "#5dd5e8",
            accent_container: "#004f58",
            glow_a: "#1d5fc9",
            glow_b: "#0e8fa3",
        },
        light: Tones {
            primary: "#1d5fc9",
            on_primary: "#ffffff",
            primary_container: "#d8e6ff",
            on_primary_container: "#001b3f",
            accent: "#00788a",
            accent_container: "#a8eefc",
            glow_a: "#a9c7ff",
            glow_b: "#93e3ef",
        },
    },
    Palette {
        id: "ember",
        name: "Ember",
        swatch: ("#b4471a", "#9c3f60"),
        dark: Tones {
            primary: "#ffb59a",
            on_primary: "#5a1c00",
            primary_container: "#8a3410",
            on_primary_container: "#ffdbcf",
            accent: "#ffb1c8",
            accent_container: "#7b2949",
            glow_a: "#c2410c",
            glow_b: "#9c3f60",
        },
        light: Tones {
            primary: "#b4471a",
            on_primary: "#ffffff",
            primary_container: "#ffdbcf",
            on_primary_container: "#3a0b00",
            accent: "#9c3f60",
            accent_container: "#ffd9e2",
            glow_a: "#ffc4ab",
            glow_b: "#ffc2d4",
        },
    },
];

pub fn palette(id: &str) -> &'static Palette {
    PALETTES.iter().find(|p| p.id == id).unwrap_or(&PALETTES[0])
}

/// The slate neutrals of the family's apps (navy in the dark).
struct Neutrals {
    bg: &'static str,
    sidebar: &'static str,
    card: &'static str,
    raised: &'static str,
    text: &'static str,
    muted: &'static str,
    faint: &'static str,
    outline: &'static str,
    shade: &'static str,
    glow_strength: (f32, f32),
}

const DARK: Neutrals = Neutrals {
    bg: "#0b1220",
    sidebar: "#0f1828",
    card: "#131d30",
    raised: "#1a2539",
    text: "#e5e9f0",
    muted: "#a9b4c6",
    faint: "#7d889c",
    outline: "#263349",
    shade: "rgba(0, 0, 0, 0.36)",
    glow_strength: (0.40, 0.28),
};

const LIGHT: Neutrals = Neutrals {
    bg: "#f5f7fb",
    sidebar: "#eef2f8",
    card: "#ffffff",
    raised: "#ffffff",
    text: "#111827",
    muted: "#4b5565",
    faint: "#6b7585",
    outline: "#dde3ec",
    shade: "rgba(17, 24, 39, 0.10)",
    glow_strength: (0.80, 0.70),
};

/// libadwaita's colours set to the theme, and the family's styles over them.
fn css(palette: &Palette, dark: bool, bold: bool) -> String {
    let n = if dark { &DARK } else { &LIGHT };
    let t = if dark { &palette.dark } else { &palette.light };
    let (glow_a, glow_b) = n.glow_strength;
    let weight = |w: u32| if bold { 700 } else { w };
    format!(
        r#"
@define-color accent_color {primary};
@define-color accent_bg_color {primary};
@define-color accent_fg_color {on_primary};
@define-color window_bg_color {bg};
@define-color window_fg_color {text};
@define-color view_bg_color {bg};
@define-color view_fg_color {text};
@define-color headerbar_bg_color {bg};
@define-color headerbar_fg_color {text};
@define-color headerbar_backdrop_color {bg};
@define-color headerbar_border_color {outline};
@define-color headerbar_shade_color transparent;
@define-color sidebar_bg_color {sidebar};
@define-color sidebar_fg_color {text};
@define-color sidebar_backdrop_color {sidebar};
@define-color sidebar_shade_color {shade};
@define-color card_bg_color {card};
@define-color card_fg_color {text};
@define-color card_shade_color {shade};
@define-color popover_bg_color {raised};
@define-color popover_fg_color {text};
@define-color dialog_bg_color {raised};
@define-color dialog_fg_color {text};
@define-color thumbnail_bg_color {card};
@define-color shade_color {shade};
@define-color samm_muted {muted};
@define-color samm_faint {faint};
@define-color samm_outline {outline};
@define-color samm_primary_container {primary_container};
@define-color samm_on_primary_container {on_primary_container};
@define-color samm_accent {accent};
@define-color samm_accent_container {accent_container};

window, dialog, popover, tooltip {{ font-family: "Lexend", "Cantarell", sans-serif; font-weight: {w400}; }}
.heading, label.title, .title-1, .title-2, .title-3, .title-4, button, .section-title, .card-title {{ font-weight: {w600}; }}
.subtitle, .card-sub, .meta, .page-subtitle, .empty-text, .login-sub, row.entry label {{ font-weight: {w400}; }}
{bold_labels}

/* Every page: the background, with two soft glows of the theme at the top, like an aurora. */
.page-bg {{
  background-color: {bg};
  background-image:
    radial-gradient(ellipse 900px 400px at 12% -120px, alpha({glow_a_color}, {glow_a}), alpha({glow_a_color}, 0) 70%),
    radial-gradient(ellipse 800px 380px at 88% -140px, alpha({glow_b_color}, {glow_b}), alpha({glow_b_color}, 0) 70%);
}}
.page-bg headerbar {{ background: none; box-shadow: none; }}

/* The sidebar, as on the web: the brand, then sections as pills, then your radio and playlists. */
.brand-name {{ font-weight: 700; font-size: 0.95em; line-height: 1.1; }}
.nav-list {{ background: none; }}
.nav-list > row {{ border-radius: 999px; margin: 1px 10px; padding: 9px 14px; color: {muted}; }}
.nav-list > row:hover {{ background: alpha(currentColor, 0.06); color: {text}; }}
.nav-list > row:selected {{ background: {primary_container}; color: {on_primary_container}; font-weight: {w600}; }}
.sidebar-heading {{ font-size: 0.72em; font-weight: 700; letter-spacing: 0.08em; color: {faint}; margin: 18px 24px 4px 24px; }}
.side-list {{ background: none; }}
.side-list > row {{ border-radius: 12px; margin: 0 10px; padding: 5px 8px; }}
.side-list > row:hover {{ background: alpha(currentColor, 0.06); }}
.sidebar-user {{ color: {faint}; font-size: 0.85em; margin: 12px 24px; }}

/* Page titles and headings, as the web player's. */
.page-title {{ font-size: 2.1em; font-weight: 700; letter-spacing: -0.02em; }}
.page-subtitle {{ color: {muted}; }}
.section-title {{ font-size: 1.2em; font-weight: 700; margin-top: 22px; margin-bottom: 8px; }}
.eyebrow {{ font-size: 0.75em; font-weight: 700; letter-spacing: 0.12em; color: {accent}; }}
.hero-title {{ font-size: 2.5em; font-weight: 800; letter-spacing: -0.02em; }}
.meta {{ color: {muted}; }}

/* Lists in rounded cards. */
.boxed-list {{ border-radius: 18px; background: {card}; box-shadow: 0 0 0 1px {outline}; }}
.boxed-list > row:first-child {{ border-top-left-radius: 18px; border-top-right-radius: 18px; }}
.boxed-list > row:last-child {{ border-bottom-left-radius: 18px; border-bottom-right-radius: 18px; }}
.boxed-list > row {{ min-height: 56px; }}
.track-number {{ color: {faint}; }}

/* Covers, cards and the top result. */
.artwork {{ border-radius: 12px; background: alpha({primary_container}, 0.6); color: {on_primary_container}; }}
.artwork.round {{ border-radius: 9999px; }}
.artwork.hero {{ border-radius: 20px; box-shadow: 0 14px 36px {shade}; }}
.artwork.hero.round {{ border-radius: 9999px; }}
.card-tile {{ padding: 10px; border-radius: 16px; background: none; }}
.card-tile:hover {{ background: alpha(currentColor, 0.06); }}
.card-sub {{ color: {muted}; font-size: 0.85em; }}
.top-result {{
  padding: 22px; border-radius: 22px; color: {on_primary_container};
  background-image: linear-gradient(135deg, {primary_container}, {accent_container});
}}
.top-result:hover {{ background-image: linear-gradient(135deg, shade({primary_container}, 1.06), shade({accent_container}, 1.06)); }}
.top-result .top-kind {{ opacity: 0.85; }}

/* Empty pages: a round icon, a title and a line. */
.empty-icon {{ min-width: 72px; min-height: 72px; border-radius: 9999px; background: {primary_container}; color: {on_primary_container}; }}
.empty-title {{ font-size: 1.25em; font-weight: 700; }}
.empty-text {{ color: {muted}; }}

/* A rounded search field, and segmented switches. */
.search-field {{ border-radius: 999px; min-height: 46px; padding: 0 18px; background: {card}; box-shadow: 0 0 0 1px {outline}; font-size: 1.05em; }}
.segmented {{ background: alpha(currentColor, 0.06); border-radius: 999px; padding: 4px; }}
.segmented > button {{ border-radius: 999px; padding: 6px 18px; background: none; box-shadow: none; color: {muted}; font-weight: {w600}; }}
.segmented > button:checked {{ background: {primary_container}; color: {on_primary_container}; }}

/* The player along the bottom, as on the web. */
.player-bar {{ padding: 10px 18px; background: {sidebar}; border-top: 1px solid {outline}; }}
.player-bar .now-title {{ font-weight: 700; }}
.player-bar .now-artist {{ color: {muted}; font-size: 0.88em; }}
.play-button {{ min-width: 46px; min-height: 46px; border-radius: 9999px; padding: 0; }}
.time {{ color: {faint}; font-size: 0.8em; }}
.live-pill {{ background: {accent_container}; color: {accent}; border-radius: 6px; padding: 1px 7px; font-size: 0.72em; font-weight: 800; }}

/* Signing in: a card over the glow, as on the web. */
.login-card {{ background: {card}; border-radius: 28px; padding: 34px 32px; box-shadow: 0 12px 40px {shade}; }}
.login-title {{ font-size: 1.6em; font-weight: 700; letter-spacing: -0.01em; }}
.login-sub {{ color: {muted}; }}

/* Preferences: the themes, as two-colour swatches. */
.swatch {{ min-width: 30px; min-height: 30px; border-radius: 9999px; }}
.theme-choice {{ border-radius: 999px; padding: 6px 14px 6px 6px; }}
.theme-choice:checked {{ background: {primary_container}; color: {on_primary_container}; }}
"#,
        primary = t.primary,
        on_primary = t.on_primary,
        primary_container = t.primary_container,
        on_primary_container = t.on_primary_container,
        accent = t.accent,
        accent_container = t.accent_container,
        glow_a_color = t.glow_a,
        glow_b_color = t.glow_b,
        bg = n.bg,
        sidebar = n.sidebar,
        card = n.card,
        raised = n.raised,
        text = n.text,
        muted = n.muted,
        faint = n.faint,
        outline = n.outline,
        shade = n.shade,
        w400 = weight(400),
        w600 = weight(600),
        bold_labels = if bold { "label, text, entry { font-weight: 700; }" } else { "" },
    ) + &swatch_css()
}

/// Each theme's swatch, as a two-colour circle.
fn swatch_css() -> String {
    PALETTES
        .iter()
        .map(|p| {
            format!(
                ".swatch-{} {{ background-image: linear-gradient(135deg, {} 50%, {} 50%); }}\n",
                p.id, p.swatch.0, p.swatch.1
            )
        })
        .collect()
}

thread_local! {
    static PROVIDER: RefCell<Option<gtk::CssProvider>> = const { RefCell::new(None) };
}

/// Applies the theme ([palette], light/dark/system [style], [bold]), and keeps it right when the
/// system's dark style changes.
pub fn apply(palette_id: &str, style: &str, bold: bool) {
    let manager = adw::StyleManager::default();
    manager.set_color_scheme(match style {
        "light" => adw::ColorScheme::ForceLight,
        "dark" => adw::ColorScheme::ForceDark,
        _ => adw::ColorScheme::Default,
    });
    let Some(display) = gdk::Display::default() else { return };
    let provider = PROVIDER.with(|p| {
        p.borrow_mut()
            .get_or_insert_with(|| {
                let provider = gtk::CssProvider::new();
                gtk::style_context_add_provider_for_display(&display, &provider, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
                // The system turning dark (or light) redraws in the theme's other scheme.
                manager.connect_dark_notify(|_| {
                    let Some(ctx) = crate::ui::try_ctx() else { return };
                    // A change from Preferences applies the theme itself, right after.
                    let Ok(config) = ctx.config.try_borrow() else { return };
                    reload(&config.palette, config.bold_text);
                });
                provider
            })
            .clone()
    });
    provider.load_from_string(&css(palette(palette_id), manager.is_dark(), bold));
}

fn reload(palette_id: &str, bold: bool) {
    let dark = adw::StyleManager::default().is_dark();
    PROVIDER.with(|p| {
        if let Some(provider) = p.borrow().as_ref() {
            provider.load_from_string(&css(palette(palette_id), dark, bold));
        }
    });
}

#[link(name = "fontconfig")]
unsafe extern "C" {
    fn FcConfigAppFontAddFile(config: *mut c_void, file: *const c_char) -> c_int;
}

/// Lexend (SIL Open Font License), for this app only: added to fontconfig before any text is
/// drawn, from copies the app keeps in its cache folder.
pub fn register_fonts() {
    let fonts: [(&str, &[u8]); 4] = [
        ("Lexend-Regular.ttf", include_bytes!("../../data/fonts/lexend_regular.ttf")),
        ("Lexend-Medium.ttf", include_bytes!("../../data/fonts/lexend_medium.ttf")),
        ("Lexend-SemiBold.ttf", include_bytes!("../../data/fonts/lexend_semibold.ttf")),
        ("Lexend-Bold.ttf", include_bytes!("../../data/fonts/lexend_bold.ttf")),
    ];
    let dir = glib::user_cache_dir().join("SuperAwesomeMagicalMusic").join("fonts");
    if std::fs::create_dir_all(&dir).is_err() {
        return;
    }
    for (name, bytes) in fonts {
        let path = dir.join(name);
        let fresh = std::fs::metadata(&path).map(|m| m.len() == bytes.len() as u64).unwrap_or(false);
        if !fresh && std::fs::write(&path, bytes).is_err() {
            continue;
        }
        if let Ok(file) = CString::new(path.to_string_lossy().as_bytes()) {
            // SAFETY: a null config is fontconfig's current one; the path is a valid C string.
            unsafe {
                FcConfigAppFontAddFile(std::ptr::null_mut(), file.as_ptr());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_theme_makes_a_stylesheet_in_both_schemes() {
        for p in &PALETTES {
            for dark in [false, true] {
                let sheet = css(p, dark, false);
                assert!(sheet.contains("@define-color accent_bg_color"), "{} {dark}", p.id);
                assert!(sheet.contains(&format!(".swatch-{}", p.id)));
                assert!(!sheet.contains("{{"), "unformatted braces in {}", p.id);
            }
        }
        assert_eq!(palette("nope").id, "aurora");
        assert!(css(palette("aurora"), true, true).contains("label, text, entry { font-weight: 700; }"));
    }
}
