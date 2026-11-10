import { create } from "zustand";
import { api, ApiError, art } from "../lib/api";
import { isMissing, trackKey } from "../lib/format";
import type { ResolvedStream, Track } from "../lib/types";
import { Leveller } from "./leveller";
import { asSong, liveStreamOf, splitRadioTitle, splitTwo } from "./live";
import { usePrefs } from "./prefs";
import type Hls from "hls.js";

export interface QueueItem {
  id: string;
  track: Track;
}

export type Repeat = "off" | "all" | "one";

export interface PlayerState {
  queue: QueueItem[];
  /** The playing (or paused) song's place in the queue; -1 when nothing is queued. */
  index: number;
  playing: boolean;
  buffering: boolean;
  /** Seconds into the song. */
  position: number;
  duration: number;
  current: ResolvedStream | null;
  error: string | null;
  volume: number;
  muted: boolean;
  repeat: Repeat;
  /** When the sleep timer stops playback (epoch ms), or "track" for the end of this song. */
  sleepAt: number | "track" | null;
  /** What's on the live channel or station playing, when it says. */
  liveNow: LiveNow | null;
  /** What was on before, since it was tuned in (newest first). */
  liveHistory: LiveNow[];
  /** Thumbs given to Pandora station tracks, by track id. */
  thumbs: Record<string, "UP" | "DOWN">;
}

export interface LiveNow {
  title: string;
  artist: string;
  artworkUrl?: string;
  /** The song, to save: it plays from your library or a streaming service. */
  song?: Track;
  album?: string;
  /** When the song started and how long it is (SiriusXM says; radio stations don't). */
  startMs?: number;
  durationMs?: number;
  /** When it was first heard here. */
  heardAt?: number;
}

export const usePlayer = create<PlayerState>(() => ({
  queue: [],
  index: -1,
  playing: false,
  buffering: false,
  position: 0,
  duration: 0,
  current: null,
  error: null,
  volume: 1,
  muted: false,
  repeat: "off",
  sleepAt: null,
  liveNow: null,
  liveHistory: [],
  thumbs: {},
}));

const set = usePlayer.setState;
const state = usePlayer.getState;

const audio = new Audio();
audio.preload = "auto";
const leveller = new Leveller(audio);

/** Seconds into the song where the current stream starts (streams that can't seek restart there). */
let offset = 0;
/** Ignores answers for songs that were skipped while being looked up. */
let loadToken = 0;
let consecutiveErrors = 0;
let hls: Hls | null = null;
const resolutions = new Map<string, { at: number; value: Promise<ResolvedStream> }>();

let newId = 0;
const itemOf = (track: Track): QueueItem => ({ id: `${Date.now().toString(36)}-${(newId++).toString(36)}`, track });

/** The codecs this browser plays, so the server only converts what it must. */
export const CODECS: string[] = (() => {
  const a = document.createElement("audio");
  const can = (type: string) => a.canPlayType(type) !== "";
  const codecs: string[] = [];
  if (can("audio/flac")) codecs.push("flac");
  if (can("audio/mpeg")) codecs.push("mp3");
  if (can('audio/mp4; codecs="mp4a.40.2"') && can("audio/aac")) codecs.push("aac");
  // YouTube's Opus comes in WebM, the server's in Ogg: both must play.
  if (can('audio/webm; codecs="opus"') && can('audio/ogg; codecs="opus"')) codecs.push("opus");
  if (can('audio/ogg; codecs="vorbis"')) codecs.push("vorbis");
  if (can('audio/mp4; codecs="alac"')) codecs.push("alac");
  if (can("audio/wav")) codecs.push("wav");
  return codecs;
})();

function resolve(track: Track): Promise<ResolvedStream> {
  const { kbps, thorough } = usePrefs.getState();
  const key = `${trackKey(track)}|${kbps}|${thorough}`;
  const cached = resolutions.get(key);
  if (cached && Date.now() - cached.at < 30 * 60_000) return cached.value;
  const value = api.resolve(track, kbps, CODECS, thorough);
  value.catch(() => resolutions.delete(key));
  resolutions.set(key, { at: Date.now(), value });
  if (resolutions.size > 200) resolutions.delete(resolutions.keys().next().value!);
  return value;
}

// ---- Listening history ----

let listenedMs = 0;
let lastTick = 0;
let reported = false;

function reportListen() {
  const { current, queue, index } = state();
  const item = queue[index];
  if (!item || reported || current?.live) return;
  const length = (item.track.durationMs ?? 0) || state().duration * 1000;
  // Counted like a scrobble: half the song, or four minutes, but at least 30 seconds.
  const needed = Math.max(30_000, Math.min(length / 2 || 30_000, 240_000));
  if (listenedMs < needed) return;
  reported = true;
  const track = asSong(current?.track ?? item.track);
  api.addListens([{ track, playedAt: Date.now() - listenedMs, listenedMs, client: "web" }]).catch(() => undefined);
}

// ---- Loading and playing ----

async function load(index: number, autoplay: boolean, startAt = 0) {
  const item = state().queue[index];
  if (!item) return;
  reportListen();
  listenedMs = 0;
  reported = false;
  const token = ++loadToken;
  audio.pause();
  set({ index, current: null, buffering: true, error: null, position: startAt, duration: (item.track.durationMs ?? 0) / 1000, playing: autoplay, liveNow: null, liveHistory: [] });
  try {
    const live = liveStreamOf(item.track);
    const resolved: ResolvedStream = live
      ? { track: item.track, streamUrl: live.url, description: live.description, seekable: !live.live, transcoded: false, withinCap: true, extension: "", live: live.live, hls: live.hls }
      : await resolve(item.track);
    if (token !== loadToken) return;
    let src = resolved.streamUrl;
    offset = 0;
    if (startAt > 0 && !resolved.seekable && !resolved.live) {
      src += `&start=${startAt.toFixed(1)}`;
      offset = startAt;
    }
    await attach(src, resolved.hls === true);
    if (token !== loadToken) return;
    if (resolved.live) set({ duration: 0, position: 0 });
    if (startAt > 0 && resolved.seekable) {
      audio.addEventListener("loadedmetadata", () => { audio.currentTime = startAt; }, { once: true });
    }
    leveller.newSong(resolved.gainDb);
    set({ current: resolved });
    updateMediaSession();
    if (autoplay) await playAudio();
    else set({ buffering: false });
    consecutiveErrors = 0;
    prefetchNext();
    onLiveStart(item.track);
  } catch (e) {
    if (token !== loadToken) return;
    failed(e instanceof ApiError ? e.message : "Couldn't play this song");
  }
}

/** Points the player at [src]: a plain file, or an HLS playlist (with hls.js where the browser needs it). */
async function attach(src: string, isHls: boolean) {
  if (hls) {
    hls.destroy();
    hls = null;
  }
  if (!isHls || audio.canPlayType("application/vnd.apple.mpegurl")) {
    audio.src = src;
    return;
  }
  const { default: Hls } = await import("hls.js");
  if (!Hls.isSupported()) throw new ApiError(0, "This browser can't play live streams");
  const instance = new Hls({ backBufferLength: 60, maxBufferLength: 60, liveSyncDurationCount: 3 });
  let part: number | null = null;
  instance.on(Hls.Events.ERROR, (_, data) => {
    const segment = data.frag ? ` (segment ${data.frag.sn}, track part ${data.frag.cc}, from ${upstreamHost(data.frag.url)})` : "";
    const code = data.response?.code ? ` HTTP ${data.response.code}` : "";
    reportLive(`${data.fatal ? "fatal " : ""}${data.details}${code}${segment}${data.error?.message ? `: ${data.error.message}` : ""}`, data.details);
    if (!data.fatal) return;
    if (data.type === Hls.ErrorTypes.NETWORK_ERROR) instance.startLoad();
    else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) recoverMedia(instance);
    else failed("The live stream stopped");
  });
  // Each track of an Xtra channel is a new part of the stream: noted, to line up with errors.
  instance.on(Hls.Events.FRAG_CHANGED, (_, data) => {
    if (data.frag.cc === part) return;
    if (part != null) reportLive(`playing track part ${data.frag.cc} (segment ${data.frag.sn}, from ${upstreamHost(data.frag.url)})`, `part${data.frag.cc}`);
    part = data.frag.cc;
  });
  instance.loadSource(src);
  instance.attachMedia(audio);
  hls = instance;
}

/**
 * After the media pipeline failed (a track whose audio the browser couldn't take, say): hls.js
 * rebuilds it, and playback carries on if it was meant to be playing. Twice in a minute is the
 * limit; then it's given up as failed.
 */
let mediaRecoveries: number[] = [];
function recoverMedia(instance: Hls) {
  const now = Date.now();
  mediaRecoveries = mediaRecoveries.filter((at) => now - at < 60_000);
  if (mediaRecoveries.length >= 2) {
    failed("The live stream stopped");
    return;
  }
  mediaRecoveries.push(now);
  // hls.js's own advice: the second time, the audio codec may be what's wrong (a track encoded
  // differently from the one before), so it's swapped before trying again.
  const swap = mediaRecoveries.length === 2;
  reportLive(`recovering from a media error${swap ? " (swapping the audio codec)" : ""}`, `recover${mediaRecoveries.length}`);
  if (swap) instance.swapAudioCodec();
  instance.recoverMediaError();
  if (state().playing) window.setTimeout(() => { if (hls === instance) audio.play().catch(() => undefined); }, 300);
}

/** The service's host behind one of the server's relayed addresses. */
function upstreamHost(url: string | undefined): string {
  try {
    const relayed = new URL(url ?? "", location.href).searchParams.get("u");
    return new URL(relayed ?? url ?? "").host || "?";
  } catch {
    return "?";
  }
}

const lastReports: Record<string, number> = {};
/**
 * Tells the server what the player ran into on a SiriusXM channel (the server can't see the
 * browser): it goes in SiriusXM's timeline, beside what SiriusXM sent. The same thing at most
 * every few seconds.
 */
function reportLive(event: string, key = event) {
  const track = state().queue[state().index]?.track;
  if (!track?.via || track.source !== "SIRIUSXM" || !track.sourceId.startsWith("channel:")) return;
  const now = Date.now();
  if (now - (lastReports[key] ?? 0) < 5_000) return;
  lastReports[key] = now;
  const [, channel] = splitTwo(track.sourceId.slice("channel:".length));
  const ahead = player.bufferedAhead().toFixed(1);
  api.playerReport(track.via, { event: `${event} [at ${Math.round(audio.currentTime)}s, ${ahead}s loaded ahead]`, channel }).catch(() => undefined);
}

// ---- Live radio: what's on, and SiriusXM's station tracks ----

let livePoll: number | null = null;
let fetchingMore = false;

function onLiveStart(track: Track) {
  if (livePoll != null) clearInterval(livePoll);
  livePoll = null;
  const live = liveStreamOf(track);
  if (!live) return;
  if (live.kind === "station-track") {
    const [stationId, trackId] = splitTwo(track.sourceId.slice("stationtrack:".length));
    api.stationProgress(track.via!, stationId, trackId, 0, true).catch(() => undefined);
    void moreFromStation();
  }
  void pollLive();
  livePoll = window.setInterval(() => void pollLive(), live.kind === "station-track" ? 30_000 : 10_000);
}

/** What's on now; the song before goes into the history when it changes. */
function heard(now: LiveNow | null) {
  const before = state().liveNow;
  const same = before && now && before.title === now.title && before.artist === now.artist;
  if (same) {
    set({ liveNow: { ...now, heardAt: before.heardAt } });
    return;
  }
  const history = before ? [before, ...state().liveHistory.filter((h) => h.title !== before.title || h.artist !== before.artist)].slice(0, 50) : state().liveHistory;
  set({ liveNow: now ? { ...now, heardAt: Date.now() } : null, liveHistory: history });
}

async function pollLive() {
  const { queue, index, playing } = state();
  const track = queue[index]?.track;
  const live = track && liveStreamOf(track);
  if (!track || !live || (!playing && state().liveNow)) return;
  try {
    if (live.kind === "channel") {
      const [type, id] = splitTwo(track.sourceId.slice("channel:".length));
      // Where the player is in the stream, as a time: what's heard, not what's newest.
      const at = hls?.playingDate?.getTime() ?? Date.now();
      const now = await api.channelNow(track.via!, type, id, at);
      if (state().queue[state().index]?.track !== track) return;
      heard(now ? { title: now.title, artist: now.artist, album: now.album, artworkUrl: now.artworkUrl, song: now.song, startMs: now.startMs, durationMs: now.durationMs } : null);
    } else if (live.kind === "radio") {
      const { title } = await api.radioNow(track.via!, track.sourceId.replace(/^hls:/, ""));
      if (state().queue[state().index]?.track !== track) return;
      heard(title ? splitRadioTitle(title) : null);
    } else if (playing) {
      const [stationId, trackId] = splitTwo(track.sourceId.slice("stationtrack:".length));
      api.stationProgress(track.via!, stationId, trackId, Math.round(audio.currentTime * 1000), false).catch(() => undefined);
    }
    updateMediaSession();
  } catch {
    // What's-on is a nicety; the music keeps playing.
  }
}

/** Near the end of a Pandora station's queue, asks SiriusXM for more of it. */
async function moreFromStation() {
  const { queue, index } = state();
  const last = queue[queue.length - 1]?.track;
  if (fetchingMore || !last || index < queue.length - 3) return;
  if (!(last.source === "SIRIUSXM" && last.sourceId.startsWith("stationtrack:") && last.via)) return;
  const [stationId] = splitTwo(last.sourceId.slice("stationtrack:".length));
  fetchingMore = true;
  try {
    const more = await api.station(last.via, { stationId, name: last.album, more: true });
    if (more.tracks.length) set({ queue: [...state().queue, ...more.tracks.map(itemOf)] });
  } catch {
    // Tried again at the next track.
  } finally {
    fetchingMore = false;
  }
}

function failed(message: string) {
  consecutiveErrors++;
  set({ error: message, buffering: false, playing: false });
  // Skip what can't be played, as the app does, but don't run through a whole queue of failures.
  const { index, queue } = state();
  if (consecutiveErrors < 4 && index + 1 < queue.length) {
    const token = loadToken;
    setTimeout(() => { if (token === loadToken) void load(index + 1, true); }, 1500);
  }
}

async function playAudio() {
  await leveller.start();
  try {
    await audio.play();
  } catch (e) {
    // A browser refusing to start without a click: wait for one.
    if (e instanceof DOMException && e.name === "NotAllowedError") set({ playing: false, buffering: false });
    else if (!(e instanceof DOMException && e.name === "AbortError")) throw e;
  }
}

function prefetchNext() {
  const { queue, index } = state();
  const next = queue[index + 1];
  if (next) resolve(next.track).catch(() => undefined);
}

function nextIndex(): number | null {
  const { queue, index, repeat } = state();
  if (index + 1 < queue.length) return index + 1;
  if (repeat === "all" && queue.length) return 0;
  return null;
}

// ---- Audio events ----

audio.addEventListener("timeupdate", () => {
  const now = performance.now();
  if (!audio.paused && lastTick) listenedMs += Math.min(now - lastTick, 2000);
  lastTick = now;
  set({ position: offset + audio.currentTime });
  updatePositionState();
});
/** When the player started waiting for data (0: it isn't), and whether a pause is the person's. */
let stalledAt = 0;
let pausing = false;
const STALL_REPORT_MS = 8_000;

audio.addEventListener("durationchange", () => {
  if (isFinite(audio.duration) && audio.duration > 0) set({ duration: offset + audio.duration });
});
audio.addEventListener("playing", () => {
  if (stalledAt && Date.now() - stalledAt > STALL_REPORT_MS) reportLive(`playing again after ${Math.round((Date.now() - stalledAt) / 1000)}s`, "resumed");
  stalledAt = 0;
  set({ playing: true, buffering: false, error: null });
});
audio.addEventListener("pause", () => {
  lastTick = 0;
  if (!pausing && hls && !audio.ended) reportLive("the browser paused it (not the pause button)", "paused");
  pausing = false;
  if (!audio.ended) set({ playing: false });
});
audio.addEventListener("waiting", () => {
  set({ buffering: true });
  stalledAt = Date.now();
  const at = stalledAt;
  window.setTimeout(() => {
    if (stalledAt === at && state().playing) reportLive(`stalled for ${STALL_REPORT_MS / 1000}s`, "stalled");
  }, STALL_REPORT_MS);
});
audio.addEventListener("ended", () => {
  reportListen();
  if (state().sleepAt === "track") {
    set({ sleepAt: null, playing: false });
    return;
  }
  if (state().repeat === "one") {
    void load(state().index, true);
    return;
  }
  const next = nextIndex();
  if (next != null) void load(next, true);
  else set({ playing: false, position: 0 });
});
audio.addEventListener("error", () => {
  if (!audio.src || state().buffering === false && !state().playing) return;
  // A live stream's media pipeline failing: rebuilt rather than given up (see recoverMedia).
  if (hls) {
    reportLive(`media error ${audio.error?.code ?? "?"}${audio.error?.message ? `: ${audio.error.message}` : ""}`, "mediaerror");
    recoverMedia(hls);
    return;
  }
  failed("The song stopped loading");
});
audio.volume = state().volume;

// The sleep timer fades out over the last 10 seconds.
setInterval(() => {
  const { sleepAt, volume, muted } = state();
  if (typeof sleepAt !== "number") return;
  const left = sleepAt - Date.now();
  if (left <= 0) {
    audio.pause();
    audio.volume = muted ? 0 : volume;
    set({ sleepAt: null });
  } else if (left < 10_000) {
    audio.volume = (muted ? 0 : volume) * (left / 10_000);
  }
}, 250);

// ---- Media keys and the system's now-playing controls ----

function updateMediaSession() {
  if (!("mediaSession" in navigator)) return;
  const item = state().queue[state().index];
  if (!item) return;
  const base = state().current?.track ?? item.track;
  const now = state().liveNow;
  const track = now ? { ...base, title: now.title || base.title, artist: now.artist || base.title, album: base.title, artworkUrl: now.artworkUrl ?? base.artworkUrl } : base;
  const artwork = art(track.artworkUrl, 512);
  navigator.mediaSession.metadata = new MediaMetadata({
    title: track.title,
    artist: track.artist,
    album: track.album ?? "",
    artwork: artwork ? [{ src: artwork, sizes: "512x512" }] : [],
  });
  document.title = `${track.title} · ${track.artist}`;
}

function updatePositionState() {
  if (!("mediaSession" in navigator) || !navigator.mediaSession.setPositionState) return;
  const { duration, position } = state();
  if (duration > 0 && position <= duration) {
    try {
      navigator.mediaSession.setPositionState({ duration, position, playbackRate: 1 });
    } catch {
      // Some browsers are fussy about the numbers.
    }
  }
}

if ("mediaSession" in navigator) {
  const handlers: [MediaSessionAction, MediaSessionActionHandler][] = [
    ["play", () => void player.play()],
    ["pause", () => player.pause()],
    ["nexttrack", () => player.next()],
    ["previoustrack", () => player.previous()],
    ["seekto", (details) => details.seekTime != null && player.seek(details.seekTime)],
    ["seekbackward", () => player.seek(Math.max(0, state().position - 10))],
    ["seekforward", () => player.seek(state().position + 10)],
  ];
  for (const [action, handler] of handlers) {
    try {
      navigator.mediaSession.setActionHandler(action, handler);
    } catch {
      // Not every browser knows every action.
    }
  }
}

// ---- Keeping the queue across visits ----

const STORAGE_KEY = "samm.player";

function save() {
  try {
    const { queue, index, position, volume, muted, repeat } = state();
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ queue: queue.slice(0, 1000), index, position, volume, muted, repeat }));
  } catch {
    // Storage full or off: the queue just won't survive a reload.
  }
}

function restore() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "null");
    if (!saved || !Array.isArray(saved.queue)) return;
    const index = Math.min(saved.index ?? -1, saved.queue.length - 1);
    set({
      queue: saved.queue,
      index,
      duration: (saved.queue[index]?.track?.durationMs ?? 0) / 1000,
      position: saved.position ?? 0,
      volume: saved.volume ?? 1,
      muted: saved.muted ?? false,
      repeat: saved.repeat ?? "off",
    });
    audio.volume = saved.muted ? 0 : saved.volume ?? 1;
  } catch {
    // Nothing usable saved.
  }
}

restore();
let saveTimer: number | null = null;
usePlayer.subscribe(() => {
  if (saveTimer == null) saveTimer = window.setTimeout(() => { saveTimer = null; save(); }, 1000);
});
window.addEventListener("beforeunload", () => {
  reportListen();
  save();
});
usePrefs.subscribe((prefs, previous) => {
  if (prefs.levelling !== previous.levelling) leveller.setEnabled(prefs.levelling);
  if (prefs.kbps !== previous.kbps || prefs.thorough !== previous.thorough) resolutions.clear();
});
leveller.setEnabled(usePrefs.getState().levelling);

// ---- What the interface calls ----

export const player = {
  /** Where a live stream is, as a time (what's heard, not what's newest); null when it can't say. */
  liveClock(): number | null {
    return hls?.playingDate?.getTime() ?? null;
  },

  /** Replaces the queue with [tracks] and plays from [start]. */
  /** Carries on with a queue another device was playing, from the same moment. */
  takeOver(tracks: Track[], index: number, seconds: number, playing: boolean) {
    if (!tracks.length) return;
    set({ queue: tracks.map(itemOf) });
    void load(Math.min(Math.max(index, 0), tracks.length - 1), playing, Math.max(0, seconds));
  },

  playTracks(tracks: Track[], start = 0) {
    // Songs a playlist names that weren't found are left out.
    const from = tracks[Math.min(start, tracks.length - 1)];
    const playable = tracks.filter((t) => !isMissing(t));
    if (!playable.length) return;
    set({ queue: playable.map(itemOf) });
    void load(Math.max(0, from ? playable.indexOf(from) : 0), true);
  },

  playShuffled(tracks: Track[]) {
    player.playTracks(shuffled(tracks), 0);
  },

  /** Puts [tracks] right after the playing song. */
  playNext(all: Track[]) {
    const tracks = all.filter((t) => !isMissing(t));
    if (!tracks.length) return;
    const { queue, index } = state();
    if (index < 0) return player.playTracks(tracks);
    set({ queue: [...queue.slice(0, index + 1), ...tracks.map(itemOf), ...queue.slice(index + 1)] });
    prefetchNext();
  },

  addToQueue(all: Track[]) {
    const tracks = all.filter((t) => !isMissing(t));
    if (!tracks.length) return;
    const { queue, index } = state();
    set({ queue: [...queue, ...tracks.map(itemOf)] });
    if (index < 0) void load(0, true);
  },

  jumpTo(index: number) {
    void load(index, true);
  },

  remove(index: number) {
    const { queue, index: current } = state();
    if (index === current) {
      const rest = queue.filter((_, i) => i !== index);
      set({ queue: rest });
      if (rest.length && index < rest.length) void load(index, state().playing);
      else {
        audio.pause();
        hls?.destroy();
        hls = null;
        audio.removeAttribute("src");
        set({ index: rest.length ? rest.length - 1 : -1, current: null, playing: false, liveNow: null, liveHistory: [] });
      }
      return;
    }
    set({ queue: queue.filter((_, i) => i !== index), index: index < current ? current - 1 : current });
  },

  move(from: number, to: number) {
    const { queue, index } = state();
    if (from === to || from < 0 || to < 0 || from >= queue.length || to >= queue.length) return;
    const next = [...queue];
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved);
    let current = index;
    if (from === index) current = to;
    else if (from < index && to >= index) current--;
    else if (from > index && to <= index) current++;
    set({ queue: next, index: current });
  },

  /** Shuffles what's still to come. */
  shuffleUpcoming() {
    const { queue, index } = state();
    set({ queue: [...queue.slice(0, index + 1), ...shuffled(queue.slice(index + 1))] });
    prefetchNext();
  },

  clearUpcoming() {
    const { queue, index } = state();
    set({ queue: queue.slice(0, index + 1) });
  },

  async play() {
    const { index, queue, current, position } = state();
    if (index < 0 || !queue[index]) return;
    // After a reload, or after an error: load the song (where it was) first.
    if (!current || !audio.src) return load(index, true, position);
    set({ playing: true });
    await playAudio();
  },

  pause() {
    pausing = true;
    audio.pause();
  },

  toggle() {
    if (state().playing && !audio.paused) player.pause();
    else void player.play();
  },

  next() {
    const next = nextIndex();
    if (next != null) void load(next, true);
  },

  previous() {
    const { index, position } = state();
    if (position > 3 || index <= 0) player.seek(0);
    else void load(index - 1, true);
  },

  seek(seconds: number) {
    const { current, duration, playing } = state();
    if (current?.live) return;
    const to = Math.max(0, duration > 0 ? Math.min(seconds, duration - 0.5) : seconds);
    if (!current) {
      set({ position: to });
      return;
    }
    if (current.seekable) {
      audio.currentTime = to;
    } else {
      // The server starts the song again from there.
      offset = to;
      audio.src = `${current.streamUrl}&start=${to.toFixed(1)}`;
      set({ position: to, buffering: true });
      if (playing) void playAudio();
    }
  },

  setVolume(volume: number) {
    const v = Math.min(1, Math.max(0, volume));
    audio.volume = v;
    set({ volume: v, muted: false });
  },

  toggleMute() {
    const muted = !state().muted;
    audio.volume = muted ? 0 : state().volume;
    set({ muted });
  },

  cycleRepeat() {
    const order: Repeat[] = ["off", "all", "one"];
    set({ repeat: order[(order.indexOf(state().repeat) + 1) % order.length] });
  },

  sleepIn(minutes: number | "track" | null) {
    audio.volume = state().muted ? 0 : state().volume;
    set({ sleepAt: minutes == null ? null : minutes === "track" ? "track" : Date.now() + minutes * 60_000 });
  },

  /** Plays a live channel or station (replacing the queue). */
  playLive(track: Track) {
    player.playTracks([track]);
  },

  /** Starts a Pandora artist station: its first tracks, more as they're played. */
  startStation(tracks: Track[]) {
    player.playTracks(tracks);
  },

  /** Thumbs a Pandora station track up or down (down skips it, as Pandora does). */
  async thumb(direction: "UP" | "DOWN") {
    const { queue, index, thumbs } = state();
    const track = queue[index]?.track;
    if (!track?.via || !track.sourceId.startsWith("stationtrack:")) return;
    const [stationId, trackId] = splitTwo(track.sourceId.slice("stationtrack:".length));
    const next = thumbs[trackId] === direction ? "NONE" : direction;
    const updated = { ...thumbs };
    if (next === "NONE") delete updated[trackId];
    else updated[trackId] = next;
    set({ thumbs: updated });
    try {
      await api.stationThumb(track.via, stationId, trackId, next, Math.round(audio.currentTime * 1000));
      if (next === "DOWN") player.next();
    } catch (e) {
      set({ thumbs });
      throw e;
    }
  },

  get levellingGainDb() {
    return leveller.currentGainDb;
  },

  /** Seconds loaded ahead of where it's playing. */
  bufferedAhead(): number {
    const at = audio.currentTime;
    for (let i = 0; i < audio.buffered.length; i++) {
      if (audio.buffered.start(i) <= at + 0.5 && audio.buffered.end(i) >= at) return Math.max(0, audio.buffered.end(i) - at);
    }
    return 0;
  },
};

function shuffled<T>(items: T[]): T[] {
  const copy = [...items];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}
