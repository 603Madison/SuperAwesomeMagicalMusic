import type { Track } from "../lib/types";

/** How a live item (a SiriusXM channel or station track, or a radio station) plays. */
export interface LiveStream {
  url: string;
  hls: boolean;
  /** No end and no seeking. */
  live: boolean;
  description: string;
  kind: "channel" | "station-track" | "radio";
}

const q = encodeURIComponent;

export function liveStreamOf(track: Track): LiveStream | null {
  const via = track.via;
  if (!via) return null;
  if (track.source === "SIRIUSXM" && track.sourceId.startsWith("channel:")) {
    const [type, id] = splitTwo(track.sourceId.slice("channel:".length));
    if (!type || !id) return null;
    return { url: `/api/live/sxm/${q(via)}/channel/${q(type)}/${q(id)}`, hls: true, live: true, description: "SiriusXM", kind: "channel" };
  }
  if (track.source === "SIRIUSXM" && track.sourceId.startsWith("stationtrack:")) {
    const [, trackId] = splitTwo(track.sourceId.slice("stationtrack:".length));
    if (!trackId) return null;
    return { url: `/api/live/sxm/${q(via)}/station-track/${q(trackId)}`, hls: false, live: false, description: "Pandora station · SiriusXM", kind: "station-track" };
  }
  if (track.source === "RADIO") {
    const hls = track.sourceId.startsWith("hls:");
    const id = track.sourceId.replace(/^hls:/, "");
    return { url: `/api/live/radio/${q(via)}/station/${q(id)}/stream`, hls, live: true, description: "Radio", kind: "radio" };
  }
  return null;
}

/** [type, id] of a channel, or [station id, track id] of a station track. */
export function splitTwo(value: string): [string, string] {
  const at = value.indexOf(":");
  return at < 0 ? [value, ""] : [value.slice(0, at), value.slice(at + 1)];
}

export const isLive = (track: Track) => liveStreamOf(track)?.live === true;

/** A station track kept as the song itself (its link expires); a played song stays as it is. */
export function asSong(track: Track): Track {
  if (track.source === "SIRIUSXM" && track.sourceId.startsWith("stationtrack:")) {
    return { ...track, sourceId: `song:${splitTwo(track.sourceId.slice("stationtrack:".length))[1]}`, via: undefined };
  }
  return track;
}

/** "Artist - Title" as radio stations send it. */
export function splitRadioTitle(text: string): { title: string; artist: string } {
  const at = text.indexOf(" - ");
  return at > 0 ? { artist: text.slice(0, at).trim(), title: text.slice(at + 3).trim() } : { artist: "", title: text.trim() };
}
