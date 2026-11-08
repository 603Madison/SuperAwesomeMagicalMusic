import { useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { Disc3, ListEnd, ListPlus, ListStart, Mic2, MoreHorizontal, Pause, Play, Plus, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { duration, SOURCE_LABELS, trackKey } from "../lib/format";
import { session, useSession } from "../lib/session";
import type { Track } from "../lib/types";
import { player, usePlayer } from "../player/player";
import { Artwork } from "./Artwork";
import { albumNamed, artistNamed } from "./Cards";
import { Button, errorToast, Field, Menu, Modal, toast, type MenuItem } from "./ui";

export interface Row {
  /** Stable key for the row (a playlist entry's id, or the track's own key). */
  key: string;
  track: Track;
}

/** A list of songs: click one to play the list from it. */
export function TrackList({ rows, extraItems, showAlbum = true, numbered = false }: {
  rows: Row[];
  /** More menu items for a row (like "remove from playlist"). */
  extraItems?: (row: Row, index: number) => MenuItem[];
  showAlbum?: boolean;
  numbered?: boolean;
}) {
  const playingKey = usePlayer((s) => {
    const item = s.queue[s.index];
    return item ? trackKey(item.track) : null;
  });
  const playing = usePlayer((s) => s.playing);
  const tracks = rows.map((r) => r.track);

  return (
    <ol className="track-list">
      {rows.map((row, i) => {
        const isCurrent = playingKey === trackKey(row.track);
        return (
          <li
            key={row.key}
            className={`track-row ${isCurrent ? "is-current" : ""}`}
            onDoubleClick={() => player.playTracks(tracks, i)}
          >
            <button
              className="track-play"
              aria-label={isCurrent && playing ? `Pause ${row.track.title}` : `Play ${row.track.title}`}
              onClick={() => (isCurrent ? player.toggle() : player.playTracks(tracks, i))}
            >
              {numbered && <span className="track-number">{i + 1}</span>}
              <Artwork url={row.track.artworkUrl} size={44} />
              <span className="track-play-icon">{isCurrent && playing ? <Pause size={18} /> : <Play size={18} />}</span>
            </button>
            <div className="track-text" onClick={() => (isCurrent ? player.toggle() : player.playTracks(tracks, i))}>
              <div className="track-title">{row.track.title}</div>
              <div className="track-sub">
                {row.track.artist}
                {showAlbum && row.track.album ? <span className="track-album"> · {row.track.album}</span> : null}
              </div>
            </div>
            {/* Where a song plays from is the app's business; radio is worth saying. */}
            {isRadio(row.track) ? <span className="badge track-source">{SOURCE_LABELS[row.track.source] ?? row.track.source}</span> : <span className="track-source" />}
            <span className="track-duration">{duration(row.track.durationMs)}</span>
            <TrackMenu track={row.track} extra={extraItems?.(row, i)} />
          </li>
        );
      })}
    </ol>
  );
}

const isRadio = (track: Track) => track.source === "RADIO" || (track.source === "SIRIUSXM" && !track.sourceId.startsWith("song:"));

export function TrackMenu({ track, extra = [] }: { track: Track; extra?: MenuItem[] }) {
  const playlists = useSession((s) => s.playlists);
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const browse: MenuItem[] = isRadio(track) || !track.artist ? [] : [
    { label: "Go to artist", icon: <Mic2 size={18} />, onSelect: () => navigate(artistNamed(track.artist)) },
    ...(track.album ? [{ label: "Go to album", icon: <Disc3 size={18} />, onSelect: () => navigate(albumNamed(track.album!, track.artist)) }] : []),
  ];
  const items: MenuItem[] = [
    { label: "Play next", icon: <ListStart size={18} />, onSelect: () => { player.playNext([track]); toast(`“${track.title}” plays next`); } },
    { label: "Add to queue", icon: <ListEnd size={18} />, onSelect: () => { player.addToQueue([track]); toast(`Added “${track.title}” to the queue`); } },
    {
      label: "Add to playlist",
      icon: <ListPlus size={18} />,
      items: [
        { label: "New playlist…", icon: <Plus size={18} />, onSelect: () => setCreating(true) },
        ...playlists.map((p) => ({ label: p.name, onSelect: () => addTo(p.id, p.name, [track]) })),
      ],
    },
    ...browse,
    ...extra,
  ];
  return (
    <>
      <Menu label={`More for ${track.title}`} trigger={<MoreHorizontal size={20} />} items={items} />
      {creating && <NewPlaylistDialog tracks={[track]} onClose={() => setCreating(false)} />}
    </>
  );
}

async function addTo(id: string, name: string, tracks: Track[]) {
  try {
    await api.addToPlaylist(id, tracks);
    toast(tracks.length === 1 ? `Added to ${name}` : `Added ${tracks.length} songs to ${name}`);
    void session.refreshPlaylists();
  } catch (e) {
    errorToast(e);
  }
}

export function NewPlaylistDialog({ tracks = [], onClose, onCreated }: { tracks?: Track[]; onClose: () => void; onCreated?: (id: string) => void }) {
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const create = async () => {
    setBusy(true);
    try {
      const created = await api.createPlaylist(name.trim() || "New playlist", tracks);
      toast(`Made “${created.summary.name}”`);
      void session.refreshPlaylists();
      onCreated?.(created.summary.id);
      onClose();
    } catch (e) {
      errorToast(e);
      setBusy(false);
    }
  };
  return (
    <Modal
      title="New playlist"
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={create}>Create</Button></>}
    >
      <form onSubmit={(e) => { e.preventDefault(); void create(); }}>
        <Field label="Name">
          <input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="Road trip" maxLength={200} />
        </Field>
      </form>
    </Modal>
  );
}

export const removeItem = (onRemove: () => void): MenuItem => ({ label: "Remove from playlist", icon: <Trash2 size={18} />, danger: true, onSelect: onRemove });

export function PlayButtons({ tracks, children }: { tracks: Track[]; children?: ReactNode }) {
  return (
    <div className="play-buttons">
      <Button variant="primary" disabled={!tracks.length} onClick={() => player.playTracks(tracks)}><Play size={18} /> Play</Button>
      <Button disabled={tracks.length < 2} onClick={() => player.playShuffled(tracks)}>Shuffle</Button>
      {children}
    </div>
  );
}
