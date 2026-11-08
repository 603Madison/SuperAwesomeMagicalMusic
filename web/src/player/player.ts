import { create } from "zustand";
import { api, ApiError, art } from "../lib/api";
import { trackKey } from "../lib/format";
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

interface PlayerState {
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
  /** Thumbs given to Pandora station tracks, by track id. */
  thumbs: Record<string, "UP" | "DOWN">;
}

export interface LiveNow {
  title: string;
  artist: string;
  artworkUrl?: string;
  /** The song, to save: it plays from your library or a streaming service. */
  song?: Track;
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
  set({ index, current: null, buffering: true, error: null, position: startAt, duration: (item.track.durationMs ?? 0) / 1000, playing: autoplay, liveNow: null });
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
  instance.on(Hls.Events.ERROR, (_, data) => {
    if (!data.fatal) return;
    if (data.type === Hls.ErrorTypes.NETWORK_ERROR) instance.startLoad();
    else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) instance.recoverMediaError();
    else failed("The live stream stopped");
  });
  instance.loadSource(src);
  instance.attachMedia(audio);
  hls = instance;
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
      set({ liveNow: now ? { title: now.title, artist: now.artist, artworkUrl: now.artworkUrl, song: now.song } : null });
    } else if (live.kind === "radio") {
      const { title } = await api.radioNow(track.via!, track.sourceId.replace(/^hls:/, ""));
      if (state().queue[state().index]?.track !== track) return;
      set({ liveNow: title ? splitRadioTitle(title) : null });
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
audio.addEventListener("durationchange", () => {
  if (isFinite(audio.duration) && audio.duration > 0) set({ duration: offset + audio.duration });
});
audio.addEventListener("playing", () => set({ playing: true, buffering: false, error: null }));
audio.addEventListener("pause", () => {
  lastTick = 0;
  if (!audio.ended) set({ playing: false });
});
audio.addEventListener("waiting", () => set({ buffering: true }));
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
  /** Replaces the queue with [tracks] and plays from [start]. */
  playTracks(tracks: Track[], start = 0) {
    if (!tracks.length) return;
    set({ queue: tracks.map(itemOf) });
    void load(Math.min(start, tracks.length - 1), true);
  },

  playShuffled(tracks: Track[]) {
    player.playTracks(shuffled(tracks), 0);
  },

  /** Puts [tracks] right after the playing song. */
  playNext(tracks: Track[]) {
    const { queue, index } = state();
    if (index < 0) return player.playTracks(tracks);
    set({ queue: [...queue.slice(0, index + 1), ...tracks.map(itemOf), ...queue.slice(index + 1)] });
    prefetchNext();
  },

  addToQueue(tracks: Track[]) {
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
        set({ index: rest.length ? rest.length - 1 : -1, current: null, playing: false, liveNow: null });
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
};

function shuffled<T>(items: T[]): T[] {
  const copy = [...items];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}
