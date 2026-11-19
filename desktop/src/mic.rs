//! The microphone, for Listen: recorded through GStreamer (whatever the system's default input
//! is, PipeWire or PulseAudio) as 16 kHz mono, and handed over as a WAV file of everything so far.

use std::path::PathBuf;

use anyhow::{Context, Result};
use gst::prelude::*;

pub const RATE: u32 = 16_000;

pub struct Mic {
    pipeline: gst::Element,
    file: PathBuf,
}

impl Mic {
    /// Starts recording. `SAMM_MIC` can name another GStreamer source (for trying it without a
    /// microphone: `SAMM_MIC="filesrc location=song.wav ! wavparse"`).
    pub fn start() -> Result<Mic> {
        let file = std::env::temp_dir().join(format!("samm-listen-{}.pcm", std::process::id()));
        let _ = std::fs::remove_file(&file);
        let source = std::env::var("SAMM_MIC").unwrap_or_else(|_| "autoaudiosrc".into());
        let location = file.to_string_lossy().replace('\\', "\\\\").replace('"', "\\\"");
        let description = format!(
            "{source} ! audioconvert ! audioresample ! audio/x-raw,format=S16LE,rate={RATE},channels=1 ! filesink location=\"{location}\" buffer-mode=unbuffered"
        );
        let pipeline = gst::parse::launch(&description).context("the microphone couldn't be opened")?;
        pipeline.set_state(gst::State::Playing).context("the microphone couldn't be started")?;
        Ok(Mic { pipeline, file })
    }

    /// Everything heard so far, as a WAV file.
    pub fn wav(&self) -> Vec<u8> {
        wav_of(&std::fs::read(&self.file).unwrap_or_default(), RATE)
    }

    pub fn stop(&self) {
        let _ = self.pipeline.set_state(gst::State::Null);
    }
}

impl Drop for Mic {
    fn drop(&mut self) {
        self.stop();
        let _ = std::fs::remove_file(&self.file);
    }
}

/// 16-bit mono [pcm] at [rate] as a WAV file.
pub fn wav_of(pcm: &[u8], rate: u32) -> Vec<u8> {
    let pcm = &pcm[..pcm.len() & !1];
    let mut out = Vec::with_capacity(pcm.len() + 44);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + pcm.len() as u32).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&rate.to_le_bytes());
    out.extend_from_slice(&(rate * 2).to_le_bytes());
    out.extend_from_slice(&2u16.to_le_bytes());
    out.extend_from_slice(&16u16.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&(pcm.len() as u32).to_le_bytes());
    out.extend_from_slice(pcm);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recordings_go_as_wav_files() {
        let wav = wav_of(&[1, 0, 2, 0, 3], 16_000);
        assert_eq!(&wav[..4], b"RIFF");
        assert_eq!(&wav[8..16], b"WAVEfmt ");
        assert_eq!(u32::from_le_bytes(wav[24..28].try_into().unwrap()), 16_000);
        // The odd byte left over isn't half a sample.
        assert_eq!(u32::from_le_bytes(wav[40..44].try_into().unwrap()), 4);
        assert_eq!(wav.len(), 48);
    }
}
