//! The server's API, as the web player and the phone app use it (see web/src/lib/api.ts), signed in
//! with an app token (`Authorization: Bearer`).

use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use reqwest::{Method, StatusCode};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::json;

mod account;
mod library;
pub use account::*;
pub use library::*;

// ---- What the server sends ----

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct Track {
    /// JELLYFIN, QOBUZ, YOUTUBE_MUSIC, SIRIUSXM or RADIO.
    pub source: String,
    pub source_id: String,
    pub title: String,
    pub artist: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub album: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub artwork_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub isrc: Option<String>,
    /// The source (server-side) it came from.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub via: Option<String>,
}

/// How a live channel or station plays: its stream on the server.
pub struct LiveStream {
    pub path: String,
    pub kind: LiveKind,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum LiveKind {
    /// A SiriusXM channel (HLS), with what's on from the server.
    Channel,
    /// A radio station, with what's on from its stream's title.
    Radio,
    /// One of the person's own stations, made by the server: what's on, what's next and its DJ.
    Personal,
}

/// The time zone here ("Europe/London"), so a personal station's DJ gives the local time.
pub fn time_zone() -> String {
    let from_env = std::env::var("TZ").ok().map(|tz| tz.trim_start_matches(':').to_string());
    let from_file = || std::fs::read_to_string("/etc/timezone").ok().map(|s| s.trim().to_string());
    let from_link = || {
        std::fs::read_link("/etc/localtime")
            .ok()
            .and_then(|p| p.to_string_lossy().split_once("zoneinfo/").map(|(_, zone)| zone.to_string()))
    };
    from_env
        .filter(|tz| !tz.is_empty() && !tz.starts_with('/'))
        .or_else(from_file)
        .or_else(from_link)
        .filter(|tz| !tz.is_empty())
        .unwrap_or_else(|| "UTC".into())
}

fn split_two(value: &str) -> (&str, &str) {
    value.split_once(':').unwrap_or((value, ""))
}

impl Track {
    pub fn is_channel(&self) -> bool {
        self.source == "SIRIUSXM" && self.source_id.starts_with("channel:")
    }

    pub fn is_radio(&self) -> bool {
        self.source == "RADIO"
    }

    pub fn is_live(&self) -> bool {
        self.is_channel() || self.is_radio()
    }

    /// One of the person's own stations (their Radio, Rewind or Discovery).
    pub fn is_personal(&self) -> bool {
        self.is_radio() && self.via.as_deref() == Some("personal")
    }

    /// A Pandora station's song, which plays once from the station.
    pub fn is_station_track(&self) -> bool {
        self.source == "SIRIUSXM" && self.source_id.starts_with("stationtrack:")
    }

    /// A song missing from every source (a playlist keeps it greyed out).
    pub fn is_missing(&self) -> bool {
        self.source_id.starts_with("missing:")
    }

    /// Where a live channel, station or station song plays from (as liveStreamOf in the web player).
    pub fn live_stream(&self) -> Option<LiveStream> {
        let via = enc(self.via.as_deref()?);
        if self.is_channel() {
            let (kind, id) = split_two(&self.source_id["channel:".len()..]);
            if kind.is_empty() || id.is_empty() {
                return None;
            }
            return Some(LiveStream {
                path: format!("/api/live/sxm/{via}/channel/{}/{}", enc(kind), enc(id)),
                kind: LiveKind::Channel,
            });
        }
        if self.is_personal() {
            return Some(LiveStream {
                // Tuned in with this app's listener id, so the server can say what's heard here.
                path: format!("/api/live/radio/{via}/station/{}/stream?tz={}&listener={}", enc(&self.source_id), enc(&time_zone()), listener_id()),
                kind: LiveKind::Personal,
            });
        }
        if self.is_radio() {
            let id = self.source_id.trim_start_matches("hls:");
            return Some(LiveStream {
                path: format!("/api/live/radio/{via}/station/{}/stream", enc(id)),
                kind: LiveKind::Radio,
            });
        }
        None
    }

    /// The channel's type and id, for what's on.
    pub fn channel_parts(&self) -> Option<(String, String)> {
        let (kind, id) = split_two(self.source_id.strip_prefix("channel:")?);
        Some((kind.to_string(), id.to_string()))
    }

    /// A Pandora station's song as the song itself, for history (its link expires).
    pub fn as_song(&self) -> Track {
        if self.is_station_track() {
            let id = self.source_id.rsplit(':').next().unwrap_or_default();
            return Track {
                source_id: format!("song:{id}"),
                via: None,
                ..self.clone()
            };
        }
        self.clone()
    }

    pub fn subtitle(&self) -> String {
        match self.album.as_deref().filter(|a| !a.is_empty()) {
            Some(album) if !self.is_live() => format!("{} · {album}", self.artist),
            _ => self.artist.clone(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct User {
    pub id: String,
    pub username: String,
    #[serde(default)]
    pub display_name: String,
    #[serde(default)]
    pub role: String,
    /// The admin made at first start, which can always sign in with its password.
    #[serde(default)]
    pub break_glass: bool,
    /// Turned off by an admin (can't sign in).
    #[serde(default)]
    pub disabled: bool,
    /// Signs in through single sign-on.
    #[serde(default)]
    pub oidc: bool,
    #[serde(default)]
    pub has_password: bool,
    #[serde(default)]
    pub last_login_at: Option<i64>,
}

impl User {
    pub fn is_admin(&self) -> bool {
        self.role == "ADMIN"
    }

    /// Their name, or their username when they have none.
    pub fn name(&self) -> &str {
        if self.display_name.is_empty() { &self.username } else { &self.display_name }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthConfig {
    pub server_name: String,
    #[serde(default)]
    pub local_login: bool,
    #[serde(default)]
    pub oidc: Option<OidcButton>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct OidcButton {
    pub label: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SignedIn {
    pub user: User,
    pub token: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtistSummary {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub artwork_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AlbumSummary {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub artist: String,
    #[serde(default)]
    pub year: Option<i32>,
    #[serde(default)]
    pub artwork_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct TopResult {
    pub kind: String,
    #[serde(default)]
    pub artist: Option<ArtistSummary>,
    #[serde(default)]
    pub album: Option<AlbumSummary>,
    #[serde(default)]
    pub track: Option<Track>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SearchResults {
    #[serde(default)]
    pub top: Option<TopResult>,
    #[serde(default)]
    pub songs: Vec<Track>,
    #[serde(default)]
    pub artists: Vec<ArtistSummary>,
    #[serde(default)]
    pub albums: Vec<AlbumSummary>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtistPage {
    pub artist: ArtistSummary,
    #[serde(default)]
    pub top_songs: Vec<Track>,
    #[serde(default)]
    pub albums: Vec<AlbumSummary>,
    #[serde(default)]
    pub singles: Vec<AlbumSummary>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AlbumPage {
    pub album: AlbumSummary,
    #[serde(default)]
    pub tracks: Vec<Track>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistSummary {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub count: i64,
    #[serde(default)]
    pub artwork: Vec<String>,
    /// When it was last played, on any device.
    #[serde(default)]
    pub played_at: Option<i64>,
    /// Goes up with every change (reordering says which it changed).
    #[serde(default)]
    pub revision: i64,
    /// Whether it's this person's own (false: someone shared it with them).
    #[serde(default = "yes")]
    pub mine: bool,
    /// Whose it is, when it's someone else's.
    #[serde(default)]
    pub owner: Option<PlaylistPerson>,
    /// The people it's shared with (besides its owner): they add, remove and reorder songs too.
    #[serde(default)]
    pub members: Vec<PlaylistPerson>,
}

/// Someone a playlist is shared with, or who shares it (anyone on the server, to share with).
#[derive(Debug, Clone, Deserialize)]
pub struct PlaylistPerson {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub username: String,
    #[serde(default)]
    pub name: String,
}

impl PlaylistPerson {
    /// Their name, or their username when they have none.
    pub fn shown(&self) -> &str {
        if self.name.is_empty() { &self.username } else { &self.name }
    }
}

impl PlaylistSummary {
    /// "Shared by Maddie", or "Shared with Sam and Alex", for a shared playlist.
    pub fn shared_note(&self) -> Option<String> {
        if !self.mine {
            return self.owner.as_ref().map(|o| format!("Shared by {}", o.shown()));
        }
        let names: Vec<&str> = self.members.iter().map(PlaylistPerson::shown).collect();
        match names.len() {
            0 => None,
            1 => Some(format!("Shared with {}", names[0])),
            n => Some(format!("Shared with {} and {}", names[..n - 1].join(", "), names[n - 1])),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistEntry {
    #[serde(default)]
    pub entry_id: String,
    pub track: Track,
    /// Who added it, on a shared playlist.
    #[serde(default)]
    pub added_by: Option<PlaylistPerson>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Playlist {
    pub summary: PlaylistSummary,
    #[serde(default)]
    pub entries: Vec<PlaylistEntry>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedStream {
    pub track: Track,
    pub stream_url: String,
    #[serde(default)]
    pub description: String,
    /// It can be jumped about in (a file); otherwise (a conversion) the server starts it again
    /// from where asked.
    #[serde(default = "yes")]
    pub seekable: bool,
    /// The copy's bitrate, when the server knows it.
    #[serde(default)]
    pub kbps: Option<i32>,
    /// Whether it fits the quality cap asked for.
    #[serde(default = "yes")]
    pub within_cap: bool,
    /// Its loudness gain (Jellyfin's normalization), for even volume.
    #[serde(default)]
    pub gain_db: Option<f64>,
    /// Made smaller (or converted) on the server.
    #[serde(default)]
    pub transcoded: bool,
    /// Its format, as a file extension ("flac", "opus").
    #[serde(default)]
    pub extension: String,
}

/// A song's lyrics: timed lines (synced) or plain ones, and where they're from.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct LyricsView {
    #[serde(default)]
    pub lines: Vec<LyricLine>,
    #[serde(default)]
    pub source: String,
    #[serde(default)]
    pub synced: bool,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LyricLine {
    #[serde(default)]
    pub time_ms: Option<i64>,
    #[serde(default)]
    pub text: String,
}

fn yes() -> bool {
    true
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceView {
    pub id: String,
    pub kind: String,
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub hidden: bool,
    #[serde(default)]
    pub name: String,
    /// "global" (shared by the admins) or "personal".
    #[serde(default)]
    pub scope: String,
    #[serde(default)]
    pub editable: bool,
    /// What's saved, but its secrets (the server's address, the username…).
    #[serde(default)]
    pub settings: serde_json::Map<String, serde_json::Value>,
    /// The secrets that are set, by name (their values are never sent).
    #[serde(default)]
    pub secrets_set: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ChannelView {
    pub track: Track,
    #[serde(default)]
    pub number: Option<i32>,
    #[serde(default)]
    pub category: String,
    #[serde(default)]
    pub locked: bool,
}

/// What's on a live channel.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NowView {
    pub title: String,
    #[serde(default)]
    pub artist: String,
    #[serde(default)]
    pub album: Option<String>,
    #[serde(default)]
    pub artwork_url: Option<String>,
    #[serde(default)]
    pub song: Option<Track>,
    /// When it started (the server's clock) and how long it is.
    #[serde(default)]
    pub start_ms: i64,
    #[serde(default)]
    pub duration_ms: i64,
}

/// What Listen heard.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ListenResult {
    #[serde(default)]
    pub found: bool,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub artist: Option<String>,
    #[serde(default)]
    pub album: Option<String>,
    #[serde(default)]
    pub artwork_url: Option<String>,
    /// A copy that plays, from your sources.
    #[serde(default)]
    pub song: Option<Track>,
    /// Where the song was when the recording ended.
    #[serde(default)]
    pub position_ms: Option<i64>,
    #[serde(default)]
    pub via: Option<String>,
    #[serde(default)]
    pub message: Option<String>,
    /// Not sure yet: sent back with the next, longer try.
    #[serde(default)]
    pub candidate: Option<String>,
}

/// A song Listen recognised (Recently heard).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Heard {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub artist: String,
    #[serde(default)]
    pub artwork_url: Option<String>,
    #[serde(default)]
    pub song: Option<Track>,
    #[serde(default)]
    pub at: i64,
}

/// How much of your library Listen knows, and whether AudD is there for the rest.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct ListenStatus {
    #[serde(default)]
    pub learned: i64,
    #[serde(default)]
    pub total: i64,
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub libraries: bool,
    #[serde(default)]
    pub audd: bool,
}

/// One of the person's own stations, as the Radio page lists it.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PersonalStation {
    pub track: Track,
    pub kind: String,
    pub name: String,
    #[serde(default)]
    pub tagline: String,
    #[serde(default)]
    pub ready: bool,
    #[serde(default)]
    pub on_air: Option<NowView>,
}

/// What the station's DJ says over the start of a song.
#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct DjLine {
    pub id: String,
    pub text: String,
    /// When it's said, on the station's clock.
    #[serde(default, rename = "atMs")]
    pub at_ms: i64,
}

/// This app's id when it tunes in to one of the person's own stations (new each time it starts).
pub fn listener_id() -> &'static str {
    static ID: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    ID.get_or_init(|| {
        let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
        format!("d{:x}{:x}", nanos, std::process::id())
    })
}

/// What a skip on one of the person's own stations says: `retune` when this player is behind the station.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SkipResult {
    #[serde(default)]
    pub retune: bool,
}

/// What's on one of the person's own stations, what's next, its thumbs and its DJ.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PersonalNow {
    /// How far behind the station this player's stream is (when it said where it is).
    #[serde(default)]
    pub behind_ms: Option<i64>,
    #[serde(default)]
    pub now: Option<NowView>,
    #[serde(default)]
    pub song_key: Option<String>,
    #[serde(default)]
    pub thumb: Option<String>,
    #[serde(default)]
    pub next: Vec<NowView>,
    /// What it played before.
    #[serde(default)]
    pub recent: Vec<NowView>,
    #[serde(default)]
    pub dj: Option<DjLine>,
    /// A line for the DJ to say on tuning in.
    #[serde(default)]
    pub welcome: Option<String>,
    /// The server's clock, and how far behind it the station is heard.
    #[serde(default)]
    pub server_time: i64,
    #[serde(default)]
    pub heard_delay_ms: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Listen {
    pub track: Track,
    pub played_at: i64,
    pub listened_ms: i64,
    pub client: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub station: Option<String>,
}

// ---- Asking ----

/// The server, signed in or not. Cheap to clone (the HTTP client is shared).
#[derive(Clone)]
pub struct Api {
    base: String,
    token: Option<String>,
    http: reqwest::Client,
}

pub fn enc(value: &str) -> String {
    url::form_urlencoded::byte_serialize(value.as_bytes())
        .collect::<String>()
        .replace('+', "%20")
}

fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .user_agent(concat!("SuperAwesomeMagicalMusic-Desktop/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(60))
        .build()
        .expect("the HTTP client")
}

#[derive(Deserialize)]
struct ErrorBody {
    error: Option<String>,
    message: Option<String>,
}

impl Api {
    pub fn new(base: &str, token: Option<String>) -> Api {
        Api {
            base: base.trim_end_matches('/').to_string(),
            token,
            http: client(),
        }
    }

    pub fn base(&self) -> &str {
        &self.base
    }

    pub fn token(&self) -> Option<&str> {
        self.token.as_deref()
    }

    /// The server's address for [path] (which the server gives as `/api/…`).
    pub fn url(&self, path: &str) -> String {
        if path.starts_with("http://") || path.starts_with("https://") {
            path.to_string()
        } else {
            format!("{}{}", self.base, path)
        }
    }

    async fn send<T: DeserializeOwned>(&self, method: Method, path: &str, body: Option<serde_json::Value>) -> Result<T> {
        let mut request = self.http.request(method, self.url(path)).header("Accept", "application/json");
        if let Some(token) = &self.token {
            request = request.bearer_auth(token);
        }
        if let Some(body) = body {
            request = request.json(&body);
        }
        let response = request.send().await.map_err(|e| anyhow!(describe(&e)))?;
        Self::answer(response, path).await
    }

    /// What the server answered: the value, or its error as a message for people.
    async fn answer<T: DeserializeOwned>(response: reqwest::Response, path: &str) -> Result<T> {
        let status = response.status();
        if !status.is_success() {
            let text = response.text().await.unwrap_or_default();
            let said = serde_json::from_str::<ErrorBody>(&text).ok().and_then(|b| b.error.or(b.message));
            if status == StatusCode::UNAUTHORIZED {
                bail!(Unauthorized(said.unwrap_or_else(|| "Sign in again".into())));
            }
            bail!(said.unwrap_or_else(|| format!("The server answered {status}")));
        }
        let bytes = response.bytes().await?;
        if bytes.is_empty() || bytes.as_ref() == b"null" {
            return serde_json::from_slice(b"null").context("an empty answer");
        }
        serde_json::from_slice(&bytes).with_context(|| format!("an unexpected answer from {path}"))
    }

    // Listen: what's playing around you.

    pub async fn listen_status(&self) -> Result<ListenStatus> {
        self.get("/api/listen/status").await
    }

    /// A recording ([wav], what's been heard so far) to recognise; [last] on the last try.
    pub async fn listen(&self, wav: Vec<u8>, last: bool, candidate: Option<&str>) -> Result<ListenResult> {
        let mut path = format!("/api/listen?final={last}");
        if let Some(candidate) = candidate {
            path += &format!("&candidate={}", enc(candidate));
        }
        let mut request = self
            .http
            .post(self.url(&path))
            .header("Accept", "application/json")
            .header("Content-Type", "audio/wav")
            .body(wav);
        if let Some(token) = &self.token {
            request = request.bearer_auth(token);
        }
        let response = request.send().await.map_err(|e| anyhow!(describe(&e)))?;
        Self::answer(response, &path).await
    }

    pub async fn listen_heard(&self) -> Result<Vec<Heard>> {
        self.get("/api/listen/heard").await
    }

    pub async fn forget_heard(&self, id: &str) -> Result<serde_json::Value> {
        self.send(Method::DELETE, &format!("/api/listen/heard/{}", enc(id)), None).await
    }

    async fn get<T: DeserializeOwned>(&self, path: &str) -> Result<T> {
        self.send(Method::GET, path, None).await
    }

    async fn post<T: DeserializeOwned>(&self, path: &str, body: serde_json::Value) -> Result<T> {
        self.send(Method::POST, path, Some(body)).await
    }

    // Signing in.

    pub async fn auth_config(&self) -> Result<AuthConfig> {
        self.get("/api/auth/config").await
    }

    pub async fn login(&self, username: &str, password: &str, device: &str) -> Result<SignedIn> {
        self.post(
            "/api/auth/login",
            json!({ "username": username, "password": password, "app": true, "deviceName": device }),
        )
        .await
    }

    /// Finishes signing in through the browser: the code it handed back, and this app's secret.
    pub async fn app_token(&self, code: &str, verifier: &str, device: &str) -> Result<SignedIn> {
        self.post(
            "/api/auth/app/token",
            json!({ "code": code, "verifier": verifier, "deviceName": device }),
        )
        .await
    }

    /// Hands a sign-in ticket from SuperAwesomeMagicalOrchestrator to the server, for the app's own token.
    pub async fn orchestrator_sign_in(&self, ticket: &str, device: &str) -> Result<SignedIn> {
        self.post(
            "/api/auth/orchestrator",
            json!({ "ticket": ticket, "deviceName": device }),
        )
        .await
    }

    pub async fn logout(&self) -> Result<serde_json::Value> {
        self.post("/api/auth/logout", json!({})).await
    }

    // Music.

    pub async fn search(&self, query: &str) -> Result<SearchResults> {
        self.get(&format!("/api/search?q={}", enc(query))).await
    }

    pub async fn artist(&self, id: Option<&str>, name: &str) -> Result<ArtistPage> {
        match id {
            Some(id) => self.get(&format!("/api/artist?id={}", enc(id))).await,
            None => self.get(&format!("/api/artist?name={}", enc(name))).await,
        }
    }

    pub async fn album(&self, id: Option<&str>, title: &str, artist: &str) -> Result<AlbumPage> {
        match id {
            Some(id) => self.get(&format!("/api/album?id={}", enc(id))).await,
            None => self.get(&format!("/api/album?title={}&artist={}", enc(title), enc(artist))).await,
        }
    }

    pub async fn resolve(&self, track: &Track, kbps: u32, thorough: bool) -> Result<ResolvedStream> {
        // What GStreamer plays here (with its usual plug-ins).
        let codecs = ["opus", "vorbis", "aac", "mp3", "flac", "alac"];
        self.post(
            "/api/play/resolve",
            json!({ "track": track, "kbps": kbps, "codecs": codecs, "thorough": thorough }),
        )
        .await
    }

    pub async fn playlists(&self) -> Result<Vec<PlaylistSummary>> {
        self.get("/api/playlists").await
    }

    pub async fn playlist(&self, id: &str) -> Result<Playlist> {
        self.get(&format!("/api/playlists/{}", enc(id))).await
    }

    pub async fn playlist_played(&self, id: &str) -> Result<serde_json::Value> {
        self.post(&format!("/api/playlists/{}/played", enc(id)), json!({})).await
    }

    pub async fn create_playlist(&self, name: &str, tracks: &[Track]) -> Result<Playlist> {
        self.post("/api/playlists", json!({ "name": name, "tracks": tracks })).await
    }

    pub async fn rename_playlist(&self, id: &str, name: &str, description: &str) -> Result<PlaylistSummary> {
        self.send(
            Method::PATCH,
            &format!("/api/playlists/{}", enc(id)),
            Some(json!({ "name": name, "description": description })),
        )
        .await
    }

    pub async fn delete_playlist(&self, id: &str) -> Result<serde_json::Value> {
        self.send(Method::DELETE, &format!("/api/playlists/{}", enc(id)), None).await
    }

    pub async fn add_to_playlist(&self, id: &str, tracks: &[Track]) -> Result<Playlist> {
        self.post(&format!("/api/playlists/{}/tracks", enc(id)), json!({ "tracks": tracks }))
            .await
    }

    pub async fn remove_from_playlist(&self, id: &str, entry_ids: &[String]) -> Result<Playlist> {
        self.post(&format!("/api/playlists/{}/remove", enc(id)), json!({ "entryIds": entry_ids }))
            .await
    }

    pub async fn reorder_playlist(&self, id: &str, revision: i64, entry_ids: &[String]) -> Result<Playlist> {
        self.send(
            Method::PUT,
            &format!("/api/playlists/{}/order", enc(id)),
            Some(json!({ "revision": revision, "entryIds": entry_ids })),
        )
        .await
    }

    /// The playlist as a CSV file (Exportify's columns), as the server writes it.
    pub async fn export_playlist(&self, id: &str) -> Result<Vec<u8>> {
        let mut request = self.http.get(self.url(&format!("/api/playlists/{}/export.csv", enc(id))));
        if let Some(token) = &self.token {
            request = request.bearer_auth(token);
        }
        let response = request.send().await.map_err(|e| anyhow!(describe(&e)))?;
        if !response.status().is_success() {
            bail!("The server answered {}", response.status());
        }
        Ok(response.bytes().await?.to_vec())
    }

    pub async fn add_listens(&self, listens: Vec<Listen>) -> Result<serde_json::Value> {
        self.post("/api/history", json!({ "listens": listens })).await
    }

    // Radio.

    pub async fn sources(&self) -> Result<Vec<SourceView>> {
        self.get("/api/sources").await
    }

    pub async fn channels(&self, source: &str) -> Result<Vec<ChannelView>> {
        self.get(&format!("/api/live/sxm/{}/channels", enc(source))).await
    }

    pub async fn channel_now(&self, source: &str, kind: &str, id: &str, at_ms: i64) -> Result<Option<NowView>> {
        self.get(&format!(
            "/api/live/sxm/{}/now?type={}&id={}&at={at_ms}",
            enc(source),
            enc(kind),
            enc(id)
        ))
        .await
    }

    /// What the channel played before [at_ms] (its last few hours).
    pub async fn channel_recent(&self, source: &str, kind: &str, id: &str, at_ms: i64) -> Result<Vec<NowView>> {
        self.get(&format!(
            "/api/live/sxm/{}/recent?type={}&id={}&at={at_ms}",
            enc(source),
            enc(kind),
            enc(id)
        ))
        .await
    }

    /// The song's lyrics, from its library or LRCLIB (none: no lines).
    pub async fn lyrics(&self, track: &Track) -> Result<LyricsView> {
        let found: Option<LyricsView> = self.post("/api/lyrics", json!({ "track": track })).await?;
        Ok(found.unwrap_or_default())
    }

    pub async fn personal_stations(&self) -> Result<Vec<PersonalStation>> {
        self.get("/api/radio/personal").await
    }

    /// What's on, as heard here: [position_ms] into the stream this app tuned in to.
    pub async fn personal_now(&self, id: &str, position_ms: Option<i64>) -> Result<PersonalNow> {
        let heard = position_ms.map(|p| format!("&listener={}&pos={}", listener_id(), p.max(0))).unwrap_or_default();
        self.get(&format!("/api/radio/personal/{}/now?tz={}{heard}", enc(id), enc(&time_zone()))).await
    }

    /// Skips what's on ([heard]: the song heard here; behind the station, it says to tune in again).
    pub async fn personal_skip(&self, id: &str, heard: Option<&str>) -> Result<SkipResult> {
        let key = heard.map(|k| format!("?key={}", enc(k))).unwrap_or_default();
        self.send::<SkipResult>(Method::POST, &format!("/api/radio/personal/{}/skip{key}", enc(id)), Some(json!({}))).await
    }

    pub async fn personal_thumb(&self, id: &str, key: &str, thumb: &str) -> Result<()> {
        self.send::<serde_json::Value>(
            Method::POST,
            &format!("/api/radio/personal/{}/thumb", enc(id)),
            Some(json!({ "key": key, "thumb": thumb })),
        )
        .await
        .map(|_| ())
    }

    /// Thumbs a Pandora station's song: UP, DOWN or NONE, [elapsed_ms] into it.
    pub async fn station_thumb(&self, source: &str, station: &str, song: &str, thumb: &str, elapsed_ms: i64) -> Result<()> {
        self.send::<serde_json::Value>(
            Method::POST,
            &format!("/api/live/sxm/{}/station-track/{}/{}/thumb", enc(source), enc(station), enc(song)),
            Some(json!({ "thumb": thumb, "elapsedMs": elapsed_ms })),
        )
        .await
        .map(|_| ())
    }

    /// What's on a radio station: "Artist - Title", and the song in full where the station says
    /// (your FM tuner's stations: its cover, and the song for lyrics).
    pub async fn radio_now(&self, source: &str, id: &str) -> Result<Option<(String, Option<NowView>)>> {
        #[derive(Deserialize)]
        struct Now {
            title: Option<String>,
            #[serde(default)]
            now: Option<NowView>,
        }
        let now: Option<Now> = self
            .get(&format!("/api/live/radio/{}/station/{}/now", enc(source), enc(id)))
            .await?;
        Ok(now.and_then(|n| {
            let title = n.title.filter(|t| !t.trim().is_empty()).or_else(|| n.now.as_ref().map(|s| format!("{} - {}", s.artist, s.title)))?;
            Some((title, n.now))
        }))
    }

    pub async fn live_favorites(&self) -> Result<Vec<Track>> {
        self.get("/api/live/favorites").await
    }

    pub async fn set_live_favorite(&self, track: &Track, favorite: bool) -> Result<Vec<Track>> {
        self.send(
            Method::PUT,
            "/api/live/favorites",
            Some(json!({ "track": track, "favorite": favorite })),
        )
        .await
    }

    pub async fn live_played(&self, track: &Track) -> Result<serde_json::Value> {
        self.post("/api/live/played", json!({ "track": track })).await
    }
}

/// The server turned the sign-in down: the app goes back to signing in.
#[derive(Debug)]
pub struct Unauthorized(pub String);

impl std::fmt::Display for Unauthorized {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Unauthorized {}

/// Whether [error] means the sign-in is no longer good.
pub fn is_unauthorized(error: &anyhow::Error) -> bool {
    error.downcast_ref::<Unauthorized>().is_some()
}

/// A connection error, as people would say it.
fn describe(error: &reqwest::Error) -> String {
    let text = format!("{error:?}");
    if error.is_timeout() {
        "The server didn't answer in time".into()
    } else if text.contains("dns error") || text.contains("failed to lookup") {
        "The server's name couldn't be found".into()
    } else if text.contains("Connection refused") {
        "The server refused the connection: it may be stopped".into()
    } else if text.contains("certificate") {
        "The server's certificate isn't trusted".into()
    } else if error.is_connect() {
        "Couldn't connect to the server".into()
    } else {
        error.to_string()
    }
}

/// The server's address as typed (`music.example.com`, `https://music.example.com/settings`): its
/// origin, https first when no scheme was given.
pub fn candidates(typed: &str) -> Vec<String> {
    let text = typed.trim();
    if text.is_empty() {
        return vec![];
    }
    let tries = if text.contains("://") {
        vec![text.to_string()]
    } else {
        vec![format!("https://{text}"), format!("http://{text}")]
    };
    tries
        .into_iter()
        .filter_map(|t| url::Url::parse(&t).ok())
        .filter(|u| (u.scheme() == "https" || u.scheme() == "http") && u.host_str().is_some())
        .map(|u| u.origin().ascii_serialization())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn addresses_become_origins() {
        assert_eq!(
            candidates("music.example.com"),
            vec!["https://music.example.com", "http://music.example.com"]
        );
        assert_eq!(
            candidates("https://music.example.com/settings?x=1"),
            vec!["https://music.example.com"]
        );
        assert_eq!(candidates("http://127.0.0.1:8099"), vec!["http://127.0.0.1:8099"]);
        assert!(candidates("  ").is_empty());
    }

    #[test]
    fn live_streams_are_where_the_web_player_finds_them() {
        let channel = Track {
            source: "SIRIUSXM".into(),
            source_id: "channel:xtra:altap".into(),
            via: Some("sxm 1".into()),
            ..Default::default()
        };
        let live = channel.live_stream().unwrap();
        assert_eq!(live.path, "/api/live/sxm/sxm%201/channel/xtra/altap");
        assert_eq!(live.kind, LiveKind::Channel);
        assert_eq!(channel.channel_parts(), Some(("xtra".into(), "altap".into())));
        let radio = Track {
            source: "RADIO".into(),
            source_id: "hls:abc".into(),
            via: Some("r".into()),
            ..Default::default()
        };
        assert_eq!(radio.live_stream().unwrap().path, "/api/live/radio/r/station/abc/stream");
        // One of the person's own stations: the server's broadcast, told the time zone.
        let mine = Track {
            source: "RADIO".into(),
            source_id: "mine".into(),
            via: Some("personal".into()),
            ..Default::default()
        };
        let stream = mine.live_stream().unwrap();
        assert_eq!(stream.kind, LiveKind::Personal);
        assert!(stream.path.starts_with("/api/live/radio/personal/station/mine/stream?tz="), "{}", stream.path);
        assert!(stream.path.ends_with(&format!("&listener={}", listener_id())), "{}", stream.path);
        assert!(!time_zone().is_empty());
        let song = Track {
            source: "JELLYFIN".into(),
            source_id: "s1".into(),
            via: Some("j".into()),
            ..Default::default()
        };
        assert!(song.live_stream().is_none());
        let station = Track {
            source: "SIRIUSXM".into(),
            source_id: "stationtrack:st1:tr9".into(),
            via: Some("s".into()),
            ..Default::default()
        };
        assert_eq!(station.as_song().source_id, "song:tr9");
    }
}
