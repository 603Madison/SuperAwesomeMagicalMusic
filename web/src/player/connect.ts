import { create } from "zustand";
import type { Track } from "../lib/types";
import { player, usePlayer, type PlayerState } from "./player";

/** What a device is playing, as it last said (the server moves the position along). */
export interface PlayState {
  track?: Track;
  queue?: Track[];
  index: number;
  positionMs: number;
  durationMs: number;
  playing: boolean;
  volume: number;
  live: boolean;
  repeat: string;
}

export interface Device {
  id: string;
  name: string;
  kind: "web" | "phone" | "speaker";
  state?: PlayState;
  /** Playing, or the one that played last: what the others show and control. */
  active: boolean;
}

interface ConnectState {
  devices: Device[];
  /** This browser's id among them. */
  me: string;
  online: boolean;
  /** When the list arrived, for moving positions along. */
  receivedAt: number;
}

export const useConnect = create<ConnectState>(() => ({ devices: [], me: deviceId(), online: false, receivedAt: 0 }));

/** The device another device should show and control, if it isn't this one. */
export function useRemote(): Device | null {
  const devices = useConnect((s) => s.devices);
  const me = useConnect((s) => s.me);
  const playingHere = usePlayer((s) => s.playing);
  const active = devices.find((d) => d.active);
  if (!active || active.id === me || playingHere || !active.state?.track) return null;
  return active;
}

/** Where [device]'s song is now. */
export function positionOf(device: Device, receivedAt: number): number {
  const state = device.state;
  if (!state) return 0;
  const moved = state.positionMs + (state.playing && !state.live ? Date.now() - receivedAt : 0);
  return state.durationMs > 0 ? Math.min(moved, state.durationMs) : moved;
}

let socket: WebSocket | null = null;
let stopped = true;
let retries = 0;
let unsubscribe: (() => void) | null = null;
let lastSent = "";
let lastSentAt = 0;
let lastPosition = 0;

/**
 * This browser as one of the person's devices (like Spotify Connect): it says what it's playing,
 * hears what the others are doing, and does what they ask. Playback can move between them.
 */
export const connect = {
  start() {
    if (!stopped) return;
    stopped = false;
    open();
    unsubscribe = usePlayer.subscribe((state, before) => {
      if (changed(state, before)) report(state);
    });
  },

  stop() {
    stopped = true;
    unsubscribe?.();
    unsubscribe = null;
    socket?.close();
    socket = null;
  },

  /** Moves playback to [to] (this browser, to take it here). */
  transfer(to: string) {
    send({ type: "transfer", to });
  },

  command(target: string, command: { action: string; positionMs?: number; volume?: number }) {
    send({ type: "command", target, command });
  },
};

function open() {
  if (socket || stopped) return;
  const params = new URLSearchParams({ id: useConnect.getState().me, name: deviceName(), kind: "web" });
  const ws = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/api/connect/ws?${params}`);
  socket = ws;
  ws.onopen = () => {
    retries = 0;
    useConnect.setState({ online: true });
    lastSent = "";
    report(usePlayer.getState());
  };
  ws.onmessage = (event) => {
    try {
      handle(JSON.parse(String(event.data)));
    } catch {
      // Not for us.
    }
  };
  ws.onclose = () => {
    if (socket === ws) socket = null;
    useConnect.setState({ online: false });
    if (!stopped) window.setTimeout(open, Math.min(30_000, 1_000 * 2 ** retries++));
  };
}

function send(message: unknown) {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
}

function handle(message: { type: string; devices?: Device[]; you?: string; command?: { action: string; positionMs?: number; volume?: number; state?: PlayState } }) {
  if (message.type === "devices" && message.devices) {
    useConnect.setState({ devices: message.devices, receivedAt: Date.now(), ...(message.you ? { me: message.you } : {}) });
  } else if (message.type === "command" && message.command) {
    const command = message.command;
    switch (command.action) {
      case "play": void player.play(); break;
      case "pause": player.pause(); break;
      case "next": player.next(); break;
      case "previous": player.previous(); break;
      case "seek": if (command.positionMs != null) player.seek(command.positionMs / 1000); break;
      case "volume": if (command.volume != null) player.setVolume(command.volume); break;
      case "take": {
        const state = command.state;
        if (state?.queue?.length) player.takeOver(state.queue, state.index, state.positionMs / 1000, state.playing);
        break;
      }
    }
  }
}

/** Whether what others see has changed: another song, playing or not, a jump, the volume. */
function changed(state: PlayerState, before: PlayerState): boolean {
  if (state.index !== before.index || state.playing !== before.playing || state.queue !== before.queue) return true;
  if (state.volume !== before.volume || state.repeat !== before.repeat || state.current !== before.current) return true;
  const expected = lastPosition + (state.playing ? (Date.now() - lastSentAt) / 1000 : 0);
  if (Math.abs(state.position - expected) > 3) return true; // A seek.
  return state.playing && Date.now() - lastSentAt > 15_000; // Now and then, to stay in step.
}

function report(state: PlayerState) {
  const item = state.queue[state.index];
  const play: PlayState = {
    track: item?.track,
    queue: state.queue.map((q) => q.track),
    index: state.index,
    positionMs: Math.round(state.position * 1000),
    durationMs: Math.round(state.duration * 1000),
    playing: state.playing,
    volume: state.volume,
    live: !!state.current?.live,
    repeat: state.repeat,
  };
  const text = JSON.stringify({ type: "state", state: play });
  if (text === lastSent) return;
  lastSent = text;
  lastSentAt = Date.now();
  lastPosition = state.position;
  send(JSON.parse(text));
}

function deviceId(): string {
  const make = () => Array.from(crypto.getRandomValues(new Uint8Array(12)), (b) => b.toString(16).padStart(2, "0")).join("");
  try {
    const kept = localStorage.getItem("samm.device.id");
    if (kept) return kept;
    const id = make();
    localStorage.setItem("samm.device.id", id);
    return id;
  } catch {
    return make();
  }
}

/** "Firefox on Linux", "Chrome on Windows", "Safari on iPhone"… */
export function deviceName(): string {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Browser";
  const os = /iPhone|iPad/.test(ua) ? "iPhone" : /Android/.test(ua) ? "Android" : /Mac OS X/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : /Linux|X11/.test(ua) ? "Linux" : "";
  return os ? `${browser} on ${os}` : browser;
}
