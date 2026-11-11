//! The app's settings: the server, the sign-in token and a few choices, as JSON in
//! `~/.config/SuperAwesomeMagicalMusic/settings.json` (readable by this user only: it holds the token).

use std::fs;
use std::io::Write;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use crate::api::User;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Config {
    /// The server's origin, like `https://music.example.com`.
    pub server: Option<String>,
    pub token: Option<String>,
    pub user: Option<User>,
    pub volume: f64,
    /// The bitrate cap in kbps; 0 is the original quality.
    pub kbps: u32,
    /// Compare every service for the best copy before playing (slower to start).
    pub thorough: bool,
    pub width: i32,
    pub height: i32,
    pub maximized: bool,
    /// The theme: aurora (the default), violet, ocean or ember.
    pub palette: String,
    /// light, dark, or system.
    pub style: String,
    /// All text in Lexend Bold.
    pub bold_text: bool,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            server: None,
            token: None,
            user: None,
            volume: 0.8,
            kbps: 0,
            thorough: true,
            width: 1180,
            height: 760,
            maximized: false,
            palette: "aurora".into(),
            style: "system".into(),
            bold_text: false,
        }
    }
}

fn dir() -> PathBuf {
    gtk::glib::user_config_dir().join("SuperAwesomeMagicalMusic")
}

impl Config {
    pub fn load() -> Config {
        fs::read_to_string(dir().join("settings.json"))
            .ok()
            .and_then(|text| serde_json::from_str(&text).ok())
            .unwrap_or_default()
    }

    pub fn save(&self) {
        let result = (|| -> std::io::Result<()> {
            fs::create_dir_all(dir())?;
            let path = dir().join("settings.json");
            let mut file = fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .mode(0o600)
                .open(&path)?;
            file.set_permissions(fs::Permissions::from_mode(0o600))?;
            file.write_all(serde_json::to_string_pretty(self).unwrap_or_default().as_bytes())
        })();
        if let Err(e) = result {
            eprintln!("Couldn't save the settings: {e}");
        }
    }
}
