import type { SourceType, Track } from "./types";

export function duration(ms?: number): string {
  if (ms == null || !isFinite(ms) || ms < 0) return "";
  const total = Math.round(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
}

export function longDuration(ms: number): string {
  const minutes = Math.round(ms / 60000);
  if (minutes < 60) return `${minutes} min`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m ? `${h} hr ${m} min` : `${h} hr`;
}

export const SOURCE_LABELS: Record<SourceType, string> = {
  JELLYFIN: "Jellyfin",
  QOBUZ: "Qobuz",
  YOUTUBE_MUSIC: "YouTube Music",
  SIRIUSXM: "SiriusXM",
  RADIO: "Radio",
};

export function trackKey(track: Track): string {
  return `${track.via ?? ""}|${track.source}:${track.sourceId}`;
}

export function when(time: number): string {
  const diff = Date.now() - time;
  const minute = 60_000;
  if (diff < minute) return "just now";
  if (diff < 60 * minute) return `${Math.floor(diff / minute)} min ago`;
  if (diff < 24 * 60 * minute) return `${Math.floor(diff / (60 * minute))} hr ago`;
  const date = new Date(time);
  const sameYear = date.getFullYear() === new Date().getFullYear();
  return date.toLocaleDateString(undefined, { month: "short", day: "numeric", year: sameYear ? undefined : "numeric" });
}

export function plural(n: number, word: string): string {
  return `${n} ${word}${n === 1 ? "" : "s"}`;
}

/** A song a playlist names that wasn't found in any source: shown greyed out, never played. */
export const isMissing = (track: Track): boolean => track.sourceId.startsWith("missing:");
