import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { AlertCircle, Search as SearchIcon } from "lucide-react";
import { api } from "../lib/api";
import type { SearchGroup } from "../lib/types";
import { TrackList } from "../components/TrackList";
import { Empty, Spinner } from "../components/ui";
import { trackKey } from "../lib/format";

/** Searches every source at once; results come back grouped by source, library first. */
export function Search() {
  const [params, setParams] = useSearchParams();
  const query = params.get("q") ?? "";
  const [text, setText] = useState(query);
  const [groups, setGroups] = useState<SearchGroup[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => { input.current?.focus(); }, []);

  // Searches as you type, after a pause.
  useEffect(() => {
    const handle = setTimeout(() => {
      if (text.trim() !== query) setParams(text.trim() ? { q: text.trim() } : {}, { replace: true });
    }, 450);
    return () => clearTimeout(handle);
  }, [text, query, setParams]);

  useEffect(() => {
    if (!query) { setGroups(null); return; }
    let cancelled = false;
    setLoading(true);
    setError(null);
    api.search(query).then(
      (r) => { if (!cancelled) { setGroups(r.groups); setLoading(false); } },
      (e) => { if (!cancelled) { setError(e.message); setLoading(false); } },
    );
    return () => { cancelled = true; };
  }, [query]);

  const sorted = [...(groups ?? [])].sort((a, b) => Number(b.kind === "JELLYFIN") - Number(a.kind === "JELLYFIN"));

  return (
    <div className="page">
      <form className="search-box" onSubmit={(e) => { e.preventDefault(); setParams(text.trim() ? { q: text.trim() } : {}); }}>
        <SearchIcon size={20} aria-hidden />
        <input ref={input} type="search" placeholder="Songs, artists, albums…" value={text} onChange={(e) => setText(e.target.value)} aria-label="Search" />
        {loading && <Spinner size={18} />}
      </form>

      {error && <div className="notice notice-error">{error}</div>}
      {!query && <Empty icon={<SearchIcon size={40} />} title="Search everything at once">Your library, and every service you or your admins have added.</Empty>}
      {groups && !groups.length && <Empty title="No sources to search">Add a source in Settings, or ask an admin to share one.</Empty>}
      {sorted.map((group) => (
        <section key={group.sourceId} className="result-group">
          <h2 className="section-title">{group.sourceName} <span className="count">{group.tracks.length || ""}</span></h2>
          {group.error && <p className="muted"><AlertCircle size={14} /> {group.error}</p>}
          {!group.error && !group.tracks.length && <p className="muted">Nothing found here.</p>}
          <TrackList rows={group.tracks.map((t) => ({ key: trackKey(t), track: t }))} />
        </section>
      ))}
    </div>
  );
}
