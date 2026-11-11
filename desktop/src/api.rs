//! The server's API, as the web player and the phone app use it (see web/src/lib/api.ts), signed in
//! with an app token (`Authorization: Bearer`).

use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use reqwest::{Method, StatusCode};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::json;

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
}

fn split_two(value: &str) -> (&str, &str) {
    value.split_once(':').unwrap_or((value, ""))
}

impl Track {
    pub fn key(&self) -> String {
        format!("{}|{}:{}", self.via.as_deref().unwrap_or(""), self.source, self.source_id)
    }

    pub fn is_channel(&self) -> bool {
        self.source == "SIRIUSXM" && self.source_id.starts_with("channel:")
    }

    pub fn is_radio(&self) -> bool {
        self.source == "RADIO"
    }

    pub fn is_live(&self) -> bool {
        self.is_channel() || self.is_radio()
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
            return Some(LiveStream { path: format!("/api/live/sxm/{via}/channel/{}/{}", enc(kind), enc(id)), kind: LiveKind::Channel });
        }
        if self.is_radio() {
            let id = self.source_id.trim_start_matches("hls:");
            return Some(LiveStream { path: format!("/api/live/radio/{via}/station/{}/stream", enc(id)), kind: LiveKind::Radio });
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
            return Track { source_id: format!("song:{id}"), via: None, ..self.clone() };
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
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthConfig {
    pub server_name: String,
    pub version: String,
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
    #[serde(default)]
    pub track_count: Option<i32>,
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
    pub duration_ms: i64,
    #[serde(default)]
    pub artwork: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlaylistEntry {
    pub entry_id: String,
    pub track: Track,
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
    #[serde(default)]
    pub gain_db: Option<f64>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SourceView {
    pub id: String,
    pub kind: String,
    pub name: String,
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub hidden: bool,
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
    url::form_urlencoded::byte_serialize(value.as_bytes()).collect::<String>().replace('+', "%20")
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
        Api { base: base.trim_end_matches('/').to_string(), token, http: client() }
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
        self.post("/api/auth/login", json!({ "username": username, "password": password, "app": true, "deviceName": device })).await
    }

    /// Finishes signing in through the browser: the code it handed back, and this app's secret.
    pub async fn app_token(&self, code: &str, verifier: &str, device: &str) -> Result<SignedIn> {
        self.post("/api/auth/app/token", json!({ "code": code, "verifier": verifier, "deviceName": device })).await
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
        self.post("/api/play/resolve", json!({ "track": track, "kbps": kbps, "codecs": codecs, "thorough": thorough })).await
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

    pub async fn history(&self) -> Result<Vec<Listen>> {
        self.get("/api/history?limit=200").await
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
        self.get(&format!("/api/live/sxm/{}/now?type={}&id={}&at={at_ms}", enc(source), enc(kind), enc(id))).await
    }

    pub async fn radio_search(&self, source: &str, query: &str) -> Result<Vec<Track>> {
        self.get(&format!("/api/live/radio/{}/search?q={}", enc(source), enc(query))).await
    }

    pub async fn radio_now(&self, source: &str, id: &str) -> Result<Option<String>> {
        #[derive(Deserialize)]
        struct Now {
            title: Option<String>,
        }
        let now: Option<Now> = self.get(&format!("/api/live/radio/{}/station/{}/now", enc(source), enc(id))).await?;
        Ok(now.and_then(|n| n.title).filter(|t| !t.trim().is_empty()))
    }

    pub async fn live_favorites(&self) -> Result<Vec<Track>> {
        self.get("/api/live/favorites").await
    }

    pub async fn set_live_favorite(&self, track: &Track, favorite: bool) -> Result<Vec<Track>> {
        self.send(Method::PUT, "/api/live/favorites", Some(json!({ "track": track, "favorite": favorite }))).await
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
    let tries = if text.contains("://") { vec![text.to_string()] } else { vec![format!("https://{text}"), format!("http://{text}")] };
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
        assert_eq!(candidates("music.example.com"), vec!["https://music.example.com", "http://music.example.com"]);
        assert_eq!(candidates("https://music.example.com/settings?x=1"), vec!["https://music.example.com"]);
        assert_eq!(candidates("http://127.0.0.1:8099"), vec!["http://127.0.0.1:8099"]);
        assert!(candidates("  ").is_empty());
    }

    #[test]
    fn live_streams_are_where_the_web_player_finds_them() {
        let channel = Track { source: "SIRIUSXM".into(), source_id: "channel:xtra:altap".into(), via: Some("sxm 1".into()), ..Default::default() };
        let live = channel.live_stream().unwrap();
        assert_eq!(live.path, "/api/live/sxm/sxm%201/channel/xtra/altap");
        assert_eq!(live.kind, LiveKind::Channel);
        assert_eq!(channel.channel_parts(), Some(("xtra".into(), "altap".into())));
        let radio = Track { source: "RADIO".into(), source_id: "hls:abc".into(), via: Some("r".into()), ..Default::default() };
        assert_eq!(radio.live_stream().unwrap().path, "/api/live/radio/r/station/abc/stream");
        let song = Track { source: "JELLYFIN".into(), source_id: "s1".into(), via: Some("j".into()), ..Default::default() };
        assert!(song.live_stream().is_none());
        let station = Track { source: "SIRIUSXM".into(), source_id: "stationtrack:st1:tr9".into(), via: Some("s".into()), ..Default::default() };
        assert_eq!(station.as_song().source_id, "song:tr9");
    }
}
