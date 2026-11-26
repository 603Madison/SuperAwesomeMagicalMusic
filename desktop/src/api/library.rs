//! The library side of the server's API (as web/src/lib/api.ts has it): sharing and importing
//! playlists, history, Wrapped, and Pandora stations and radio for the Radio page.

use anyhow::Result;
use reqwest::Method;
use serde::{Deserialize, Serialize};
use serde_json::json;

use super::{Api, Playlist, PlaylistPerson, PlaylistSummary, Track, enc, time_zone};

/// What an import made: the playlist, how many songs were found, and those that weren't.
#[derive(Debug, Clone, Deserialize)]
pub struct Imported {
    pub playlist: Playlist,
    #[serde(default)]
    pub found: i64,
    #[serde(default)]
    pub missing: Vec<String>,
    #[serde(default)]
    pub note: Option<String>,
}

/// Where a playlist is imported from: a link, a list (typed, pasted or a file's), or a playlist
/// kept on one of your Subsonic servers.
#[derive(Debug, Clone, PartialEq)]
pub enum ImportFrom {
    Link(String),
    List { text: String, name: String },
    Library { source: String, playlist: String },
}

impl ImportFrom {
    fn body(&self) -> serde_json::Value {
        match self {
            ImportFrom::Link(url) => json!({ "url": url }),
            ImportFrom::List { text, name } => json!({ "text": text, "name": name }),
            ImportFrom::Library { source, playlist } => json!({ "source": source, "playlist": playlist }),
        }
    }
}

/// Looking again for a playlist's songs that weren't found.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FoundAgain {
    pub playlist: Playlist,
    #[serde(default)]
    pub found: i64,
    #[serde(default)]
    pub still_missing: i64,
}

/// A playlist kept on a Subsonic server, to import.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SourcePlaylist {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub song_count: Option<i64>,
}

/// An artist SiriusXM has a Pandora station for (or can make one for).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtistView {
    pub id: String,
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub artwork_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub station_id: Option<String>,
    #[serde(default = "talent")]
    pub entity_type: String,
}

fn talent() -> String {
    "talent".into()
}

/// A Pandora station, and its first (or next) songs.
#[derive(Debug, Clone, Deserialize)]
pub struct StationTracks {
    pub station: Track,
    #[serde(default)]
    pub tracks: Vec<Track>,
}

/// The start of a Pandora artist station's id, as a favourite keeps it.
pub const ARTIST_STATION: &str = "channel:artist-station:";

impl Track {
    /// A favourite Pandora artist station (played by starting the station, not tuning in).
    pub fn is_artist_station(&self) -> bool {
        self.source == "SIRIUSXM" && self.source_id.starts_with(ARTIST_STATION)
    }

    /// A station on the person's FM tuner: "FM", or "HD2" for an HD Radio subchannel.
    pub fn tuner_badge(&self) -> Option<String> {
        let id = self.source_id.strip_prefix("tuner:")?;
        let hd = id
            .find("-hd")
            .and_then(|at| id[at + 3..].chars().next())
            .filter(char::is_ascii_digit);
        Some(match hd {
            Some(n) => format!("HD{n}"),
            None => "FM".into(),
        })
    }
}

// ---- Wrapped ----

/// A year of listening (the server's Wrapped). Minutes are listened time.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Wrapped {
    pub year: i32,
    pub years: Vec<i32>,
    pub so_far: bool,
    pub listens: i64,
    pub minutes: i64,
    pub songs: i64,
    pub artists: i64,
    pub albums: i64,
    pub days: i64,
    pub top_songs: Vec<WrappedSong>,
    pub top_artists: Vec<WrappedArtist>,
    pub top_albums: Vec<WrappedAlbum>,
    pub top_stations: Vec<WrappedShare>,
    pub sources: Vec<WrappedShare>,
    pub devices: Vec<WrappedShare>,
    pub months: Vec<i64>,
    pub hours: Vec<i64>,
    pub weekdays: Vec<i64>,
    pub biggest_day: Option<WrappedDay>,
    pub longest_streak: i64,
    pub first_song: Option<WrappedSong>,
    pub on_repeat: Option<WrappedRepeat>,
    pub discoveries: Vec<WrappedArtist>,
    pub new_artists: i64,
    pub personality: WrappedPersonality,
}

#[derive(Debug, Clone, Deserialize)]
pub struct WrappedSong {
    pub track: Track,
    #[serde(default)]
    pub plays: i64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WrappedArtist {
    pub name: String,
    #[serde(default)]
    pub plays: i64,
    #[serde(default)]
    pub minutes: i64,
    #[serde(default)]
    pub artwork_url: Option<String>,
    #[serde(default)]
    pub top_song: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WrappedAlbum {
    pub title: String,
    #[serde(default)]
    pub artist: String,
    #[serde(default)]
    pub minutes: i64,
    #[serde(default)]
    pub artwork_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct WrappedShare {
    pub name: String,
    #[serde(default)]
    pub minutes: i64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct WrappedDay {
    /// "2026-03-14".
    pub date: String,
    #[serde(default)]
    pub minutes: i64,
    #[serde(default)]
    pub listens: i64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct WrappedRepeat {
    pub track: Track,
    #[serde(default)]
    pub plays: i64,
    #[serde(default)]
    pub date: String,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct WrappedPersonality {
    pub name: String,
    pub description: String,
}

impl Api {
    // Shared playlists.

    /// The people on the server, to share playlists with.
    pub async fn people(&self) -> Result<Vec<PlaylistPerson>> {
        self.get("/api/people").await
    }

    /// Shares a playlist (yours) with someone: they add, remove and reorder its songs too.
    pub async fn share_playlist(&self, id: &str, user_id: &str) -> Result<PlaylistSummary> {
        self.post(&format!("/api/playlists/{}/members", enc(id)), json!({ "userId": user_id }))
            .await
    }

    /// Stops sharing it with someone (its owner), or leaves it (yourself).
    pub async fn unshare_playlist(&self, id: &str, user_id: &str) -> Result<serde_json::Value> {
        self.send(
            Method::DELETE,
            &format!("/api/playlists/{}/members/{}", enc(id), enc(user_id)),
            None,
        )
        .await
    }

    // Importing, and finding what wasn't found.

    pub async fn import_playlist(&self, from: &ImportFrom) -> Result<Imported> {
        self.post("/api/playlists/import", from.body()).await
    }

    pub async fn find_missing(&self, id: &str) -> Result<FoundAgain> {
        self.post(&format!("/api/playlists/{}/find-missing", enc(id)), json!({})).await
    }

    /// The playlists kept on one of your Subsonic servers.
    pub async fn source_playlists(&self, source: &str) -> Result<Vec<SourcePlaylist>> {
        self.get(&format!("/api/sources/{}/playlists", enc(source))).await
    }

    // History, a hundred at a time.

    /// What was listened to, newest first: the latest, or those before [before].
    pub async fn history_before(&self, before: Option<i64>) -> Result<Vec<super::Listen>> {
        let older = before.map(|b| format!("&before={b}")).unwrap_or_default();
        self.get(&format!("/api/history?limit={HISTORY_PAGE}{older}")).await
    }

    /// Forgets everything listened to, on every device.
    pub async fn clear_history(&self) -> Result<serde_json::Value> {
        self.send(Method::DELETE, "/api/history", None).await
    }

    /// A year of listening (this one, or [year]), in this computer's time zone.
    pub async fn wrapped(&self, year: Option<i32>) -> Result<Wrapped> {
        let year = year.map(|y| format!("&year={y}")).unwrap_or_default();
        self.get(&format!("/api/wrapped?tz={}{year}", enc(&time_zone()))).await
    }

    // Pandora stations, through SiriusXM.

    pub async fn sxm_artists(&self, source: &str, query: &str) -> Result<Vec<ArtistView>> {
        self.get(&format!("/api/live/sxm/{}/artists?q={}", enc(source), enc(query))).await
    }

    /// Starts (or finds) an artist's station.
    pub async fn artist_station(&self, source: &str, artist: &ArtistView) -> Result<StationTracks> {
        self.post(&format!("/api/live/sxm/{}/station", enc(source)), json!({ "artist": artist }))
            .await
    }

    /// A station already made (a favourite): its songs.
    pub async fn station_tracks(&self, source: &str, station_id: &str, name: &str) -> Result<StationTracks> {
        self.post(
            &format!("/api/live/sxm/{}/station", enc(source)),
            json!({ "stationId": station_id, "name": name }),
        )
        .await
    }

    // Radio.

    /// Stations by name or genre, or (no query) what's popular in [country].
    pub async fn radio_find(&self, source: &str, query: &str, country: Option<&str>) -> Result<Vec<Track>> {
        let country = country.map(|c| format!("&country={}", enc(c))).unwrap_or_default();
        self.get(&format!("/api/live/radio/{}/search?q={}{country}", enc(source), enc(query)))
            .await
    }

    pub async fn radio_nearby(&self, source: &str, lat: f64, lon: f64) -> Result<Vec<Track>> {
        self.get(&format!("/api/live/radio/{}/nearby?lat={lat:.4}&lon={lon:.4}", enc(source)))
            .await
    }
}

/// How many listens History asks for at a time.
pub const HISTORY_PAGE: usize = 100;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn imports_say_where_from() {
        assert_eq!(ImportFrom::Link("https://x".into()).body(), json!({ "url": "https://x" }));
        assert_eq!(
            ImportFrom::List {
                text: "A - B".into(),
                name: "Mine".into()
            }
            .body(),
            json!({ "text": "A - B", "name": "Mine" })
        );
        assert_eq!(
            ImportFrom::Library {
                source: "s".into(),
                playlist: "p".into()
            }
            .body(),
            json!({ "source": "s", "playlist": "p" })
        );
    }

    #[test]
    fn tuner_stations_are_badged() {
        let station = |id: &str| Track {
            source: "RADIO".into(),
            source_id: id.into(),
            ..Default::default()
        };
        assert_eq!(station("tuner:t1:101.1").tuner_badge().as_deref(), Some("FM"));
        assert_eq!(station("tuner:t1:101.1-hd2").tuner_badge().as_deref(), Some("HD2"));
        assert_eq!(station("hls:abc").tuner_badge(), None);
        let favourite = Track {
            source: "SIRIUSXM".into(),
            source_id: format!("{ARTIST_STATION}123"),
            ..Default::default()
        };
        assert!(favourite.is_artist_station());
    }

    #[test]
    fn wrapped_reads_what_the_server_sends() {
        let wrapped: Wrapped = serde_json::from_value(json!({
            "year": 2026, "years": [2025, 2026], "soFar": true, "listens": 3, "minutes": 12,
            "topSongs": [{ "track": { "source": "JELLYFIN", "sourceId": "1", "title": "T", "artist": "A" }, "plays": 2 }],
            "hours": [0, 1], "biggestDay": { "date": "2026-03-14", "minutes": 9, "listens": 3 },
            "personality": { "name": "The Explorer", "description": "…" }
        }))
        .unwrap();
        assert_eq!(wrapped.years, vec![2025, 2026]);
        assert_eq!(wrapped.top_songs[0].plays, 2);
        assert_eq!(wrapped.biggest_day.unwrap().date, "2026-03-14");
        assert_eq!(wrapped.personality.name, "The Explorer");
    }
}
