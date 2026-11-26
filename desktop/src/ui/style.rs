//! The styles of the library, settings and admin pages (forms, sources, Wrapped), over the
//! theme's colours (named in theme.rs), as the web player draws them.

use std::cell::Cell;

use gtk::gdk;

const CSS: &str = r#"
/* Forms, as the web's: a label over the field, help under it. */
.field-label { font-weight: 600; font-size: 0.92em; }
.field-help { color: @samm_muted; font-size: 0.84em; }
.panel { background: @card_bg_color; border-radius: 22px; padding: 22px 24px; box-shadow: 0 0 0 1px @samm_outline; }
.panel-title { font-size: 1.15em; font-weight: 700; }
.notice { padding: 10px 14px; border-radius: 12px; background: @samm_surface2; }
.notice.ok { background: alpha(@success_color, 0.15); }
.notice.bad { background: alpha(@error_color, 0.15); }
.link-code { font-family: monospace; font-size: 1.8em; font-weight: 700; letter-spacing: 0.12em; }
.code-entry { font-family: monospace; font-size: 1.4em; letter-spacing: 0.12em; }
.copy-text { font-family: monospace; font-size: 0.88em; padding: 8px 12px; border-radius: 10px; background: @samm_surface2; }

/* Sources: a letter in the kind's colour. */
.source-icon { border-radius: 12px; font-weight: 700; font-size: 1.05em; background: @samm_primary_container; color: @samm_on_primary_container; }
.kind-jellyfin { background-image: linear-gradient(135deg, #7b4bd8, #2e9bd8); color: white; }
.kind-qobuz { background: #1d1d1d; color: white; }
.kind-youtube_music { background: #ff0033; color: white; }
.kind-lidarr { background: #2a9d4b; color: white; }
.kind-subsonic { background-image: linear-gradient(135deg, #f2a516, #e5532d); color: white; }
.kind-sonos { background: #1b1b1b; color: white; }
.kind-music_assistant { background-image: linear-gradient(135deg, #18bcf2, #0b6fb8); color: white; }
row.is-off { opacity: 0.6; }
.kind-group-title { font-size: 0.78em; font-weight: 700; letter-spacing: 0.08em; color: @samm_muted; margin-top: 6px; }
.role-admin { background: @samm_primary_container; color: @samm_on_primary_container; }

/* Playlists: the songs not found, and who shares it. */
.missing-note { padding: 12px 16px; border-radius: 16px; background: @samm_surface2; color: @samm_muted; margin-bottom: 8px; }
.track-note { color: @samm_faint; }

/* Radio: a star over a card's corner. */
.card-star { margin: 6px; border-radius: 9999px; background: alpha(black, 0.45); color: white; min-width: 32px; min-height: 32px; padding: 0; }
.card-star:hover { background: alpha(black, 0.6); }

/* Wrapped. */
.wrapped-banner { padding: 14px 18px; border-radius: 18px; color: white; background-image: linear-gradient(120deg, @samm_hero_a, @samm_hero_b); }
.wrapped-banner .banner-title { font-weight: 700; }
.wrapped-banner .banner-sub { font-size: 0.86em; color: alpha(white, 0.9); }
.wrapped-hero { padding: 28px; border-radius: 24px; color: white; background-image: linear-gradient(135deg, @samm_hero_a, @samm_hero_b); box-shadow: 0 10px 30px alpha(black, 0.18); }
.wrapped-hero .wrapped-eyebrow { font-size: 0.8em; letter-spacing: 0.12em; font-weight: 600; color: alpha(white, 0.85); }
.wrapped-hero .wrapped-big { font-size: 2.5em; font-weight: 700; letter-spacing: -0.02em; color: white; }
.wrapped-hero .wrapped-line { color: alpha(white, 0.9); }
.wrapped-chip { padding: 6px 12px; border-radius: 999px; background: alpha(white, 0.18); font-weight: 600; font-size: 0.9em; color: white; }
.wrapped-hero button.light { background: white; color: @samm_hero_a; border-radius: 999px; padding: 8px 18px; }
.wrapped-hero button.clear { background: alpha(white, 0.16); color: white; border-radius: 999px; padding: 8px 18px; }
.wrapped-panel { padding: 22px; border-radius: 20px; background: @samm_surface2; }
.wrapped-panel .panel-sub { color: @samm_muted; font-size: 0.85em; font-weight: 600; margin-top: 6px; }
.wrapped-caption { font-size: 0.8em; color: @samm_muted; }
.wrapped-bars { border-bottom: 1px solid alpha(@samm_outline, 0.6); }
.wrapped-bar-fill { background: @accent_bg_color; border-radius: 4px 4px 0 0; }
.wrapped-bar:hover .wrapped-bar-fill { background: @samm_accent; }
.wrapped-tick { font-size: 0.7em; color: @samm_faint; }
.wrapped-share-value { font-size: 0.8em; color: @samm_muted; }
.wrapped-share-bar trough { min-height: 8px; border-radius: 4px; background: alpha(@accent_bg_color, 0.14); }
.wrapped-share-bar progress { min-height: 8px; border-radius: 4px; background: @accent_bg_color; }
.wrapped-rank { padding: 6px 10px; border-radius: 12px; }
.wrapped-rank-n { color: @samm_faint; }
.wrapped-tile { padding: 18px; border-radius: 18px; background: @samm_surface2; }
.wrapped-tile-label { font-size: 0.74em; letter-spacing: 0.08em; color: @samm_accent; font-weight: 600; }
.wrapped-tile-value { font-size: 1.35em; font-weight: 700; }

/* The story: a slide at a time, each over its own gradient. */
.story { color: white; }
.story label { color: white; }
.story-0 { background-image: linear-gradient(160deg, #5b3fd1, #8e3f5c); }
.story-1 { background-image: linear-gradient(160deg, #1d1b6b, #0f766e); }
.story-2 { background-image: linear-gradient(160deg, #8e3f5c, #d97706); }
.story-3 { background-image: linear-gradient(160deg, #0b1220, #5b3fd1); }
.story-4 { background-image: linear-gradient(160deg, #0f766e, #5b3fd1); }
.story-5 { background-image: linear-gradient(160deg, #3b0764, #be185d); }
.story-eyebrow { font-size: 0.85em; letter-spacing: 0.14em; font-weight: 600; color: alpha(white, 0.85); }
.story-big { font-size: 2.6em; font-weight: 700; letter-spacing: -0.02em; }
.story-huge { font-size: 5.5em; font-weight: 700; letter-spacing: -0.04em; }
.story-mid { font-size: 1.5em; font-weight: 600; }
.story-text { font-size: 1.1em; color: alpha(white, 0.92); }
.story-rank { font-size: 1.3em; font-weight: 700; color: alpha(white, 0.75); }
.story button { background: alpha(white, 0.16); color: white; border-radius: 999px; }
.story button.light { background: white; color: #3b2a8f; }
.story-bar-fill { background: alpha(white, 0.85); border-radius: 3px 3px 0 0; }
.story-dots { padding: 4px; }
"#;

thread_local! {
    static LOADED: Cell<bool> = const { Cell::new(false) };
}

/// Adds these styles to the app (once).
pub fn ensure() {
    if LOADED.with(|l| l.replace(true)) {
        return;
    }
    let Some(display) = gdk::Display::default() else { return };
    let provider = gtk::CssProvider::new();
    provider.load_from_string(CSS);
    gtk::style_context_add_provider_for_display(&display, &provider, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
}
