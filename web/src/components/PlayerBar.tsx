import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
  Laptop, ListMusic, Loader2, MonitorSpeaker, Moon, Pause, Play, Repeat, Repeat1, Shuffle, SkipBack, SkipForward, Smartphone, Speaker,
  Volume1, Volume2, VolumeX, MicVocal,
} from "lucide-react";
import { StationThumbs } from "./Thumbs";
import { duration } from "../lib/format";
import { player, usePlayer } from "../player/player";
import { useSeek, useSeekBar } from "../player/useSeek";
import { connect, positionOf, useConnect, useRemote, type Device } from "../player/connect";
import { Artwork } from "./Artwork";
import { favoriteOf, FavoriteButton } from "./Favorite";
import { IconButton, Menu, type MenuItem } from "./ui";

export function PlayerBar({ queueOpen, onToggleQueue }: { queueOpen: boolean; onToggleQueue: () => void }) {
  const item = usePlayer((s) => s.queue[s.index]);
  const current = usePlayer((s) => s.current);
  const playing = usePlayer((s) => s.playing);
  const buffering = usePlayer((s) => s.buffering);
  const error = usePlayer((s) => s.error);
  const repeat = usePlayer((s) => s.repeat);
  const sleepAt = usePlayer((s) => s.sleepAt);
  const liveNow = usePlayer((s) => s.liveNow);
  const navigate = useNavigate();
  const base = current?.track ?? item?.track;
  // The channel or station playing, or the Pandora station this song is from.
  const favorite = favoriteOf(base);
  // On live radio, the song on now (when the channel says), with the channel underneath.
  const track = base && liveNow ? { ...base, title: liveNow.title || base.title, artist: liveNow.artist || base.title, artworkUrl: liveNow.artworkUrl ?? base.artworkUrl } : base;
  const detail = error ?? (current?.live ? (liveNow ? base?.title : current.description) : current?.description) ?? (buffering ? "Finding the best copy…" : "");
  // Another of the person's devices playing (and not this one): the bar controls it instead.
  const remote = useRemote();
  if (remote) return <RemoteBar device={remote} queueOpen={queueOpen} onToggleQueue={onToggleQueue} />;

  return (
    <footer className="player-bar">
      <MiniProgress />
      <div className="player-now">
        {track ? (
          <>
            <button className="player-art" onClick={() => navigate("/now-playing")} aria-label="Open what's playing">
              <Artwork url={track.artworkUrl} size={56} />
            </button>
            <div className="player-text">
              <Link to="/now-playing" className="player-title">{track.title}</Link>
              <div className="player-artist">{track.artist}</div>
              <div className={`player-detail ${error ? "is-error" : ""}`}>{detail}</div>
            </div>
          </>
        ) : (
          <div className="player-text"><div className="player-artist">Nothing playing</div></div>
        )}
      </div>

      <div className="player-center">
        <div className="player-controls">
          <IconButton label="Shuffle what's next" onClick={() => player.shuffleUpcoming()} disabled={!track}><Shuffle size={18} /></IconButton>
          <IconButton label="Previous" onClick={() => player.previous()} disabled={!track}><SkipBack size={22} /></IconButton>
          <button className="play-pause" aria-label={playing ? "Pause" : "Play"} onClick={() => player.toggle()} disabled={!track}>
            {buffering && playing ? <Loader2 className="spin" size={26} /> : playing ? <Pause size={26} /> : <Play size={26} />}
          </button>
          <IconButton label="Next" onClick={() => player.next()} disabled={!track}><SkipForward size={22} /></IconButton>
          <IconButton label={`Repeat: ${repeat}`} active={repeat !== "off"} onClick={() => player.cycleRepeat()}>
            {repeat === "one" ? <Repeat1 size={18} /> : <Repeat size={18} />}
          </IconButton>
        </div>
        <Progress />
      </div>

      <div className="player-right">
        <StationThumbs track={base} />
        {favorite && <FavoriteButton track={favorite} size={18} />}
        <IconButton label="Lyrics" onClick={() => navigate("/now-playing?view=lyrics")} disabled={!track || current?.live}><MicVocal size={18} /></IconButton>
        <IconButton label="Queue" active={queueOpen} onClick={onToggleQueue}><ListMusic size={18} /></IconButton>
        <Devices />
        <Menu
          label={sleepAt ? "Sleep timer (on)" : "Sleep timer"}
          trigger={<Moon size={18} className={sleepAt ? "is-on" : ""} />}
          items={[
            ...[15, 30, 45, 60, 90].map((m) => ({ label: `In ${m} minutes`, onSelect: () => player.sleepIn(m) })),
            { label: "At the end of this song", onSelect: () => player.sleepIn("track") },
            ...(sleepAt ? [{ label: "Turn off the sleep timer", onSelect: () => player.sleepIn(null) }] : []),
          ]}
        />
        <Volume />
      </div>
    </footer>
  );
}

/**
 * Live radio has no end and can't be scrubbed. When the channel says how long the song is, how far
 * into it the stream is; and, once, that it's live.
 */
function LiveProgress() {
  const now = usePlayer((s) => s.liveNow);
  usePlayer((s) => s.position); // Moves along with the stream.
  const clock = player.liveClock();
  const length = now?.durationMs ?? 0;
  if (!now?.startMs || length <= 0 || clock == null) return <div className="progress progress-live"><span className="live-pill">Live</span></div>;
  const into = Math.min(Math.max(clock - now.startMs, 0), length);
  return (
    <div className="progress progress-live has-song">
      <span className="time"><span className="live-pill">Live</span></span>
      <div className="live-bar" role="progressbar" aria-label="How far into the song" aria-valuemin={0} aria-valuemax={length} aria-valuenow={into}>
        <div style={{ width: `${(into / length) * 100}%` }} />
      </div>
      <span className="time">{duration(length)}</span>
    </div>
  );
}

/**
 * How far into the song, as a line along the top of the bar: on phones, where the bar has no
 * room for the seek bar (the song's page has it).
 */
function MiniProgress() {
  const position = usePlayer((s) => s.position);
  const total = usePlayer((s) => s.duration);
  const live = usePlayer((s) => s.current?.live);
  if (live || !(total > 0)) return null;
  return (
    <div className="player-mini-progress" aria-hidden="true">
      <div style={{ width: `${Math.min(100, (position / total) * 100)}%` }} />
    </div>
  );
}

function Progress() {
  const live = usePlayer((s) => s.current?.live);
  const seek = useSeek();
  if (live) return <LiveProgress />;
  return (
    <div className="progress">
      <span className="time">{duration(seek.shown * 1000)}</span>
      <input {...seek.input} aria-label="Position in the song" style={{ "--fill": `${seek.fill}%` } as React.CSSProperties} />
      <span className="time">{duration(seek.max * 1000)}</span>
    </div>
  );
}

const KIND_ICONS = { web: Laptop, phone: Smartphone, speaker: Speaker } as const;

/** The person's devices: choosing one moves playback there (this browser, to play it here). */
function Devices() {
  const devices = useConnect((s) => s.devices);
  const me = useConnect((s) => s.me);
  const online = useConnect((s) => s.online);
  const others = devices.filter((d) => d.id !== me);
  const playingElsewhere = others.some((d) => d.active && d.state?.playing);
  const items: MenuItem[] = [
    { label: "This browser", icon: <Laptop size={18} />, onSelect: () => connect.transfer(me) },
    ...others.map((d) => {
      const Icon = KIND_ICONS[d.kind] ?? Laptop;
      const note = d.active && d.state?.playing ? " · playing" : "";
      return { label: `${d.name}${note}`, icon: <Icon size={18} />, onSelect: () => connect.transfer(d.id) };
    }),
    ...(others.length ? [] : [{ label: online ? "Open the app or another browser to see it here" : "Connecting…", disabled: true }]),
  ];
  return (
    <Menu
      label={playingElsewhere ? "Playing on another device" : "Play on a device"}
      trigger={<MonitorSpeaker size={18} className={playingElsewhere ? "is-on" : ""} />}
      items={items}
    />
  );
}

/** Controls for the song another device is playing, as Spotify shows "Playing on…". */
function RemoteBar({ device, queueOpen, onToggleQueue }: { device: Device; queueOpen: boolean; onToggleQueue: () => void }) {
  const receivedAt = useConnect((s) => s.receivedAt);
  const [, tick] = useState(0);
  useEffect(() => {
    const timer = window.setInterval(() => tick((n) => n + 1), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const state = device.state!;
  const track = state.track!;
  const Icon = KIND_ICONS[device.kind] ?? Laptop;
  const send = (action: string, extra: { positionMs?: number; volume?: number } = {}) => connect.command(device.id, { action, ...extra });
  const seek = useSeekBar(positionOf(device, receivedAt) / 1000, state.durationMs / 1000, `${device.id}|${track.source}:${track.sourceId}`, (to) => send("seek", { positionMs: Math.round(to * 1000) }));
  return (
    <footer className="player-bar is-remote">
      <div className="player-now">
        <div className="player-art"><Artwork url={track.artworkUrl} size={56} /></div>
        <div className="player-text">
          <div className="player-title">{track.title}</div>
          <div className="player-artist">{track.artist}</div>
          <div className="player-detail remote-on"><Icon size={13} /> Playing on {device.name}</div>
        </div>
      </div>
      <div className="player-center">
        <div className="player-controls">
          <IconButton label="Previous" onClick={() => send("previous")} disabled={state.live}><SkipBack size={22} /></IconButton>
          <button className="play-pause" aria-label={state.playing ? "Pause" : "Play"} onClick={() => send(state.playing ? "pause" : "play")}>
            {state.playing ? <Pause size={26} /> : <Play size={26} />}
          </button>
          <IconButton label="Next" onClick={() => send("next")} disabled={state.live}><SkipForward size={22} /></IconButton>
        </div>
        {state.live ? (
          <div className="progress progress-live"><span className="live-pill">Live</span></div>
        ) : (
          <div className="progress">
            <span className="time">{duration(seek.shown * 1000)}</span>
            <input {...seek.input} aria-label="Position in the song" style={{ "--fill": `${seek.fill}%` } as React.CSSProperties} />
            <span className="time">{duration(seek.max * 1000)}</span>
          </div>
        )}
      </div>
      <div className="player-right">
        <button className="btn btn-tonal play-here" onClick={() => connect.transfer(useConnect.getState().me)}>Play here</button>
        <IconButton label="Queue" active={queueOpen} onClick={onToggleQueue}><ListMusic size={18} /></IconButton>
        <Devices />
        <div className="volume">
          <IconButton label="Volume" onClick={() => send("volume", { volume: state.volume > 0 ? 0 : 0.8 })}>
            {state.volume === 0 ? <VolumeX size={18} /> : state.volume < 0.5 ? <Volume1 size={18} /> : <Volume2 size={18} />}
          </IconButton>
          <RemoteVolume volume={state.volume} onChange={(v) => send("volume", { volume: v })} />
        </div>
      </div>
    </footer>
  );
}

/** The other device's volume: moves at once here, and is sent a few times a second while dragged. */
function RemoteVolume({ volume, onChange }: { volume: number; onChange: (volume: number) => void }) {
  const [held, setHeld] = useState<number | null>(null);
  const lastSent = useRef(0);
  useEffect(() => { if (held != null && Math.abs(held - volume) < 0.02) setHeld(null); }, [volume, held]);
  const level = held ?? volume;
  return (
    <input
      type="range" min={0} max={1} step={0.01} value={level} aria-label="Volume"
      style={{ "--fill": `${level * 100}%` } as React.CSSProperties}
      onChange={(e) => {
        const v = Number(e.target.value);
        setHeld(v);
        if (Date.now() - lastSent.current > 150) { lastSent.current = Date.now(); onChange(v); }
      }}
      onPointerUp={() => { if (held != null) onChange(held); }}
    />
  );
}

function Volume() {
  const volume = usePlayer((s) => s.volume);
  const muted = usePlayer((s) => s.muted);
  const level = muted ? 0 : volume;
  return (
    <div className="volume">
      <IconButton label={muted ? "Unmute" : "Mute"} onClick={() => player.toggleMute()}>
        {level === 0 ? <VolumeX size={18} /> : level < 0.5 ? <Volume1 size={18} /> : <Volume2 size={18} />}
      </IconButton>
      <input
        type="range"
        min={0}
        max={1}
        step={0.01}
        value={level}
        aria-label="Volume"
        style={{ "--fill": `${level * 100}%` } as React.CSSProperties}
        onChange={(e) => player.setVolume(Number(e.target.value))}
      />
    </div>
  );
}

/** Space plays and pauses; arrows seek, and with Shift skip songs. */
export function useKeyboardShortcuts() {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement;
      if (target.closest("input, textarea, select, [contenteditable=true]") || e.metaKey || e.ctrlKey || e.altKey) return;
      if (e.code === "Space" && !target.closest("button")) {
        e.preventDefault();
        player.toggle();
      } else if (e.key === "ArrowRight") {
        if (e.shiftKey) player.next();
        else player.seek(usePlayer.getState().position + 10);
      } else if (e.key === "ArrowLeft") {
        if (e.shiftKey) player.previous();
        else player.seek(Math.max(0, usePlayer.getState().position - 10));
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);
}
