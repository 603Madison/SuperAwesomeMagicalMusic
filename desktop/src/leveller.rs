//! Even volume, as the web player's leveller (web/src/player/leveller.ts): a song whose source
//! knows its loudness (Jellyfin's normalization gain) gets that fixed gain. Anything else is
//! levelled by ear: a slowly moving measure of how loud the music is (silence doesn't count) sets
//! the gain, which moves by at most a couple of dB a second. A limiter after it (GStreamer's
//! `rglimiter`) stops boosts clipping.
//!
//! This is the arithmetic; the player feeds it GStreamer's `level` readings four times a second
//! and sets a `volume` element to [Leveller::amplitude].

const TARGET_DB: f64 = -16.0;
/// Jellyfin's gain aims at -18 LUFS.
const KNOWN_GAIN_OFFSET_DB: f64 = 2.0;
const MIN_GAIN_DB: f64 = -12.0;
const MAX_GAIN_DB: f64 = 6.0;
const START_LEVEL_DB: f64 = -14.0;
const SILENCE_DB: f64 = -45.0;
const TIME_CONSTANT_S: f64 = 8.0;
const MAX_DB_PER_SECOND: f64 = 2.0;

fn db_to_power(db: f64) -> f64 {
    10f64.powf(db / 10.0)
}

fn power_to_db(power: f64) -> f64 {
    10.0 * power.max(1e-12).log10()
}

#[derive(Debug, Clone)]
pub struct Leveller {
    enabled: bool,
    known_gain_db: Option<f64>,
    gain_db: f64,
    mean_square: f64,
}

impl Default for Leveller {
    fn default() -> Self {
        Leveller {
            enabled: true,
            known_gain_db: None,
            gain_db: 0.0,
            mean_square: db_to_power(START_LEVEL_DB),
        }
    }
}

impl Leveller {
    /// A new song: [gain_db] is its known loudness gain, if its source has one.
    pub fn new_song(&mut self, gain_db: Option<f64>) {
        self.known_gain_db = gain_db;
        if let Some(known) = gain_db
            && self.enabled
        {
            self.gain_db = (known + KNOWN_GAIN_OFFSET_DB).clamp(MIN_GAIN_DB, MAX_GAIN_DB);
        }
    }

    pub fn set_enabled(&mut self, enabled: bool) {
        self.enabled = enabled;
        if !enabled {
            self.gain_db = 0.0;
        } else if let Some(known) = self.known_gain_db {
            self.gain_db = (known + KNOWN_GAIN_OFFSET_DB).clamp(MIN_GAIN_DB, MAX_GAIN_DB);
        }
    }

    pub fn enabled(&self) -> bool {
        self.enabled
    }

    /// Whether the gain is the song's own (not measured by ear).
    pub fn known(&self) -> bool {
        self.known_gain_db.is_some()
    }

    /// [seconds] more of the music, which measured [rms_db] (the loudest channel's RMS level).
    pub fn heard(&mut self, rms_db: f64, seconds: f64) {
        if !self.enabled {
            return;
        }
        if let Some(known) = self.known_gain_db {
            self.gain_db = (known + KNOWN_GAIN_OFFSET_DB).clamp(MIN_GAIN_DB, MAX_GAIN_DB);
            return;
        }
        let power = db_to_power(rms_db);
        if power > db_to_power(SILENCE_DB) {
            self.mean_square += (power - self.mean_square) * (1.0 - (-seconds / TIME_CONSTANT_S).exp());
        }
        let target = (TARGET_DB - power_to_db(self.mean_square)).clamp(MIN_GAIN_DB, MAX_GAIN_DB);
        let step = MAX_DB_PER_SECOND * seconds;
        self.gain_db += (target - self.gain_db).clamp(-step, step);
    }

    /// The gain now, in dB.
    pub fn gain_db(&self) -> f64 {
        if self.enabled { self.gain_db } else { 0.0 }
    }

    /// The gain now, as a factor for GStreamer's `volume`.
    pub fn amplitude(&self) -> f64 {
        10f64.powf(self.gain_db() / 20.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_known_gain_is_used_as_the_web_player_uses_it() {
        let mut l = Leveller::default();
        l.new_song(Some(-7.5));
        assert!((l.gain_db() - -5.5).abs() < 1e-9);
        l.heard(-3.0, 0.25);
        assert!((l.gain_db() - -5.5).abs() < 1e-9, "a known gain isn't measured over");
        l.new_song(Some(20.0));
        assert_eq!(l.gain_db(), MAX_GAIN_DB);
        l.set_enabled(false);
        assert_eq!(l.gain_db(), 0.0);
        assert_eq!(l.amplitude(), 1.0);
    }

    #[test]
    fn loud_music_is_turned_down_gently_and_silence_is_ignored() {
        let mut l = Leveller::default();
        l.new_song(None);
        // A second of very loud music: down by at most 2 dB.
        for _ in 0..4 {
            l.heard(-4.0, 0.25);
        }
        assert!(l.gain_db() < 0.0 && l.gain_db() >= -2.0 - 1e-9, "{}", l.gain_db());
        // A long while of it: towards -16 dB.
        for _ in 0..400 {
            l.heard(-4.0, 0.25);
        }
        assert!((l.gain_db() - -12.0).abs() < 0.5, "{}", l.gain_db());
        let before = l.gain_db();
        for _ in 0..40 {
            l.heard(-80.0, 0.25);
        }
        assert!((l.gain_db() - before).abs() < 1e-9, "silence doesn't count");
    }

    #[test]
    fn quiet_music_is_turned_up_no_more_than_6_db() {
        let mut l = Leveller::default();
        for _ in 0..800 {
            l.heard(-30.0, 0.25);
        }
        assert!((l.gain_db() - MAX_GAIN_DB).abs() < 1e-6, "{}", l.gain_db());
        assert!((l.amplitude() - 10f64.powf(0.3)).abs() < 1e-6);
    }
}
