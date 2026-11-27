import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowDown, ArrowUp, Download, FileDown, FileUp, ListMusic, LogOut, Pencil, Plus, Search as SearchIcon, Trash2, Users, X } from "lucide-react";
import { api } from "../lib/api";
import { isMissing, longDuration, plural } from "../lib/format";
import { session, useSession } from "../lib/session";
import type { Playlist, PlaylistPerson, PlaylistSummary } from "../lib/types";
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
            <div className="card-sub">{sharedNote(p) ?? plural(p.count, "song")}</div>
          </Link>
        ))}
      </div>
      {creating && <NewPlaylistDialog onClose={() => setCreating(false)} onCreated={(id) => navigate(`/playlists/${id}`)} />}
      {importing && <ImportDialog onClose={() => setImporting(false)} onImported={(id) => navigate(`/playlists/${id}`)} />}
    </div>
  );
}

function ImportDialog({ onClose, onImported }: { onClose: () => void; onImported: (id: string) => void }) {
  const [mode, setMode] = useState<"link" | "list" | "server">("link");
  const [url, setUrl] = useState("");
  const [text, setText] = useState("");
  const [name, setName] = useState("");
  const [fileName, setFileName] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [report, setReport] = useState<{ id: string; missing: string[]; note?: string } | null>(null);
  // Playlists kept on the person's Subsonic servers (Navidrome and the like) come in as they are.
  const sources = useLoad(() => api.sources(), []);
  const servers = (sources.data ?? []).filter((s) => s.kind === "SUBSONIC" && s.enabled && !s.hidden);
  const [serverId, setServerId] = useState("");
  const server = serverId || servers[0]?.id || "";
  const [picked, setPicked] = useState("");
  const ready = mode === "link" ? !!url.trim() : mode === "server" ? !!server && !!picked : !!text.trim();
  const run = async () => {
    if (!ready) return;
    setBusy(true);
    try {
      const result = await api.importPlaylist(
        mode === "link" ? { url: url.trim() } : mode === "server" ? { source: server, playlist: picked } : { text, name: name.trim() },
      );
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
            <p>
              These {plural(report.missing.length, "song")} weren't found in your sources. They're in the playlist
              anyway, greyed out, and are looked for again every few hours (or with “Look for them again”):
            </p>
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
        {servers.length > 0 && (
          <button role="tab" aria-selected={mode === "server"} className={mode === "server" ? "is-active" : ""} onClick={() => setMode("server")}>From your library</button>
        )}
      </div>
      <form className="form-stack" onSubmit={(e) => { e.preventDefault(); void run(); }}>
        {mode === "server" ? (
          <>
            {servers.length > 1 && (
              <Field label="Server">
                <select value={server} onChange={(e) => { setServerId(e.target.value); setPicked(""); }}>
                  {servers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
              </Field>
            )}
            <ServerPlaylists sourceId={server} picked={picked} onPick={setPicked} />
          </>
        ) : mode === "link" ? (
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
            <Field label="Songs" help="One a line, like “Artist - Title”; or a CSV export (from Exportify, TuneMyMusic, Soundiiz…) with title and artist columns: choose the file, or paste it. For Qobuz and other services, export the playlist with TuneMyMusic or Soundiiz.">
              <textarea autoFocus rows={10} value={text} onChange={(e) => setText(e.target.value)} placeholder={"Radiohead - Paranoid Android\nFleetwood Mac - Dreams"} />
            </Field>
            <label className="btn btn-tonal file-pick">
              <FileUp size={16} /> {fileName ? `From ${fileName}` : "Choose a file (CSV or text)"}
              <input
                type="file"
                accept=".csv,.tsv,.txt,.m3u,.m3u8,text/csv,text/plain,text/tab-separated-values"
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  e.target.value = "";
                  if (!file) return;
                  if (file.size > 1_000_000) { errorToast(new Error("That file is too big (1 MB at most)")); return; }
                  void file.text().then((content) => {
                    setText(content);
                    setFileName(file.name);
                    // The file's name names the playlist, unless one was typed.
                    if (!name.trim()) setName(file.name.replace(/\.[^.]+$/, "").replace(/[_]+/g, " ").trim());
                  }, errorToast);
                }}
              />
            </label>
          </>
        )}
      </form>
      {busy && <p className="muted"><Spinner size={14} /> Finding the songs… this takes a moment for long playlists.</p>}
    </Modal>
  );
}

/** The playlists kept on a Subsonic server, to pick one to import. */
function ServerPlaylists({ sourceId, picked, onPick }: { sourceId: string; picked: string; onPick: (id: string) => void }) {
  const playlists = useLoad(() => api.sourcePlaylists(sourceId), [sourceId]);
  if (playlists.loading && !playlists.data) return <Spinner />;
  if (playlists.error) return <div className="notice notice-error">{playlists.error}</div>;
  if (!playlists.data?.length) return <p className="muted">There are no playlists on this server.</p>;
  return (
    <Field label="Playlist" help="Its songs come in as your library has them.">
      <select value={picked} onChange={(e) => onPick(e.target.value)}>
        <option value="">Choose a playlist…</option>
        {playlists.data.map((p) => (
          <option key={p.id} value={p.id}>{p.name}{p.songCount != null ? ` (${plural(p.songCount, "song")})` : ""}</option>
        ))}
      </select>
    </Field>
  );
}

export function PlaylistDetail() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const { data, error, loading, setData } = useLoad(() => api.playlist(id), [id]);
  const [renaming, setRenaming] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [finding, setFinding] = useState(false);
  const [sharing, setSharing] = useState(false);
  const [confirmLeave, setConfirmLeave] = useState(false);
  const me = useSession((s) => s.user);

  if (loading && !data) return <div className="center-pad"><Spinner /></div>;
  if (error || !data) return <Empty title="Couldn't open this playlist">{error}</Empty>;
  const { summary, entries } = data;
  const tracks = entries.map((e) => e.track);
  const missing = tracks.filter(isMissing).length;
  const mine = summary.mine !== false;
  const shared = !mine || (summary.members?.length ?? 0) > 0;

  const apply = (next: Playlist) => { setData(next); void session.refreshPlaylists(); };
  const findAgain = async () => {
    setFinding(true);
    try {
      const result = await api.findMissing(id);
      apply(result.playlist);
      toast(result.found ? `Found ${plural(result.found, "song")}${result.stillMissing ? `; ${result.stillMissing} still missing` : ""}` : "Still not found anywhere");
    } catch (e) {
      errorToast(e);
    }
    setFinding(false);
  };
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
          <span className="eyebrow">{shared ? "Shared playlist" : "Playlist"}</span>
          <h1>{summary.name}</h1>
          {summary.description && <p className="muted">{summary.description}</p>}
          {shared && <p className="muted playlist-people"><Users size={14} /> {peopleLine(summary)}</p>}
          <p className="muted">{plural(summary.count, "song")}{summary.durationMs ? ` · ${longDuration(summary.durationMs)}` : ""}</p>
          <PlayButtons tracks={tracks} onPlay={() => session.playlistPlayed(id)}>
            <Button variant="ghost" onClick={() => { player.addToQueue(tracks); toast("Added to the queue"); }} disabled={!tracks.length}>Add to queue</Button>
            {mine && <Button variant="ghost" onClick={() => setSharing(true)}><Users size={16} /> Share</Button>}
            {mine && <Button variant="ghost" onClick={() => setRenaming(true)}><Pencil size={16} /> Rename</Button>}
            <a className="btn btn-ghost" href={`/api/playlists/${encodeURIComponent(id)}/export.csv`} download title="Download as a CSV file (Exportify's columns), for a spreadsheet or another service">
              <FileDown size={16} /> Export CSV
            </a>
            {mine
              ? <Button variant="ghost" onClick={() => setConfirmDelete(true)}><Trash2 size={16} /> Delete</Button>
              : <Button variant="ghost" onClick={() => setConfirmLeave(true)}><LogOut size={16} /> Leave</Button>}
          </PlayButtons>
        </div>
      </div>
      {!entries.length && <Empty title="This playlist is empty">Add songs from search with their ••• menu.</Empty>}
      {missing > 0 && (
        <div className="missing-note">
          <p>
            {missing === 1
              ? "1 song wasn't found in your sources: it's greyed out and skipped. It's looked for again every few hours, in case it's turned up (Lidarr may be getting it)."
              : `${missing} songs weren't found in your sources: they're greyed out and skipped. They're looked for again every few hours, in case they've turned up (Lidarr may be getting them).`}
          </p>
          <Button variant="tonal" busy={finding} onClick={() => void findAgain()}><SearchIcon size={16} /> Look for them again</Button>
        </div>
      )}
      <TrackList
        onPlay={() => session.playlistPlayed(id)}
        numbered
        rows={entries.map((e) => ({ key: e.entryId, track: e.track, note: shared && e.addedBy ? `added by ${e.addedBy.id === me?.id ? "you" : e.addedBy.name}` : undefined }))}
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
      {sharing && <ShareDialog summary={summary} onClose={() => setSharing(false)} onChanged={(next) => { setData({ ...data, summary: next }); void session.refreshPlaylists(); }} />}
      {confirmLeave && me && (
        <Modal title={`Leave “${summary.name}”?`} onClose={() => setConfirmLeave(false)} footer={<>
          <Button variant="ghost" onClick={() => setConfirmLeave(false)}>Stay</Button>
          <Button variant="danger" onClick={async () => {
            try {
              await api.unsharePlaylist(id, me.id);
              void session.refreshPlaylists();
              navigate("/playlists");
            } catch (e) { errorToast(e); }
          }}>Leave</Button>
        </>}>
          <p>It goes from your playlists. The songs you added stay in it{summary.owner ? `, and it stays ${summary.owner.name}'s` : ""}.</p>
        </Modal>
      )}
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
          <p>The playlist goes for good, on every device{(summary.members?.length ?? 0) > 0 ? ", and for everyone it's shared with" : ""}. The songs stay in their sources.</p>
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

/** "Shared by Maddie", or "Shared with Sam and Alex", for a playlist card. */
function sharedNote(p: PlaylistSummary): string | null {
  if (p.mine === false && p.owner) return `Shared by ${p.owner.name}`;
  if (p.members?.length) return `Shared with ${names(p.members)}`;
  return null;
}

/** Who a shared playlist is between, for its page. */
function peopleLine(p: PlaylistSummary): string {
  const others = p.members ?? [];
  if (p.mine === false && p.owner) {
    return `${p.owner.name}'s, shared with you${others.length > 1 ? ` and ${others.length - 1} more` : ""}. Everyone adds, removes and reorders songs.`;
  }
  return `Shared with ${names(others)}. Everyone adds, removes and reorders songs.`;
}

function names(people: PlaylistPerson[]): string {
  const all = people.map((p) => p.name || p.username);
  if (all.length <= 1) return all.join("");
  if (all.length <= 3) return `${all.slice(0, -1).join(", ")} and ${all[all.length - 1]}`;
  return `${all.slice(0, 2).join(", ")} and ${all.length - 2} more`;
}

/** Sharing a playlist: who it's shared with (each can be taken off), and adding someone. */
function ShareDialog({ summary, onClose, onChanged }: { summary: PlaylistSummary; onClose: () => void; onChanged: (s: PlaylistSummary) => void }) {
  const people = useLoad(() => api.people(), []);
  const [members, setMembers] = useState<PlaylistPerson[]>(summary.members ?? []);
  const [busy, setBusy] = useState<string | null>(null);
  const others = (people.data ?? []).filter((p) => !members.some((m) => m.id === p.id));
  const share = async (person: PlaylistPerson) => {
    setBusy(person.id);
    try {
      const next = await api.sharePlaylist(summary.id, person.id);
      setMembers(next.members ?? []);
      onChanged({ ...summary, members: next.members ?? [] });
      toast(`Shared with ${person.name || person.username}`);
    } catch (e) { errorToast(e); }
    setBusy(null);
  };
  const unshare = async (person: PlaylistPerson) => {
    setBusy(person.id);
    try {
      await api.unsharePlaylist(summary.id, person.id);
      const next = members.filter((m) => m.id !== person.id);
      setMembers(next);
      onChanged({ ...summary, members: next });
    } catch (e) { errorToast(e); }
    setBusy(null);
  };
  return (
    <Modal title={`Share “${summary.name}”`} onClose={onClose} footer={<Button variant="primary" onClick={onClose}>Done</Button>}>
      <p className="muted">Everyone it's shared with sees it with their playlists, and can add, remove and reorder its songs. Only you can rename it, delete it or share it.</p>
      {members.length > 0 && (
        <>
          <h3 className="share-heading">Shared with</h3>
          <ul className="share-list">
            {members.map((m) => (
              <li key={m.id} className="share-row">
                <span className="share-name">{m.name || m.username}<span className="muted"> · {m.username}</span></span>
                <Button variant="ghost" busy={busy === m.id} onClick={() => void unshare(m)} aria-label={`Stop sharing with ${m.name || m.username}`}><X size={16} /> Remove</Button>
              </li>
            ))}
          </ul>
        </>
      )}
      <h3 className="share-heading">Share with</h3>
      {people.loading && !people.data ? <Spinner />
        : others.length === 0 ? <p className="muted">{(people.data ?? []).length ? "It's shared with everyone on this server." : "There's no one else on this server yet."}</p>
        : (
          <ul className="share-list">
            {others.map((p) => (
              <li key={p.id} className="share-row">
                <span className="share-name">{p.name || p.username}<span className="muted"> · {p.username}</span></span>
                <Button variant="tonal" busy={busy === p.id} onClick={() => void share(p)}><Plus size={16} /> Share</Button>
              </li>
            ))}
          </ul>
        )}
    </Modal>
  );
}
