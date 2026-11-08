import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
  ListMusic, Loader2, Moon, Pause, Play, Repeat, Repeat1, Shuffle, SkipBack, SkipForward, ThumbsDown, ThumbsUp, Volume1, Volume2, VolumeX, MicVocal,
} from "lucide-react";
import { errorToast } from "./ui";
import { splitTwo } from "../player/live";
import { duration } from "../lib/format";
import { player, usePlayer } from "../player/player";
import { Artwork } from "./Artwork";
import { IconButton, Menu } from "./ui";

export function PlayerBar({ queueOpen, onToggleQueue }: { queueOpen: boolean; onToggleQueue: () => void }) {
  const item = usePlayer((s) => s.queue[s.index]);
  const current = usePlayer((s) => s.current);
  const playing = usePlayer((s) => s.playing);
  const buffering = usePlayer((s) => s.buffering);
  const error = usePlayer((s) => s.error);
  const repeat = usePlayer((s) => s.repeat);
  const sleepAt = usePlayer((s) => s.sleepAt);
  const liveNow = usePlayer((s) => s.liveNow);
  const thumbs = usePlayer((s) => s.thumbs);
  const navigate = useNavigate();
  const base = current?.track ?? item?.track;
  // On live radio, the song on now (when the channel says), with the channel underneath.
  const track = base && liveNow ? { ...base, title: liveNow.title || base.title, artist: liveNow.artist || base.title, artworkUrl: liveNow.artworkUrl ?? base.artworkUrl } : base;
  const detail = error ?? (current?.live ? (liveNow ? base?.title : current.description) : current?.description) ?? (buffering ? "Finding the best copy…" : "");
  const stationTrackId = base?.sourceId.startsWith("stationtrack:") ? splitTwo(base.sourceId.slice("stationtrack:".length))[1] : null;
  const thumb = stationTrackId ? thumbs[stationTrackId] : undefined;

  return (
    <footer className="player-bar">
      <div className="player-now">
        {track ? (
          <>
            <button className="player-art" onClick={() => navigate("/now-playing")} aria-label="Open what's playing">
              <Artwork url={track.artworkUrl} size={56} />
            </button>
            <div className="player-text">
              <Link to="/now-playing" className="player-title">{track.title}</Link>
              <div className="player-artist">{track.artist}</div>
              <div className={`player-detail ${error ? "is-error" : ""}`}>{current?.live && !error && <span className="live-pill">Live</span>}{detail}</div>
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
        {stationTrackId && (
          <>
            <IconButton label="Thumbs down (skips it)" active={thumb === "DOWN"} onClick={() => player.thumb("DOWN").catch(errorToast)}><ThumbsDown size={18} /></IconButton>
            <IconButton label="Thumbs up" active={thumb === "UP"} onClick={() => player.thumb("UP").catch(errorToast)}><ThumbsUp size={18} /></IconButton>
          </>
        )}
        <IconButton label="Lyrics" onClick={() => navigate("/now-playing?lyrics=1")} disabled={!track || current?.live}><MicVocal size={18} /></IconButton>
        <IconButton label="Queue" active={queueOpen} onClick={onToggleQueue}><ListMusic size={18} /></IconButton>
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

function Progress() {
  const position = usePlayer((s) => s.position);
  const total = usePlayer((s) => s.duration);
  const live = usePlayer((s) => s.current?.live);
  const [dragging, setDragging] = useState<number | null>(null);
  if (live) return <div className="progress progress-live"><span className="live-pill">Live</span></div>;
  const shown = dragging ?? position;
  const max = total > 0 ? total : 0;
  return (
    <div className="progress">
      <span className="time">{duration(shown * 1000)}</span>
      <input
        type="range"
        min={0}
        max={max || 1}
        step={0.1}
        value={Math.min(shown, max || 1)}
        disabled={!max}
        aria-label="Position in the song"
        style={{ "--fill": `${max ? (Math.min(shown, max) / max) * 100 : 0}%` } as React.CSSProperties}
        onChange={(e) => setDragging(Number(e.target.value))}
        onPointerUp={() => { if (dragging != null) { player.seek(dragging); setDragging(null); } }}
        onKeyUp={() => { if (dragging != null) { player.seek(dragging); setDragging(null); } }}
      />
      <span className="time">{duration(max * 1000)}</span>
    </div>
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
