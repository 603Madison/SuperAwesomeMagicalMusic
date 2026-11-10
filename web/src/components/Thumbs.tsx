import { ThumbsDown, ThumbsUp } from "lucide-react";
import type { Track } from "../lib/types";
import { splitTwo } from "../player/live";
import { player, usePlayer } from "../player/player";
import { errorToast, IconButton } from "./ui";

/** Thumbs up and down for a Pandora station's song (down skips it, as Pandora does); nothing for anything else. */
export function StationThumbs({ track, size = 18 }: { track: Track | undefined; size?: number }) {
  const thumbs = usePlayer((s) => s.thumbs);
  const trackId = track?.sourceId.startsWith("stationtrack:") ? splitTwo(track.sourceId.slice("stationtrack:".length))[1] : null;
  if (!trackId) return null;
  const thumb = thumbs[trackId];
  return (
    <>
      <IconButton label="Thumbs down (skips it)" active={thumb === "DOWN"} onClick={() => player.thumb("DOWN").catch(errorToast)}><ThumbsDown size={size} /></IconButton>
      <IconButton label="Thumbs up" active={thumb === "UP"} onClick={() => player.thumb("UP").catch(errorToast)}><ThumbsUp size={size} /></IconButton>
    </>
  );
}
