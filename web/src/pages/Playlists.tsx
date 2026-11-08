import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowDown, ArrowUp, Download, FileDown, ListMusic, Pencil, Plus, Trash2 } from "lucide-react";
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
          Make one, or import one from Spotify, Apple Music, Tidal, Deezer, YouTube and more. Songs are found in your own sources.
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
  const [mode, setMode] = useState<"link" | "list">("link");
  const [url, setUrl] = useState("");
  const [text, setText] = useState("");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [report, setReport] = useState<{ id: string; missing: string[]; note?: string } | null>(null);
  const ready = mode === "link" ? !!url.trim() : !!text.trim();
  const run = async () => {
    if (!ready) return;
    setBusy(true);
    try {
      const result = await api.importPlaylist(mode === "link" ? { url: url.trim() } : { text, name: name.trim() });
      void session.refreshPlaylists();
      toast(`Imported ${plural(result.found, "song")}`);
      if (result.missing.length || result.note) {
        setReport({ id: result.playlist.summary.id, missing: result.missing, note: result.note });
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
  if (report) {
    const done = () => { onImported(report.id); onClose(); };
    return (
      <Modal title="Imported" onClose={done} footer={<Button variant="primary" onClick={done}>Open the playlist</Button>}>
        {report.note && <p className="notice">{report.note}.</p>}
        {report.missing.length > 0 && (
          <>
            <p>These {plural(report.missing.length, "song")} weren't found in your sources:</p>
            <ul className="plain-list">{report.missing.map((m, i) => <li key={i}>{m}</li>)}</ul>
          </>
        )}
      </Modal>
    );
  }
  return (
    <Modal
      title="Import a playlist"
      onClose={onClose}
      footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={!ready} onClick={run}>Import</Button></>}
    >
      <div className="tabs" role="tablist">
        <button role="tab" aria-selected={mode === "link"} className={mode === "link" ? "is-active" : ""} onClick={() => setMode("link")}>From a link</button>
        <button role="tab" aria-selected={mode === "list"} className={mode === "list" ? "is-active" : ""} onClick={() => setMode("list")}>From a list</button>
      </div>
      <form className="form-stack" onSubmit={(e) => { e.preventDefault(); void run(); }}>
        {mode === "link" ? (
          <Field
            label="Playlist or album link"
            help="Spotify, Apple Music, Tidal, Deezer, YouTube or ListenBrainz. Each song is found in your own sources, your library first."
          >
            <input autoFocus value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://…" />
          </Field>
        ) : (
          <>
            <Field label="Name">
              <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Imported playlist" maxLength={200} />
            </Field>
            <Field label="Songs" help="One a line, like “Artist - Title”; or paste a CSV export (from Exportify, TuneMyMusic, Soundiiz…) with title and artist columns. For Qobuz and other services, export the playlist with TuneMyMusic or Soundiiz.">
              <textarea autoFocus rows={10} value={text} onChange={(e) => setText(e.target.value)} placeholder={"Radiohead - Paranoid Android\nFleetwood Mac - Dreams"} />
            </Field>
          </>
        )}
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
            <a className="btn btn-ghost" href={`/api/playlists/${encodeURIComponent(id)}/export.csv`} download title="Download as a CSV file (Exportify's columns), for a spreadsheet or another service">
              <FileDown size={16} /> Export CSV
            </a>
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
