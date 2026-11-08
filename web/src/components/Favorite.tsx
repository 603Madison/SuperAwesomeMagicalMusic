import { Heart } from "lucide-react";
import { liveKey, session, useSession } from "../lib/session";
import type { Track } from "../lib/types";
import { errorToast, toast } from "./ui";

/** Whether a live channel or station can be a favourite (Pandora station tracks come and go). */
export const canFavorite = (track?: Track | null): track is Track =>
  !!track?.via && (track.source === "RADIO" || (track.source === "SIRIUSXM" && track.sourceId.startsWith("channel:")));

/** A heart that makes a live channel or station a favourite (they're listed on the Radio page). */
export function FavoriteButton({ track, size = 20, className = "icon-btn" }: { track: Track; size?: number; className?: string }) {
  const favorite = useSession((s) => s.favorites.some((f) => liveKey(f) === liveKey(track)));
  const toggle = async () => {
    try {
      await session.setFavorite(track, !favorite);
      toast(favorite ? `Removed ${track.title} from your favourites` : `${track.title} is in your favourites, on the Radio page`);
    } catch (e) {
      errorToast(e);
    }
  };
  return (
    <button
      className={`${className} favorite-btn ${favorite ? "is-favorite" : ""}`}
      onClick={(e) => { e.stopPropagation(); void toggle(); }}
      aria-pressed={favorite}
      aria-label={favorite ? `Remove ${track.title} from favourites` : `Add ${track.title} to favourites`}
      title={favorite ? "Favourite (click to remove)" : "Add to favourites"}
    >
      <Heart size={size} fill={favorite ? "currentColor" : "none"} />
    </button>
  );
}
