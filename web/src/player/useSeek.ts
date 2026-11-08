import { useEffect, useRef, useState, type ChangeEvent } from "react";
import { player, usePlayer } from "./player";

/**
 * A seek bar for the song playing: it follows the song, except while it's being dragged; the song
 * moves when the bar is let go (anywhere, even off the bar), or at once for a click or a key. A new
 * song always starts with the bar where it really is.
 */
export function useSeek() {
  const position = usePlayer((s) => s.position);
  const total = usePlayer((s) => s.duration);
  const song = usePlayer((s) => {
    const track = s.queue[s.index]?.track;
    return track ? `${s.index}|${track.via ?? ""}|${track.source}:${track.sourceId}` : "";
  });
  const [held, setHeld] = useState<number | null>(null);
  const heldAt = useRef<number | null>(null);
  const pressed = useRef(false);
  const releasedAt = useRef(0);

  useEffect(() => {
    heldAt.current = null;
    pressed.current = false;
    setHeld(null);
  }, [song]);

  useEffect(() => {
    const release = () => {
      if (!pressed.current) return;
      pressed.current = false;
      releasedAt.current = Date.now();
      const to = heldAt.current;
      heldAt.current = null;
      setHeld(null);
      if (to != null) player.seek(to);
    };
    window.addEventListener("pointerup", release);
    window.addEventListener("pointercancel", release);
    window.addEventListener("blur", release);
    return () => {
      window.removeEventListener("pointerup", release);
      window.removeEventListener("pointercancel", release);
      window.removeEventListener("blur", release);
    };
  }, []);

  const max = total > 0 ? total : 0;
  const shown = Math.min(held ?? position, max || Infinity);
  return {
    shown,
    max,
    fill: max ? (Math.min(shown, max) / max) * 100 : 0,
    input: {
      type: "range" as const,
      min: 0,
      max: max || 1,
      step: 0.1,
      value: Math.min(shown, max || 1),
      disabled: !max,
      onPointerDown: () => { pressed.current = true; },
      onChange: (e: ChangeEvent<HTMLInputElement>) => {
        const to = Number(e.target.value);
        if (pressed.current) {
          heldAt.current = to;
          setHeld(to);
        } else if (Date.now() - releasedAt.current > RELEASE_ECHO_MS) {
          // A key (the arrows): straight there. Just after letting go, the browser reports the
          // bar's old value once more; that isn't a seek.
          player.seek(to);
        }
      },
    },
  };
}

const RELEASE_ECHO_MS = 600;
