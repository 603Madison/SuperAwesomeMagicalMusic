//! The DJ talks: on the person's own stations, what the DJ says is spoken over the start of the
//! song (as the web player has the browser say it), through the desktop's speech
//! (speech-dispatcher's `spd-say`), when "The DJ talks" is on in Preferences.

use std::cell::{Cell, RefCell};
use std::collections::HashSet;
use std::time::Duration;

use gtk::glib;

use crate::api::Track;
use crate::ui::ctx;

thread_local! {
    static SPOKEN: RefCell<HashSet<String>> = RefCell::new(HashSet::new());
    /// The station tuned in to (welcomed once per tune-in).
    static WELCOMED: RefCell<Option<String>> = const { RefCell::new(None) };
    static WAS_PLAYING: Cell<bool> = const { Cell::new(false) };
    /// Goes up when the DJ is told to be quiet: lines waiting to be said are dropped.
    static HUSH: Cell<u64> = const { Cell::new(0) };
}

/// Whether this computer can speak (speech-dispatcher's `spd-say` is installed).
pub fn can_speak() -> bool {
    glib::find_program_in_path("spd-say").is_some()
}

/// How long to wait before saying a line said at [at_ms] on a station whose clock is
/// [server_time] now and heard [heard_delay_ms] behind; none when it's too late (tuned in after it).
pub fn wait_for(at_ms: i64, server_time: i64, heard_delay_ms: i64) -> Option<Duration> {
    let wait = at_ms + heard_delay_ms - server_time;
    (wait >= -6_000).then(|| Duration::from_millis(wait.max(0) as u64))
}

/// Listens to the player for the DJ's lines (once, when the window opens).
pub fn start() {
    ctx().player.subscribe(|state| {
        let station = state.current().filter(|t| Track::is_personal(t)).map(|t| t.source_id.clone());
        let playing = state.playing && station.is_some();
        if WAS_PLAYING.with(|w| w.replace(playing)) && !playing {
            quiet();
        }
        if station.is_none() {
            WELCOMED.with(|w| w.replace(None));
        }
        if !playing || !ctx().config.borrow().dj_voice {
            return;
        }
        let Some(info) = &state.personal else { return };
        if let Some(welcome) = info.welcome.clone()
            && WELCOMED.with(|w| *w.borrow() != station)
        {
            WELCOMED.with(|w| w.replace(station.clone()));
            say_later(welcome, Duration::from_millis(1200));
        }
        if let Some(line) = &info.dj
            && SPOKEN.with(|s| s.borrow_mut().insert(line.id.clone()))
            && let Some(wait) = wait_for(line.at_ms, info.server_time, info.heard_delay_ms)
        {
            say_later(line.text.clone(), wait);
        }
    });
}

fn say_later(text: String, wait: Duration) {
    let hush = HUSH.with(Cell::get);
    glib::timeout_add_local_once(wait, move || {
        let state = ctx().player.state();
        let still = state.playing && state.current().is_some_and(Track::is_personal);
        if HUSH.with(Cell::get) == hush && still && ctx().config.borrow().dj_voice {
            speak(&text);
        }
    });
}

fn speak(text: &str) {
    let text = text.to_string();
    // Waited for on its own thread, so it's reaped when it's done.
    std::thread::spawn(move || {
        let _ = std::process::Command::new("spd-say").args(["--rate", "3", "--", &text]).status();
    });
}

/// Stops the DJ (a pause, another song or station).
pub fn quiet() {
    HUSH.with(|h| h.set(h.get() + 1));
    if can_speak() {
        std::thread::spawn(|| {
            let _ = std::process::Command::new("spd-say").arg("--cancel").status();
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lines_are_said_when_heard_or_not_at_all() {
        // Heard 5 s behind the station: a line at the station's "now" is said in 5 s.
        assert_eq!(wait_for(10_000, 10_000, 5_000), Some(Duration::from_millis(5_000)));
        // A little late: said now.
        assert_eq!(wait_for(10_000, 14_000, 0), Some(Duration::ZERO));
        // Tuned in long after: left unsaid.
        assert_eq!(wait_for(10_000, 30_000, 0), None);
    }
}
