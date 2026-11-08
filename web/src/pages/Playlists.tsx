import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowDown, ArrowUp, Download, ListMusic, Pencil, Plus, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { longDuration, plural } from "../lib/format";
import { session, useSession } from "../lib/session";
import type { Playlist } from "../lib/types";
import { Mosaic } from "../components/Artwork";
import { NewPlaylistDialog, PlayButtons, removeItem, TrackList } from "../components/TrackList";
import { Button, Empty, errorToast, Field, Modal, PageHeader, Spinner, toast, useLoad } from "../components/ui";
import { player } from "../player/player";

export function Playlists() {
  const playlists = useSession((s) => s.playlists);
  const [creating, setCreating] = useState(false);
  const [importing, setImporting] = useState(false);
  const navigate = useNavigate();
  return (
    <div className="page">
      <PageHeader
        title="Playlists"
        actions={<>
          <Button onClick={() => setImporting(true)}><Download size={18} /> Import</Button>
          <Button variant="primary" onClick={() => setCreating(true)}><Plus size={18} /> New playlist</Button>
        </>}
      />
      {!playlists.length && (
        <Empty icon={<ListMusic size={40} />} title="No playlists yet">
          Make one, or import one from Spotify or YouTube. Songs are found in your own sources.
        </Empty>
      )}
      <div className="card-grid">
        {playlists.map((p) => (
          <Link key={p.id} to={`/playlists/${p.id}`} className="card">
            <Mosaic urls={p.artwork} size={180} />
            <div className="card-title">{p.name}</div>
            <div className="card-sub">{plural(p.count, "song")}</div>
          </Link>
        ))}
      </div>
      {creating && <NewPlaylistDialog onClose={() => setCreating(false)} onCreated={(id) => navigate(`/playlists/${id}`)} />}
      {importing && <ImportDialog onClose={() => setImporting(false)} onImported={(id) => navigate(`/playlists/${id}`)} />}
    </div>
  );
}

function ImportDialog({ onClose, onImported }: { onClose: () => void; onImported: (id: string) => void }) {
  const [url, setUrl] = useState("");
  const [busy, setBusy] = useState(false);
  const [missing, setMissing] = useState<string[] | null>(null);
  const [created, setCreated] = useState<string | null>(null);
  const run = async () => {
    setBusy(true);
    try {
      const result = await api.importPlaylist(url);
      void session.refreshPlaylists();
      toast(`Imported ${plural(result.found, "song")}`);
      if (result.missing.length) {
        setMissing(result.missing);
        setCreated(result.playlist.summary.id);
        setBusy(false);
      } else {
        onImported(result.playlist.summary.id);
        onClose();
      }
    } catch (e) {
      errorToast(e);
      setBusy(false);
    }
  };
  if (missing && created) {
    return (
      <Modal title="Imported" onClose={() => { onImported(created); onClose(); }} footer={<Button variant="primary" onClick={() => { onImported(created); onClose(); }}>Open the playlist</Button>}>
        <p>These {plural(missing.length, "song")} weren't found in your sources:</p>
        <ul className="plain-list">{missing.map((m, i) => <li key={i}>{m}</li>)}</ul>
      </Modal>
    );
  }
  return (
    <Modal
      title="Import a playlist"
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={!url.trim()} onClick={run}>Import</Button></>}
    >
      <form onSubmit={(e) => { e.preventDefault(); void run(); }}>
        <Field label="Playlist link" help="A public Spotify playlist, or a YouTube / YouTube Music playlist. Each song is matched in your sources, your library first.">
          <input autoFocus value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://open.spotify.com/playlist/…" />
        </Field>
      </form>
      {busy && <p className="muted"><Spinner size={14} /> Finding the songs… this takes a moment for long playlists.</p>}
    </Modal>
  );
}

export function PlaylistDetail() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const { data, error, loading, setData } = useLoad(() => api.playlist(id), [id]);
  const [renaming, setRenaming] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  if (loading && !data) return <div className="center-pad"><Spinner /></div>;
  if (error || !data) return <Empty title="Couldn't open this playlist">{error}</Empty>;
  const { summary, entries } = data;
  const tracks = entries.map((e) => e.track);

  const apply = (next: Playlist) => { setData(next); void session.refreshPlaylists(); };
  const remove = async (entryId: string) => {
    try { apply(await api.removeFromPlaylist(id, [entryId])); } catch (e) { errorToast(e); }
  };
  const move = async (from: number, to: number) => {
    const order = entries.map((e) => e.entryId);
    const [moved] = order.splice(from, 1);
    order.splice(to, 0, moved);
    try { apply(await api.reorderPlaylist(id, summary.revision, order)); } catch (e) { errorToast(e); }
  };

  return (
    <div className="page">
      <div className="playlist-hero">
        <Mosaic urls={summary.artwork} size={200} />
        <div className="playlist-hero-text">
          <span className="eyebrow">Playlist</span>
          <h1>{summary.name}</h1>
          {summary.description && <p className="muted">{summary.description}</p>}
          <p className="muted">{plural(summary.count, "song")}{summary.durationMs ? ` · ${longDuration(summary.durationMs)}` : ""}</p>
          <PlayButtons tracks={tracks}>
            <Button variant="ghost" onClick={() => { player.addToQueue(tracks); toast("Added to the queue"); }} disabled={!tracks.length}>Add to queue</Button>
            <Button variant="ghost" onClick={() => setRenaming(true)}><Pencil size={16} /> Rename</Button>
            <Button variant="ghost" onClick={() => setConfirmDelete(true)}><Trash2 size={16} /> Delete</Button>
          </PlayButtons>
        </div>
      </div>
      {!entries.length && <Empty title="This playlist is empty">Add songs from search with their ••• menu.</Empty>}
      <TrackList
        numbered
        rows={entries.map((e) => ({ key: e.entryId, track: e.track }))}
        extraItems={(_, i) => [
          { label: "Move up", icon: <ArrowUp size={18} />, disabled: i === 0, onSelect: () => move(i, i - 1) },
          { label: "Move down", icon: <ArrowDown size={18} />, disabled: i === entries.length - 1, onSelect: () => move(i, i + 1) },
          removeItem(() => remove(entries[i].entryId)),
        ]}
      />
      {renaming && <RenameDialog name={summary.name} description={summary.description} onClose={() => setRenaming(false)} onSave={async (name, description) => {
        await api.renamePlaylist(id, name, description);
        setData({ ...data, summary: { ...summary, name, description } });
        void session.refreshPlaylists();
      }} />}
      {confirmDelete && (
        <Modal title={`Delete “${summary.name}”?`} onClose={() => setConfirmDelete(false)} footer={<>
          <Button variant="ghost" onClick={() => setConfirmDelete(false)}>Keep it</Button>
          <Button variant="danger" onClick={async () => {
            try {
              await api.deletePlaylist(id);
              void session.refreshPlaylists();
              navigate("/playlists");
            } catch (e) { errorToast(e); }
          }}>Delete</Button>
        </>}>
          <p>The playlist goes for good, on every device. The songs stay in their sources.</p>
        </Modal>
      )}
    </div>
  );
}

function RenameDialog({ name, description, onClose, onSave }: { name: string; description: string; onClose: () => void; onSave: (n: string, d: string) => Promise<void> }) {
  const [n, setN] = useState(name);
  const [d, setD] = useState(description);
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try { await onSave(n.trim(), d.trim()); onClose(); } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="Rename playlist" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={!n.trim()} onClick={save}>Save</Button></>}>
      <form onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <Field label="Name"><input autoFocus value={n} onChange={(e) => setN(e.target.value)} maxLength={200} /></Field>
        <Field label="Description"><textarea value={d} onChange={(e) => setD(e.target.value)} rows={3} maxLength={2000} /></Field>
      </form>
    </Modal>
  );
}
