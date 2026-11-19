import { create } from "zustand";
import type { NowView, Track } from "./types";

/** Which copy of a tuner station is playing, and how well the tuner hears it. */
export interface RadioSignal {
  via: "tuner" | "internet";
  /** 0 (static) to 1 (clear). */
  score?: number;
  detail?: string;
  hd: boolean;
}

/**
 * What a radio station's "now" says. Every player reads `title` ("Artist - Title"); stations on
 * a tuner also send the song itself (with its cover) and their signal.
 */
export interface RadioNow {
  title?: string;
  now?: NowView;
  signal?: RadioSignal;
}

interface Known {
  station?: string;
  now?: NowView;
  signal?: RadioSignal;
}

const useKnown = create<Known>(() => ({}));

/** Keeps what a station's "now" said last (asked for by the player as the station plays). */
export function rememberRadioNow(station: string, now: RadioNow) {
  useKnown.setState({ station, now: now.now, signal: now.signal });
}

const stationOf = (track: Track | undefined) =>
  track?.source === "RADIO" && track.via ? `${track.via}|${track.sourceId.replace(/^hls:/, "")}` : undefined;

interface LiveLike {
  title: string;
  artist: string;
  album?: string;
  artworkUrl?: string;
  song?: Track;
}

/**
 * [liveNow] with what the station said about the same song beyond its title: its cover, its
 * album, and the song itself (for its lyrics).
 */
export function useRadioDetail<T extends LiveLike>(liveNow: T | null, track: Track | undefined): T | null {
  const known = useKnown();
  if (!liveNow || !known.now || known.station !== stationOf(track)) return liveNow;
  if (known.now.title !== liveNow.title || known.now.artist !== liveNow.artist) return liveNow;
  return {
    ...liveNow,
    artworkUrl: liveNow.artworkUrl ?? known.now.artworkUrl,
    album: liveNow.album ?? known.now.album,
    song: liveNow.song ?? known.now.song,
  };
}

/** The tuner station's signal, while one plays. */
export function useRadioSignal(track: Track | undefined): RadioSignal | undefined {
  const known = useKnown();
  return known.station && known.station === stationOf(track) ? known.signal : undefined;
}

/** "On your tuner" or "Online", for a station on both. */
export function signalLabel(signal: RadioSignal | undefined): string | undefined {
  if (!signal) return undefined;
  if (signal.via === "internet") return "Online";
  return signal.hd ? "HD Radio on your tuner" : "On your tuner";
}
