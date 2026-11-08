import { Heart } from "lucide-react";
import { liveKey, session, useSession } from "../lib/session";
import type { Track } from "../lib/types";
import { api } from "../lib/api";
import { player } from "../player/player";
import { errorToast, toast } from "./ui";

/** Whether a live channel or station can be a favourite (Pandora station tracks come and go). */
export const canFavorite = (track?: Track | null): track is Track =>
  !!track?.via && (track.source === "RADIO" || (track.source === "SIRIUSXM" && track.sourceId.startsWith("channel:")));

const STATION = "channel:artist-station:";
const STATION_TRACK = "stationtrack:";

/** Whether a favourite is a Pandora artist station (played by starting the station, not tuning in). */
export const isStation = (track: Track) => track.source === "SIRIUSXM" && track.sourceId.startsWith(STATION);

/** Pandora stations started here, by id: their names, for the heart on what they play. */
const knownStations = new Map<string, Track>();

export function rememberStation(station: Track) {
  if (isStation(station)) knownStations.set(station.sourceId.slice(STATION.length), station);
}

/**
 * What the heart beside [track] keeps: the channel or radio station itself, or the Pandora
 * station a song came from. Null for anything else.
 */
export function favoriteOf(track?: Track | null): Track | null {
  if (!track?.via) return null;
  if (track.source === "SIRIUSXM" && track.sourceId.startsWith(STATION_TRACK)) {
    const stationId = track.sourceId.slice(STATION_TRACK.length).split(":")[0];
    const sourceId = STATION + stationId;
    return knownStations.get(stationId)
      ?? useSession.getState().favorites.find((f) => f.sourceId === sourceId && f.via === track.via)
      ?? { source: "SIRIUSXM", sourceId, title: "Pandora station", artist: "Pandora station · SiriusXM", album: "Pandora artist stations", via: track.via };
  }
  return canFavorite(track) ? track : null;
}

/** Plays a favourite: tunes a channel or station in, or starts a Pandora station. */
export async function playFavorite(track: Track) {
  if (!isStation(track)) {
    player.playLive(track);
    return;
  }
  try {
    const { station, tracks } = await api.station(track.via!, { stationId: track.sourceId.slice(STATION.length), name: track.title });
    rememberStation(station);
    if (!tracks.length) throw new Error("SiriusXM sent nothing for this station");
    player.startStation(tracks);
  } catch (e) {
    errorToast(e);
  }
}

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
